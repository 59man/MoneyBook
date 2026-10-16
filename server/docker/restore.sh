#!/bin/bash
# restore <dump> [attachments.tgz] [--yes]   — run inside the container: docker exec -it moneybook restore /backups/daily/moneybook-<date>.dump [...]
set -euo pipefail
umask 077
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

echo "This will stop the server and replace the moneybook database with $dump"
[ -z "$tgz" ] || echo "and replace the attachments with $tgz."
echo "The API key stays as it is; devices have to log in again."
if [ "$yes" != 1 ]; then
    read -r -p "Type yes to continue: " ans
    [ "$ans" = yes ] || { echo "aborted"; exit 1; }
fi

# The lock is a symlink to our pid (created atomically, so it is never half written). The entrypoint keeps the server
# stopped while that pid lives and restarts it when we are gone, however we end (even kill -9: a dead pid is a stale lock).
mine() { [ "$(readlink "$LOCK" 2>/dev/null)" = "$$" ]; }
for try in 1 2; do
    ln -s "$$" "$LOCK" 2>/dev/null && break
    other=$(readlink "$LOCK" 2>/dev/null || true)
    if [ -n "$other" ] && kill -0 "$other" 2>/dev/null; then echo "error: another restore is running (pid $other)" >&2; exit 1; fi
    rm -f "$LOCK"
done
mine || { echo "error: could not take the restore lock" >&2; exit 1; }

old_att=0     # 1 while the old attachments sit in .attachments-old
finish() {
    rc=$?
    trap - EXIT
    if [ "$rc" -ne 0 ]; then
        [ "$old_att" = 1 ] && [ -d "$DATA/.attachments-old" ] && { rm -rf "$DATA/attachments"; mv "$DATA/.attachments-old" "$DATA/attachments"; }
        echo "error: the restore failed; the live data is unchanged" >&2
    fi
    psql -d postgres -q -c 'drop database if exists moneybook_restore with (force)' > /dev/null 2>&1 || true
    rm -rf "$DATA/.attachments-new"
    mine && rm -f "$LOCK"
    exit "$rc"
}
trap finish EXIT
trap 'exit 130' INT TERM HUP

if [ -f "$PIDFILE" ]; then
    pid=$(cat "$PIDFILE")
    kill "$pid" 2>/dev/null || true
    for _ in $(seq 60); do kill -0 "$pid" 2>/dev/null || break; sleep 0.5; done
    ! kill -0 "$pid" 2>/dev/null || { echo "error: the server did not stop" >&2; exit 1; }
fi

# the live key and hash stay (a dump carries the key of its day), and so does "everyone is signed out" (see below)
IFS=$'\t' read -r key keyhash < <(psql -d moneybook -X -tA -F $'\t' -v ON_ERROR_STOP=1 \
    -c "select coalesce(api_key, ''), coalesce(key_hash, '') from server_meta limit 1")

# 1. restore into a side database; the live one is not touched until everything else has worked
psql -d postgres -v ON_ERROR_STOP=1 -c 'drop database if exists moneybook_restore with (force)' -c 'create database moneybook_restore'
pg_restore -d moneybook_restore --no-owner --exit-on-error < "$dump"
# a new epoch tells every phone that the data went back in time: it resets its cursor and pushes everything again.
# The dump's refresh tokens and devices go (they belong to a key that may be gone), and the key stays the live one.
psql -d moneybook_restore -X -v ON_ERROR_STOP=1 -v key="$key" -v keyhash="$keyhash" <<'SQL'
begin;
select to_regclass('server_meta') is not null as has_meta \gset
\if :has_meta
  update server_meta set epoch = gen_random_uuid();
  select exists (select 1 from information_schema.columns where table_name = 'server_meta' and column_name = 'api_key') as has_key \gset
  \if :has_key
    update server_meta set api_key = nullif(:'key', ''), key_hash = nullif(:'keyhash', '');
  \endif
\endif
delete from refresh_token;
delete from device;
commit;
SQL

# 2. attachments into a side directory
if [ -n "$tgz" ]; then
    rm -rf "$DATA/.attachments-new" "$DATA/.attachments-old"
    mkdir "$DATA/.attachments-new"
    tar xzf "$tgz" -C "$DATA/.attachments-new"
    [ -d "$DATA/.attachments-new/attachments" ]
fi

# 3. swap: attachments first (renames, undone by finish if the database swap fails), then the database in one transaction
if [ -n "$tgz" ]; then
    cd "$DATA"
    if [ -e attachments ]; then mv attachments .attachments-old; old_att=1; fi
    mv .attachments-new/attachments attachments
fi
for _ in 1 2 3 4 5; do
    psql -d postgres -X -q -c "select pg_terminate_backend(pid) from pg_stat_activity where datname in ('moneybook', 'moneybook_restore') and pid <> pg_backend_pid()" > /dev/null || true
    psql -d postgres -X -q -v ON_ERROR_STOP=1 -c 'drop database if exists moneybook_old with (force)' || continue   # not allowed inside a transaction
    psql -d postgres -X -q -1 -v ON_ERROR_STOP=1 \
        -c 'alter database moneybook rename to moneybook_old' -c 'alter database moneybook_restore rename to moneybook' && swapped=1 && break
    sleep 1
done
[ "${swapped:-0}" = 1 ]
psql -d postgres -X -q -c 'drop database if exists moneybook_old with (force)' || echo "warning: could not drop moneybook_old" >&2
rm -rf "$DATA/.attachments-old"; old_att=0
echo "database restored"
[ -z "$tgz" ] || echo "attachments restored"
echo "restore done; the server is starting again"
