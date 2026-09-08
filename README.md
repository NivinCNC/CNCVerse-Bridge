# CNCVerse Bridge

[![Join us on Telegram](https://img.shields.io/badge/Telegram-Join%20Group-2CA5E0?style=for-the-badge&logo=telegram&logoColor=white)](https://t.me/cncverse)
[![Support Project (UPI Supported)](https://img.shields.io/badge/Support%20Project%20%28UPI%20Supported%29-FF0000?style=for-the-badge&logo=buy-me-a-coffee&logoColor=black)](https://cncverse.pages.dev)

An application addon to run Cloudstream extensions on Nuvio, Stremio, and every other Stremio-supported platform.

> **Note:** This app is currently in the alpha stage. You may experience bugs or crashes. Join our community to report issues! If you are a developer, PRs for fixes are always welcome.

## Downloads

CNCVerse Bridge is available for both Android and Desktop (Windows).

Go to the **[Releases](../../releases)** page to download:
- **Android:** Download the `.apk` file.
- **Desktop:** Download the `.msi` or `.exe` file for windows.

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

## Getting Started (Desktop)

1. **Install and Run:** Install the `.msi` (Windows) or run the desktop app. 
2. The app will launch and display the server status and the local addon URL.
3. **Same-Device Streaming:** If you run Stremio on the same computer, you can click the **Add to Stremio** button or manually add `http://127.0.0.1:8080/manifest.json` in Stremio.
4. **Local Network Streaming:** You can also use the Desktop app to host the bridge for other devices on your Wi-Fi network. Simply use the local IP address shown in the app (e.g., `http://192.168.1.100:8080/manifest.json`) on your TV or phone.

### ⚠️ Desktop Limitations

Currently, the Desktop version lacks full **WebView support**. This means:
- Features relying on Cloudflare bypass (which uses a hidden WebView) will not work.
- FebBox / ShowBox login flows that require a web interface will fail.
For full compatibility with these specific providers, please use the Android version.

---

## Support & Community

Join our **[Telegram group](https://t.me/cncverse)** to discuss extensions, request features, or report issues.

If you find this project useful, consider supporting the development!  
**[☕ Buy Me a Coffee](https://buymeacoffee.com/nivincnc)**

---

## License

All rights reserved. No part of this codebase may be copied, modified, distributed, or otherwise used without explicit permission from the copyright owner.

**Note:** Files originating from the Cloudstream project retain their original licenses and copyright notices as applicable under the Cloudstream project and are not covered by this proprietary license. See the [LICENSE](LICENSE) file for more details.
