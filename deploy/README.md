# MoneyBook server deployment

Containers (`docker-compose.yml`): `db` (Postgres 17), `api` (Ktor) and `backup` (nightly dump). The server runs on a machine you control (for example a home server or a CasaOS box). Only the API port is published, and only on `127.0.0.1`: the API speaks plain HTTP, so HTTPS comes from the host's own Tailscale (`tailscale serve`, below). Run every command below from this directory.

## Prerequisites

- Docker with a recent Compose v2 (2.24.4 or newer).
- Tailscale on the machine (CasaOS has it as an app) and on the phone, with HTTPS certificates enabled for the tailnet.
- Your login on that machine must be allowed to use Docker: in the `docker` group, or prefix every `docker` command below with `sudo`.

## First start

```bash
cp .env.example .env              # then edit it, never commit it
openssl rand -base64 48           # paste into MB_JWT_SECRET; pick a long POSTGRES_PASSWORD
openssl rand -hex 32              # paste into MB_API_KEY (see "API key" below)
                                  # MB_HOST_PORT is the loopback port (default 8095; pick another if it is taken)
mkdir -m 700 backups              # owned by you; BACKUP_UID/BACKUP_GID in .env must be your ids (id -u, id -g)
docker compose up -d --build
docker compose ps                 # db healthy; backup stays "starting" until its first dump
docker compose exec backup sh /backup/backup.sh now   # first dump now, so backup turns healthy (else it is unhealthy after 26 h with no dump)
curl http://127.0.0.1:8095/health # {"status":"ok"}; use your MB_HOST_PORT
```

### On a home server (for example CasaOS)

The image is built from the repo root (`server/` and `shared/` are in the build context), so copy the tree to the machine (or clone the repository there), then run the first start with `sudo docker compose` if your user is not in the `docker` group:

```bash
# from the repo root; copy only what the build needs (never your own exports or .env files)
rsync -a --relative --exclude build --exclude .gradle --exclude .kotlin --exclude deploy/.env --exclude deploy/backups \
    gradle shared server deploy .dockerignore <user>@<server-ip>:moneybook/
ssh <user>@<server-ip>
cd moneybook/deploy
```

To keep the backups on a bigger disk, make `backups` a symlink before the first start: `mkdir -m 700 /path/to/moneybook-backups && ln -s /path/to/moneybook-backups backups`.

Publish it with the host's Tailscale. First enable MagicDNS and HTTPS certificates in the Tailscale admin console (DNS page). Then, on the machine (if Tailscale runs in a container, prefix with `sudo docker exec tailscale`; check the name with `sudo docker ps`):

```bash
tailscale serve --bg --https=443 http://127.0.0.1:8095    # your MB_HOST_PORT
tailscale serve status
tailscale status --self                                    # shows the machine name
```

If 443 is already served for something else, use `--https=8443`; the URL then ends in `:8443`.

The app's server URL is `https://<machine name>.<tailnet>.ts.net`. The phone needs the Tailscale app on to sync, at home too. Nothing listens on the LAN, so `http://<server-ip>:8095` does not answer from other devices. Check it:

- `docker version` on the machine: Docker older than 28 may let LAN hosts reach ports published on `127.0.0.1`.
- From another machine on the LAN, `curl -m 3 http://<server-ip>:8095/health` must fail (time out or refuse). If it answers, stop and fix the firewall before logging in.

## API key

The server has one owner and no accounts. The app logs in with a single key, `MB_API_KEY` in `.env` (at least 32 characters; the server refuses to start without it):

```bash
openssl rand -hex 32              # put the output in .env as MB_API_KEY=...
docker compose up -d              # applies a changed .env
```

Paste the same key into the app (More, Sync, Server URL and API key). Changing `MB_API_KEY` and running `docker compose up -d` signs every device out: each one has to log in again with the new key (the server remembers only a hash of the key and drops all refresh tokens when it changes). Phones keep their data and merge again after the new login. Access tokens last 15 minutes, so a signed-out device may still sync for a few minutes.

## Backups

`backup` writes at 03:00 (container time zone, `TZ` in `.env`) into `deploy/backups/`:

```
backups/daily/    moneybook-YYYY-MM-DD.dump  attachments-YYYY-MM-DD.tgz   newest 7
backups/weekly/   the Sunday pair                                         newest 4
backups/monthly/  the 1st-of-month pair                                   newest 6
```

Files are owner-only (600, directories 700). A failed run is logged (`docker compose logs backup`) and never deletes older backups. The `backup` service has a healthcheck that wants a dump from the last two days, so `docker compose ps` shows `unhealthy` when backups stop; a fresh install shows `starting` for 26 hours unless you run `backup.sh now` once (below). Run one now:

```bash
docker compose exec backup sh /backup/backup.sh now
```

**Backups hold your financial data.** Keep `deploy/backups` owner-only (`chmod 700 backups`) and copy it off this machine yourself (another disk, an encrypted remote); a backup on the same disk does not survive the disk.

## Restore drill

Do this once before you need it. It replaces the live data, so on a stack that holds real entries make sure the backup you restore from is the one you want.

1. `docker compose exec backup sh /backup/backup.sh now`
2. Note what the data looks like: log in with curl and save a pull (`POST /v1/sync` with `{"cursor":0}`), or count rows: `docker compose exec db psql -U postgres moneybook -c 'select count(*) from sync_record'`.
3. Make the damage: `docker compose stop api && docker compose exec db psql -U postgres -d postgres -c 'drop database moneybook with (force)'`
4. `./restore.sh backups/daily/moneybook-<date>.dump backups/daily/attachments-<date>.tgz` (type `yes`; `--yes` skips the question). It checks the files first, stops the API, recreates the database, restores it, replaces the attachments and starts the API. The attachments file is optional. If a step fails it exits non-zero and does not start the API; fix the cause and run it again (the backup files are not touched).
5. Repeat the pull from step 2 with a fresh login and compare. An attachment you uploaded before must still download.

`restore.sh` gives the server a new epoch, so phones notice the data went back in time and push all their data again (M4); nothing a phone still holds is lost. A restore can also leave phones signed out (the refresh tokens come from the dump); log in again with the key: the same server merges automatically and nothing is lost.

Restoring brings back the refresh tokens that existed at the backup time; devices that logged in later sign in again.

## Updating and logs

```bash
git pull && docker compose up -d --build   # rebuilds the api image; the database migrates itself at start
docker compose logs -f api                 # also: db, backup
docker compose down                        # stops, keeps volumes; `down -v` deletes the data
```
