#!/bin/sh
# MoneyBook backups (POSIX sh, runs in postgres:17-alpine).
#   backup.sh        nightly loop: sleep to 03:00 (container TZ), back up, repeat
#   backup.sh now    one backup immediately
# Needs PGHOST PGUSER PGPASSWORD PGDATABASE in the environment. Env overrides for tests:
# BACKUP_DIR (/backups), ATTACH_DIR (/data/attachments), BACKUP_DATE (YYYY-MM-DD).
set -u
umask 077
BACKUP_DIR=${BACKUP_DIR:-/backups}
ATTACH_DIR=${ATTACH_DIR:-/data/attachments}

# keep the newest $2 dumps in $1; an old dump goes with its attachments tarball
rotate() {
    n=$(ls -1 "$1"/moneybook-*.dump 2>/dev/null | wc -l)
    [ "$n" -gt "$2" ] || return 0
    ls -1 "$1"/moneybook-*.dump | sort | head -n "$((n - $2))" | while read -r f; do
        d=${f##*/moneybook-}
        d=${d%.dump}
        rm -f "$f" "$1/attachments-$d.tgz"
    done
}

backup_now() {
    day=${BACKUP_DATE:-$(date +%F)}
    mkdir -p "$BACKUP_DIR/daily" "$BACKUP_DIR/weekly" "$BACKUP_DIR/monthly" || return 1
    cd "$BACKUP_DIR" || return 1
    dump=daily/moneybook-$day.dump
    att=daily/attachments-$day.tgz
    pg_dump -Fc -f "$dump.tmp" || { rm -f "$dump.tmp"; return 1; }
    # an empty or missing attachments directory still gives a valid (empty) tarball
    src=$ATTACH_DIR
    if [ ! -d "$src" ]; then
        src=$(mktemp -d)/attachments
        mkdir "$src"
    fi
    tar czf "$att.tmp" -C "$(dirname "$src")" "$(basename "$src")" \
        || { rm -f "$dump.tmp" "$att.tmp"; return 1; }
    mv "$dump.tmp" "$dump" && mv "$att.tmp" "$att" || return 1
    if [ "$(date -d "$day" +%u)" = 7 ]; then cp "$dump" "$att" weekly/ || return 1; fi
    if [ "${day##*-}" = 01 ]; then cp "$dump" "$att" monthly/ || return 1; fi
    rotate daily 7
    rotate weekly 4
    rotate monthly 6
    echo "backup ok $dump $(wc -c < "$dump") bytes, $att $(wc -c < "$att") bytes"
}

if [ "${1:-}" = now ]; then
    backup_now
    exit $?
fi

# ponytail: no DST handling; a night with a clock change may fire an hour off
while :; do
    h=$(date +%H); m=$(date +%M); s=$(date +%S)
    wait=$((10800 - (${h#0} * 3600 + ${m#0} * 60 + ${s#0})))
    [ "$wait" -gt 0 ] || wait=$((wait + 86400))
    sleep "$wait"
    backup_now || echo "backup FAILED (exit $?)" >&2
done
