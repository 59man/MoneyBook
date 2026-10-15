#!/usr/bin/env bash
# Restore MoneyBook from a backup: ./restore.sh <dump> [attachments.tgz] [--yes]
# Run from deploy/.
set -euo pipefail

# absolute paths first: the script changes into its own directory below
yes=0; files=()
for a in "$@"; do
    if [ "$a" = --yes ]; then yes=1; else files+=("$(realpath -- "$a")"); fi
done
cd "$(dirname "$0")"
[ "${#files[@]}" -ge 1 ] && [ "${#files[@]}" -le 2 ] || { echo "usage: $0 <dump> [attachments.tgz] [--yes]" >&2; exit 2; }
dump=${files[0]}; tgz=${files[1]:-}
[ -f "$dump" ] || { echo "error: no such file: $dump" >&2; exit 1; }
[ -z "$tgz" ] || [ -f "$tgz" ] || { echo "error: no such file: $tgz" >&2; exit 1; }

# validate before touching anything
docker compose up -d --wait db
docker compose exec -T db pg_restore --list < "$dump" > /dev/null \
    || { echo "error: $dump is not a readable pg_dump custom-format file" >&2; exit 1; }
if [ -n "$tgz" ]; then
    list=$(tar tzf "$tgz") || { echo "error: $tgz is not a readable tar.gz" >&2; exit 1; }
    if printf '%s\n' "$list" | grep -qvE '^attachments(/|$)' || printf '%s\n' "$list" | grep -q '\.\.'; then
        echo "error: $tgz must hold only attachments/..." >&2; exit 1
    fi
    # only regular files and directories: a symlink or hard link could point out of the data volume
    if tar tvzf "$tgz" | grep -qv '^[-d]'; then
        echo "error: $tgz must hold only regular files and directories" >&2; exit 1
    fi
fi

echo "This will stop the api, DROP the moneybook database and replace it with $dump"
[ -z "$tgz" ] || echo "and replace the attachments in the data volume with $tgz."
if [ "$yes" != 1 ]; then
    read -r -p "Type yes to continue: " ans
    [ "$ans" = yes ] || { echo "aborted"; exit 1; }
fi

docker compose stop api
docker compose exec -T db psql -U postgres -d postgres -v ON_ERROR_STOP=1 \
    -c 'drop database if exists moneybook with (force)' -c 'create database moneybook'
docker compose exec -T db pg_restore -U postgres -d moneybook --no-owner --exit-on-error < "$dump"
# a new epoch tells every phone that the data went back in time: it resets its cursor and pushes everything again
docker compose exec -T db psql -U postgres -d moneybook -v ON_ERROR_STOP=1 \
    -c "do \$\$ begin if to_regclass('server_meta') is not null then update server_meta set epoch = gen_random_uuid(); end if; end \$\$"
echo "database restored"

if [ -n "$tgz" ]; then
    # throwaway container on the data volume (api itself is stopped); extract aside, then swap
    docker compose run --rm --no-deps -T --user root --entrypoint sh api -ec '
        cd /data
        rm -rf .attachments-new .attachments-old && mkdir .attachments-new
        tar xzf - -C .attachments-new
        [ -d .attachments-new/attachments ]
        if [ -e attachments ]; then mv attachments .attachments-old; fi
        mv .attachments-new/attachments attachments
        chown -R 1000:1000 attachments
        rm -rf .attachments-new .attachments-old' < "$tgz"
    echo "attachments restored"
fi

docker compose up -d api
echo "restore done"
