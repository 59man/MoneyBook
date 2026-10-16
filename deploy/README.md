# MoneyBook server deployment

One container, `moneybook` (`docker-compose.yml`): Postgres 17 and the Ktor server together, built from `server/Dockerfile`. It runs on a machine you control (a home server, a NAS, a small VPS) and is reached over your Tailscale network at `http://<tailscale-ip>:8095/` (for example `http://100.x.y.z:8095/`). The server decides who may talk to it by source address (below), so no HTTPS proxy is needed. Run every command below from this directory.

Data lives in the Docker volume `data` (`/data`: the database, the photos and the generated secrets). Backups go to `./backups` on the host (`/backups` in the container).

## Prerequisites

- Docker with a recent Compose v2 (2.24.4 or newer).
- Tailscale on the machine and on the phone.
- Your login on that machine must be allowed to use Docker: in the `docker` group, or prefix every `docker` command below with `sudo`.

## First start

```bash
cp .env.example .env              # everything in it is optional; never commit it
mkdir -m 700 backups              # the container user is uid 1000, see "Backups directory" below
docker compose up -d --build
docker compose ps                 # moneybook becomes healthy within a minute
curl http://127.0.0.1:8095/health # {"status":"ok"}; use your MB_HOST_PORT
```

Then open `http://<tailscale-ip>:8095/` in a browser on a device in your tailnet (`tailscale ip -4` on the server prints the address). Copy the server URL and the API key from the page into the app (More, Sync); the phone needs Tailscale on, at home too.

### Backups directory

The container runs as uid/gid 1000 and never as root, so `./backups` must be owned by 1000. If your user is 1000 (`id -u`), `mkdir -m 700 backups` is enough. Otherwise: `sudo mkdir -m 700 backups && sudo chown 1000:1000 backups`. A symlink to a bigger disk works the same way (the target must be owned by 1000).

### On a home server

The image is built from the repo root (`server/` and `shared/` are in the build context), so copy the tree to the machine (or clone the repository there) and run the first start there, with `sudo docker compose` if needed:

```bash
# from the repo root; copy only what the build needs (never your own exports or .env files)
rsync -a --relative --exclude build --exclude .gradle --exclude .kotlin --exclude deploy/.env --exclude deploy/backups \
    gradle shared server deploy .dockerignore <user>@<server-ip>:moneybook/
ssh <user>@<server-ip>
cd moneybook/deploy
```

## The page

`GET /` is a status page, served only to trusted networks. It shows:

- **Connect the app**: the server URL (the address you opened) and the API key, with Show and Copy and Regenerate.
- **Devices**: every device that logged in, with its last sync; Remove signs one out.
- **Data**: how many entries, accounts, categories and photos the server holds.
- **Backups**: the last backup, the files, and Back up now.

The same data is available as JSON from `GET /admin/status`. The page is plain http, so Copy works through a fallback (browsers only allow the clipboard API on https).

## Trusted networks and allowed hosts

The server checks every request before anything else:

