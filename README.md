# CNCVerse Bridge

[![Join us on Telegram](https://img.shields.io/badge/Telegram-Join%20Group-2CA5E0?style=for-the-badge&logo=telegram&logoColor=white)](https://t.me/cncverse)
[![Support Project (UPI Supported)](https://img.shields.io/badge/Support%20Project%20%28UPI%20Supported%29-FF0000?style=for-the-badge&logo=buy-me-a-coffee&logoColor=black)](https://cncverse.pages.dev)

[![Discord](https://invidget.switchblade.xyz/jG8aKTtDze)](https://discord.gg/jG8aKTtDze)

An application addon to run Cloudstream extensions on Nuvio, Stremio, and every other Stremio-supported platform.

> **Note:** This app is currently in the alpha stage. You may experience bugs or crashes. Join our community to report issues! If you are a developer, PRs for fixes are always welcome.

## Downloads

CNCVerse Bridge is available for Android and Desktop (Windows/Linux).

Go to the **[Releases](../../releases)** page to download:
- **Android:** Download the `.apk` file.
- **Desktop:** Download the `.msi`/`.exe` for Windows.
- **Server:** Download the `.deb` for Linux (Debian/Ubuntu and derivatives).

---

## Getting Started (Android)

1. **Install and Run:** Install the downloaded `.apk` and open the CNCVerse Bridge app.
2. **Start Server:** The app will run a local server in the background and display an addon URL on the screen (e.g., `http://127.0.0.1:8080/manifest.json`).

### Usage with Stremio

1. Copy the addon URL provided in the CNCVerse Bridge app.
2. **Important:** Stremio needs HTTPS: set up your Cloudflare Tunnel (see [Stremio Mode](#stremio-mode-https-through-your-own-cloudflare-tunnel)) and enable the "Stremio Mode" toggle.
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

### ⚠️ Desktop Limitations

Currently, the Desktop version lacks full **WebView support**. This means:
- Features relying on Cloudflare bypass will use the system browser (Chrome/Chromium/Edge/Brave are auto-detected on Linux) instead of a hidden WebView.
- FebBox / ShowBox login flows that require a web interface open in the system browser.
For full compatibility with these specific providers, please use the Android version.

---

## Stremio Mode (HTTPS through your own Cloudflare Tunnel)

Stremio (especially Stremio Web) needs an HTTPS addon URL. Stremio Mode serves the bridge through **your own** Cloudflare Tunnel and domain:

1. Add your domain to Cloudflare (the free plan is enough).
2. In the Cloudflare dashboard open **Zero Trust → Networks → Tunnels → Create a tunnel** (Cloudflared).
3. Copy the token from the install command (the long text after `--token`).
4. Under **Public Hostname** add e.g. `bridge.yourdomain.com` → service **HTTP**, URL `localhost:8080`.
5. In the app open **Settings → Cloudflare Tunnel**, paste the token and the hostname, and save.
6. Turn on **Stremio Mode** on the Server tab. Your addon URL is `https://bridge.yourdomain.com/manifest.json`.

The app downloads `cloudflared` on first use.

---

## What's in the app

- **Server** — start/stop, addon URLs for Nuvio (LAN) and Stremio (HTTPS), Stremio Mode
- **Extensions** — repositories, search & install, update all, install a whole repo, switch extensions on/off, per-extension settings
- **Logs** — live log with copy/clear
- **Settings** — Cloudflare Tunnel, streams (resolutions, CAM, size limits, non-seekable files, subtitles, sorting, extension priority), catalogs (hide all or pick which ones; genres come from each home page, refreshed every 30 min), stream formatter (on by default, presets and preview), stream cache

---

## Building from source

```bash
./gradlew :androidApp:assembleRelease                        # Android apk (signed when a keystore is configured)
./gradlew :desktopApp:packageReleaseDistributionForCurrentOS # Windows .msi/.exe or Linux .deb
./gradlew :desktopApp:createDistributable                    # desktop portable folder
```

### Releases

Pushing a version tag builds and publishes everything through GitHub Actions (`.github/workflows/release.yml`):

```bash
git tag v1.0.0 && git push origin v1.0.0
```

The Android signing key comes from the repository secrets `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` and `ANDROID_KEY_PASSWORD`.

---

## Support & Community

Join our **[Telegram group](https://t.me/cncverse)** to discuss extensions, request features, or report issues.

If you find this project useful, consider supporting the development!  
**[☕ Buy Me a Coffee](https://buymeacoffee.com/nivincnc)**

---

## License

All rights reserved. No part of this codebase may be copied, modified, distributed, or otherwise used without explicit permission from the copyright owner.

**Note:** Files originating from the Cloudstream project retain their original licenses and copyright notices as applicable under the Cloudstream project and are not covered by this proprietary license. See the [LICENSE](LICENSE) file for more details.
