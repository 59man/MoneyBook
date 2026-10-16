#!/bin/bash
# MoneyBook container entrypoint: Postgres 17 on 127.0.0.1 + the server, as uid 1000. See deploy/README.md.
set -euo pipefail
umask 077
DATA=${MB_DATA:-/data}
PGBIN=/usr/lib/postgresql/17/bin
PGDIR=$DATA/pg
SECRETS=$DATA/secrets.env
LOCK=/tmp/mb-restore.lock
PIDFILE=/tmp/mb-app.pid
export PATH="$PGBIN:$PATH"
rm -f "$LOCK" "$PIDFILE"
# a restore killed between its renames: put the old attachments back (the database is repaired below)
[ -e "$DATA/attachments" ] || [ ! -d "$DATA/.attachments-old" ] || mv "$DATA/.attachments-old" "$DATA/attachments"
mkdir -p "$DATA/attachments"

# PID 1 ignores signals that have no handler, so the trap goes in before anything is started (docker stop must not wait 10 s)
pg=""; app=""
shutdown() {     # SIGTERM to the server, then a fast shutdown (SIGINT) of Postgres
    trap - TERM INT
    if [ -n "$app" ]; then kill -TERM "$app" 2>/dev/null || true; wait "$app" 2>/dev/null || true; fi
    if [ -n "$pg" ]; then kill -INT "$pg" 2>/dev/null || true; wait "$pg" 2>/dev/null || true; fi
}
trap 'shutdown; exit 0' TERM INT

# 1. secrets, once
if [ ! -f "$SECRETS" ]; then
    tmp=$(mktemp "$DATA/.secrets.XXXXXX")
    {
        echo "MB_DB_PASSWORD='$(head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n')'"
        echo "MB_JWT_SECRET='$(head -c 48 /dev/urandom | base64 -w0)'"
    } > "$tmp"
    mv "$tmp" "$SECRETS"
fi
set -a; . "$SECRETS"; set +a                       # exports MB_DB_PASSWORD, MB_JWT_SECRET
export PGPASSWORD=$MB_DB_PASSWORD PGHOST=127.0.0.1 PGUSER=postgres PGPORT=${MB_PG_PORT:-5432}

# 2. database cluster, once (into pg.init, renamed when complete)
if [ ! -d "$PGDIR" ]; then
    rm -rf "$PGDIR.init"
    pwfile=$(mktemp); printf '%s' "$MB_DB_PASSWORD" > "$pwfile"
    initdb -D "$PGDIR.init" -U postgres --auth=scram-sha-256 --pwfile="$pwfile" -E UTF8 > /dev/null
    rm -f "$pwfile"
    mv "$PGDIR.init" "$PGDIR"
fi

# 3. Postgres: loopback and a socket in /tmp only
postgres -D "$PGDIR" -c listen_addresses=127.0.0.1 -c port="$PGPORT" -c unix_socket_directories=/tmp &
pg=$!
for _ in $(seq 120); do
    pg_isready -q -h 127.0.0.1 && break
    kill -0 "$pg" 2>/dev/null || { echo "postgres did not start" >&2; exit 1; }
    sleep 0.5
done
pg_isready -q -h 127.0.0.1 || { echo "postgres not ready" >&2; kill "$pg"; exit 1; }
hasdb() { psql -d postgres -tAc "select 1 from pg_database where datname = '$1'" | grep -q 1; }
# a restore killed between its two renames leaves the old database under another name
if ! hasdb moneybook && hasdb moneybook_old; then psql -d postgres -qc 'alter database moneybook_old rename to moneybook'; fi
hasdb moneybook || createdb moneybook

start_app() {
    MB_DB_URL=jdbc:postgresql://127.0.0.1:$PGPORT/moneybook MB_DB_USER=postgres MB_DATA="$DATA" MB_BACKUP_DIR=/backups \
        /app/bin/server &      # the start script ends in `exec java`, so $! is the JVM
    app=$!
    echo "$app" > "$PIDFILE"
}

# 4. supervise: either process dying ends the container (restart: unless-stopped brings it back); a restore pauses the server only
start_app
while :; do
    wait -n "$pg" "$app" || true
    kill -0 "$pg" 2>/dev/null || { echo "postgres exited" >&2; shutdown; exit 1; }
    kill -0 "$app" 2>/dev/null && continue
    if [ -L "$LOCK" ]; then
        # restore holds the lock as a symlink to its pid; a dead pid (restore killed) is a stale lock: take it and go on
        while [ -L "$LOCK" ] && kill -0 "$(readlink "$LOCK")" 2>/dev/null; do sleep 1; done
        rm -f "$LOCK"
        start_app
        continue
    fi
    echo "server exited" >&2; shutdown; exit 1
done
