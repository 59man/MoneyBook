#!/bin/bash
# restore <dump> [attachments.tgz] [--yes]   — run inside the container: docker exec -it moneybook restore /backups/daily/moneybook-<date>.dump [...]
set -euo pipefail
DATA=${MB_DATA:-/data}
LOCK=/tmp/mb-restore.lock
PIDFILE=/tmp/mb-app.pid

yes=0; files=()
for a in "$@"; do if [ "$a" = --yes ]; then yes=1; else files+=("$a"); fi; done
[ "${#files[@]}" -ge 1 ] && [ "${#files[@]}" -le 2 ] || { echo "usage: restore <dump> [attachments.tgz] [--yes]" >&2; exit 2; }
dump=${files[0]}; tgz=${files[1]:-}
[ -f "$dump" ] || { echo "error: no such file: $dump" >&2; exit 1; }
[ -z "$tgz" ] || [ -f "$tgz" ] || { echo "error: no such file: $tgz" >&2; exit 1; }

set -a; . "$DATA/secrets.env"; set +a
export PGPASSWORD=$MB_DB_PASSWORD PGHOST=127.0.0.1 PGUSER=postgres

# validate before touching anything
pg_restore --list < "$dump" > /dev/null || { echo "error: $dump is not a readable pg_dump custom-format file" >&2; exit 1; }
if [ -n "$tgz" ]; then
    list=$(tar tzf "$tgz") || { echo "error: $tgz is not a readable tar.gz" >&2; exit 1; }
    if printf '%s\n' "$list" | grep -qvE '^attachments(/|$)' || printf '%s\n' "$list" | grep -q '\.\.'; then
        echo "error: $tgz must hold only attachments/..." >&2; exit 1
    fi
    # only regular files and directories: a symlink or hard link could point out of the data volume
    if tar tvzf "$tgz" | grep -qv '^[-d]'; then echo "error: $tgz must hold only regular files and directories" >&2; exit 1; fi
fi

echo "This will stop the server, DROP the moneybook database and replace it with $dump"
[ -z "$tgz" ] || echo "and replace the attachments with $tgz."
if [ "$yes" != 1 ]; then
    read -r -p "Type yes to continue: " ans
    [ "$ans" = yes ] || { echo "aborted"; exit 1; }
fi

# the entrypoint restarts the server when this lock goes away (also if we fail: it then runs on whatever state the database is in)
touch "$LOCK"
trap 'rm -f "$LOCK"' EXIT
if [ -f "$PIDFILE" ]; then
    pid=$(cat "$PIDFILE")
    kill "$pid" 2>/dev/null || true
    for _ in $(seq 60); do kill -0 "$pid" 2>/dev/null || break; sleep 0.5; done
    ! kill -0 "$pid" 2>/dev/null || { echo "error: the server did not stop" >&2; exit 1; }
fi

psql -d postgres -v ON_ERROR_STOP=1 -c 'drop database if exists moneybook with (force)' -c 'create database moneybook'
pg_restore -d moneybook --no-owner --exit-on-error < "$dump"
# a new epoch tells every phone that the data went back in time: it resets its cursor and pushes everything again
psql -d moneybook -v ON_ERROR_STOP=1 \
    -c "do \$\$ begin if to_regclass('server_meta') is not null then update server_meta set epoch = gen_random_uuid(); end if; end \$\$"
echo "database restored"

if [ -n "$tgz" ]; then
    cd "$DATA"
    rm -rf .attachments-new .attachments-old && mkdir .attachments-new
    tar xzf "$tgz" -C .attachments-new
    [ -d .attachments-new/attachments ]
    if [ -e attachments ]; then mv attachments .attachments-old; fi
    mv .attachments-new/attachments attachments
    rm -rf .attachments-new .attachments-old
    echo "attachments restored"
fi
echo "restore done; the server is starting again"