- **Network**: the TCP source address must be in `MB_TRUSTED_NETWORKS`, a comma-separated list of CIDRs. The default (empty) is `100.64.0.0/10,127.0.0.0/8,::1/128`: Tailscale and loopback. Others get 403 `{"error":"forbidden_network"}`.
- **Host**: the `Host` header must be an IP address, `localhost`, or a name in `MB_ALLOWED_HOSTS` (comma-separated, optional). Otherwise 403 `forbidden_host`. This stops DNS-rebinding tricks; add your MagicDNS name here only if you want to open the page by name.
- **Origin**: every state-changing request from a browser (the page's buttons) must carry this page's own origin. Otherwise 403 `forbidden_origin`. The app is not affected.

`GET /health` skips all three checks. If you want a LAN device to reach the server too, add its network to `MB_TRUSTED_NETWORKS` (for example `100.64.0.0/10,127.0.0.0/8,::1/128,10.1.2.0/24`), but then the app still sends plain http only to Tailscale addresses.

The compose file publishes the port on `0.0.0.0` (IPv4 only), so Docker keeps each client's real source address for these rules; the rules, not the port binding, are what keep the LAN out. Check it from another machine on the LAN (not in your tailnet):

```bash
curl -m 5 http://<lan-ip-of-server>:8095/          # {"error":"forbidden_network"}
curl -m 5 http://<lan-ip-of-server>:8095/health    # {"status":"ok"} (health is exempt)
```

If the first command returns the page, stop and check `MB_TRUSTED_NETWORKS` and your Docker version (with an old Docker or a userland proxy the source address may be replaced by the Docker bridge's, which is not trusted by default either; do not add the bridge network to the list).

### Required: block spoofed Tailscale addresses on the Docker host

The rules above trust the source address. Docker publishes the port through NAT, and a machine on your LAN can send packets whose source address is a made-up `100.64.0.0/10` one (a LAN switch does not check it), which would pass as "Tailscale". Close that on the Docker host with one firewall rule that drops anything claiming a Tailscale address that did not arrive through the Tailscale interface:

```bash
sudo iptables -I DOCKER-USER -s 100.64.0.0/10 ! -i tailscale0 -j DROP
```

`DOCKER-USER` is where Docker leaves room for your own rules; it is kept when Docker restarts but not across a reboot, so make it permanent with a systemd unit, `/etc/systemd/system/moneybook-firewall.service`:

```ini
[Unit]
Description=Drop spoofed Tailscale source addresses before Docker's published ports
After=docker.service
Requires=docker.service

[Service]
Type=oneshot
RemainAfterExit=yes
ExecStart=/bin/sh -c 'iptables -C DOCKER-USER -s 100.64.0.0/10 ! -i tailscale0 -j DROP 2>/dev/null || iptables -I DOCKER-USER -s 100.64.0.0/10 ! -i tailscale0 -j DROP'

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload && sudo systemctl enable --now moneybook-firewall.service
sudo iptables -L DOCKER-USER -n -v      # the DROP rule is listed (packet counter grows when something is dropped)
```

If your Tailscale interface is not called `tailscale0` (`ip link`), use its name. (With nftables only, add an equivalent rule to the forward hook.) Also from the LAN, `curl -m 5 http://<server-lan-ip>:8095/` must still fail as shown above.

Good to know:

- A tool on the Docker host itself that opens `http://127.0.0.1:8095/` arrives through Docker's proxy from the bridge gateway (for example `172.17.0.1`), which is not trusted, so it gets 403. Use the machine's Tailscale address (`http://100.x.y.z:8095/`) or `docker exec moneybook ...`.
- `/health` is exempt from all checks (it only answers that the server is up).
- The backups in `./backups` contain the API key in plain text (the database holds it so the page can show it). They are owner-only files in a 700 directory; keep it that way and treat the folder like a password.

## API key

The server has one owner and no accounts; the app logs in with one API key. Where it comes from:

- By default the server makes a 64-hex-character key at its first start, stores it, and shows it on the page. Regenerate on the page makes a new one.
- Or set `MB_API_KEY` in `.env` (at least 32 characters, `openssl rand -hex 32`) and run `docker compose up -d`. The key from `.env` wins over the stored one, and Regenerate then answers 409 `key_from_env`: change `.env` instead. Removing `MB_API_KEY` later keeps the last key.

Whenever the key changes, every device is signed out: it has to log in again with the new key (the server drops all refresh tokens). Phones keep their data and merge again after the new login. Access tokens last 15 minutes, so a signed-out device may still sync for a few minutes. The key is stored in the database, so a backup holds it.

## Backups

The server backs up in-process, at 03:00 (container time zone, `TZ` in `.env`), and on demand with Back up now on the page. Files go to `backups/`:

```
backups/daily/    moneybook-YYYY-MM-DD.dump  attachments-YYYY-MM-DD.tgz   newest 7
backups/weekly/   the Sunday pair                                         newest 4
backups/monthly/  the 1st-of-month pair                                   newest 6
```

Files are owner-only (600, directories 700). A failed run is shown on the page and logged, and never deletes older backups.

**Backups hold your financial data and the API key.** Keep `backups` owner-only and copy it off this machine yourself (another disk, an encrypted remote); a backup on the same disk does not survive the disk.

## Restore

Restore replaces the live data, so make sure the backup is the one you want. It runs inside the container; the container keeps running and the server pauses for a few seconds:

```bash
docker exec -it moneybook restore /backups/daily/moneybook-<date>.dump /backups/daily/attachments-<date>.tgz
```

Type `yes` to continue (`--yes` skips the question). The attachments file is optional. The command checks the files first (a bad file is refused and nothing is touched), stops the server, restores the dump into a side database, and only when that worked swaps it in (and the attachments with it) and starts the server again. If anything fails it exits non-zero, prints that the live data is unchanged and the server starts again on the old data; fix the cause and run it again (the backup files are not touched). If the restore process is killed, the entrypoint notices the dead lock and starts the server again by itself.

The restore gives the server a new epoch, so phones notice the data went back in time and push all their data again; nothing a phone still holds is lost. The API key stays the current one (the backup's key is not brought back), but every device is signed out and logs in again with the key shown on the page. Try it once on test data before you need it.

## Updating, logs, stopping

```bash
git pull && docker compose up -d --build   # rebuilds the image; the database migrates itself at start
docker compose logs -f moneybook
docker compose down                        # stops, keeps the volume; `down -v` deletes the data
```

`docker stop` gives the server and Postgres up to 30 s to shut down cleanly. `.env` options: `MB_API_KEY`, `MB_HOST_PORT`, `TZ`, `MB_TRUSTED_NETWORKS`, `MB_ALLOWED_HOSTS`.

## Moving from the old three-container stack

Earlier versions ran `db`, `api` and `backup` and used `tailscale serve` for HTTPS. Stop that stack with its old compose file (`docker compose down`, add `-v` only if the data is disposable), turn the `tailscale serve` entry off, then start this one. The data is not migrated automatically: restore an old `pg_dump` with `pg_restore` into the new database if you need it, or log in from the phone and merge.
