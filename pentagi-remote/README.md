# pentagi-remote — PentAGI on a Linux/Docker host, managed from HostMyWeb

PentAGI ([vxcontrol/pentagi](https://github.com/vxcontrol/pentagi)) needs a real
Docker daemon: it mounts `/var/run/docker.sock` and spawns agent sandbox
containers with `NET_ADMIN`/`NET_RAW`. It **cannot run on Android** — this
bundle is what you install on a Linux VPS / home server, and the HostMyWeb
PentAGI tab controls it through the small manager in `manager/`.

```
Phone (HostMyWeb) --Bearer token--> manager (127.0.0.1:8787 on the VPS,
                                     reached via Tailscale / SSH tunnel)
                                     |
                                     +-- execFile docker compose (fixed argv)
                                     +-- probes https://127.0.0.1:8443
VPS: docker compose: pentagi :8443 + pgvector + scraper
```

## 1. Install Docker (on the VPS)

```bash
curl -fsSL https://get.docker.com | sh
sudo usermod -aG docker $USER   # then re-login
docker compose version
```

## 2. Configure + start PentAGI

```bash
cp -r pentagi-remote ~/pentagi-remote && cd ~/pentagi-remote
cp .env.example .env
# Edit .env: set at least ONE LLM key, a strong PENTAGI_POSTGRES_PASSWORD
# (openssl rand -hex 24) and COOKIE_SIGNING_SALT (openssl rand -hex 32).
docker compose up -d
docker compose ps
```

PentAGI UI: `https://127.0.0.1:8443` on the host. Default login
`admin@pentagi.com` / `admin` — **change it immediately**. Expose the UI only
behind a reverse proxy with real TLS, Tailscale, or a Cloudflare Tunnel.

## 3. Run the manager

```bash
cd manager
export MANAGER_TOKEN="$(openssl rand -hex 32)"   # >= 24 chars, keep secret
export PENTAGI_DIR="$HOME/pentagi-remote"          # dir with docker-compose.yml
node server.js                                     # Node >= 18, zero dependencies
```

Or as a systemd service (`/etc/systemd/system/pentagi-manager.service`):

```ini
[Unit]
Description=PentAGI remote manager (HostMyWeb)
After=network.target docker.service

[Service]
Type=simple
User=YOUR_USER
WorkingDirectory=/home/YOUR_USER/pentagi-remote/manager
Environment=MANAGER_TOKEN=REPLACE_WITH_LONG_RANDOM_TOKEN
Environment=PENTAGI_DIR=/home/YOUR_USER/pentagi-remote
Environment=MANAGER_BIND=127.0.0.1
Environment=MANAGER_PORT=8787
ExecStart=/usr/bin/node server.js
Restart=on-failure

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload && sudo systemctl enable --now pentagi-manager
```

## 4. Connect HostMyWeb

Make the manager reachable from the phone **privately** — pick one:

- **Tailscale:** install Tailscale on VPS + phone; manager URL = `http://100.x.y.z:8787`.
- **SSH tunnel:** Termux `ssh -L 8787:127.0.0.1:8787 user@vps`; manager URL = `http://127.0.0.1:8787`.
- **Cloudflare Tunnel:** route a hostname to `http://127.0.0.1:8787` and protect it with Access.

Then in HostMyWeb → PentAGI tab → Configuration: manager URL, the same
`MANAGER_TOKEN`, PentAGI URL (e.g. `https://your-vps:8443` or its tunnel
hostname), your LLM provider, and flip *PentAGI integration enabled* on.
Start / Stop / Restart / Status / Logs then work from the tab.

## Security notes

- The manager binds `127.0.0.1` by default and must stay private (see above).
  It holds effective Docker control of this host — treat the token like a root password.
- The manager only ever runs fixed `docker compose` argv arrays (no shell);
  request bodies are ignored; the log tail is a validated integer.
- API keys live only in the remote `.env`. The manager reports *which* keys
  are set (names only) and redacts secret values from returned logs.
- Keep PentAGI itself bound to `127.0.0.1` (`PENTAGI_LISTEN_IP`) unless a
  TLS-terminating reverse proxy is in front of it.
