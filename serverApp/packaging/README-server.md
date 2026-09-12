# CNCVerse Bridge Server (headless)

UI-less Linux server edition. Runs the full bridge engine and exposes a
complete web admin panel (server control, extensions, per-plugin settings,
tunnel, logs, updates) on the same port as the Stremio addon.

## Quick start (tar.gz)

```bash
serverApp/packaging/build.sh --no-deb    # produces serverApp/dist/*.tar.gz
tar xzf cncverse-bridge-server-<version>.tar.gz -O cncverse-bridge
./bin/cncverse-bridge-server
```

Open `http://<server-ip>:8080/admin`.

## Debian / Ubuntu (.deb)

```bash
serverApp/packaging/build.sh
sudo apt install ./serverApp/dist/cncverse-bridge-server_<version>_all.deb
sudo systemctl start cncverse-bridge-server
```

The package installs a systemd service running as the `cncverse` system user,
enabled by default. Web admin: `http://<server-ip>:8080/admin`.

## Docker

```bash
docker build -f serverApp/Dockerfile -t cncverse-bridge-server .
docker run -d -p 8080:8080 -v cncverse-data:/config cncverse-bridge-server
```

## Configuration

| Environment variable | Default                | Purpose                                      |
|---------------------|------------------------|----------------------------------------------|
| `CNC_PORT`          | `8080`                 | Preferred HTTP port (also `--port=NNNN`)     |
| `CNC_CACHE_DIR`     | `~/.cncverse_bridge`   | Downloaded .cs3 plugin cache                 |
| `CNC_CONFIG_DIR`    | `~/.cncverse`          | Repos, extension settings, cloudflared       |
| `CNC_ADMIN_TOKEN`   | *(unset)*              | Token required for `/admin` + `/api/admin/*` |

## Security notes

The server binds `0.0.0.0` so Stremio/Nuvio on your LAN can reach the addon.
The admin panel is equally reachable — for anything beyond a trusted home LAN,
set `CNC_ADMIN_TOKEN` and/or front it with a reverse proxy.
