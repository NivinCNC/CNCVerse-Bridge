# CNCVerse Bridge

[![Join us on Telegram](https://img.shields.io/badge/Telegram-Join%20Group-2CA5E0?style=for-the-badge&logo=telegram&logoColor=white)](https://t.me/cncverse)
[![Support Project (UPI Supported)](https://img.shields.io/badge/Support%20Project%20%28UPI%20Supported%29-FF0000?style=for-the-badge&logo=buy-me-a-coffee&logoColor=black)](https://cncverse.pages.dev)

An application addon to run Cloudstream extensions on Nuvio, Stremio, and every other Stremio-supported platform.

> **Note:** This app is currently in the alpha stage. You may experience bugs or crashes. Join our community to report issues! If you are a developer, PRs for fixes are always welcome.

## Downloads

CNCVerse Bridge is available for Android, Desktop (Windows/Linux) and as a **headless server** for Linux.

Go to the **[Releases](../../releases)** page to download:
- **Android:** Download the `.apk` file.
- **Desktop:** Download the `.msi`/`.exe` for Windows or the `.deb` for Linux (Debian/Ubuntu and derivatives).
- **Server (Linux, no UI):** Download `cncverse-bridge-server_<version>_all.deb` or the portable `.tar.gz`.

---

## Getting Started (Android)

1. **Install and Run:** Install the downloaded `.apk` and open the CNCVerse Bridge app.
2. **Start Server:** The app will run a local server in the background and display an addon URL on the screen (e.g., `http://127.0.0.1:8080/manifest.json`).

### Usage with Stremio

1. Copy the addon URL provided in the CNCVerse Bridge app.
2. **Important:** Enable the "Stremio Mode" toggle in the CNCVerse Bridge app if you are using it with Stremio.
3. Open the **Stremio** app and go to the **Addons** section.
4. Paste the copied URL into the search bar or addon URL field.
5. Tap **Install** to add the CNCVerse Bridge addon.

### Usage with Nuvio

1. Ensure the CNCVerse Bridge app is running in the background.
2. Open the **Nuvio** app.
3. Nuvio will automatically detect the local CNCVerse Bridge addon—no manual URL pasting is required!

---

## Getting Started (Desktop — Windows & Linux)

1. **Install and Run:** Install the `.msi` (Windows) or `.deb` (Linux), or run the desktop app from the distribution folder.
2. The app will launch and display the server status and the local addon URL.
3. **Same-Device Streaming:** If you run Stremio on the same computer, you can click the **Add to Stremio** button or manually add `http://127.0.0.1:8080/manifest.json` in Stremio.
4. **Local Network Streaming:** You can also use the Desktop app to host the bridge for other devices on your Wi-Fi network. Simply use the local IP address shown in the app (e.g., `http://192.168.1.100:8080/manifest.json`) on your TV or phone.

### Optional: web admin panel on the desktop app

The desktop app can also expose the **web admin panel** (same one the server edition uses):

```bash
CNC_WEB_ADMIN=1 CNC_ADMIN_TOKEN=my-secret ./CNCVerse\ Bridge
# then open http://127.0.0.1:8080/admin?token=my-secret
```

### ⚠️ Desktop Limitations

Currently, the Desktop version lacks full **WebView support**. This means:
- Features relying on Cloudflare bypass will use the system browser (Chrome/Chromium/Edge/Brave are auto-detected on Linux) instead of a hidden WebView.
- FebBox / ShowBox login flows that require a web interface open in the system browser.
For full compatibility with these specific providers, please use the Android version.

---

## Getting Started (Server — headless Linux, no UI)

The server edition runs the **exact same bridge engine** without any desktop UI. Everything the desktop app can do is available from a **web admin panel** served on the same port as the addon: server start/stop/restart, Stremio mode + Cloudflare tunnel, repository management, plugin install/update/uninstall/enable, per-plugin settings and live logs.

### Install (.deb, recommended)

```bash
sudo apt install ./cncverse-bridge-server_<version>_all.deb
sudo systemctl start cncverse-bridge-server     # also enabled on boot
```

The service runs as the `cncverse` system user and keeps its data under that user's home. Then open:

```
http://<server-ip>:8080/admin
```

### Portable (tar.gz)

```bash
tar xzf cncverse-bridge-server-<version>.tar.gz
./bin/cncverse-bridge-server --port=8080
```

### Docker

```bash
docker build -f serverApp/Dockerfile -t cncverse-bridge-server .
docker run -d -p 8080:8080 -v cncverse-data:/config cncverse-bridge-server
```

### Configuration (environment variables)

| Variable           | Default              | Purpose                                       |
|--------------------|----------------------|-----------------------------------------------|
| `CNC_PORT`         | `8080`               | Preferred HTTP port (also `--port=NNNN`)       |
| `CNC_CACHE_DIR`    | `~/.cncverse_bridge` | Downloaded `.cs3` plugin cache                 |
| `CNC_CONFIG_DIR`   | `~/.cncverse`        | Repos, extension settings, cloudflared binary  |
| `CNC_ADMIN_TOKEN`  | *(unset)*            | Token required for `/admin` and `/api/admin/*` |

> **Security:** the server binds `0.0.0.0` so Stremio on your LAN can reach it — which also makes the admin panel reachable from your network. For anything beyond a trusted home LAN, set `CNC_ADMIN_TOKEN` (then open `/admin?token=…`) and/or put the server behind a reverse proxy.

### Daily use from the browser

- **Server** tab — start/stop/restart, copy the addon URL, enable Stremio Mode + the Cloudflare tunnel (cloudflared is downloaded on demand)
- **Extensions** tab — add/remove repositories, search & install plugins, update, enable/disable
- **Settings** tab — per-plugin settings (toggles, tokens, provider lists) discovered at runtime, exactly like the desktop gear dialog; changes apply with one click via *Apply & Reload*
- **Logs** tab — live log tail with copy/clear

The Stremio addon endpoints behave identically to the desktop edition — point Stremio/Nuvio at `http://<server-ip>:8080/manifest.json`.

---

## Building from source

```bash
./gradlew :desktopApp:packageDeb          # desktop Linux .deb (needs fakeroot)
./gradlew :desktopApp:createDistributable # desktop portable folder
./gradlew :desktopApp:packageMsi           # desktop Windows .msi
serverApp/packaging/build.sh              # headless server .deb + .tar.gz
./gradlew :androidApp:assembleDebug        # Android apk
```

---

## Support & Community

Join our **[Telegram group](https://t.me/cncverse)** to discuss extensions, request features, or report issues.

If you find this project useful, consider supporting the development!  
**[☕ Buy Me a Coffee](https://buymeacoffee.com/nivincnc)**

---

## License

All rights reserved. No part of this codebase may be copied, modified, distributed, or otherwise used without explicit permission from the copyright owner.

**Note:** Files originating from the Cloudstream project retain their original licenses and copyright notices as applicable under the Cloudstream project and are not covered by this proprietary license. See the [LICENSE](LICENSE) file for more details.
