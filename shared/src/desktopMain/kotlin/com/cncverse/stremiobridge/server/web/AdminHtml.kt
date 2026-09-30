package com.cncverse.stremiobridge.server.web

import com.cncverse.stremiobridge.state.ServerState

/**
 * The single-page web admin UI served at `/admin`. Pure HTML + CSS + vanilla
 * JS (no build step, no external assets). Fully responsive (mobile-first),
 * includes:
 *  - Server, Extensions, Logs, About tabs (Settings tab removed — each addon
 *    card has an inline ⚙ Settings drawer)
 *  - Install All button per repo
 *  - Live UI update after install (no reload required)
 *  - Append-only log rendering (no glitch from full DOM replacement)
 *  - Profile-based manifest URL (per-session extension disable)
 *  - Local-only repos (not saved globally) with "Save Globally" button
 *  - 30-min update check triggered client-side after 30 minutes
 *
 * NOTE: JavaScript below intentionally avoids template literals and any '$'
 * characters so it can live inside a Kotlin raw string without escaping.
 */
object AdminHtml {

    val page: String = """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">
<title>CNCVerse Bridge — Admin Panel</title>
<link rel="icon" type="image/png" href="/logo.png">
<link rel="shortcut icon" href="/logo.png">
<link rel="apple-touch-icon" href="/logo.png">
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700;800&display=swap" rel="stylesheet">
<style>
/* ── Eye-Friendly Base Themes ────────────────────────── */
:root, [data-base-theme="slate"] {
  --bg: #0f172a;
  --surface: #1e293b;
  --surface-active: #334155;
  --card: #1e293b;
  --card2: #243248;
  --card-radius: 14px;
  --border: #334155;
  --border-focus: #475569;
  --border-active: var(--accent);
  --divider: rgba(255, 255, 255, 0.08);
  --accent: #8b5cf6;
  --accent-hover: #7c3aed;
  --accent-light: rgba(139, 92, 246, 0.16);
  --accent-glow: rgba(139, 92, 246, 0.28);
  --text: #ffffff;
  --text2: #cbd5e1;
  --muted: #94a3b8;
  --green: #10b981;
  --green-bg: rgba(16, 185, 129, 0.14);
  --red: #f43f5e;
  --red-bg: rgba(244, 63, 94, 0.14);
  --amber: #f59e0b;
  --amber-bg: rgba(245, 158, 11, 0.14);
  --blue: #38bdf8;
  --blue-bg: rgba(56, 189, 248, 0.14);
  --shadow: 0 4px 20px rgba(0, 0, 0, 0.35);
}

[data-base-theme="charcoal"] {
  --bg: #18181b;
  --surface: #27272a;
  --surface-active: #3f3f46;
  --card: #27272a;
  --card2: #323236;
  --card-radius: 14px;
  --border: #3f3f46;
  --border-focus: #52525b;
  --border-active: var(--accent);
  --divider: rgba(255, 255, 255, 0.08);
  --text: #ffffff;
  --text2: #d4d4d8;
  --muted: #a1a1aa;
  --green: #10b981;
  --green-bg: rgba(16, 185, 129, 0.14);
  --red: #f43f5e;
  --red-bg: rgba(244, 63, 94, 0.14);
  --amber: #f59e0b;
  --amber-bg: rgba(245, 158, 11, 0.14);
  --blue: #38bdf8;
  --blue-bg: rgba(56, 189, 248, 0.14);
  --shadow: 0 4px 20px rgba(0, 0, 0, 0.35);
}

[data-base-theme="navy"] {
  --bg: #0d1117;
  --surface: #161b22;
  --surface-active: #21262d;
  --card: #161b22;
  --card2: #1c2128;
  --card-radius: 14px;
  --border: #30363d;
  --border-focus: #484f58;
  --border-active: var(--accent);
  --divider: rgba(255, 255, 255, 0.08);
  --text: #ffffff;
  --text2: #cbd5e1;
  --muted: #94a3b8;
  --green: #10b981;
  --green-bg: rgba(16, 185, 129, 0.14);
  --red: #f43f5e;
  --red-bg: rgba(244, 63, 94, 0.14);
  --amber: #f59e0b;
  --amber-bg: rgba(245, 158, 11, 0.14);
  --blue: #38bdf8;
  --blue-bg: rgba(56, 189, 248, 0.14);
  --shadow: 0 4px 20px rgba(0, 0, 0, 0.35);
}

[data-base-theme="forest"] {
  --bg: #0c1512;
  --surface: #13221d;
  --surface-active: #1a2f28;
  --card: #13221d;
  --card2: #172a24;
  --card-radius: 14px;
  --border: #223c33;
  --border-focus: #2f5246;
  --border-active: var(--accent);
  --divider: rgba(255, 255, 255, 0.08);
  --text: #ffffff;
  --text2: #c7eedd;
  --muted: #8ecbb0;
  --green: #10b981;
  --green-bg: rgba(16, 185, 129, 0.14);
  --red: #f43f5e;
  --red-bg: rgba(244, 63, 94, 0.14);
  --amber: #f59e0b;
  --amber-bg: rgba(245, 158, 11, 0.14);
  --blue: #38bdf8;
  --blue-bg: rgba(56, 189, 248, 0.14);
  --shadow: 0 4px 20px rgba(0, 0, 0, 0.35);
}

[data-base-theme="oled"] {
  --bg: #000000;
  --surface: #0a0a0a;
  --surface-active: #141414;
  --card: #0f0f0f;
  --card2: #161616;
  --card-radius: 14px;
  --border: #242424;
  --border-focus: #383838;
  --border-active: var(--accent);
  --divider: rgba(255, 255, 255, 0.08);
  --text: #ffffff;
  --text2: #e5e5e5;
  --muted: #a3a3a3;
  --green: #10b981;
  --green-bg: rgba(16, 185, 129, 0.14);
  --red: #f43f5e;
  --red-bg: rgba(244, 63, 94, 0.14);
  --amber: #f59e0b;
  --amber-bg: rgba(245, 158, 11, 0.14);
  --blue: #38bdf8;
  --blue-bg: rgba(56, 189, 248, 0.14);
  --shadow: 0 4px 20px rgba(0, 0, 0, 0.45);
}

[data-base-theme="light"], [data-theme="light"] {
  --bg: #f8fafc;
  --surface: #ffffff;
  --surface-active: #f1f5f9;
  --card: #ffffff;
  --card2: #f8fafc;
  --card-radius: 14px;
  --border: #e2e8f0;
  --border-focus: #cbd5e1;
  --border-active: var(--accent);
  --divider: rgba(0, 0, 0, 0.08);
  --text: #0f172a;
  --text2: #334155;
  --muted: #64748b;
  --green: #059669;
  --green-bg: rgba(5, 150, 105, 0.1);
  --red: #e11d48;
  --red-bg: rgba(225, 29, 72, 0.1);
  --amber: #d97706;
  --amber-bg: rgba(217, 119, 6, 0.1);
  --blue: #0284c7;
  --blue-bg: rgba(2, 132, 199, 0.1);
  --shadow: 0 4px 20px rgba(0, 0, 0, 0.06);
}
* { box-sizing: border-box; margin: 0; padding: 0; }
html {
  background: var(--bg);
  color: var(--text);
  min-height: 100vh;
  overflow-x: clip;
}
body {
  background: var(--bg);
  color: var(--text);
  font-family: 'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
  font-size: 13.5px;
  line-height: 1.5;
  min-height: 100vh;
  -webkit-font-smoothing: antialiased;
  overflow-x: clip;
  max-width: 100vw;
  width: 100%;
}
a { color: var(--accent); text-decoration: none; }
a:hover { text-decoration: underline; }

/* ── Sticky Top Navigation Header ────────────────────── */
.sticky-nav-header {
  position: sticky;
  top: 0;
  z-index: 1000;
  background: var(--surface);
  border-bottom: 1px solid var(--divider);
  backdrop-filter: blur(16px);
  -webkit-backdrop-filter: blur(16px);
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.25);
  width: 100%;
}

header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 16px;
  height: 52px;
  gap: 10px;
  width: 100%;
}
.hdr-brand {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}
.logo-box {
  width: 32px; height: 32px;
  border-radius: 8px;
  background: var(--accent-light);
  border: 1px solid rgba(92, 112, 214, 0.3);
  display: flex; align-items: center; justify-content: center;
  font-weight: 800; font-size: 11px;
  color: var(--accent); letter-spacing: -0.5px;
  flex: 0 0 32px;
  overflow: hidden;
}
.logo-box-img {
  width: 24px;
  height: 24px;
  object-fit: contain;
  display: block;
}
.hdr-title-wrap { display: flex; flex-direction: column; min-width: 0; }
.hdr-title {
  font-size: 14px; font-weight: 800; letter-spacing: -0.3px;
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
  color: var(--text); line-height: 1.2;
}
.hdr-sub { font-size: 11px; color: var(--muted); white-space: nowrap; }
.hdr-actions {
  display: flex; align-items: center; gap: 8px;
  flex-shrink: 0;
}
.statuspill {
  display: flex; align-items: center; gap: 6px;
  background: var(--card); border: 1px solid var(--border);
  border-radius: 999px; padding: 4px 11px;
  font-size: 11.5px; font-weight: 600; white-space: nowrap;
}
.dot { width: 7px; height: 7px; border-radius: 50%; display: inline-block; flex-shrink: 0; }
.dot.green { background: var(--green); box-shadow: 0 0 6px var(--green); }
.dot.amber { background: var(--amber); }
.dot.red { background: var(--red); }
.dot.gray { background: var(--muted); }

.btn-icon-hdr {
  background: var(--card);
  border: 1px solid var(--border);
  color: var(--text);
  width: 36px;
  height: 36px;
  border-radius: 9px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: all 0.15s ease;
  flex-shrink: 0;
  text-decoration: none;
}
.btn-icon-hdr:hover {
  background: var(--surface-active);
  border-color: var(--border-focus);
}
.btn-donate-heart {
  color: #f43f5e !important;
}
.btn-donate-heart:hover {
  background: rgba(244, 63, 94, 0.15) !important;
  border-color: rgba(244, 63, 94, 0.4) !important;
  transform: scale(1.05);
}

@media (max-width: 680px) {
  header { padding: 0 12px; height: 48px; }
  .hdr-sub { display: none; }
  .statuspill { padding: 3px 8px; font-size: 11px; }
}

/* ── App Layout & Drawer / Desktop Sidebar ────────── */
.app-layout {
  display: flex;
  min-height: calc(100vh - 52px);
  width: 100%;
}

.drawer-backdrop {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.65);
  backdrop-filter: blur(4px);
  -webkit-backdrop-filter: blur(4px);
  z-index: 2100;
  opacity: 0;
  pointer-events: none;
  transition: opacity 0.25s ease;
}
.drawer-backdrop.open {
  opacity: 1;
  pointer-events: auto;
}

.drawer {
  background: var(--surface);
  border-right: 1px solid var(--border);
  display: flex;
  flex-direction: column;
  padding: 16px 14px;
  gap: 12px;
  box-sizing: border-box;
}

@media (min-width: 860px) {
  .btn-burger {
    display: none !important;
  }
  .drawer-backdrop {
    display: none !important;
  }
  .drawer {
    position: sticky !important;
    top: 52px !important;
    left: auto !important;
    bottom: auto !important;
    transform: none !important;
    width: 250px !important;
    flex: 0 0 250px !important;
    height: calc(100vh - 52px) !important;
    box-shadow: none !important;
    z-index: 50 !important;
  }
  .drawer-hdr {
    display: none !important;
  }
  .drawer-close {
    display: none !important;
  }
  .main-wrapper {
    flex: 1;
    min-width: 0;
    max-width: 100%;
    padding: 24px 32px 64px;
    margin: 0;
    box-sizing: border-box;
  }
}

@media (max-width: 859px) {
  .drawer {
    position: fixed;
    top: 0;
    left: 0;
    bottom: 0;
    width: 280px;
    max-width: 85vw;
    z-index: 2200;
    transform: translateX(-100%);
    transition: transform 0.25s cubic-bezier(0.16, 1, 0.3, 1);
    box-shadow: 8px 0 28px rgba(0, 0, 0, 0.5);
  }
  .drawer.open {
    transform: translateX(0);
  }
  .drawer-close {
    display: flex;
  }
  .main-wrapper {
    width: 100%;
    padding: 12px 10px 48px;
  }
}

.drawer-hdr {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--border);
}
.drawer-title {
  font-size: 14px;
  font-weight: 800;
  color: var(--text);
  letter-spacing: -0.2px;
}
.drawer-close {
  background: none;
  border: none;
  color: var(--text2);
  cursor: pointer;
  padding: 6px;
  border-radius: 6px;
  align-items: center;
  justify-content: center;
  transition: all 0.15s ease;
}
.drawer-close:hover {
  background: var(--surface-active);
  color: var(--text);
}
.drawer-menu {
  display: flex;
  flex-direction: column;
  gap: 6px;
  overflow-y: auto;
  flex: 1;
}
.drawer-section-label {
  font-size: 10px;
  font-weight: 800;
  letter-spacing: 0.8px;
  text-transform: uppercase;
  color: var(--muted);
  padding: 6px 4px 2px;
}
.drawer-btn {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 12px;
  background: var(--card);
  border: 1px solid var(--border);
  border-radius: 9px;
  color: var(--text);
  font-size: 13px;
  font-weight: 600;
  text-decoration: none;
  cursor: pointer;
  transition: all 0.15s ease;
  width: 100%;
  box-sizing: border-box;
}
.drawer-btn:hover {
  background: var(--surface-active);
  border-color: var(--border-focus);
}
.drawer-btn.active {
  background: var(--accent-light);
  border-color: var(--accent);
  color: var(--text);
  font-weight: 700;
}
.drawer-btn.active svg {
  stroke: var(--accent);
}

/* ── Donation Goal Bar (Clickable Progress Bar) ───────── */
.goal-card {
  background: var(--card);
  border: 1px solid var(--border);
  border-radius: 12px;
  padding: 12px 16px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.15);
  cursor: pointer;
  text-decoration: none;
  transition: all 0.15s ease;
  width: 100%;
  box-sizing: border-box;
}
.goal-card:hover {
  border-color: var(--border-focus);
  transform: translateY(-1px);
}
.goal-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 13.5px;
  font-weight: 700;
  gap: 10px;
}
.goal-text { color: var(--text); font-weight: 700; }
.goal-pct { color: #f43f5e; font-weight: 800; font-size: 13px; }
.goal-bar-row {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
}
.goal-track {
  flex: 1;
  height: 8px;
  background: rgba(255, 255, 255, 0.08);
  border-radius: 999px;
  overflow: hidden;
}
.goal-heart-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  border-radius: 50%;
  background: rgba(244, 63, 94, 0.12);
  border: 1px solid rgba(244, 63, 94, 0.28);
  color: #f43f5e;
  flex-shrink: 0;
  transition: all 0.2s cubic-bezier(0.34, 1.56, 0.64, 1);
  box-shadow: 0 1px 4px rgba(244, 63, 94, 0.15);
}
.goal-card:hover .goal-heart-btn {
  transform: scale(1.18);
  background: rgba(244, 63, 94, 0.22);
  border-color: rgba(244, 63, 94, 0.55);
  box-shadow: 0 0 12px rgba(244, 63, 94, 0.45);
}
[data-theme="light"] .goal-track {
  background: rgba(0, 0, 0, 0.08);
}
.goal-fill {
  height: 100%;
  background: linear-gradient(90deg, #ec4899, #f43f5e);
  border-radius: 999px;
  transition: width 0.4s ease;
}

/* ── Buttons (Matching Public Version Aesthetic) ─────── */
button {
  font: inherit;
  cursor: pointer;
  border: 1px solid var(--border);
  border-radius: 9px;
  padding: 8px 14px;
  background: var(--card2);
  color: var(--text);
  font-weight: 600;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
  transition: all 0.18s cubic-bezier(0.16, 1, 0.3, 1);
  line-height: 1.2;
  white-space: nowrap;
  -webkit-tap-highlight-color: transparent;
  min-height: 36px;
  box-sizing: border-box;
}
button:hover:not(:disabled) {
  background: var(--surface-active);
  border-color: var(--border-focus);
  transform: translateY(-1px);
}
button:active:not(:disabled) {
  transform: scale(0.97);
}
button:disabled { opacity: 0.4; cursor: not-allowed; transform: none; }
button.primary {
  background: var(--accent);
  border-color: var(--accent);
  color: #ffffff;
  box-shadow: 0 2px 10px var(--accent-glow);
}
button.primary:hover:not(:disabled) {
  background: var(--accent-hover);
  border-color: var(--accent-hover);
  box-shadow: 0 4px 14px var(--accent-glow);
}
button.danger {
  background: var(--red-bg);
  color: var(--red);
  border-color: rgba(244, 63, 94, 0.28);
}
button.danger:hover:not(:disabled) {
  background: rgba(244, 63, 94, 0.22);
  border-color: var(--red);
}
button.ghost {
  background: var(--surface);
  color: var(--text2);
  border-color: var(--border);
}
button.ghost:hover:not(:disabled) {
  background: var(--surface-active);
  color: var(--text);
  border-color: var(--border-focus);
}
button.success {
  background: var(--green-bg);
  color: var(--green);
  border-color: rgba(16, 185, 129, 0.28);
}
button.success:hover:not(:disabled) {
  background: rgba(16, 185, 129, 0.22);
  border-color: var(--green);
}
button.small {
  padding: 5px 11px;
  border-radius: 7px;
  font-size: 12px;
  min-height: 30px;
}

input[type=text], input[type=password], input[type=number], select, .filter-select, .custom-select {
  font: inherit;
  background: var(--card2);
  border: 1px solid var(--border);
  border-radius: 8px;
  color: var(--text);
  padding: 8px 12px;
  outline: none;
  width: 100%;
  max-width: 100%;
  box-sizing: border-box;
  transition: border-color 0.15s;
  cursor: pointer;
}
input:focus, select:focus, .filter-select:focus, .custom-select:focus { border-color: var(--accent); }
textarea.tpl {
  font: 12.5px/1.5 ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  background: var(--card2);
  border: 1px solid var(--border);
  border-radius: 8px;
  color: var(--text);
  padding: 10px 12px;
  outline: none;
  width: 100%;
  box-sizing: border-box;
  resize: vertical;
  white-space: pre;
  overflow-x: auto;
}
textarea.tpl:focus { border-color: var(--accent); }
.fmt-label { font-size: 12px; font-weight: 600; color: var(--muted); margin: 12px 0 6px; display: block; }
.fmt-sample { background: var(--card2); border: 1px solid var(--border); border-radius: 10px; padding: 12px 14px; margin-bottom: 10px; }
.fmt-sample .fmt-kind { font-size: 11px; color: var(--muted); text-transform: uppercase; letter-spacing: .04em; margin-bottom: 6px; }
.fmt-sample .fmt-name { font-weight: 700; white-space: pre-wrap; word-break: break-word; margin-bottom: 6px; }
.fmt-sample .fmt-desc { font-size: 12.5px; white-space: pre-wrap; word-break: break-word; color: var(--text); opacity: .85; }
.fmt-vars { display: flex; flex-wrap: wrap; gap: 6px; }
.fmt-vars code { cursor: pointer; font-size: 11.5px; padding: 3px 7px; border-radius: 6px; background: var(--card2); border: 1px solid var(--border); }
.fmt-vars code:hover { border-color: var(--accent); }
.fmt-err { color: var(--red); background: var(--red-bg); border-radius: 8px; padding: 8px 12px; font-size: 12.5px; white-space: pre-wrap; }
::placeholder { color: var(--muted); }

/* Switch control */
.switch { position: relative; display: inline-block; width: 38px; height: 22px; flex: 0 0 38px; }
.switch input { opacity: 0; width: 0; height: 0; }
.switch .track {
  position: absolute; inset: 0;
  background: var(--card2); border: 1px solid var(--border);
  border-radius: 999px; transition: 0.2s; cursor: pointer;
}
.switch .track:before {
  content: ""; position: absolute; height: 16px; width: 16px;
  left: 2px; top: 2px; background: var(--muted);
  border-radius: 50%; transition: 0.2s;
}
.switch input:checked + .track { background: var(--accent); border-color: var(--accent); }
.switch input:checked + .track:before { transform: translateX(16px); background: #ffffff; }

/* ── Main View Container ─────────────────────────────── */
.main-wrapper {
  display: flex;
  flex-direction: column;
  gap: 14px;
  min-width: 0;
  box-sizing: border-box;
}
main#view {
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 14px;
  min-width: 0;
  box-sizing: border-box;
}

/* ── Multi-Column Plugins/Sources Grid ────────────────── */
.plugins-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: 12px;
  width: 100%;
}
@media (max-width: 640px) {
  .plugins-grid {
    grid-template-columns: 1fr;
    gap: 10px;
  }
}
.plugin-card {
  background: var(--card2);
  border: 1px solid var(--border);
  border-radius: 12px;
  padding: 14px;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  gap: 10px;
  transition: all 0.2s cubic-bezier(0.16, 1, 0.3, 1);
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.12);
  min-width: 0;
}
.plugin-card:hover {
  border-color: var(--border-focus);
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.2);
  transform: translateY(-1px);
}

/* ── Multi-Column Health Diagnostics Grid ────────────── */
.health-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(290px, 1fr));
  gap: 12px;
  width: 100%;
}
@media (max-width: 600px) {
  .health-grid {
    grid-template-columns: 1fr;
    gap: 10px;
  }
}
.health-card {
  background: var(--card2);
  border: 1px solid var(--border);
  border-radius: 12px;
  padding: 14px;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  gap: 10px;
  transition: all 0.2s cubic-bezier(0.16, 1, 0.3, 1);
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.12);
  min-width: 0;
}
.health-card:hover {
  border-color: var(--border-focus);
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.2);
  transform: translateY(-1px);
}

/* ── Theme Options Grid ──────────────────────────────── */
.base-theme-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
  gap: 10px;
  width: 100%;
  margin-top: 10px;
}
.base-theme-card {
  background: var(--card2);
  border: 1px solid var(--border);
  border-radius: 10px;
  padding: 10px 12px;
  display: flex;
  align-items: center;
  gap: 10px;
  cursor: pointer;
  transition: all 0.18s ease;
  user-select: none;
}
.base-theme-card:hover {
  border-color: var(--accent);
  background: var(--surface-active);
  transform: translateY(-1px);
}
.base-theme-card.active {
  border-color: var(--accent);
  background: var(--accent-light);
  box-shadow: 0 0 0 1px var(--accent);
}
.theme-swatch {
  width: 22px;
  height: 22px;
  border-radius: 6px;
  flex-shrink: 0;
  border: 1px solid rgba(255, 255, 255, 0.2);
}

/* ── Stat Cards Grid (pengu.uk inspired) ─────────────── */
.stat-cards-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
  width: 100%;
  box-sizing: border-box;
}
@media (max-width: 760px) {
  .stat-cards-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 8px;
  }
}
.stat-card {
  background: var(--card);
  border: 1px solid var(--border);
  border-radius: 11px;
  padding: 12px 14px;
  display: flex;
  flex-direction: column;
  gap: 3px;
  min-width: 0;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.15);
}
@media (max-width: 680px) {
  .stat-card { padding: 10px 11px; }
}
.stat-label {
  font-size: 9.5px;
  font-weight: 800;
  letter-spacing: 0.6px;
  text-transform: uppercase;
  color: var(--muted);
}
.stat-val {
  font-size: 18px;
  font-weight: 800;
  color: var(--text);
  letter-spacing: -0.4px;
  line-height: 1.2;
}
.stat-val.green { color: var(--green); }
.stat-sub {
  font-size: 10.5px;
  color: var(--text2);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* ── Card Containers ─────────────────────────────────── */
.card {
  background: var(--card);
  border: 1px solid var(--border);
  border-radius: 12px;
  padding: 18px 20px;
  min-width: 0;
  max-width: 100%;
  width: 100%;
  box-sizing: border-box;
  overflow: hidden;
  box-shadow: 0 2px 10px rgba(0, 0, 0, 0.15);
}
@media (max-width: 680px) {
  .card { padding: 13px 12px; border-radius: 10px; }
}
.card h2 {
  font-size: 14px;
  font-weight: 800;
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 3px;
  color: var(--text);
  letter-spacing: -0.2px;
}
.card .hint {
  font-size: 12px;
  color: var(--muted);
  margin-bottom: 14px;
}

/* ── Grids & Rows ────────────────────────────────────── */
.grid { display: grid; gap: 12px; min-width: 0; max-width: 100%; width: 100%; box-sizing: border-box; }
.cols2 { grid-template-columns: 1fr 1fr; }
@media (max-width: 680px) { .cols2 { grid-template-columns: 1fr; } }
.row { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.spacer { flex: 1 1 0; }
.muted { color: var(--text2); font-size: 12px; }

/* ── URL Box & Copy Actions (Matching Public Version Aesthetic) ── */
.urlbox {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  background: var(--card2);
  border: 1px solid var(--border);
  border-radius: 10px;
  padding: 8px 12px;
  margin-top: 8px;
  width: 100%;
  box-sizing: border-box;
  overflow: hidden;
  transition: border-color 0.15s ease;
}
.urlbox:hover {
  border-color: var(--border-focus);
}
.urlbox-left {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
  flex: 1;
}
.urlbox-label {
  font-size: 10.5px;
  font-weight: 800;
  text-transform: uppercase;
  letter-spacing: 0.5px;
  color: var(--muted);
  background: var(--surface);
  border: 1px solid var(--border);
  padding: 2px 7px;
  border-radius: 5px;
  flex-shrink: 0;
}
.urlbox code {
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 11.5px;
  color: var(--text);
  word-break: break-all;
  flex: 1;
  min-width: 0;
}
@media (max-width: 680px) {
  .urlbox {
    flex-direction: column;
    align-items: stretch;
    gap: 8px;
    padding: 9px 10px;
  }
  .urlbox code { font-size: 11px; }
  .btn-copy-action { width: 100%; }
}

.btn-copy-action {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  background: var(--surface);
  border: 1px solid var(--border);
  color: var(--text);
  padding: 6px 12px;
  border-radius: 7px;
  font-size: 11.5px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.18s cubic-bezier(0.16, 1, 0.3, 1);
  min-height: 30px;
  flex-shrink: 0;
}
.btn-copy-action:hover {
  background: var(--surface-active);
  border-color: var(--accent);
  color: var(--accent);
}
.btn-copy-action:active { transform: scale(0.97); }
.btn-copy-action.copied {
  border-color: var(--green) !important;
  color: var(--green) !important;
  background: var(--green-bg) !important;
}

/* Hero Install Card in Server Gateway */
.hero-install-admin {
  background: var(--card2);
  border: 1px solid var(--border);
  border-radius: 11px;
  padding: 14px;
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin-top: 10px;
  margin-bottom: 4px;
}
.hero-btn-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  width: 100%;
}
.btn-hero-install-admin {
  flex: 1;
  min-width: 160px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
  background: var(--accent);
  color: #ffffff;
  padding: 9px 16px;
  border-radius: 8px;
  font-size: 12.5px;
  font-weight: 700;
  text-decoration: none;
  box-shadow: 0 2px 10px var(--accent-glow);
  transition: all 0.18s ease;
  min-height: 36px;
  box-sizing: border-box;
}
.btn-hero-install-admin:hover {
  background: var(--accent-hover);
  box-shadow: 0 4px 14px var(--accent-glow);
  transform: translateY(-1px);
  color: #ffffff;
  text-decoration: none;
}
.btn-hero-copy-admin {
  flex: 1;
  min-width: 130px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  background: var(--surface);
  border: 1px solid var(--border);
  color: var(--text);
  padding: 9px 14px;
  border-radius: 8px;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.18s ease;
  min-height: 36px;
  box-sizing: border-box;
}
.btn-hero-copy-admin:hover {
  background: var(--surface-active);
  border-color: var(--border-focus);
  color: #ffffff;
}
.btn-hero-copy-admin.copied {
  border-color: var(--green) !important;
  color: var(--green) !important;
  background: var(--green-bg) !important;
}
.iconbtn:hover { background: var(--surface); color: var(--text); }
.iconbtn.danger-btn:hover { color: var(--red); background: var(--red-bg); }

/* Progress */
.progress {
  height: 5px;
  border-radius: 99px;
  background: var(--surface);
  overflow: hidden;
  margin-top: 10px;
}
.progress > div {
  height: 100%;
  background: var(--accent);
  border-radius: 99px;
  transition: width 0.35s ease;
}

/* ── Badges ──────────────────────────────────────────── */
.badge {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 10.5px;
  font-weight: 700;
  padding: 2px 7px;
  border-radius: 6px;
  border: 1px solid var(--border);
  white-space: nowrap;
  line-height: 1.3;
}
.badge.green { background: var(--green-bg); color: var(--green); border-color: rgba(16, 185, 129, 0.28); }
.badge.red { background: var(--red-bg); color: var(--red); border-color: rgba(244, 63, 94, 0.28); }
.badge.amber { background: var(--amber-bg); color: var(--amber); border-color: rgba(245, 158, 11, 0.28); }
.badge.violet { background: var(--accent-light); color: var(--accent); border-color: rgba(99, 102, 241, 0.28); }
.badge.gray { background: var(--card2); color: var(--text2); }
.badge.blue { background: var(--blue-bg); color: var(--blue); border-color: rgba(56, 189, 248, 0.28); }

/* ── Filter Pills ────────────────────────────────────── */
.pillrow {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin: 8px 0 12px;
  padding-bottom: 2px;
  max-width: 100%;
  width: 100%;
  box-sizing: border-box;
}
.pill {
  font-size: 11.5px;
  font-weight: 600;
  padding: 5px 12px;
  border-radius: 999px;
  background: var(--card2);
  border: 1px solid var(--border);
  color: var(--text2);
  cursor: pointer;
  white-space: nowrap;
  transition: all 0.15s ease;
}
.pill:hover { border-color: var(--border-focus); color: var(--text); }
.pill.active {
  background: var(--accent-light);
  border-color: var(--accent);
  color: var(--text);
  font-weight: 700;
}

/* ── Repositories List (Responsive, Zero Overflow) ────── */
.reporow {
  background: var(--card2);
  border: 1px solid var(--border);
  border-radius: 10px;
  padding: 12px;
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-width: 0;
  max-width: 100%;
  width: 100%;
  box-sizing: border-box;
  overflow: hidden;
  transition: border-color 0.15s;
}
.reporow:hover { border-color: var(--border-focus); }

@media (min-width: 768px) {
  .reporow {
    flex-direction: row;
    align-items: center;
    justify-content: space-between;
    padding: 12px 16px;
  }
}

.repo-main {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
  max-width: 100%;
  width: 100%;
  overflow: hidden;
}
.ricon { width: 32px; height: 32px; border-radius: 7px; object-fit: cover; flex-shrink: 0; }
.rletter {
  width: 32px; height: 32px; border-radius: 7px;
  background: var(--surface); border: 1px solid var(--border);
  display: flex; align-items: center; justify-content: center;
  font-size: 12px; font-weight: 800; color: var(--text); flex-shrink: 0;
}
.rinfo {
  flex: 1;
  min-width: 0;
  max-width: calc(100% - 42px);
  overflow: hidden;
}
@media (min-width: 768px) {
  .rinfo { max-width: none; }
}
.rname {
  font-weight: 700;
  font-size: 13px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--text);
  max-width: 100%;
}
.rurl {
  font-size: 11px;
  color: var(--muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 100%;
  display: block;
  margin-top: 1px;
}
.repo-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 6px;
  width: 100%;
  border-top: 1px solid var(--divider);
  padding-top: 8px;
  flex-wrap: wrap;
}
@media (min-width: 768px) {
  .repo-actions {
    width: auto;
    border-top: none;
    padding-top: 0;
    flex-shrink: 0;
  }
}

/* ── STRICT 2-COLUMN MOBILE EXTENSION GRID ───────────── */
/* CRITICAL: NEVER collapse to 1fr! Always 2 columns on mobile */
.plugins {
  display: grid;
  gap: 10px;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  width: 100%;
  box-sizing: border-box;
}
@media (max-width: 680px) {
  .plugins {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 8px;
  }
}

/* ── Modern Plugin Card (Mobile-First) ───────────────── */
.pcard {
  background: var(--card2);
  border: 1px solid var(--border);
  border-radius: 11px;
  padding: 12px;
  display: flex;
  flex-direction: column;
  gap: 7px;
  min-width: 0;
  max-width: 100%;
  box-sizing: border-box;
  overflow: hidden;
  transition: all 0.15s ease;
  box-shadow: 0 2px 6px rgba(0, 0, 0, 0.12);
}
@media (max-width: 680px) {
  .pcard {
    padding: 10px 9px;
    gap: 6px;
    border-radius: 9px;
  }
}
.pcard:hover {
  border-color: var(--border-focus);
  transform: translateY(-1px);
  box-shadow: 0 6px 16px rgba(0, 0, 0, 0.2);
}
.pcard-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 6px;
  width: 100%;
}
.picon {
  width: 32px; height: 32px;
  border-radius: 8px; object-fit: cover;
  background: var(--surface); border: 1px solid var(--border);
  flex-shrink: 0;
}
.pletter {
  width: 32px; height: 32px;
  border-radius: 8px; background: var(--surface);
  border: 1px solid var(--border);
  display: flex; align-items: center; justify-content: center;
  font-size: 13px; font-weight: 800; color: var(--text);
  flex-shrink: 0;
}
@media (max-width: 680px) {
  .picon, .pletter { width: 28px; height: 28px; border-radius: 7px; font-size: 12px; }
}

.pcard-body {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
  width: 100%;
  overflow: hidden;
}
.pcard-body .name {
  font-weight: 700;
  font-size: 12.5px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--text);
  line-height: 1.25;
  max-width: 100%;
}
@media (max-width: 680px) {
  .pcard-body .name { font-size: 11.5px; }
}
.pcard-body .meta {
  font-size: 10.5px;
  color: var(--muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 100%;
}
.pcard-body .desc {
  font-size: 11px;
  color: var(--text2);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  line-height: 1.35;
  margin-top: 2px;
}

.pcard-actions {
  display: flex;
  align-items: center;
  gap: 5px;
  margin-top: auto;
  padding-top: 6px;
  border-top: 1px solid var(--divider);
  width: 100%;
}
.pcard-ctrls {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  gap: 4px;
}

/* ── Modals ──────────────────────────────────────────── */
.modal-overlay {
  position: fixed; inset: 0;
  background: rgba(0, 0, 0, 0.65);
  backdrop-filter: blur(6px); -webkit-backdrop-filter: blur(6px);
  z-index: 2000; display: none;
  align-items: center; justify-content: center;
  padding: 16px;
}
.modal-overlay.open { display: flex; }
.modal-box {
  background: var(--card); border: 1px solid var(--border);
  border-radius: 12px; padding: 20px;
  max-width: 520px; width: 100%;
  max-height: 85vh; overflow-y: auto;
  box-shadow: 0 20px 48px rgba(0, 0, 0, 0.6);
}
.modal-title {
  font-size: 14.5px; font-weight: 700; margin-bottom: 14px;
  display: flex; align-items: center; justify-content: space-between;
}
.modal-close {
  background: none; border: none; color: var(--text2);
  cursor: pointer; padding: 4px; border-radius: 6px;
  display: inline-flex;
}
.modal-close:hover { background: var(--border); color: var(--text); }
.setting {
  background: var(--card2); border: 1px solid var(--border);
  border-radius: 9px; padding: 11px 13px; margin-bottom: 9px;
}
.setting .label { font-weight: 600; font-size: 12.5px; }
.setting .desc2 { font-size: 11px; color: var(--text2); margin: 2px 0 8px; }
.checkgrid { display: grid; grid-template-columns: 1fr 1fr; gap: 4px 12px; }
.checkrow { display: flex; align-items: center; gap: 7px; padding: 3px 0; font-size: 12px; cursor: pointer; }
.checkrow input { accent-color: var(--accent); width: 13px; height: 13px; }
.category {
  margin: 14px 0 8px; color: var(--muted);
  font-size: 10px; font-weight: 800; letter-spacing: 0.8px;
  text-transform: uppercase; border-bottom: 1px solid var(--border);
  padding-bottom: 4px;
}

/* ── Logs Panel ──────────────────────────────────────── */
.logs-wrap {
  background: var(--card2); border: 1px solid var(--border);
  border-radius: 10px; overflow: hidden;
  max-height: 65vh; display: flex; flex-direction: column;
}
.logs-toolbar {
  display: flex; align-items: center; gap: 8px;
  padding: 8px 12px; background: var(--card);
  border-bottom: 1px solid var(--border);
  flex-shrink: 0; flex-wrap: wrap;
}
.loglist {
  padding: 10px 12px; overflow-y: auto; flex: 1;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 11.5px;
}
.loglist::-webkit-scrollbar { width: 5px; }
.loglist::-webkit-scrollbar-thumb { background: var(--border); border-radius: 3px; }
.logline {
  padding: 2px 5px; border-radius: 4px;
  white-space: pre-wrap; word-break: break-word;
  margin-bottom: 1px; line-height: 1.5;
}
.logline .t { color: var(--muted); margin-right: 7px; user-select: none; font-size: 10.5px; }
.logline.INFO { color: var(--text2); }
.logline.WARN { color: var(--amber); background: var(--amber-bg); }
.logline.ERROR { color: var(--red); background: var(--red-bg); }

/* Key-value summary */
.kv { display: grid; grid-template-columns: 130px 1fr; gap: 8px; font-size: 12.5px; }
.kv .k { color: var(--muted); font-weight: 600; }

.toast {
  position: fixed; bottom: 20px; left: 50%; transform: translateX(-50%);
  background: var(--card); border: 1px solid var(--border-focus);
  color: var(--text); padding: 8px 18px; border-radius: 8px;
  font-size: 12.5px; font-weight: 600;
  box-shadow: 0 8px 28px rgba(0, 0, 0, 0.5);
  opacity: 0; transition: opacity 0.2s ease;
  pointer-events: none; z-index: 3000; max-width: 90vw; text-align: center;
}
.toast.show { opacity: 1; }
.banner {
  background: var(--accent-light); border: 1px solid rgba(99, 102, 241, 0.3);
  border-radius: 8px; padding: 10px 14px; font-size: 12.5px;
  color: var(--text); margin-bottom: 12px;
}
.banner.err { background: var(--red-bg); border-color: rgba(244, 63, 94, 0.3); color: var(--red); }
.loader {
  display: inline-block; width: 11px; height: 11px;
  border: 2px solid var(--accent); border-top-color: transparent;
  border-radius: 50%; animation: spin 0.75s linear infinite; vertical-align: -1px;
}
@keyframes spin { to { transform: rotate(360deg); } }
@keyframes pulse { 0%,100% { opacity:1; transform:scale(1); } 50% { opacity:0.4; transform:scale(0.8); } }
.empty { padding: 32px 16px; text-align: center; color: var(--text2); font-size: 13px; }
</style>
</head>
<body>

<div class="sticky-nav-header">
  <header>
    <div class="hdr-brand">
      <button class="btn-icon-hdr btn-burger" onclick="toggleDrawer(true)" title="Menu &amp; Navigation">
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><line x1="3" y1="12" x2="21" y2="12"/><line x1="3" y1="6" x2="21" y2="6"/><line x1="3" y1="18" x2="21" y2="18"/></svg>
      </button>
      <div class="logo-box">
        <img src="/logo.png" alt="CNCVerse" style="width:32px;height:32px;border-radius:8px;object-fit:contain;" onerror="this.style.display='none'">
      </div>
      <div class="hdr-title-wrap">
        <div class="hdr-title">CNCVerse Bridge</div>
        <div class="hdr-sub"><span id="hdr-active-tab" style="color:var(--accent);font-weight:700;">Server Gateway</span> &bull; <span id="subtitle">Admin Panel</span></div>
      </div>
    </div>
    <div class="hdr-actions">
      <div class="statuspill" id="statuspill">
        <span class="dot gray"></span>
        <span id="statustext">connecting...</span>
      </div>
      <button class="btn-icon-hdr" id="theme-btn" onclick="toggleTheme()" title="Toggle Theme">
        <svg id="theme-icon" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z"/></svg>
      </button>
    </div>
  </header>
</div>

<div class="app-layout">
<div class="drawer-backdrop" id="drawer-backdrop" onclick="toggleDrawer(false)"></div>
<aside class="drawer" id="drawer">
  <div class="drawer-hdr">
    <div style="display:flex;align-items:center;gap:8px;">
      <div class="logo-box" style="width:28px;height:28px;">
        <img src="/logo.png" alt="CNCVerse" style="width:28px;height:28px;border-radius:6px;object-fit:contain;" onerror="this.style.display='none'">
      </div>
      <span class="drawer-title">CNCVerse Admin</span>
    </div>
    <button class="drawer-close" onclick="toggleDrawer(false)" title="Close Menu">
      <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
    </button>
  </div>
  <div class="drawer-menu">
    <div class="drawer-section-label">ADMIN NAVIGATION</div>
    <button class="drawer-btn active" data-tab="server" onclick="openTab('server'); toggleDrawer(false);">
      <div style="display:flex;align-items:center;gap:10px;">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="2" y="2" width="20" height="8" rx="2"/><rect x="2" y="14" width="20" height="8" rx="2"/><line x1="6" y1="6" x2="6.01" y2="6"/><line x1="6" y1="18" x2="6.01" y2="18"/></svg>
        <span>Dashboard</span>
      </div>
    </button>
    <button class="drawer-btn" data-tab="extensions" onclick="openTab('extensions'); toggleDrawer(false);">
      <div style="display:flex;align-items:center;gap:10px;">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M19 11V4a1 1 0 0 0-1-1h-7a1 1 0 0 0-1 1v1a2 2 0 0 1-4 0V4a1 1 0 0 0-1-1H2a1 1 0 0 0-1 1v7a1 1 0 0 0 1 1h1a2 2 0 0 1 0 4H2a1 1 0 0 0-1 1v7a1 1 0 0 0 1 1h7a1 1 0 0 0 1-1v-1a2 2 0 0 1 4 0v1a1 1 0 0 0 1 1h7a1 1 0 0 0 1-1v-7a1 1 0 0 0-1-1h-1a2 2 0 0 1 0-4h1a1 1 0 0 0 1-1Z"/></svg>
        <span>Extensions &amp; Repos</span>
      </div>
    </button>
    <button class="drawer-btn" data-tab="health" onclick="openTab('health'); toggleDrawer(false);">
      <div style="display:flex;align-items:center;gap:10px;">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M22 12h-4l-3 9L9 3l-3 9H2"/></svg>
        <span>Stream Diagnostics</span>
      </div>
    </button>
    <button class="drawer-btn" data-tab="cache" onclick="openTab('cache'); toggleDrawer(false);">
      <div style="display:flex;align-items:center;gap:10px;">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"/></svg>
        <span>⚡ Stream Cache</span>
      </div>
    </button>
    <button class="drawer-btn" data-tab="formatter" onclick="openTab('formatter'); toggleDrawer(false);">
      <div style="display:flex;align-items:center;gap:10px;">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="4 7 4 4 20 4 20 7"/><line x1="9" y1="20" x2="15" y2="20"/><line x1="12" y1="4" x2="12" y2="20"/></svg>
        <span>Stream Formatter</span>
      </div>
    </button>
    <button class="drawer-btn" data-tab="credits" onclick="openTab('credits'); toggleDrawer(false);">
      <div style="display:flex;align-items:center;gap:10px;">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M22 21v-2a4 4 0 0 0-3-3.87"/><path d="M16 3.13a4 4 0 0 1 0 7.75"/></svg>
        <span>Team &amp; Credits</span>
      </div>
    </button>
    <button class="drawer-btn" data-tab="logs" onclick="openTab('logs'); toggleDrawer(false);">
      <div style="display:flex;align-items:center;gap:10px;">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="4 17 10 11 4 5"/><line x1="12" y1="19" x2="20" y2="19"/></svg>
        <span>Live Logs</span>
      </div>
    </button>
    <button class="drawer-btn" data-tab="about" onclick="openTab('about'); toggleDrawer(false);">
      <div style="display:flex;align-items:center;gap:10px;">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><path d="M12 16v-4"/><path d="M12 8h.01"/></svg>
        <span>About &amp; Updates</span>
      </div>
    </button>

    <div class="drawer-section-label" style="margin-top:10px;">EXTERNAL &amp; QUICK LINKS</div>
    <a class="drawer-btn" href="/" target="_blank" rel="noopener">
      <div style="display:flex;align-items:center;gap:10px;">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M3 9l9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/><polyline points="9 22 9 12 15 12 15 22"/></svg>
        <span>Open User Web App</span>
      </div>
      <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6"/><polyline points="15 3 21 3 21 9"/><line x1="10" y1="14" x2="21" y2="3"/></svg>
    </a>
    <a class="drawer-btn" href="https://t.me/cncverse" target="_blank" rel="noopener">
      <div style="display:flex;align-items:center;gap:10px;">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor"><path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm4.64 6.8c-.15 1.58-.8 5.42-1.13 7.19-.14.75-.42 1-.68 1.03-.58.05-1.02-.38-1.58-.75-.88-.58-1.38-.94-2.23-1.5-.99-.65-.35-1.01.22-1.59.15-.15 2.71-2.48 2.76-2.69a.2.2 0 0 0-.05-.18c-.06-.05-.14-.03-.21-.02-.09.02-1.49.95-4.22 2.79-.4.27-.76.41-1.08.4-.36-.01-1.04-.2-1.55-.37-.63-.2-1.12-.31-1.08-.66.02-.18.27-.36.74-.55 2.92-1.27 4.86-2.11 5.83-2.51 2.78-1.16 3.35-1.36 3.73-1.36.08 0 .27.02.39.12.1.08.13.19.14.27-.01.06.01.24 0 .38z"/></svg>
        <span>Telegram Community</span>
      </div>
      <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6"/><polyline points="15 3 21 3 21 9"/><line x1="10" y1="14" x2="21" y2="3"/></svg>
    </a>
    <a class="drawer-btn" href="https://cncverse.pages.dev" target="_blank" rel="noopener">
      <div style="display:flex;align-items:center;gap:10px;color:var(--red);">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor"><path d="M12 21.35l-1.45-1.32C5.4 15.36 2 12.28 2 8.5 2 5.42 4.42 3 7.5 3c1.74 0 3.41.81 4.5 2.09C13.09 3.81 14.76 3 16.5 3 19.58 3 22 5.42 22 8.5c0 3.78-3.4 6.86-8.55 11.54L12 21.35z"/></svg>
        <span style="font-weight:700;">Donate &amp; Support Goal</span>
      </div>
      <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6"/><polyline points="15 3 21 3 21 9"/><line x1="10" y1="14" x2="21" y2="3"/></svg>
    </a>
    <button class="drawer-btn" onclick="toggleTheme();">
      <div style="display:flex;align-items:center;gap:10px;">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="5"/><line x1="12" y1="1" x2="12" y2="3"/><line x1="12" y1="21" x2="12" y2="23"/><line x1="4.22" y1="4.22" x2="5.64" y2="5.64"/><line x1="18.36" y1="18.36" x2="19.78" y2="19.78"/><line x1="1" y1="12" x2="3" y2="12"/><line x1="21" y1="12" x2="23" y2="12"/><line x1="4.22" y1="19.78" x2="5.64" y2="18.36"/><line x1="18.36" y1="5.64" x2="19.78" y2="4.22"/></svg>
        <span>Toggle Dark / Light</span>
      </div>
    </button>
  </div>
  <div id="admin-sidebar-footer" style="margin-top:auto; font-size:11px; color:var(--muted); text-align:center; padding-top:12px; border-top:1px solid var(--border);">
    <div style="font-weight:700; color:var(--text);">CNCVerse Bridge v2.5 Admin</div>
    <div style="color:var(--text-muted); font-size:10px;">CloudStream 3 JVM Core</div>
    <div id="admin-footer-credits-list" style="margin-top:5px; font-size:10px; opacity:0.85;"></div>
  </div>
</aside>

<div class="main-wrapper">
  <!-- DONATION GOAL BAR (Click anywhere, including progress bar, to visit donation page) -->
  <a class="goal-card" id="goal-card" title="Click to view &amp; support CNCVerse Community Goal" href="https://cncverse.pages.dev" target="_blank" rel="noopener">
    <div class="goal-top">
      <div class="goal-text" id="goal-text">&#36;0 raised of &#36;100 goal</div>
      <div class="goal-pct" id="goal-pct">0%</div>
    </div>
    <div class="goal-bar-row">
      <div class="goal-track">
        <div class="goal-fill" id="goal-fill" style="width: 0%;"></div>
      </div>
      <span class="goal-heart-btn" title="Support CNCVerse Community Goal">
        <svg width="13" height="13" viewBox="0 0 24 24" fill="currentColor"><path d="M12 21.35l-1.45-1.32C5.4 15.36 2 12.28 2 8.5 2 5.42 4.42 3 7.5 3c1.74 0 3.41.81 4.5 2.09C13.09 3.81 14.76 3 16.5 3 19.58 3 22 5.42 22 8.5c0 3.78-3.4 6.86-8.55 11.54L12 21.35z"/></svg>
      </span>
    </div>
  </a>

  <main id="view"></main>
</div>
</div>

<div class="toast" id="toast"></div>

<!-- SETTINGS MODAL -->
<div class="modal-overlay" id="settings-modal" onclick="if(event.target===this)closeSettingsModal()">
  <div class="modal-box" id="settings-modal-box">
    <div class="muted" style="text-align:center;padding:20px"><span class="loader"></span> Loading...</div>
  </div>
</div>

<!-- ADD REPO MODAL -->
<div class="modal-overlay" id="add-repo-modal" onclick="if(event.target===this)closeAddRepoModal()">
  <div class="modal-box" style="max-width:460px">
    <div class="modal-title">
      Add Repository
      <button class="modal-close" onclick="closeAddRepoModal()" title="Close">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
      </button>
    </div>
    <div class="hint" style="margin-bottom:12px">Enter a full URL, GitHub shorthand, or shortcode. Every extension the repo offers is downloaded automatically.</div>
    <input type="text" id="add-repo-input" placeholder="https://raw.githubusercontent.com/.../repo.json" style="margin-bottom:8px" onkeydown="if(event.key==='Enter')submitAddRepo()">
    <div class="muted" style="font-size:11.5px;margin-bottom:14px">Shortcuts: <code>user/repo</code> &middot; <code>user/repo/branch</code> &middot; <code>Hexated</code> &middot; <code>!pymd</code></div>
    <div class="row" style="gap:8px">
      <button class="primary" onclick="submitAddRepo()" id="add-repo-btn">Add Repository</button>
      <button class="ghost" onclick="closeAddRepoModal()">Cancel</button>
    </div>
  </div>
</div>

<!-- BENCHMARK MODAL -->
<div class="modal-overlay" id="benchmark-modal" onclick="if(event.target===this)closeBenchmarkModal()">
  <div class="modal-box" style="max-width:400px">
    <div class="modal-title">
      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="vertical-align:-2px"><polygon points="5 3 19 12 5 21 5 3"/></svg>
      Benchmark All Sources
      <button class="modal-close" onclick="closeBenchmarkModal()" title="Close"><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg></button>
    </div>
    <div class="hint" style="margin-bottom:12px">Enter a title to test all active sources. Results show once complete. Use a popular movie for best coverage.</div>
    <input type="text" id="benchmark-query-input" placeholder="e.g. Avatar, Inception, Breaking Bad" style="margin-bottom:14px" onkeydown="if(event.key==='Enter')submitBenchmark()">
    <div class="row" style="gap:8px">
      <button class="primary" onclick="submitBenchmark()" id="benchmark-submit-btn">Run Benchmark</button>
      <button class="ghost" onclick="closeBenchmarkModal()">Cancel</button>
    </div>
  </div>
</div>

<!-- SINGLE PROBE MODAL -->
<div class="modal-overlay" id="probe-modal" onclick="if(event.target===this)closeProbeModal()">
  <div class="modal-box" style="max-width:380px">
    <div class="modal-title">
      Test Source
      <button class="modal-close" onclick="closeProbeModal()" title="Close"><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg></button>
    </div>
    <div style="margin-bottom:8px;font-size:13px;">Testing: <strong id="probe-plugin-name" style="color:var(--text)"></strong></div>
    <div class="hint" style="margin-bottom:12px">Enter a title to probe this source with.</div>
    <input type="text" id="probe-query-input" placeholder="e.g. Avatar" value="Avatar" style="margin-bottom:14px" onkeydown="if(event.key==='Enter')submitProbe()">
    <div class="row" style="gap:8px">
      <button class="primary" onclick="submitProbe()" id="probe-submit-btn">Test Now</button>
      <button class="ghost" onclick="closeProbeModal()">Cancel</button>
    </div>
  </div>
</div>

<!-- UNINSTALL MODAL -->
<div class="modal-overlay" id="uninstall-modal" onclick="if(event.target===this)closeUninstallModal()">
  <div class="modal-box" style="max-width:420px">
    <div class="modal-title" style="color:var(--red);">
      <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" style="vertical-align:-2px"><path d="M3 6h18"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/></svg>
      Uninstall Plugin
      <button class="modal-close" onclick="closeUninstallModal()" title="Close"><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg></button>
    </div>
    <div style="margin-bottom:8px;font-size:13.5px;">Are you sure you want to uninstall <strong id="uninstall-plugin-name" style="color:var(--text)"></strong>?</div>
    <div class="hint" style="margin-bottom:16px;">This will remove the extension from your bridge and user manifests. You can reinstall it anytime from the Repositories tab.</div>
    <div class="row" style="gap:8px;justify-content:flex-end;">
      <button class="ghost" onclick="closeUninstallModal()">Cancel</button>
      <button class="danger" onclick="submitUninstall()" id="uninstall-submit-btn">Uninstall Plugin</button>
    </div>
  </div>
</div>

<!-- REMOVE REPO MODAL -->
<div class="modal-overlay" id="remove-repo-modal" onclick="if(event.target===this)closeRemoveRepoModal()">
  <div class="modal-box" style="max-width:420px">
    <div class="modal-title" style="color:var(--red);">
      <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" style="vertical-align:-2px"><path d="M3 6h18"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/></svg>
      Remove Repository
      <button class="modal-close" onclick="closeRemoveRepoModal()" title="Close"><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg></button>
    </div>
    <div style="margin-bottom:8px;font-size:13.5px;">Are you sure you want to remove <strong id="remove-repo-name" style="color:var(--text)"></strong>?</div>
    <div class="hint" style="margin-bottom:16px;">This will disconnect the repository. Installed plugins from this repo will remain until uninstalled.</div>
    <div class="row" style="gap:8px;justify-content:flex-end;">
      <button class="ghost" onclick="closeRemoveRepoModal()">Cancel</button>
      <button class="danger" onclick="submitRemoveRepo()" id="remove-repo-submit-btn">Remove Repository</button>
    </div>
  </div>
</div>

<script>
"use strict";

var currentBaseTheme = "slate";
var currentAccentHex = "${ServerState.globalAccentHex}";
var savedAccentHex = localStorage.getItem("cnc_accent_hex");
if (savedAccentHex && /^#[0-9a-fA-F]{6}$/.test(savedAccentHex)) {
  currentAccentHex = savedAccentHex;
}
var lastAccentUserEditTime = 0;

function hexToGlow(hex) {
  var r = 139, g = 92, b = 246;
  if (/^#[0-9a-fA-F]{6}$/.test(hex)) {
    r = parseInt(hex.slice(1,3), 16);
    g = parseInt(hex.slice(3,5), 16);
    b = parseInt(hex.slice(5,7), 16);
  }
  return "rgba(" + r + "," + g + "," + b + ",0.28)";
}

function previewAccent(hex) {
  document.documentElement.style.setProperty("--accent", hex);
  document.documentElement.style.setProperty("--accent-hover", hex);
  document.documentElement.style.setProperty("--accent-glow", hexToGlow(hex));
  document.documentElement.style.setProperty("--border-active", hex);
}

function applyBaseTheme(t) {
  currentBaseTheme = t || "slate";
  if (currentBaseTheme !== "light") {
    localStorage.setItem("cnc_dark_palette", currentBaseTheme);
  }
  document.documentElement.setAttribute("data-base-theme", currentBaseTheme);
  document.documentElement.setAttribute("data-theme", currentBaseTheme === "light" ? "light" : "dark");
  localStorage.setItem("cnc_base_theme", currentBaseTheme);
  var icon = document.getElementById("theme-icon");
  if (icon) {
    if (currentBaseTheme === "light") {
      icon.innerHTML = '<circle cx="12" cy="12" r="5"/><line x1="12" y1="1" x2="12" y2="3"/><line x1="12" y1="21" x2="12" y2="23"/><line x1="4.22" y1="4.22" x2="5.64" y2="5.64"/><line x1="18.36" y1="18.36" x2="19.78" y2="19.78"/><line x1="1" y1="12" x2="3" y2="12"/><line x1="21" y1="12" x2="23" y2="12"/><line x1="4.22" y1="19.78" x2="5.64" y2="18.36"/><line x1="18.36" y1="5.64" x2="19.78" y2="4.22"/>';
    } else {
      icon.innerHTML = '<path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z"/>';
    }
  }
}

function applyTheme(t) {
  if (t === "light") {
    applyBaseTheme("light");
  } else {
    var darkPalette = localStorage.getItem("cnc_dark_palette") || "${ServerState.globalBaseTheme}";
    if (darkPalette === "light") darkPalette = "slate";
    applyBaseTheme(darkPalette);
  }
}

function toggleTheme() {
  var cur = document.documentElement.getAttribute("data-theme") || "dark";
  applyTheme(cur === "dark" ? "light" : "dark");
  if (typeof updateThemeCardUI === "function") updateThemeCardUI();
}

var savedBaseTheme = localStorage.getItem("cnc_base_theme") || "${ServerState.globalBaseTheme}";
applyBaseTheme(savedBaseTheme);
previewAccent(currentAccentHex);

var TOKEN = new URLSearchParams(window.location.search).get("token") || "";
var BASE = "/api/admin";
var tab = "server";
var summary = null;
var plugins = null;
var settingsData = null;
var settingsPluginId = null;
var settingsDirty = false;
var repoFilter = "all";
var searchQuery = "";
var pollTimer = null;
var logTimer = null;
var lastLogTimestamp = 0;
var logsData = [];
var autoScroll = true;

// SVG Icons
var svgCopy = '<svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect width="14" height="14" x="8" y="8" rx="2" ry="2"/><path d="M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2"/></svg>';
var svgServer = '<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="2" y="2" width="20" height="8" rx="2"/><rect x="2" y="14" width="20" height="8" rx="2"/><line x1="6" y1="6" x2="6.01" y2="6"/><line x1="6" y1="18" x2="6.01" y2="18"/></svg>';
var svgCloud = '<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M17.5 19H9a7 7 0 1 1 6.71-9h1.79a4.5 4.5 0 1 1 0 9Z"/></svg>';
var svgBox = '<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="m7.5 4.27 9 5.15"/><path d="M21 8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16Z"/><path d="m3.3 7 8.7 5 8.7-5"/><path d="M12 22V12"/></svg>';
var svgPuzzle = '<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M19 11V4a1 1 0 0 0-1-1h-7a1 1 0 0 0-1 1v1a2 2 0 0 1-4 0V4a1 1 0 0 0-1-1H2a1 1 0 0 0-1 1v7a1 1 0 0 0 1 1h1a2 2 0 0 1 0 4H2a1 1 0 0 0-1 1v7a1 1 0 0 0 1 1h7a1 1 0 0 0 1-1v-1a2 2 0 0 1 4 0v1a1 1 0 0 0 1 1h7a1 1 0 0 0 1-1v-7a1 1 0 0 0-1-1h-1a2 2 0 0 1 0-4h1a1 1 0 0 0 1-1Z"/></svg>';
var svgTerminal = '<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="4 17 10 11 4 5"/><line x1="12" y1="19" x2="20" y2="19"/></svg>';
var svgInfo = '<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/><path d="M12 16v-4"/><path d="M12 8h.01"/></svg>';
var svgGear = '<svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z"/></svg>';
var svgRefresh = '<svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 12a9 9 0 0 0-9-9 9.75 9.75 0 0 0-6.74 2.74L3 8"/><path d="M3 3v5h5"/><path d="M3 12a9 9 0 0 0 9 9 9.75 9.75 0 0 0 6.74-2.74L21 16"/><path d="M16 21h5v-5"/></svg>';
var svgTrash = '<svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="3 6 5 6 21 6"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/><line x1="10" y1="11" x2="10" y2="17"/><line x1="14" y1="11" x2="14" y2="17"/></svg>';

// ── Utilities ────────────────────────────────────────────────────────────────
function api(path, opts) {
  opts = opts || {};
  var url = BASE + path;
  if (TOKEN) url += (url.indexOf("?") >= 0 ? "&" : "?") + "token=" + encodeURIComponent(TOKEN);
  opts.headers = Object.assign({"Content-Type": "application/json"}, opts.headers || {});
  return fetch(url, opts).then(function(r) {
    if (r.status === 401) { window.location.href = "/admin?token=" + encodeURIComponent(TOKEN) + "&auth=0"; throw new Error("unauthorized"); }
    if (!r.ok) throw new Error("HTTP " + r.status);
    return r.json();
  });
}

function esc(s) {
  if (s === null || s === undefined) return "";
  return String(s).replace(/&/g,"&amp;").replace(/</g,"&lt;").replace(/>/g,"&gt;").replace(/"/g,"&quot;");
}

// For values placed inside a single-quoted JS string in an inline handler,
// e.g. onclick="fn('" + jsa(x) + "')". Escapes for JS first (backslash, quote,
// line breaks), then for the HTML attribute. esc() alone is not enough there:
// a ' or \ in a plugin name from a third-party repo would break out of the string.
function jsa(s) {
  if (s === null || s === undefined) return "";
  return esc(String(s).replace(/\\/g, "\\\\").replace(/'/g, "\\'").replace(/\r?\n|\r/g, " ").replace(/</g, "\\x3c"));
}

function toast(msg) {
  var el = document.getElementById("toast");
  el.textContent = msg;
  el.classList.add("show");
  clearTimeout(el._t);
  el._t = setTimeout(function() { el.classList.remove("show"); }, 2500);
}

function copyText(text, btn, successLabel) {
  function onDone() {
    if (btn) {
      btn.classList.add("copied");
      var lbl = btn.querySelector(".copy-lbl");
      if (lbl) {
        var origText = lbl.textContent;
        lbl.textContent = successLabel || "Copied!";
        btn.style.color = "var(--green)";
        btn.style.borderColor = "var(--green)";
        setTimeout(function() {
          lbl.textContent = origText;
          btn.classList.remove("copied");
          btn.style.color = "";
          btn.style.borderColor = "";
        }, 1800);
      } else {
        var orig = btn.innerHTML;
        btn.innerHTML = '<svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="var(--green)" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><polyline points="20 6 9 17 4 12"/></svg>';
        setTimeout(function() {
          btn.innerHTML = orig;
          btn.classList.remove("copied");
        }, 1800);
      }
    }
    toast("Copied to clipboard!");
  }
  function _fallbackCopy() {
    var ta = document.createElement("textarea");
    ta.value = text; ta.style.position = "fixed"; ta.style.opacity = "0";
    document.body.appendChild(ta); ta.focus(); ta.select();
    try { document.execCommand("copy"); onDone(); } catch(e) {}
    document.body.removeChild(ta);
  }
  if (navigator.clipboard && navigator.clipboard.writeText) {
    navigator.clipboard.writeText(text).then(onDone).catch(_fallbackCopy);
  } else {
    _fallbackCopy();
  }
}

function el(id) { return document.getElementById(id); }

function fmtTime(ts) {
  return new Date(ts).toLocaleTimeString([],{hour12:false});
}

// ── Polling ───────────────────────────────────────────────────────────────────
function poll() {
  api("/summary").then(function(s) {
    summary = s;
    if (s.footerCredits && !creditsLoaded) {
      footerCreditsData = s.footerCredits;
      updateAdminSidebarFooter();
    }
    renderStatusPill();
    render();
  }).catch(function() {
    var pill = el("statuspill");
    if (pill) pill.innerHTML = '<span class="dot red"></span><span id="statustext">offline</span>';
  });
}

function pollLogs() {
  api("/logs").then(function(l) {
    logsData = l || [];
    if (tab === "logs") appendNewLogLines();
  }).catch(function() {});
}

// ── Header status pill ────────────────────────────────────────────────────────
function renderStatusPill() {
  if (!summary) return;
  var s = summary.server;
  var dot = "gray", txt = s.status;
  if (s.status === "Running") { dot = "green"; txt = "Running \xb7 :" + s.port; }
  else if (s.status === "Starting") { dot = "amber"; txt = "Starting\u2026"; }
  else if (s.status === "Error") { dot = "red"; txt = "Error"; }
  el("statuspill").innerHTML = '<span class="dot ' + dot + '"></span><span id="statustext">' + esc(txt) + "</span>";
  el("subtitle").textContent =
    (summary.headless ? "server \xb7 " : "desktop \xb7 ") +
    "v" + summary.version + (summary.tokenRequired ? " \xb7 token" : "");
}

// ── Render dispatch ────────────────────────────────────────────────────────────
function render() {
  if (!summary) return;
  if (tab === "server") renderServer();
  else if (tab === "extensions") renderExtensions();
  else if (tab === "health") { /* rendered on open / after loadStreamHealth — poll re-renders would steal the search box focus */ }
  else if (tab === "cache") { updateCacheTelemetryInPlace(); }
  else if (tab === "credits") { /* user-edited form — never re-render from poll */ }
  else if (tab === "logs") { /* append-only, don't full re-render */ }
  else if (tab === "about") renderAbout();
}

// ── Server tab ────────────────────────────────────────────────────────────────
function urlBox(label, url) {
  var cleanUrl = url;
  return '<div class="urlbox">' +
    '<div class="urlbox-left">' +
      '<span class="urlbox-label">' + esc(label) + '</span>' +
      '<code>' + esc(url) + '</code>' +
    '</div>' +
    '<button class="btn-copy-action" title="Copy ' + esc(label) + ' URL" onclick="copyText(\'' + jsa(cleanUrl) + '\', this)">' +
      svgCopy + '<span class="copy-lbl">Copy</span>' +
    '</button>' +
  '</div>';
}

function updateServerStatsInPlace() {
  if (!summary) return;
  var s = summary.server;
  var t = summary.tunnel;
  
  var elStatus = el("stat-server-status");
  if (elStatus) {
    elStatus.textContent = s.status;
    elStatus.className = "stat-val" + (s.status === "Running" ? " green" : "");
  }
  var elPort = el("stat-server-port");
  if (elPort && s.port) elPort.textContent = "Port :" + s.port;
  
  var elPlugins = el("stat-active-plugins");
  if (elPlugins) elPlugins.textContent = s.pluginCount || 0;
  
  var elTunnel = el("stat-tunnel-status");
  if (elTunnel) {
    elTunnel.textContent = (t && t.activeUrl ? "Active" : (t && t.stremioMode ? "Ready" : "Off"));
    elTunnel.className = "stat-val" + (t && t.activeUrl ? " green" : "");
  }
  var elTunnelSub = el("stat-tunnel-sub");
  if (elTunnelSub) elTunnelSub.textContent = (t && t.cloudflaredInstalled ? "cloudflared" : "Local only");

  var elGwPlugins = el("gw-plugins-count");
  if (elGwPlugins) elGwPlugins.textContent = s.pluginCount + " active plugin(s)";

  if (summary.baseTheme && summary.baseTheme !== currentBaseTheme && !localStorage.getItem("cnc_base_theme")) {
    currentBaseTheme = summary.baseTheme;
    applyBaseTheme(currentBaseTheme);
    updateThemeCardUI();
  }
  var isEditingCustomAccent = (document.activeElement === el("admin-accent-hex-input") || 
                               document.activeElement === el("admin-accent-color-picker") ||
                               (Date.now() - lastAccentUserEditTime < 10000));
  if (summary.themeAccent && summary.themeAccent !== currentAccentHex && !isEditingCustomAccent) {
    currentAccentHex = summary.themeAccent;
    localStorage.setItem("cnc_accent_hex", currentAccentHex);
    previewAccent(currentAccentHex);
    updateThemeCardUI();
  }
}

function renderServer() {
  if (!summary) return;
  if (el("server-gateway-card")) {
    updateServerStatsInPlace();
    return;
  }
  var s = summary.server;
  var t = summary.tunnel;
  var html = '';

  // Quick Stat Cards
  html += '<div class="stat-cards-grid">';
  html += '<div class="stat-card"><div class="stat-label">Server Status</div><div class="stat-val' + (s.status === "Running" ? ' green' : '') + '" id="stat-server-status">' + esc(s.status) + '</div><div class="stat-sub" id="stat-server-port">Port :' + s.port + '</div></div>';
  html += '<div class="stat-card"><div class="stat-label">Active Plugins</div><div class="stat-val" id="stat-active-plugins">' + (s.pluginCount || 0) + '</div><div class="stat-sub">Ready in manifest</div></div>';
  html += '<div class="stat-card"><div class="stat-label">Bridge Mode</div><div class="stat-val">' + (summary.headless ? 'Server' : 'Desktop') + '</div><div class="stat-sub">v' + esc(summary.version) + '</div></div>';
  html += '<div class="stat-card"><div class="stat-label">Tunnel &amp; HTTPS</div><div class="stat-val' + (t && t.activeUrl ? ' green' : '') + '" id="stat-tunnel-status">' + (t && t.activeUrl ? 'Active' : (t && t.stremioMode ? 'Ready' : 'Off')) + '</div><div class="stat-sub" id="stat-tunnel-sub">' + (t && t.cloudflaredInstalled ? 'cloudflared' : 'Local only') + '</div></div>';
  html += '</div>';

  html += '<div class="grid cols2">';

  // Server Gateway card
  html += '<div class="card" id="server-gateway-card"><h2>' + svgServer + ' Addon Gateway</h2><div class="hint">Network endpoints exposing the Stremio protocol.</div>';
  if (s.status === "Running") {
    var catOff = s.disableCatalogsGlobally || false;
    html += '<div class="row" style="margin-bottom:8px; justify-content:space-between; align-items:center; flex-wrap:wrap; gap:8px;">';
    html += '<div class="row" style="gap:8px;"><span class="badge green">Running</span><span class="muted" id="gw-plugins-count">' + s.pluginCount + ' active plugin(s)</span></div>';
    html += '<div class="row" style="gap:8px; align-items:center;">';
    html += '<span class="muted" style="font-size:11.5px; font-weight:600;">Global Catalogs:</span>';
    html += '<button class="pill small' + (catOff ? ' amber' : ' active') + '" onclick="act(\'/server/toggle-catalogs\',{method:\'POST\',body:\'{}\'}, \'Toggling catalogs...\')" title="Click to toggle catalog shelves on/off globally">' + (catOff ? 'Excluded (Streams Only)' : 'Active') + '</button>';
    html += '</div></div>';
    
    var mainManifestUrl = s.lanUrl || ("http://" + s.ipAddress + ":" + s.port + "/manifest.json");
    var stremioProtUrl = mainManifestUrl.replace(/^https?:\/\//, "stremio://");

    html += '<div class="hero-install-admin">';
    html += '<div class="hero-btn-row">';
    html += '<a class="btn-hero-install-admin" href="' + esc(stremioProtUrl) + '"><svg width="15" height="15" viewBox="0 0 24 24" fill="currentColor"><polygon points="6 3 20 12 6 21 6 3"/></svg><span>Install in Stremio</span></a>';
    html += '<button class="btn-hero-copy-admin" onclick="copyText(\'' + jsa(mainManifestUrl) + '\', this)"><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><rect width="14" height="14" x="8" y="8" rx="2" ry="2"/><path d="M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2"/></svg><span class="copy-lbl">Copy Addon URL</span></button>';
    html += '</div>';
    html += '</div>';

    html += urlBox("LAN", mainManifestUrl);
    if (s.localhostUrl) html += urlBox("Localhost", s.localhostUrl);
    if (t.stremioMode && s.stremioModeUrl) html += urlBox("Tunnel", s.stremioModeUrl);
    html += '<div class="row" style="margin-top:14px;gap:8px">';
    html += '<button class="primary small" onclick="act(\'/server/restart\',{method:\'POST\',body:\'{}\'}, \'Restarting server...\')">Restart</button>';
    html += '<button class="danger small" onclick="act(\'/server/stop\',{method:\'POST\',body:\'{}\'}, \'Stopping server...\')">Stop</button>';
    html += '<a class="pill" href="/manifest.json" target="_blank">View Manifest</a>';
    html += '<a class="pill" href="/" target="_blank">Open Public Config</a>';
    html += '</div>';
  } else if (s.status === "Starting") {
    html += '<div class="row"><span class="badge amber"><span class="loader"></span> ' + esc(s.message || "Starting...") + '</span></div>';
  } else if (s.status === "Error") {
    html += '<div class="banner err">' + esc(s.message) + '</div>';
    html += '<button class="primary small" onclick="act(\'/server/start\',{method:\'POST\',body:\'{}\'}, \'Starting server...\')">Start Server</button>';
  } else {
    html += '<div class="badge gray">Stopped</div><div class="muted" style="margin-top:8px">The web admin stays reachable while the addon server is stopped.</div>';
    html += '<div style="margin-top:14px"><button class="primary small" onclick="act(\'/server/start\',{method:\'POST\',body:\'{}\'}, \'Starting server...\')">Start Server</button></div>';
  }
  html += '</div>';

  // Tunnel card
  html += '<div class="card"><h2>' + svgCloud + ' Stremio Mode &amp; Tunnel</h2><div class="hint">Stremio Web requires HTTPS. Cloudflare tunnel exposes your bridge securely.</div>';
  html += '<div class="row" style="margin-bottom:8px"><span class="muted" style="font-weight:600">Stremio Mode (HTTPS)</span><div class="spacer"></div>';
  html += '<label class="switch"><input type="checkbox" ' + (t.stremioMode ? "checked" : "") + ' onchange="toggleStremioMode(this.checked)"><span class="track"></span></label></div>';
  if (t.downloadProgress !== null && t.downloadProgress !== undefined) {
    html += '<div class="progress"><div style="width:' + Math.round((t.downloadProgress||0)*100) + '%"></div></div>';
    html += '<div class="muted" style="margin-top:6px">Downloading cloudflared... ' + Math.round((t.downloadProgress||0)*100) + '%</div>';
  } else if (t.activeUrl) {
    html += urlBox("Tunnel", t.activeUrl);
    html += '<div class="row" style="margin-top:10px"><span class="badge green">Tunnel Active</span></div>';
    html += '<div style="margin-top:10px"><button class="ghost small" onclick="act(\'/tunnel/stop\',{method:\'POST\',body:\'{}\'}, \'Stopping tunnel...\')">Stop Tunnel</button></div>';
  } else {
    html += '<div class="row" style="margin-top:10px"><span class="badge ' + (t.cloudflaredInstalled ? "green" : "gray") + '">' + (t.cloudflaredInstalled ? "cloudflared ready" : "cloudflared not installed") + '</span></div>';
    html += '<div style="margin-top:10px"><button class="primary small" onclick="act(\'/tunnel/start\',{method:\'POST\',body:\'{}\'}, \'Starting tunnel...\')">' + (t.cloudflaredInstalled ? "Start Tunnel" : "Download &amp; Start Tunnel") + '</button></div>';
  }
  html += '</div>';

  // Bridge Theme & Global Accent Customizer Card
  html += '<div class="card" id="theme-settings-card" style="grid-column: 1 / -1; margin-top: 4px;">';
  html += '<div class="row" style="align-items:center; justify-content:space-between; margin-bottom:12px; flex-wrap:wrap; gap:8px;">';
  html += '<div><h2><svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="vertical-align:-2px"><circle cx="12" cy="12" r="10"/><path d="M12 2a14.5 14.5 0 0 0 0 20 10 10 0 0 0 0-20"/><path d="M18.36 5.64 12 12"/></svg> Bridge Theme &amp; Accent Customizer</h2>';
  html += '<div class="hint" style="margin-bottom:0">Select an eye-friendly base theme and brand accent. Saved globally for the admin panel and public viewer.</div></div>';
  html += '<span class="badge violet">Admin Controlled</span>';
  html += '</div>';

  // Section 1: Base Themes (Eye Comfort)
  html += '<div style="margin-bottom:14px;">';
  html += '<div style="font-size:11px; font-weight:800; text-transform:uppercase; letter-spacing:0.7px; color:var(--muted); margin-bottom:8px;">1. Bridge Base Theme (Eye Comfort &amp; Dark Modes)</div>';
  html += '<div class="base-theme-grid" id="base-theme-grid-cont">';
  
  var baseThemes = [
    { id: "slate", name: "Midnight Slate", desc: "Gentle low-glare dark (soft)", bg: "#0f172a", border: "#334155" },
    { id: "charcoal", name: "Warm Charcoal", desc: "Zero blue glare / twilight", bg: "#18181b", border: "#3f3f46" },
    { id: "navy", name: "Velvet Navy", desc: "Deep oceanic dark", bg: "#0d1117", border: "#30363d" },
    { id: "forest", name: "Forest Night", desc: "Calming pine dark", bg: "#0c1512", border: "#223c33" },
    { id: "oled", name: "Pitch OLED", desc: "Pure black #000000", bg: "#000000", border: "#242424" },
    { id: "light", name: "Clean Light", desc: "Soft daylight mode", bg: "#f8fafc", border: "#cbd5e1" }
  ];

  baseThemes.forEach(function(th) {
    var isSel = (currentBaseTheme === th.id);
    html += '<div class="base-theme-card' + (isSel ? ' active' : '') + '" onclick="setBaseTheme(\'' + th.id + '\')" data-theme-id="' + th.id + '">';
    html += '<div class="theme-swatch" style="background:' + th.bg + '; border-color:' + th.border + ';"></div>';
    html += '<div style="min-width:0;">';
    html += '<div style="font-size:12.5px; font-weight:700; color:var(--text);">' + esc(th.name) + '</div>';
    html += '<div style="font-size:10px; color:var(--muted);">' + esc(th.desc) + '</div>';
    html += '</div>';
    html += '</div>';
  });
  html += '</div>';
  html += '</div>';

  // Section 2: Brand Accent
  html += '<div>';
  html += '<div style="font-size:11px; font-weight:800; text-transform:uppercase; letter-spacing:0.7px; color:var(--muted); margin-bottom:8px;">2. Brand Accent Color</div>';
  html += '<div style="display:flex; flex-wrap:wrap; gap:8px; align-items:center;" id="accent-presets-cont">';
  
  var accents = [
    { hex: "#8b5cf6", name: "Electric Violet" },
    { hex: "#06b6d4", name: "Cyber Cyan" },
    { hex: "#6366f1", name: "Indigo Blue" },
    { hex: "#10b981", name: "Emerald Green" },
    { hex: "#f59e0b", name: "Amber Gold" },
    { hex: "#f43f5e", name: "Neon Rose" }
  ];

  accents.forEach(function(ac) {
    var isSel = (currentAccentHex.toLowerCase() === ac.hex.toLowerCase());
    html += '<button class="pill' + (isSel ? ' active' : '') + '" data-hex="' + ac.hex + '" style="background:' + ac.hex + '; color:#fff; border-color:' + (isSel ? '#ffffff' : ac.hex) + '; font-weight:700; box-shadow:' + (isSel ? '0 0 0 2px var(--text)' : 'none') + ';" onclick="setAccentPreset(\'' + ac.hex + '\')">&#9679; ' + esc(ac.name) + '</button>';
  });
  html += '</div>';

  html += '<div class="row" style="margin-top:12px; gap:10px; align-items:center; flex-wrap:wrap;">';
  html += '<span style="font-size:12px; color:var(--text2); font-weight:600;">Custom Accent:</span>';
  html += '<div style="display:flex; align-items:center; gap:8px; background:var(--card2); border:1px solid var(--border); border-radius:8px; padding:4px 8px;">';
  html += '<label id="admin-accent-swatch-lbl" style="position:relative; width:24px; height:24px; border-radius:6px; border:1px solid rgba(255,255,255,0.3); background:' + esc(currentAccentHex) + '; cursor:pointer; display:inline-block; overflow:hidden; flex-shrink:0;" title="Click to pick color">';
  html += '<input type="color" id="admin-accent-color-picker" value="' + esc(currentAccentHex) + '" style="opacity:0; width:100%; height:100%; cursor:pointer; position:absolute; top:0; left:0; border:none; padding:0;" oninput="onColorPickerInput(this.value)" onchange="onColorPickerChange(this.value)">';
  html += '</label>';
  html += '<input type="text" id="admin-accent-hex-input" placeholder="#8b5cf6" value="' + esc(currentAccentHex) + '" style="width:85px; padding:2px 4px; font-size:12.5px; border:none; background:none; font-family:monospace; color:var(--text); font-weight:600;" oninput="onHexTextInput(this.value)" onkeydown="if(event.key===\'Enter\')applyCustomAccent()">';
  html += '</div>';
  html += '<button class="primary small" onclick="applyCustomAccent()">Apply</button>';
  html += '</div>';
  html += '</div>';

  html += '</div>'; // close theme card

  html += '</div>'; // close grid cols2

  el("view").innerHTML = html;
}

function updateThemeCardUI() {
  document.querySelectorAll(".base-theme-card").forEach(function(c) {
    var id = c.getAttribute("data-theme-id");
    c.classList.toggle("active", id === currentBaseTheme);
  });
  document.querySelectorAll("#accent-presets-cont button[data-hex]").forEach(function(b) {
    var h = b.getAttribute("data-hex");
    var isSel = (h && h.toLowerCase() === currentAccentHex.toLowerCase());
    b.classList.toggle("active", isSel);
    b.style.borderColor = isSel ? "#ffffff" : h;
    b.style.boxShadow = isSel ? "0 0 0 2px var(--text)" : "none";
  });
  var p = el("admin-accent-color-picker");
  if (p && document.activeElement !== p) p.value = currentAccentHex;
  var i = el("admin-accent-hex-input");
  if (i && document.activeElement !== i) i.value = currentAccentHex;
  var sw = el("admin-accent-swatch-lbl") || el("admin-accent-swatch-btn");
  if (sw) sw.style.background = currentAccentHex;
}

function setBaseTheme(t) {
  applyBaseTheme(t);
  updateThemeCardUI();
  saveBridgeTheme(currentAccentHex, hexToGlow(currentAccentHex), currentAccentHex, currentBaseTheme);
}

function setAccentPreset(hex) {
  lastAccentUserEditTime = Date.now();
  currentAccentHex = hex;
  previewAccent(hex);
  updateThemeCardUI();
  if (summary) summary.themeAccent = hex;
  localStorage.setItem("cnc_accent_hex", hex);
  saveBridgeTheme(hex, hexToGlow(hex), hex, currentBaseTheme);
}

function onColorPickerInput(val) {
  lastAccentUserEditTime = Date.now();
  currentAccentHex = val;
  var hexInput = el("admin-accent-hex-input");
  if (hexInput) hexInput.value = val;
  var sw = el("admin-accent-swatch-lbl") || el("admin-accent-swatch-btn");
  if (sw) sw.style.background = val;
  previewAccent(val);
}

function onColorPickerChange(val) {
  lastAccentUserEditTime = Date.now();
  setAccentPreset(val);
}

function onHexTextInput(val) {
  lastAccentUserEditTime = Date.now();
  val = (val || "").trim();
  if (/^#[0-9a-fA-F]{6}$/.test(val)) {
    currentAccentHex = val;
    var picker = el("admin-accent-color-picker");
    if (picker) picker.value = val;
    var sw = el("admin-accent-swatch-lbl") || el("admin-accent-swatch-btn");
    if (sw) sw.style.background = val;
    previewAccent(val);
  }
}

function applyCustomAccent() {
  lastAccentUserEditTime = Date.now();
  var hex = (el("admin-accent-hex-input") ? el("admin-accent-hex-input").value : "").trim();
  if (!/^#[0-9a-fA-F]{6}$/.test(hex)) {
    toast("Please enter a valid 6-char hex like #8b5cf6");
    return;
  }
  setAccentPreset(hex);
}

function saveBridgeTheme(hex, glow, hover, baseTheme) {
  lastAccentUserEditTime = Date.now();
  if (summary) {
    summary.themeAccent = hex;
    if (baseTheme) summary.baseTheme = baseTheme;
  }
  localStorage.setItem("cnc_accent_hex", hex);
  api("/settings/theme", {
    method: "POST",
    body: JSON.stringify({ accentHex: hex, accentGlow: glow, accentHover: hover, baseTheme: baseTheme })
  }).then(function(res) {
    toast(res.message || "✓ Theme updated globally!");
  }).catch(function(e) {
    toast("Theme save error: " + e.message);
  });
}

function toggleStremioMode(enabled) {
  act("/stremio-mode", {method:"POST", body: JSON.stringify({enabled: enabled})},
    enabled ? "Enabling Stremio mode..." : "Disabling Stremio mode...");
}

// ── Extensions & Sources Tab ──────────────────────────────────────────────────
var extStatusFilter = "all";        // "all" | "installed" | "available" | "active" | "disabled"
var extRepoFilter = "all";          // "all" | repoUrl
var extCatFilter = "all";           // "all" | tvType tag
var extSearchQuery = "";

function setExtStatusFilter(f) { extStatusFilter = f; updateExtensionsSourcesOnly(); }
function setExtRepoFilter(r)   { extRepoFilter = r;   updateExtensionsSourcesOnly(); }
function setExtCatFilter(c)    { extCatFilter = c;    updateExtensionsSourcesOnly(); }
function setExtSearch(q)       { extSearchQuery = q;  updateExtensionsSourcesOnly(); }
function resetAllExtFilters()  {
  extStatusFilter = "all";
  extRepoFilter = "all";
  extCatFilter = "all";
  extSearchQuery = "";
  var sSel = el("ext-status-select"); if (sSel) sSel.value = "all";
  var rSel = el("ext-repo-select"); if (rSel) rSel.value = "all";
  var cSel = el("ext-cat-select"); if (cSel) cSel.value = "all";
  var inp = el("sources-search-input"); if (inp) inp.value = "";
  updateExtensionsSourcesOnly();
}

function renderExtensions() {
  // If the shell is already built in the DOM, do NOT destroy innerHTML! Just update rows.
  if (el("sources-rows-container") && el("repos-rows-container")) {
    updateExtensionsSourcesOnly();
    return;
  }

  var html = "";
  var installedList = (summary && summary.installedPlugins) ? summary.installedPlugins : [];
  var repoList = (summary && summary.repos) ? summary.repos : [];
  var allPlugins = plugins || [];

  // ── 1. Connected Repositories Card ───────────────────────────────────────
  html += '<div class="card">';
  html += '<div class="row" style="align-items:center;justify-content:space-between;margin-bottom:10px;flex-wrap:wrap;gap:8px;">';
  html += '<div>';
  html += '<h2>' + svgBox + ' Connected Repositories</h2>';
  html += '<div class="hint" style="margin-bottom:0">Repositories provide source pools. Adding a repo makes its sources available below without auto-installing everything.</div>';
  html += '</div>';
  html += '<div class="row" style="gap:6px;flex-shrink:0;">';
  html += '<button class="ghost small" onclick="refreshRepositories()">' + (summary && summary.refreshing ? '<span class="loader"></span> ' : svgRefresh + ' ') + 'Refresh All</button>';
  html += '<button class="primary small" onclick="openAddRepoModal()">+ Add Repository</button>';
  html += '</div>';
  html += '</div>';
  html += '<div id="repos-rows-container"></div>';
  html += '</div>'; // close Repositories card

  // ── 2. All Available Sources Pool (The Core Manager) ──────────────────────
  html += '<div class="card" style="margin-top:14px;">';
  html += '<div class="row" style="align-items:center;justify-content:space-between;margin-bottom:12px;flex-wrap:wrap;gap:8px;">';
  html += '<div>';
  html += '<h2 style="margin-bottom:2px">' + svgPuzzle + ' Sources Pool &amp; Catalog</h2>';
  html += '<div class="hint" style="margin-bottom:0">Browse, install, and manage provider sources from all connected repositories. Installed sources are your Dev Choices on Stremio manifests.</div>';
  html += '</div>';
  html += '<div id="ext-filter-reset-wrap" style="display:none;">';
  html += '<button class="small ghost" onclick="resetAllExtFilters()" style="font-size:11.5px;color:var(--accent);border-color:var(--accent);padding:4px 10px;" title="Reset all filters">Reset Filters</button>';
  html += '</div>';
  html += '</div>';

  // ── Scalable Dropdowns Filter Bar ─────────────────────────────────────────
  html += '<div class="grid" style="grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap:8px; margin-bottom:8px;">';
  html += '<div><select class="custom-select" id="ext-status-select" onchange="setExtStatusFilter(this.value)"></select></div>';
  html += '<div><select class="custom-select" id="ext-repo-select" onchange="setExtRepoFilter(this.value)"></select></div>';
  html += '<div><select class="custom-select" id="ext-cat-select" onchange="setExtCatFilter(this.value)"></select></div>';
  html += '</div>';

  // ── Search Input ─────────────────────────────────────────────────────────
  html += '<div style="position:relative;margin-bottom:14px;margin-top:2px;">';
  html += '<input type="text" id="sources-search-input" placeholder="Search sources by name, description, category, or language…" value="' + esc(extSearchQuery) + '" oninput="setExtSearch(this.value)" style="padding-right:32px">';
  html += '<button id="sources-search-clear-btn" onclick="setExtSearch(\'\');var e=el(\'sources-search-input\');if(e)e.value=\'\';" style="position:absolute;right:8px;top:50%;transform:translateY(-50%);background:none;border:none;color:var(--muted);padding:4px;cursor:pointer;font-size:14px;line-height:1;display:' + (extSearchQuery ? 'block' : 'none') + ';" title="Clear">&times;</button>';
  html += '</div>';

  html += '<div id="sources-rows-container"><div class="empty"><span class="loader"></span> Loading available sources from repositories…</div></div>';
  html += '</div>'; // close Sources Pool card

  el("view").innerHTML = html;
  updateExtensionsSourcesOnly();
}

function updateExtensionsSourcesOnly() {
  var sourcesCont = el("sources-rows-container");
  var reposCont = el("repos-rows-container");
  if (!sourcesCont) return;

  var installedList = (summary && summary.installedPlugins) ? summary.installedPlugins : [];
  var repoList = (summary && summary.repos) ? summary.repos : [];
  var allPlugins = plugins || [];

  // Update Repos List if container exists
  if (reposCont) {
    if (!repoList.length) {
      reposCont.innerHTML = '<div class="empty">No repositories connected yet. Click "+ Add Repository" to add one.</div>';
    } else {
      var rHtml = '<div style="display:flex;flex-direction:column;gap:6px;">';
      repoList.forEach(function(r) {
        var repoSources = allPlugins.filter(function(p) { return p.repoUrl === r.url; });
        var installedInThisRepo = repoSources.filter(function(p) { return p.installed; }).length;

        rHtml += '<div class="reporow" style="align-items:center;padding:8px 12px;">';
        rHtml += '<div class="repo-main" style="flex:1;min-width:0;">';
        if (r.iconUrl) {
          rHtml += '<img class="ricon" src="' + esc(r.iconUrl) + '" onerror="this.style.display=\'none\';this.nextElementSibling.style.display=\'flex\'" alt="">';
          rHtml += '<div class="rletter" style="display:none;">' + esc((r.name||'?').charAt(0).toUpperCase()) + '</div>';
        } else {
          rHtml += '<div class="rletter">' + esc((r.name||'?').charAt(0).toUpperCase()) + '</div>';
        }
        rHtml += '<div class="rinfo" style="min-width:0;">';
        rHtml += '<div class="row" style="gap:6px;align-items:center;flex-wrap:wrap;">';
        rHtml += '<div class="rname" style="font-size:13.5px;">' + esc(r.name || r.url) + '</div>';
        if (r.isLoading) rHtml += '<span class="badge amber"><span class="loader"></span> Loading</span>';
        else if (r.error) rHtml += '<span class="badge red" title="' + esc(r.error) + '">Error</span>';
        else rHtml += '<span class="badge gray">' + installedInThisRepo + ' / ' + (r.pluginCount || repoSources.length) + ' installed</span>';
        rHtml += '</div>';
        rHtml += '<div class="rurl" title="' + esc(r.url) + '">' + esc(r.url) + '</div>';
        rHtml += '</div>';
        rHtml += '</div>';

        rHtml += '<div class="repo-actions" style="gap:6px;flex-shrink:0;">';
        if (!r.isLoading && !r.error && (repoSources.length > installedInThisRepo)) {
          rHtml += '<button class="small success" onclick="installAllFromRepo(\'' + jsa(r.url) + '\')" title="Install all extensions from this repo">Install All (' + (repoSources.length - installedInThisRepo) + ')</button>';
        }
        rHtml += '<button class="small danger" onclick="removeRepo(\'' + jsa(r.url) + '\',\'' + jsa(r.name||r.url) + '\')" title="Remove Repository">' + svgTrash + ' Remove</button>';
        rHtml += '</div>';
        rHtml += '</div>';
      });
      rHtml += '</div>';
      reposCont.innerHTML = rHtml;
    }
  }

  // Calculate counts for dropdowns
  var totalCount = allPlugins.length;
  var installedCount = 0, availableCount = 0, activeCount = 0, disabledCount = 0;
  var cats = {};

  allPlugins.forEach(function(p) {
    if (p.installed) {
      installedCount++;
      var inst = installedList.find(function(x) { return x.internalName === p.internalName; });
      if (inst && inst.enabled) activeCount++; else if (inst) disabledCount++;
    } else {
      availableCount++;
    }
    (p.tvTypes || []).forEach(function(t) { if (t) cats[t] = (cats[t] || 0) + 1; });
  });

  // Update Status Dropdown if not focused
  var sSel = el("ext-status-select");
  if (sSel && document.activeElement !== sSel) {
    var cur = sSel.value || extStatusFilter;
    sSel.innerHTML = '<option value="all"' + (cur === "all" ? " selected" : "") + '>All Statuses (' + totalCount + ')</option>' +
      '<option value="installed"' + (cur === "installed" ? " selected" : "") + '>✓ Installed (' + installedCount + ')</option>' +
      '<option value="available"' + (cur === "available" ? " selected" : "") + '>+ Available (' + availableCount + ')</option>' +
      '<option value="active"' + (cur === "active" ? " selected" : "") + '>Active on Bridge (' + activeCount + ')</option>' +
      '<option value="disabled"' + (cur === "disabled" ? " selected" : "") + '>Disabled (' + disabledCount + ')</option>';
  }

  // Update Repos Dropdown if not focused
  var rSel = el("ext-repo-select");
  if (rSel && document.activeElement !== rSel) {
    var curR = rSel.value || extRepoFilter;
    var rOptHtml = '<option value="all"' + (curR === "all" ? " selected" : "") + '>All Repositories (' + repoList.length + ')</option>';
    repoList.forEach(function(r) {
      var rCount = allPlugins.filter(function(p) { return p.repoUrl === r.url; }).length;
      rOptHtml += '<option value="' + esc(r.url) + '"' + (curR === r.url ? " selected" : "") + '>' + esc(r.name || r.url) + ' (' + rCount + ')</option>';
    });
    rSel.innerHTML = rOptHtml;
  }

  // Update Types Dropdown if not focused
  var cSel = el("ext-cat-select");
  var catKeys = Object.keys(cats).sort();
  if (cSel && document.activeElement !== cSel) {
    var curC = cSel.value || extCatFilter;
    var cOptHtml = '<option value="all"' + (curC === "all" ? " selected" : "") + '>All Content Types (' + catKeys.length + ')</option>';
    catKeys.forEach(function(c) {
      cOptHtml += '<option value="' + esc(c) + '"' + (curC === c ? " selected" : "") + '>' + esc(c) + ' (' + cats[c] + ')</option>';
    });
    cSel.innerHTML = cOptHtml;
  }

  // Update Reset Button & Search Clear
  var resetWrap = el("ext-filter-reset-wrap");
  var hasActiveFilters = (extStatusFilter !== "all" || extRepoFilter !== "all" || extCatFilter !== "all" || !!extSearchQuery);
  if (resetWrap) resetWrap.style.display = hasActiveFilters ? "block" : "none";

  var clearBtn = el("sources-search-clear-btn");
  if (clearBtn) clearBtn.style.display = extSearchQuery ? "block" : "none";

  if (!allPlugins.length) {
    if (summary && summary.refreshing) {
      sourcesCont.innerHTML = '<div class="empty"><span class="loader"></span> Loading available sources from repositories…</div>';
    } else {
      sourcesCont.innerHTML = '<div class="empty">No sources found. <button class="small ghost" style="display:inline-flex;margin-left:6px;" onclick="refreshRepositories()">' + svgRefresh + ' Refresh Repos</button></div>';
    }
    return;
  }

  // Filter sources
  var filteredSources = allPlugins.filter(function(p) {
    if (extStatusFilter === "installed" && !p.installed) return false;
    if (extStatusFilter === "available" && p.installed) return false;
    if (extStatusFilter === "active" || extStatusFilter === "disabled") {
      if (!p.installed) return false;
      var inst = installedList.find(function(x) { return x.internalName === p.internalName; });
      if (extStatusFilter === "active" && (!inst || !inst.enabled)) return false;
      if (extStatusFilter === "disabled" && (!inst || inst.enabled)) return false;
    }
    if (extRepoFilter !== "all" && p.repoUrl !== extRepoFilter) return false;
    if (extCatFilter !== "all" && !(p.tvTypes || []).includes(extCatFilter)) return false;
    if (extSearchQuery) {
      var q = extSearchQuery.toLowerCase();
      var text = ((p.displayName||"") + " " + (p.name||"") + " " + (p.internalName||"") + " " + (p.description||"") + " " + (p.language||"") + " " + (p.tvTypes||[]).join(" ") + " " + (p.repoName||"")).toLowerCase();
      if (text.indexOf(q) < 0) return false;
    }
    return true;
  });

  if (!filteredSources.length) {
    sourcesCont.innerHTML = '<div class="empty">No sources match your filters. Try selecting "All Sources" or clearing your search.</div>';
  } else {
    var rowsHtml = '<div class="plugins-grid">';
    filteredSources.forEach(function(p) {
      rowsHtml += renderUnifiedSourceRow(p, installedList);
    });
    rowsHtml += '</div>';
    sourcesCont.innerHTML = rowsHtml;
  }
}

function renderUnifiedSourceRow(p, installedList) {
  var inst = null;
  if (installedList) {
    inst = installedList.find(function(x) { return x.internalName === p.internalName; });
  }

  var html = '<div class="plugin-card">';

  // Top header: Icon + Name + Version + Repo Name
  html += '<div style="display:flex; gap:10px; align-items:flex-start;">';
  if (p.iconUrl) {
    html += '<img class="ricon" src="' + esc(p.iconUrl) + '" onerror="this.style.display=\'none\';this.nextElementSibling.style.display=\'flex\'" alt="" style="width:38px;height:38px;border-radius:9px;object-fit:cover;flex:0 0 38px;">';
    html += '<div class="rletter" style="display:none;width:38px;height:38px;border-radius:9px;font-size:14px;">' + esc((p.displayName||p.name||'?').charAt(0).toUpperCase()) + '</div>';
  } else {
    html += '<div class="rletter" style="width:38px;height:38px;border-radius:9px;font-size:14px;">' + esc((p.displayName||p.name||'?').charAt(0).toUpperCase()) + '</div>';
  }
  html += '<div style="flex:1;min-width:0;">';
  html += '<div class="row" style="gap:6px;align-items:center;flex-wrap:wrap;">';
  html += '<div class="rname" style="font-size:14px;font-weight:700;">' + esc(p.displayName || p.name) + '</div>';
  html += '<span class="badge gray" style="font-size:10px;">v' + esc(p.version || "1.0") + '</span>';
  if (p.repoName) {
    html += '<span class="badge" style="background:rgba(255,255,255,0.06);color:var(--text2);font-size:10px;">' + esc(p.repoName) + '</span>';
  }
  if (p.installed && p.updateAvailable) {
    html += '<span class="badge amber" style="font-size:10px;">Update v' + esc(p.version) + '</span>';
  }
  html += '</div>';

  // Badges row
  html += '<div class="row" style="gap:4px;margin-top:4px;flex-wrap:wrap;">';
  if (p.tvTypes && p.tvTypes.length) {
    p.tvTypes.slice(0, 3).forEach(function(t) {
      html += '<span class="badge" style="background:var(--accent-light);color:var(--accent);font-size:9.5px;padding:1px 6px;">' + esc(t) + '</span>';
    });
  }
  if (p.language) {
    html += '<span class="badge gray" style="font-size:9.5px;padding:1px 6px;">' + esc(p.language.toUpperCase()) + '</span>';
  }
  html += '</div>';
  html += '</div>'; // close top info
  html += '</div>'; // close top header

  // Description
  if (p.description) {
    html += '<div class="muted" style="font-size:11.5px;line-height:1.4;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden;" title="' + esc(p.description) + '">' + esc(p.description) + '</div>';
  } else {
    html += '<div class="muted" style="font-size:11px;font-style:italic;">No description provided</div>';
  }

  // Bottom action bar
  html += '<div style="display:flex;align-items:center;justify-content:space-between;padding-top:10px;border-top:1px solid var(--divider);margin-top:auto;gap:8px;">';

  if (p.installState === "Installing") {
    html += '<button class="small ghost" disabled style="width:100%"><span class="loader"></span> ' + esc(p.installProgress || "Installing…") + '</button>';
  } else if (!p.installed) {
    html += '<button class="small primary" onclick="installPlugin(\'' + jsa(p.internalName) + '\')" style="width:100%">+ Install</button>';
  } else {
    html += '<div class="row" style="align-items:center;gap:6px;">';
    if (inst) {
      html += '<label class="switch" title="' + (inst.enabled ? "Enabled on manifest" : "Disabled") + '">';
      html += '<input type="checkbox" ' + (inst.enabled ? "checked" : "") + ' onchange="togglePlugin(\'' + jsa(p.internalName) + '\')">';
      html += '<span class="track"></span>';
      html += '</label>';
      html += '<span style="font-size:11px;font-weight:700;' + (inst.enabled ? 'color:var(--green)' : 'color:var(--muted)') + '">' + (inst.enabled ? 'Active' : 'Off') + '</span>';
    }
    html += '</div>';

    html += '<div class="row" style="gap:6px;align-items:center;">';
    if (p.updateAvailable) {
      html += '<button class="small primary" onclick="installPlugin(\'' + jsa(p.internalName) + '\')" title="Update to latest version">↑</button>';
    }
    if (inst && inst.hasSettings) {
      html += '<button class="iconbtn small" onclick="toggleSettingsDrawer(\'' + jsa(p.internalName) + '\')" title="Configure Settings">' + svgGear + '</button>';
    }
    html += '<button class="iconbtn small danger-btn" onclick="uninstallPlugin(\'' + jsa(p.internalName) + '\',\'' + jsa(p.displayName||p.name) + '\')" title="Uninstall">' + svgTrash + '</button>';
    html += '</div>';
  }

  html += '</div>'; // close bottom action bar
  html += '</div>'; // close plugin-card
  return html;
}

function normalizeRepoUrl(raw) {
  raw = (raw || "").trim();
  if (!raw) return null;
  if (!raw.includes("/") && !raw.includes(".") && !raw.includes(":")) {
    return raw;
  }
  if (!raw.includes("://") && raw.indexOf(".") < 0) {
    var parts = raw.split("/").filter(Boolean);
    if (parts.length === 2) {
      return "https://raw.githubusercontent.com/" + parts[0] + "/" + parts[1] + "/builds/repo.json";
    } else if (parts.length >= 3) {
      return "https://raw.githubusercontent.com/" + parts[0] + "/" + parts[1] + "/" + parts[2] + "/repo.json";
    }
  }
  if (raw.indexOf("github.com") >= 0 && raw.indexOf("raw.githubusercontent.com") < 0 && raw.indexOf(".json") < 0) {
    var stripped = raw.replace(/^https?:\/\//, "").replace(/^\/+/, "");
    var seg = stripped.replace(/^github\.com\//, "").split("/").filter(Boolean);
    if (seg.length >= 2) {
      return "https://raw.githubusercontent.com/" + seg[0] + "/" + seg[1] + "/builds/repo.json";
    }
  }
  if (raw.indexOf("://") < 0) raw = "https://" + raw;
  return raw;
}

function openAddRepoModal() {
  var inp = el("add-repo-input");
  if (inp) inp.value = "";
  el("add-repo-modal").classList.add("open");
  setTimeout(function() { var i = el("add-repo-input"); if (i) i.focus(); }, 80);
}

function closeAddRepoModal() {
  el("add-repo-modal").classList.remove("open");
}

function submitAddRepo() {
  var input = el("add-repo-input");
  var url = normalizeRepoUrl(input ? input.value : "");
  if (!url) return;
  if (input) input.value = url;
  var btn = el("add-repo-btn");
  if (btn) { btn.disabled = true; btn.textContent = "Adding\u2026"; }
  api("/repos/add", {method:"POST", body: JSON.stringify({url: url, saveGlobally: true})})
    .then(function(r) {
      toast(r && r.message ? r.message : "Repo added!");
      closeAddRepoModal();
      poll();
      loadPlugins();
    })
    .catch(function(e) { toast("Failed: " + e.message); })
    .finally(function() {
      var b = el("add-repo-btn"); if (b) { b.disabled = false; b.textContent = "Add Repository"; }
    });
}

function refreshRepositories() {
  toast("Refreshing repositories…");
  api("/repos/refresh", {method:"POST", body:"{}"})
    .then(function(r) {
      if (r && r.message) toast(r.message);
      poll();
      loadPlugins();
    })
    .catch(function(e) {
      toast("Refresh failed: " + (e.message || e));
    });
}

// ── Uninstall Modal Logic ─────────────────────────────────────────────────────
var _pendingUninstallId = null;
function openUninstallModal(id, name) {
  _pendingUninstallId = id;
  var nameEl = el("uninstall-plugin-name");
  if (nameEl) nameEl.textContent = name || id;
  el("uninstall-modal").classList.add("open");
}
function closeUninstallModal() {
  el("uninstall-modal").classList.remove("open");
  _pendingUninstallId = null;
}
function submitUninstall() {
  var id = _pendingUninstallId;
  if (!id) return;
  closeUninstallModal();
  act("/plugins/uninstall", {method:"POST", body: JSON.stringify({internalName: id})}, "Plugin uninstalled");
}
function uninstallPlugin(id, name) {
  openUninstallModal(id, name || id);
}

// ── Remove Repo Modal Logic ───────────────────────────────────────────────────
var _pendingRemoveRepoUrl = null;
function openRemoveRepoModal(url, name) {
  _pendingRemoveRepoUrl = url;
  var nameEl = el("remove-repo-name");
  if (nameEl) nameEl.textContent = name || url;
  el("remove-repo-modal").classList.add("open");
}
function closeRemoveRepoModal() {
  el("remove-repo-modal").classList.remove("open");
  _pendingRemoveRepoUrl = null;
}
function submitRemoveRepo() {
  var url = _pendingRemoveRepoUrl;
  if (!url) return;
  closeRemoveRepoModal();
  act("/repos/remove", {method:"POST", body: JSON.stringify({url: url})}, "Repo removed");
}
function removeRepo(url, name) {
  openRemoveRepoModal(url, name || url);
}

function installAllFromRepo(repoUrl) {
  api("/plugins/install-all-from-repo", {method:"POST", body: JSON.stringify({repoUrl: repoUrl})})
    .then(function(r) {
      toast(r && r.message ? r.message : "Installing all extensions\u2026");
      loadPlugins();
      poll();
    }).catch(function(e) { toast("Action failed: " + e.message); });
}

function installPlugin(id) {
  updatePluginCardState(id, "Installing", null, null);
  api("/plugins/install", {method:"POST", body: JSON.stringify({internalName: id})})
    .then(function() {
      loadPlugins();
      poll();
    }).catch(function(e) { toast("Install failed: " + e.message); });
}

function togglePlugin(id) {
  api("/plugins/toggle", {method:"POST", body: JSON.stringify({internalName: id})})
    .then(function(newPlugins) {
      if (Array.isArray(newPlugins)) {
        plugins = newPlugins;
        if (tab === "extensions") renderExtensions();
      }
      poll();
    }).catch(function(e) { toast("Toggle failed: " + e.message); });
}

function updatePluginCardState(id, state, progress, error) {
  if (!plugins) return;
  plugins = plugins.map(function(p) {
    if (p.internalName !== id) return p;
    return Object.assign({}, p, {
      installState: state || p.installState,
      installProgress: progress !== undefined ? progress : p.installProgress,
      error: error !== undefined ? error : p.error
    });
  });
  if (tab === "extensions") {
    var card = el("pc-" + id);
    if (card) {
      var p = null;
      for (var i = 0; i < plugins.length; i++) { if (plugins[i].internalName === id) { p = plugins[i]; break; } }
      if (p) card.outerHTML = renderPluginCard(p);
    }
  }
}

// ── Settings modal popup ──────────────────────────────────────────────────────
var settingsModalPluginId = null;

function closeSettingsModal() {
  el("settings-modal").classList.remove("open");
  settingsModalPluginId = null;
}

function toggleSettingsDrawer(id) {
  var modal = el("settings-modal");
  var box   = el("settings-modal-box");
  if (modal.classList.contains("open") && settingsModalPluginId === id) {
    closeSettingsModal();
    return;
  }
  settingsModalPluginId = id;
  var pluginName = id;
  if (plugins) {
    var pm = plugins.find(function(x) { return x.internalName === id; });
    if (pm) pluginName = pm.displayName || pm.name || id;
  }
  box.innerHTML = '<div class="modal-title">' + esc(pluginName) + ' Settings<button class="modal-close" onclick="closeSettingsModal()" title="Close">&times;</button></div>' +
    '<div class="muted" style="text-align:center;padding:20px"><span class="loader"></span> Loading settings…</div>';
  modal.classList.add("open");

  api("/plugins/" + encodeURIComponent(id) + "/settings/discover", {method:"POST", body:"{}"})
    .then(function() { return new Promise(function(res) { setTimeout(res, 700); }); })
    .then(function() { return api("/plugins/" + encodeURIComponent(id) + "/settings"); })
    .then(function(data) {
      if (settingsModalPluginId !== id) return;
      var header = '<div class="modal-title">' + esc(pluginName) + ' Settings<button class="modal-close" onclick="closeSettingsModal()" title="Close">&times;</button></div>';
      if (!data.settings || !data.settings.length) {
        box.innerHTML = header + '<div class="muted" style="padding:12px;text-align:center">No configurable options found yet.<br>Try using the plugin first, then reopen.</div>';
        return;
      }
      var html = header;
      var byCat = {}, order = [];
      data.settings.forEach(function(st) {
        if (!byCat[st.category]) { byCat[st.category] = []; order.push(st.category); }
        byCat[st.category].push(st);
      });
      order.forEach(function(cat) {
        html += '<div class="category">' + esc(cat) + '</div>';
        byCat[cat].forEach(function(st) { html += renderSetting(st, id); });
      });
      html += '<div class="row" style="margin-top:16px;gap:10px">';
      html += '<button class="primary small" onclick="applySettings(\'' + jsa(id) + '\')">Apply &amp; Reload</button>';
      html += '<button class="ghost small" onclick="closeSettingsModal()">Close</button>';
      html += '</div>';
      box.innerHTML = html;
    })
    .catch(function(e) {
      if (settingsModalPluginId !== id) return;
      var header = '<div class="modal-title">' + esc(pluginName) + ' Settings<button class="modal-close" onclick="closeSettingsModal()" title="Close">&times;</button></div>';
      box.innerHTML = header + '<div class="muted" style="padding:12px">Failed to load settings: ' + esc(e.message) + '</div>';
    });
}

document.addEventListener("keydown", function(e) {
  if (e.key === "Escape") {
    if (el("settings-modal").classList.contains("open")) closeSettingsModal();
    if (el("add-repo-modal").classList.contains("open")) closeAddRepoModal();
    if (el("benchmark-modal").classList.contains("open")) closeBenchmarkModal();
    if (el("probe-modal").classList.contains("open")) closeProbeModal();
  }
});

function renderSetting(st, pluginId) {
  var html = '<div class="setting">';
  html += '<div class="label">' + esc(st.friendlyName) + '</div>';
  if (st.description) html += '<div class="desc2">' + esc(st.description) + '</div>';

  if (st.options) {
    html += '<div style="margin-top:4px">';
    Object.keys(st.options).forEach(function(label) {
      var val = st.options[label];
      var checked = (st.currentValue || st.defaultValue || "") === val;
      html += '<label class="checkrow"><input type="radio" name="opt-' + esc(pluginId) + '-' + esc(st.storageKey) + '" ' + (checked?"checked":"") + ' onchange="saveSettingValue(\'' + jsa(pluginId) + '\',\'' + jsa(st.storageKey) + '\',\'' + jsa(val) + '\')"><span>' + esc(label) + '</span></label>';
    });
    html += '</div>';
  } else if (st.type === "StringSet") {
    var opts = st.defaultSet || [];
    var cur = st.currentSet || [];
    html += '<div class="row" style="margin-bottom:6px">';
    html += '<button class="small ghost" onclick="setAllValues(\'' + jsa(pluginId) + '\',\'' + jsa(st.storageKey) + '\',' + (st.isDisabledStyle?"false":"true") + ',' + JSON.stringify(opts).replace(/"/g,"&quot;") + ')">' + (st.isDisabledStyle?"Enable All":"Select All") + '</button>';
    html += '<button class="small ghost" onclick="setAllValues(\'' + jsa(pluginId) + '\',\'' + jsa(st.storageKey) + '\',' + (st.isDisabledStyle?"true":"false") + ',[])">' + (st.isDisabledStyle?"Disable All":"Deselect All") + '</button></div>';
    if (opts.length) {
      html += '<div class="checkgrid">';
      opts.forEach(function(o) {
        var checked = st.isDisabledStyle ? cur.indexOf(o)<0 : cur.indexOf(o)>=0;
        html += '<label class="checkrow"><input type="checkbox" ' + (checked?"checked":"") + ' onchange="toggleSetVal(\'' + jsa(pluginId) + '\',\'' + jsa(st.storageKey) + '\',\'' + jsa(o) + '\',' + (st.isDisabledStyle?"true":"false") + ',this)"><span>' + esc(o.replace("API","").replace("Api","")) + '</span></label>';
      });
      html += '</div>';
    }
  } else if (st.isBooleanLike) {
    var on = st.currentValue==="true"||(st.currentValue==null&&(st.defaultValue==="true"||st.defaultValue===true));
    html += '<div class="row" style="margin-top:4px"><span class="muted">' + (on?"Enabled":"Disabled") + '</span><div class="spacer"></div>';
    html += '<label class="switch"><input type="checkbox" ' + (on?"checked":"") + ' onchange="saveSettingValue(\'' + jsa(pluginId) + '\',\'' + jsa(st.storageKey) + '\',this.checked?\'true\':\'false\')"><span class="track"></span></label></div>';
  } else {
    var isNum = st.type==="Int"||st.type==="Long"||st.type==="Float";
    html += '<input ' + (isNum?'type="number" step="any"':'type="text"') + ' value="' + esc(st.currentValue!=null?st.currentValue:(st.defaultValue!=null?st.defaultValue:"")) + '" onchange="saveSettingValue(\'' + jsa(pluginId) + '\',\'' + jsa(st.storageKey) + '\',this.value||null)">';
  }
  html += '</div>';
  return html;
}

function saveSettingValue(pluginId, storageKey, value) {
  api("/plugins/" + encodeURIComponent(pluginId) + "/settings/value", {
    method:"POST",
    body: JSON.stringify({storageKey:storageKey, value:value===undefined?null:value})
  }).then(function() { toast("Saved"); }).catch(function(e) { toast("Save failed: "+e.message); });
}

function toggleSetVal(pluginId, storageKey, item, disabledStyle, checkbox) {
  var form = checkbox.closest(".checkgrid");
  if (!form) return;
  api("/plugins/" + encodeURIComponent(pluginId) + "/settings", {})
    .then(function(data) {
      var st = data.settings.find(function(s) { return s.storageKey === storageKey; });
      if (!st) return;
      var cur2 = (st.currentSet||[]).slice();
      var idx = cur2.indexOf(item);
      if (disabledStyle) {
        if (idx>=0) cur2.splice(idx,1); else cur2.push(item);
        if (!checkbox.checked) { if (idx<0) cur2.push(item); }
        else { if (idx>=0) cur2.splice(cur2.indexOf(item),1); }
      } else {
        if (checkbox.checked) { if (idx<0) cur2.push(item); }
        else { if (idx>=0) cur2.splice(idx,1); }
      }
      saveSettingValue(pluginId, storageKey, cur2.join("\n"));
    });
}

function setAllValues(pluginId, storageKey, selectAll, opts) {
  var arr = typeof opts==="string" ? JSON.parse(opts) : opts;
  saveSettingValue(pluginId, storageKey, selectAll ? arr.join("\n") : null);
}

function applySettings(pluginId) {
  api("/plugins/" + encodeURIComponent(pluginId) + "/settings/apply", {method:"POST",body:"{}"})
    .then(function() { toast("Settings applied \u2014 plugins reloaded"); poll(); })
    .catch(function(e) { toast("Apply failed: "+e.message); });
}

// ── Logs tab ─────────────────────────────────────────────────────────────────
var logLevelFilter = "ALL";

function setLogLevelFilter(lvl) {
  logLevelFilter = lvl;
  renderLogs();
}

function getFilteredLogs() {
  if (logLevelFilter === "ALL") return logsData;
  if (logLevelFilter === "STREAMS") {
    return logsData.filter(function(l) {
      return (l.message || "").indexOf("STREAM") >= 0 || (l.message || "").indexOf("stream(s)") >= 0 || (l.message || "").indexOf("loadLinks") >= 0;
    });
  }
  return logsData.filter(function(l) { return l.level === logLevelFilter; });
}

function renderLogs() {
  var filtered = getFilteredLogs();
  var html = '<div class="card"><h2>' + svgTerminal + ' Live Logs</h2><div class="hint">Real-time system events, search requests, stream extractions, and plugin operations.</div>';
  html += '<div class="logs-wrap"><div class="logs-toolbar">';
  html += '<button class="ghost small" onclick="copyLogs()">' + svgCopy + ' Copy All</button>';
  html += '<button class="ghost small" onclick="clearLogs()">Clear</button>';
  html += '<div class="row" style="gap:4px;margin-left:6px">';
  html += '<button class="small ' + (logLevelFilter==="ALL"?"primary":"ghost") + '" onclick="setLogLevelFilter(\'ALL\')" style="padding:2px 8px;font-size:11px">All</button>';
  html += '<button class="small ' + (logLevelFilter==="STREAMS"?"success":"ghost") + '" onclick="setLogLevelFilter(\'STREAMS\')" style="padding:2px 8px;font-size:11px">Streams Only</button>';
  html += '<button class="small ' + (logLevelFilter==="ERROR"?"danger":"ghost") + '" onclick="setLogLevelFilter(\'ERROR\')" style="padding:2px 8px;font-size:11px">Errors</button>';
  html += '<button class="small ' + (logLevelFilter==="WARN"?"primary":"ghost") + '" onclick="setLogLevelFilter(\'WARN\')" style="padding:2px 8px;font-size:11px">Warnings</button>';
  html += '<button class="small ' + (logLevelFilter==="INFO"?"primary":"ghost") + '" onclick="setLogLevelFilter(\'INFO\')" style="padding:2px 8px;font-size:11px">Info</button>';
  html += '</div>';
  html += '<span class="muted" style="font-size:11px;margin-left:6px">' + filtered.length + ' entries</span>';
  html += '<label class="row" style="gap:6px;font-size:12px;color:var(--text2);margin-left:auto"><input type="checkbox" id="autoscrollcb" ' + (autoScroll?"checked":"") + ' onchange="autoScroll=this.checked"> Auto-scroll</label>';
  html += '</div><div class="loglist" id="logsbox">';
  html += renderAllLogLines();
  html += '</div></div></div>';
  el("view").innerHTML = html;
  lastLogTimestamp = logsData.length > 0 ? (logsData[logsData.length - 1].timestamp || 0) : 0;
  var box = el("logsbox");
  if (box && autoScroll) box.scrollTop = box.scrollHeight;
}

function renderAllLogLines() {
  var filtered = getFilteredLogs();
  if (!filtered.length) return '<div class="empty">No log entries matching filter.</div>';
  return filtered.map(function(l) { return logLine(l); }).join("");
}

function logLine(l) {
  return '<div class="logline ' + esc(l.level) + '"><span class="t">' + fmtTime(l.timestamp) + '</span>' + esc(l.message) + '</div>';
}

function appendNewLogLines() {
  if (tab !== "logs") return;
  var box = el("logsbox");
  if (!box) return;
  if (logsData.length === 0) {
    box.innerHTML = '<div class="empty">No log entries yet.</div>';
    lastLogTimestamp = 0;
    return;
  }
  var newLines = logsData.filter(function(l) {
    if ((l.timestamp || 0) <= lastLogTimestamp) return false;
    if (logLevelFilter === "STREAMS") {
      return (l.message || "").indexOf("STREAM") >= 0 || (l.message || "").indexOf("stream(s)") >= 0 || (l.message || "").indexOf("loadLinks") >= 0;
    }
    if (logLevelFilter !== "ALL" && l.level !== logLevelFilter) return false;
    return true;
  });
  lastLogTimestamp = logsData[logsData.length - 1].timestamp || lastLogTimestamp;
  if (!newLines.length) return;
  var empty = box.querySelector(".empty");
  if (empty) empty.remove();
  var frag = document.createDocumentFragment();
  newLines.forEach(function(l) {
    var d = document.createElement("div");
    d.className = "logline " + l.level;
    d.innerHTML = '<span class="t">' + fmtTime(l.timestamp) + '</span>' + esc(l.message);
    frag.appendChild(d);
  });
  box.appendChild(frag);
  if (autoScroll) box.scrollTop = box.scrollHeight;
}

function clearLogs() {
  api("/logs/clear", {method:"POST",body:"{}"})
    .then(function() {
      logsData = [];
      lastLogTimestamp = 0;
      var box = el("logsbox");
      if (box) box.innerHTML = '<div class="empty">Logs cleared.</div>';
      toast("Logs cleared");
    }).catch(function(e) { toast("Clear failed: "+e.message); });
}

function copyLogs() {
  if (!logsData.length) return;
  var text = logsData.map(function(l) { return new Date(l.timestamp).toISOString()+" "+l.level+" "+l.message; }).join("\n");
  copyText(text);
}

// ── About tab ────────────────────────────────────────────────────────────────
function renderAbout() {
  var u = summary.update;
  var html = '<div class="grid cols2">';
  html += '<div class="card"><h2>' + svgInfo + ' System Information</h2><div class="hint">CNCVerse Bridge runtime environment and active parameters.</div>';
  html += '<div class="kv">';
  html += '<div class="k">Version</div><div>' + esc(summary.version) + '</div>';
  html += '<div class="k">Mode</div><div>' + (summary.headless ? "Headless Server" : "Desktop") + '</div>';
  html += '<div class="k">Platform</div><div>' + esc(summary.platform) + '</div>';
  html += '<div class="k">Loaded Plugins</div><div>' + summary.server.pluginCount + '</div>';
  html += '<div class="k">Repositories</div><div>' + (summary.repos ? summary.repos.length : 0) + '</div>';
  html += '</div>';
  html += '<div class="row" style="margin-top:14px;gap:6px">';
  html += '<a class="pill" href="https://github.com/NivinCNC/CNCVerse-Bridge" target="_blank" rel="noreferrer">GitHub</a>';
  html += '<a class="pill" href="https://t.me/cncverse" target="_blank" rel="noreferrer">Telegram</a>';
  html += '<a class="pill" href="https://cncverse.pages.dev" target="_blank" rel="noreferrer">Support</a>';
  html += '</div>';
  html += '<div class="muted" style="margin-top:16px;font-size:12px;line-height:1.6;">Made with <span style="color:#f43f5e">&#10084;&#65039;</span> &bull; <a href="https://t.me/NivinCNC" target="_blank" rel="noreferrer" style="color:var(--text);text-decoration:none;font-weight:700;">NivinCNC</a> <span style="opacity:0.5;font-size:10px;">Bridge Creator &amp; Maintainer</span> &bull; <a href="https://discord.com/users/sleepycat555" target="_blank" rel="noreferrer" style="color:var(--text);text-decoration:none;font-weight:700;">Ayu</a> <span style="opacity:0.5;font-size:10px;">UI &amp; JVM Core Developer</span></div>';
  html += '</div>';

  html += '<div class="card"><h2>' + svgRefresh + ' System Updates</h2><div class="hint">OTA updates from GitHub releases. Extensions update automatically in background.</div>';
  html += '<button class="primary small" onclick="checkUpdate()">' + svgRefresh + ' Check for Updates</button>';
  if (u && u.tagName) {
    html += '<div class="banner" style="margin-top:12px">New version available: <b>' + esc(u.tagName) + '</b></div>';
    if (u.body) html += '<div class="muted" style="margin-top:6px;white-space:pre-wrap;max-height:160px;overflow:auto">' + esc(u.body) + '</div>';
    if (u.downloadProgress !== null && u.downloadProgress !== undefined) {
      html += '<div class="progress" style="margin-top:10px"><div style="width:' + Math.round((u.downloadProgress||0)*100) + '%"></div></div>';
      html += '<div class="muted" style="margin-top:6px">Downloading... ' + Math.round((u.downloadProgress||0)*100) + '%</div>';
    } else {
      html += '<div style="margin-top:10px"><button class="primary small" onclick="act(\'/update/apply\',{method:\'POST\',body:\'{}\'}, \'Downloading update...\')">Download &amp; Install</button></div>';
    }
  } else if (u) {
    html += '<div class="banner" style="margin-top:12px">You are running the latest version.</div>';
  }
  html += '</div></div>';
  el("view").innerHTML = html;
}

function checkUpdate() {
  api("/update/check").then(function(u) {
    summary.update = u.tagName ? u : {tagName:"",htmlUrl:"",body:"",assetName:"",assetUrl:"",downloadProgress:null};
    toast(u.tagName ? "Update available: " + u.tagName : "No update available");
    renderAbout();
  }).catch(function(e) { toast("Update check failed: "+e.message); });
}

// ── Formatter tab ─────────────────────────────────────────────────────────────
// Rendered once when the tab opens (not on every poll) so edits are never lost.
var fmtState = null;
var fmtPreviewTimer = null;

function renderFormatter() {
  var view = el("view");
  view.innerHTML = '<div class="card"><div class="muted" style="text-align:center;padding:20px"><span class="loader"></span> Loading formatter...</div></div>';
  api("/formatter").then(function(s) {
    fmtState = s;
    var html = '<div class="grid cols2">';

    html += '<div class="card"><h2>Stream Formatter</h2>';
    html += '<div class="hint">Rewrites each stream&#39;s name and description shown in Stremio using a template. Off by default &mdash; streams keep their original text until you enable it.</div>';
    html += '<div class="row" style="gap:10px;margin:6px 0 4px"><label class="switch"><input type="checkbox" id="fmt-enabled"' + (s.enabled ? ' checked' : '') + '><span class="track"></span></label><span>Apply formatter to streams</span></div>';
    html += '<div style="margin:12px 0;padding:12px;background:var(--bg-elevated, #1c1d22);border-radius:8px;border:1px solid var(--border, #2a2b32);">';
    html += '<div style="font-weight:600;font-size:12.5px;color:var(--text, #fff);margin-bottom:8px;display:flex;align-items:center;gap:6px;"><span>✨ Choose a Stream Style Preset:</span></div>';
    html += '<div class="row" style="gap:8px;flex-wrap:wrap;">';
    (s.presets || []).forEach(function(p) {
      html += '<button type="button" class="small ghost" onclick="applyPreset(\'' + jsa(p.id) + '\')" title="' + esc(p.description) + '">' + esc(p.title) + '</button>';
    });
    html += '</div></div>';
    html += '<label class="fmt-label" for="fmt-name">Name template <span class="muted" style="font-weight:400">(stream title line in Stremio)</span></label>';
    html += '<textarea class="tpl" id="fmt-name" rows="3" spellcheck="false"></textarea>';
    html += '<label class="fmt-label" for="fmt-desc">Description template <span class="muted" style="font-weight:400">(blank lines are removed)</span></label>';
    html += '<textarea class="tpl" id="fmt-desc" rows="9" spellcheck="false"></textarea>';
    html += '<div class="row" style="gap:8px;margin-top:12px;flex-wrap:wrap">';
    html += '<button class="primary small" onclick="saveFormatter()">Save</button>';
    html += '<button class="ghost small" onclick="clearFormatter()">Clear</button>';
    html += '</div>';
    html += '<div id="fmt-status" style="margin-top:10px"></div>';
    html += '</div>';

    html += '<div class="card"><h2>Live Preview</h2><div class="hint">Rendered against sample streams as you type.</div><div id="fmt-preview"></div></div>';
    html += '</div>';

    html += '<div class="card" style="margin-top:14px"><h2>Variables &amp; Syntax</h2>';
    html += '<div class="hint">Click a variable to copy it.</div><div class="fmt-vars">';
    (s.variables || []).forEach(function(v) {
      html += '<code onclick="copyText(\'{' + esc(v) + '}\')">{' + esc(v) + '}</code>';
    });
    html += '</div>';
    html += '<div class="muted" style="font-size:12.5px;line-height:1.7;margin-top:12px">' +
      '<b>Modifiers</b> (chain with <code>::</code>): exists, length, join(&#39;sep&#39;), default(&#39;text&#39;), replace(&#39;a&#39;,&#39;b&#39;), upper, lower, title, trim, first, last, truncate(n), bytes (GB), bytes2 (GiB), istrue, isfalse<br>' +
      '<b>Comparisons</b>: <code>=</code> <code>!=</code> <code>&gt;</code> <code>&gt;=</code> <code>&lt;</code> <code>&lt;=</code> <code>~</code> (contains)<br>' +
      '<b>Conditions</b>: <code>{stream.resolution::=2160p["4K"||"HD"]}</code> &mdash; branches are templates and can nest; <code>["text"]</code> alone means empty otherwise<br>' +
      '<b>stream.source</b> is the server the extension reports (e.g. FslServer, HubCloud) &mdash; empty when it just repeats the extension name<br>' +
      '<b>Lists</b>: stream.specs, stream.languages, stream.subtitles, stream.visualTags, stream.audioTags, stream.seasonEpisode &middot; <b>size</b> is in bytes (use bytes / bytes2)<br>' +
      'Size, languages and tags are parsed from the extension&#39;s release name, so they are only present when the source names them.' +
      '</div></div>';

    view.innerHTML = html;
    el("fmt-name").value = s.nameTemplate || "";
    el("fmt-desc").value = s.descriptionTemplate || "";
    el("fmt-name").addEventListener("input", scheduleFormatterPreview);
    el("fmt-desc").addEventListener("input", scheduleFormatterPreview);
    previewFormatter();
  }).catch(function(e) {
    view.innerHTML = '<div class="card"><div class="fmt-err">Could not load formatter: ' + esc(e.message) + '</div></div>';
  });
}

function formatterBody(includeEnabled) {
  var body = { nameTemplate: el("fmt-name").value, descriptionTemplate: el("fmt-desc").value };
  if (includeEnabled) body.enabled = el("fmt-enabled").checked;
  return JSON.stringify(body);
}

function scheduleFormatterPreview() {
  clearTimeout(fmtPreviewTimer);
  fmtPreviewTimer = setTimeout(previewFormatter, 350);
}

function previewFormatter() {
  var box = el("fmt-preview");
  if (!box) return;
  api("/formatter/preview", { method: "POST", body: formatterBody(false) }).then(function(r) {
    if (!el("fmt-preview")) return;
    if (!r.ok) { box.innerHTML = '<div class="fmt-err">' + esc(r.error) + '</div>'; return; }
    var html = '';
    (r.samples || []).forEach(function(smp) {
      html += '<div class="fmt-sample"><div class="fmt-kind">' + esc(smp.label) + '</div>' +
        '<div class="fmt-name">' + esc(smp.name) + '</div>' +
        '<div class="fmt-desc">' + esc(smp.description) + '</div></div>';
    });
    box.innerHTML = html || '<div class="muted">Both templates are empty &mdash; streams keep their original text.</div>';
  }).catch(function(e) { box.innerHTML = '<div class="fmt-err">Preview failed: ' + esc(e.message) + '</div>'; });
}

function saveFormatter() {
  var status = el("fmt-status");
  api("/formatter", { method: "POST", body: formatterBody(true) }).then(function(r) {
    status.innerHTML = r.ok ? '<span class="badge green">' + esc(r.message) + '</span>' : '<div class="fmt-err">' + esc(r.message) + '</div>';
    if (r.ok) toast(r.message);
  }).catch(function(e) { status.innerHTML = '<div class="fmt-err">Save failed: ' + esc(e.message) + '</div>'; });
}

function applyPreset(id) {
  if (!fmtState || !fmtState.presets) return;
  var found = fmtState.presets.find(function(p) { return p.id === id; });
  if (!found) return;
  el("fmt-name").value = found.nameTemplate;
  el("fmt-desc").value = found.descriptionTemplate;
  previewFormatter();
  toast(found.title + " preset loaded — click Save to apply");
}

function loadFormatterPreset() {
  if (!fmtState) return;
  el("fmt-name").value = fmtState.presetName;
  el("fmt-desc").value = fmtState.presetDescription;
  previewFormatter();
  toast("CNCVerse preset loaded — press Save to apply");
}

function clearFormatter() {
  el("fmt-name").value = "";
  el("fmt-desc").value = "";
  previewFormatter();
}

// ── Shared actions ────────────────────────────────────────────────────────────
function act(path, opts, msg) {
  api(path, opts).then(function(r) {
    if (msg) toast(r && r.message ? r.message : msg);
    poll();
    if (tab === "extensions") loadPlugins();
  }).catch(function(e) { toast("Action failed: "+e.message); });
}

var tabTitles = {
  "server": "Dashboard",
  "extensions": "Extensions & Repositories",
  "health": "Stream Diagnostics",
  "cache": "Stream Cache & Deduplication",
  "formatter": "Stream Formatter",
  "credits": "Team & Developer Credits",
  "logs": "System Logs",
  "about": "About & Updates"
};

function toggleDrawer(open) {
  var d = document.getElementById("drawer");
  var b = document.getElementById("drawer-backdrop");
  if (!d || !b) return;
  if (window.innerWidth >= 860) return;
  if (open) {
    d.classList.add("open");
    b.classList.add("open");
  } else {
    d.classList.remove("open");
    b.classList.remove("open");
  }
}

function openTab(t) {
  tab = t;
  document.querySelectorAll(".drawer-btn[data-tab]").forEach(function(b) {
    b.classList.toggle("active", b.getAttribute("data-tab") === t);
  });
  var sub = document.getElementById("hdr-active-tab");
  if (sub && tabTitles[t]) sub.textContent = tabTitles[t];

  if (t === "logs") { renderLogs(); pollLogs(); }
  else if (t === "extensions") { renderExtensions(); loadPlugins(); }
  else if (t === "health") { renderStreamHealth(); loadStreamHealth(); }
  else if (t === "cache") { renderCacheTab(); loadCacheConfig(); }
  else if (t === "formatter") { renderFormatter(); }
  else if (t === "credits") { loadCredits(); }
  else if (t === "about") { renderAbout(); }
  else render();
}

// ── Authors & Credits Manager Tab ─────────────────────────────────────────────
var creditsData = [];
var footerCreditsData = [];
var creditsLoaded = false;
var creditsSubTab = 'team'; // 'team' | 'repos' | 'footer'

function loadCredits() {
  Promise.all([
    api("/credits"),
    api("/footer-credits")
  ]).then(function(res) {
    creditsData = res[0] || [];
    footerCreditsData = res[1] || [];
    creditsLoaded = true;
    if (tab === "credits") renderCredits();
    updateAdminSidebarFooter();
  }).catch(function(e) {
    if (tab === "credits") toast("Failed to load credits: " + e.message);
  });
}

function renderCredits() {
  var html = '<div class="card">';
  html += '<div class="row" style="align-items:center; justify-content:space-between; margin-bottom:12px; flex-wrap:wrap; gap:10px;">';
  html += '<div><h2>Team &amp; Developer Credits</h2>';
  html += '<div class="hint" style="margin-bottom:0">Manage bridge developers, contributors, and footer section credits.</div></div>';
  html += '<div class="row" style="gap:8px; flex-shrink:0;">';
  if (creditsSubTab === 'team') {
    html += '<button class="primary small" onclick="addNewAuthorCredit()">+ Add Contributor</button>';
  } else if (creditsSubTab === 'footer') {
    html += '<button class="primary small" onclick="addNewFooterCredit()">+ Add Footer Credit</button>';
  }
  html += '<button class="success small" onclick="saveAllCredits()">Save All Changes</button>';
  html += '<button class="ghost small" onclick="creditsLoaded=false; loadCredits();">' + svgRefresh + ' Reload</button>';
  html += '</div></div>';

  var teamEntries = creditsData.filter(function(c) { return c.isCurated && (!c.repoUrl || c.repoUrl === ""); });
  var repoEntries = creditsData.filter(function(c) { return !c.isCurated || (c.repoUrl && c.repoUrl !== ""); });

  html += '<div class="row" style="gap:8px; margin: 10px 0 16px; border-bottom: 1px solid var(--border); padding-bottom: 12px; flex-wrap:wrap;">';
  html += '<button class="pill ' + (creditsSubTab === 'team' ? 'active' : '') + '" onclick="creditsSubTab=\'team\'; renderCredits();">&#128101; Core Team &amp; Contributors (' + teamEntries.length + ')</button>';
  html += '<button class="pill ' + (creditsSubTab === 'repos' ? 'active' : '') + '" onclick="creditsSubTab=\'repos\'; renderCredits();">&#128218; Repository Maintainer Links (' + repoEntries.length + ')</button>';
  html += '<button class="pill ' + (creditsSubTab === 'footer' ? 'active' : '') + '" onclick="creditsSubTab=\'footer\'; renderCredits();">&#129462; Footer Section Credits (' + footerCreditsData.length + ')</button>';
  html += '</div>';

  if (creditsSubTab === 'team') {
    if (!teamEntries.length) {
      html += '<div class="empty">No team contributors configured. Click "+ Add Contributor" to create one.</div>';
    } else {
      html += '<div style="display:flex; flex-direction:column; gap:12px;">';
      teamEntries.forEach(function(c) {
        var idx = creditsData.indexOf(c);
        html += '<div class="card2" style="padding:14px; border:1px solid var(--border); border-radius:10px;">';
        
        // Card Header
        html += '<div class="row" style="justify-content:space-between; align-items:center; margin-bottom:10px; padding-bottom:8px; border-bottom:1px solid var(--divider);">';
        html += '<div class="row" style="gap:8px; align-items:center; min-width:0;">';
        if (c.avatarUrl) {
          html += '<img src="' + esc(c.avatarUrl) + '" style="width:28px;height:28px;border-radius:6px;object-fit:cover;flex:0 0 28px;" onerror="this.style.display=\'none\'">';
        }
        html += '<span style="font-weight:700; font-size:13px;">' + esc(c.authorName || 'Untitled') + '</span>';
        if (c.roleBadge) html += '<span class="badge violet">' + esc(c.roleBadge) + '</span>';
        html += '</div>';
        
        html += '<div class="row" style="gap:5px; flex-shrink:0;">';
        var tIdx = teamEntries.indexOf(c);
        if (tIdx > 0) html += '<button class="ghost small" onclick="moveCredit(' + idx + ',-1)" style="padding:3px 7px;">&uarr;</button>';
        if (tIdx < teamEntries.length - 1) html += '<button class="ghost small" onclick="moveCredit(' + idx + ',1)" style="padding:3px 7px;">&darr;</button>';
        html += '<button class="danger small" onclick="deleteAuthorCredit(' + idx + ')" style="padding:3px 8px;">' + svgTrash + ' Remove</button>';
        html += '</div></div>';

        // Editable fields
        html += '<div class="grid cols2" style="gap:10px;">';
        html += '<div><label style="font-size:10.5px;font-weight:700;color:var(--muted);text-transform:uppercase;">Contributor Name</label>';
        html += '<input type="text" value="' + esc(c.authorName || '') + '" oninput="updateCreditField(' + idx + ',\'authorName\',this.value)" placeholder="e.g. NivinCNC"></div>';

        html += '<div><label style="font-size:10.5px;font-weight:700;color:var(--muted);text-transform:uppercase;">Role / Badge (Custom Tag)</label>';
        html += '<input type="text" value="' + esc(c.roleBadge || '') + '" oninput="updateCreditField(' + idx + ',\'roleBadge\',this.value)" placeholder="e.g. Core Developer, UI Designer"></div>';

        html += '<div><label style="font-size:10.5px;font-weight:700;color:var(--muted);text-transform:uppercase;">Avatar URL</label>';
        html += '<input type="text" value="' + esc(c.avatarUrl || '') + '" oninput="updateCreditField(' + idx + ',\'avatarUrl\',this.value)" placeholder="https://github.com/username.png"></div>';

        html += '<div><label style="font-size:10.5px;font-weight:700;color:var(--muted);text-transform:uppercase;">GitHub Profile URL</label>';
        html += '<input type="text" value="' + esc(c.githubUrl || '') + '" oninput="updateCreditField(' + idx + ',\'githubUrl\',this.value)" placeholder="https://github.com/username"></div>';

        html += '<div><label style="font-size:10.5px;font-weight:700;color:var(--muted);text-transform:uppercase;">Discord Handle or URL</label>';
        html += '<input type="text" value="' + esc(c.discordUrl || '') + '" oninput="updateCreditField(' + idx + ',\'discordUrl\',this.value)" placeholder="username or https://discord.com/users/..."></div>';

        html += '<div><label style="font-size:10.5px;font-weight:700;color:var(--muted);text-transform:uppercase;">Telegram URL</label>';
        html += '<input type="text" value="' + esc(c.telegramUrl || '') + '" oninput="updateCreditField(' + idx + ',\'telegramUrl\',this.value)" placeholder="https://t.me/username"></div>';

        html += '<div><label style="font-size:10.5px;font-weight:700;color:var(--muted);text-transform:uppercase;">Donation / Support URL</label>';
        html += '<input type="text" value="' + esc(c.donationUrl || '') + '" oninput="updateCreditField(' + idx + ',\'donationUrl\',this.value)" placeholder="https://buymeacoffee.com/..."></div>';

        html += '<div><label style="font-size:10.5px;font-weight:700;color:var(--muted);text-transform:uppercase;">Website URL</label>';
        html += '<input type="text" value="' + esc(c.websiteUrl || '') + '" oninput="updateCreditField(' + idx + ',\'websiteUrl\',this.value)" placeholder="https://example.com"></div>';
        html += '</div>';

        html += '<div style="margin-top:10px;"><label style="font-size:10.5px;font-weight:700;color:var(--muted);text-transform:uppercase;">Bio / Description</label>';
        html += '<textarea style="width:100%;min-height:52px;font-family:inherit;font-size:12px;background:var(--card);border:1px solid var(--border);border-radius:6px;padding:6px 10px;color:var(--text);box-sizing:border-box;" oninput="updateCreditField(' + idx + ',\'description\',this.value)" placeholder="Brief description of contributions">' + esc(c.description || '') + '</textarea></div>';

        html += '</div>';
      });
      html += '</div>';
    }
  } else if (creditsSubTab === 'footer') {
    // ── Footer Section Credits Sub-Tab ──
    html += '<div class="hint" style="margin-bottom:12px;">Configure names, custom tags, and embedded links shown in the public website footer and the admin sidebar. Nothing is hardcoded. Leave empty to show no footer credits.</div>';

    // Live Footer Preview Box
    html += '<div style="background:var(--card); border:1px dashed var(--border); border-radius:10px; padding:12px 16px; margin-bottom:14px;">';
    html += '<div style="font-size:10.5px; font-weight:700; text-transform:uppercase; color:var(--muted); margin-bottom:6px;">Live Public Footer Preview</div>';
    var previewParts = footerCreditsData.map(function(item) {
      var linkHtml = item.url ? ('<a href="' + esc(item.url) + '" target="_blank" rel="noopener" style="color:var(--accent); font-weight:700; text-decoration:none;">' + esc(item.name || 'Name') + '</a>') : ('<span style="font-weight:700;">' + esc(item.name || 'Name') + '</span>');
      return item.label ? ('<span>' + esc(item.label) + ' ' + linkHtml + '</span>') : ('<span>' + linkHtml + '</span>');
    });
    var previewContent = previewParts.length ? previewParts.join(' <span style="opacity:0.5;">&middot;</span> ') : '<span style="color:var(--muted); font-style:italic;">(No footer credits configured — footer is empty)</span>';
    html += '<div style="display:flex; justify-content:center; align-items:center; gap:8px; font-size:12px; color:var(--text); padding:8px; background:var(--bg); border-radius:6px; flex-wrap:wrap;">' + previewContent + '</div>';
    html += '</div>';

    if (!footerCreditsData.length) {
      html += '<div class="empty">No footer credits configured. Click "+ Add Footer Credit" to add a name and embed any kind of link into the footer section.</div>';
    } else {
      html += '<div style="display:flex; flex-direction:column; gap:12px;">';
      footerCreditsData.forEach(function(item, fIdx) {
        html += '<div class="card2" style="padding:14px; border:1px solid var(--border); border-radius:10px;">';
        html += '<div class="row" style="justify-content:space-between; align-items:center; margin-bottom:10px; padding-bottom:8px; border-bottom:1px solid var(--divider);">';
        html += '<span style="font-weight:700; font-size:13px;">Footer Credit #' + (fIdx + 1) + (item.name ? (' &mdash; ' + esc(item.name)) : '') + '</span>';
        html += '<div class="row" style="gap:5px;">';
        if (fIdx > 0) html += '<button class="ghost small" onclick="moveFooterCredit(' + fIdx + ',-1)" style="padding:3px 7px;">&uarr;</button>';
        if (fIdx < footerCreditsData.length - 1) html += '<button class="ghost small" onclick="moveFooterCredit(' + fIdx + ',1)" style="padding:3px 7px;">&darr;</button>';
        html += '<button class="danger small" onclick="deleteFooterCredit(' + fIdx + ')" style="padding:3px 8px;">' + svgTrash + ' Remove</button>';
        html += '</div></div>';

        html += '<div class="grid cols3" style="gap:10px;">';
        html += '<div><label style="font-size:10.5px;font-weight:700;color:var(--muted);text-transform:uppercase;">Tag / Prefix</label>';
        html += '<input type="text" value="' + esc(item.label || '') + '" oninput="updateFooterCreditField(' + fIdx + ',\'label\',this.value)" placeholder="e.g. Developed by, UI by, Sponsored by"></div>';

        html += '<div><label style="font-size:10.5px;font-weight:700;color:var(--muted);text-transform:uppercase;">Name</label>';
        html += '<input type="text" value="' + esc(item.name || '') + '" oninput="updateFooterCreditField(' + fIdx + ',\'name\',this.value)" placeholder="e.g. NivinCNC, Ayu"></div>';

        html += '<div><label style="font-size:10.5px;font-weight:700;color:var(--muted);text-transform:uppercase;">Embed Link URL</label>';
        html += '<input type="text" value="' + esc(item.url || '') + '" oninput="updateFooterCreditField(' + fIdx + ',\'url\',this.value)" placeholder="https://t.me/..., https://github.com/..."></div>';
        html += '</div>';

        html += '</div>';
      });
      html += '</div>';
    }
  } else {
    // ── Repository Maintainer Links Sub-Tab ──
    html += '<div class="hint" style="margin-bottom:12px;">Auto-populated from installed repos. Add custom contact, Discord, Telegram, or donation links for repository maintainers so users can support them.</div>';
    if (!repoEntries.length) {
      html += '<div class="empty">No repositories installed. Install a repository from Extensions &amp; Repos to configure maintainer links.</div>';
    } else {
      html += '<div style="display:flex; flex-direction:column; gap:12px;">';
      repoEntries.forEach(function(c) {
        var idx = creditsData.indexOf(c);
        html += '<div class="card2" style="padding:14px; border:1px solid var(--border); border-radius:10px;">';
        
        // Header
        html += '<div class="row" style="justify-content:space-between; align-items:center; margin-bottom:10px; padding-bottom:8px; border-bottom:1px solid var(--divider);">';
        html += '<div class="row" style="gap:8px; align-items:center; min-width:0;">';
        if (c.avatarUrl) {
          html += '<img src="' + esc(c.avatarUrl) + '" style="width:28px;height:28px;border-radius:6px;object-fit:cover;flex:0 0 28px;" onerror="this.style.display=\'none\'">';
        }
        html += '<span style="font-weight:700; font-size:13px;">' + esc(c.authorName || 'Untitled Repo') + '</span>';
        if (c.pluginCount) html += '<span class="badge violet" style="font-size:10px;">' + c.pluginCount + ' sources</span>';
        html += '<span class="badge green" style="font-size:9px;">Installed Repo</span>';
        html += '</div></div>';

        // Fields
        html += '<div class="grid cols2" style="gap:10px;">';
        html += '<div><label style="font-size:10.5px;font-weight:700;color:var(--muted);text-transform:uppercase;">Repository URL</label>';
        html += '<input type="text" value="' + esc(c.repoUrl || '') + '" disabled style="opacity:0.6;cursor:not-allowed;"></div>';

        html += '<div><label style="font-size:10.5px;font-weight:700;color:var(--muted);text-transform:uppercase;">Maintainer GitHub URL</label>';
        html += '<input type="text" value="' + esc(c.githubUrl || '') + '" oninput="updateCreditField(' + idx + ',\'githubUrl\',this.value)" placeholder="https://github.com/username"></div>';

        html += '<div><label style="font-size:10.5px;font-weight:700;color:var(--muted);text-transform:uppercase;">Discord Invite or Handle</label>';
        html += '<input type="text" value="' + esc(c.discordUrl || '') + '" oninput="updateCreditField(' + idx + ',\'discordUrl\',this.value)" placeholder="https://discord.gg/... or username"></div>';

        html += '<div><label style="font-size:10.5px;font-weight:700;color:var(--muted);text-transform:uppercase;">Telegram Group or Channel</label>';
        html += '<input type="text" value="' + esc(c.telegramUrl || '') + '" oninput="updateCreditField(' + idx + ',\'telegramUrl\',this.value)" placeholder="https://t.me/channel"></div>';

        html += '<div><label style="font-size:10.5px;font-weight:700;color:var(--muted);text-transform:uppercase;">Support / Donation URL</label>';
        html += '<input type="text" value="' + esc(c.donationUrl || '') + '" oninput="updateCreditField(' + idx + ',\'donationUrl\',this.value)" placeholder="https://buymeacoffee.com/..."></div>';

        html += '<div><label style="font-size:10.5px;font-weight:700;color:var(--muted);text-transform:uppercase;">Website URL</label>';
        html += '<input type="text" value="' + esc(c.websiteUrl || '') + '" oninput="updateCreditField(' + idx + ',\'websiteUrl\',this.value)" placeholder="https://example.com"></div>';
        html += '</div>';

        html += '<div style="margin-top:10px;"><label style="font-size:10.5px;font-weight:700;color:var(--muted);text-transform:uppercase;">Maintainer Note / Bio</label>';
        html += '<textarea style="width:100%;min-height:52px;font-family:inherit;font-size:12px;background:var(--card);border:1px solid var(--border);border-radius:6px;padding:6px 10px;color:var(--text);box-sizing:border-box;" oninput="updateCreditField(' + idx + ',\'description\',this.value)" placeholder="Description or message">' + esc(c.description || '') + '</textarea></div>';

        html += '</div>';
      });
      html += '</div>';
    }
  }

  html += '<div style="margin-top:16px;display:flex;justify-content:flex-end;gap:8px;">';
  html += '<button class="success" onclick="saveAllCredits()">Save All Changes</button>';
  html += '</div>';
  html += '</div>';
  el("view").innerHTML = html;
}

function updateCreditField(idx, field, value) {
  if (creditsData[idx]) creditsData[idx][field] = value;
}

function addNewAuthorCredit() {
  creditsData.push({
    id: "author_" + Date.now(),
    authorName: "",
    roleBadge: "Core Developer",
    description: "",
    avatarUrl: "",
    repoUrl: "",
    githubUrl: "",
    discordUrl: "",
    telegramUrl: "",
    donationUrl: "",
    websiteUrl: "",
    isCurated: true
  });
  creditsSubTab = 'team';
  renderCredits();
}

function deleteAuthorCredit(idx) {
  creditsData.splice(idx, 1);
  renderCredits();
}

function moveCredit(idx, dir) {
  var target = idx + dir;
  if (target < 0 || target >= creditsData.length) return;
  var temp = creditsData[idx];
  creditsData[idx] = creditsData[target];
  creditsData[target] = temp;
  renderCredits();
}

function addNewFooterCredit() {
  footerCreditsData.push({
    id: "ft_" + Date.now(),
    label: "Developed by",
    name: "",
    url: ""
  });
  creditsSubTab = 'footer';
  renderCredits();
  updateAdminSidebarFooter();
}

function deleteFooterCredit(idx) {
  footerCreditsData.splice(idx, 1);
  renderCredits();
  updateAdminSidebarFooter();
}

function moveFooterCredit(idx, dir) {
  var target = idx + dir;
  if (target < 0 || target >= footerCreditsData.length) return;
  var temp = footerCreditsData[idx];
  footerCreditsData[idx] = footerCreditsData[target];
  footerCreditsData[target] = temp;
  renderCredits();
  updateAdminSidebarFooter();
}

function updateFooterCreditField(idx, field, value) {
  if (footerCreditsData[idx]) {
    footerCreditsData[idx][field] = value;
    updateAdminSidebarFooter();
    // Update live preview in real time if present
    var previewParts = footerCreditsData.map(function(item) {
      var linkHtml = item.url ? ('<a href="' + esc(item.url) + '" target="_blank" rel="noopener" style="color:var(--accent); font-weight:700; text-decoration:none;">' + esc(item.name || 'Name') + '</a>') : ('<span style="font-weight:700;">' + esc(item.name || 'Name') + '</span>');
      return item.label ? ('<span>' + esc(item.label) + ' ' + linkHtml + '</span>') : ('<span>' + linkHtml + '</span>');
    });
    var el = document.getElementById("footer-live-preview");
    if (el) {
      el.innerHTML = previewParts.length ? previewParts.join(' <span style="opacity:0.5;">&middot;</span> ') : '<span style="color:var(--muted); font-style:italic;">(No footer credits configured — footer is empty)</span>';
    }
  }
}

function updateAdminSidebarFooter() {
  var el = document.getElementById("admin-footer-credits-list");
  if (!el) return;
  if (!footerCreditsData || !footerCreditsData.length) {
    el.innerHTML = "";
    return;
  }
  var parts = footerCreditsData.map(function(item) {
    var linkHtml = item.url ? ('<a href="' + esc(item.url) + '" target="_blank" rel="noopener" style="color:var(--accent); text-decoration:none; font-weight:600;">' + esc(item.name || '') + '</a>') : ('<span style="font-weight:600;">' + esc(item.name || '') + '</span>');
    return item.label ? (esc(item.label) + ' ' + linkHtml) : linkHtml;
  });
  el.innerHTML = parts.join(' &middot; ');
}

function saveAllCredits() {
  toast("Saving all credits & footer links…");
  Promise.all([
    api("/credits/save", {
      method: "POST",
      body: JSON.stringify(creditsData)
    }),
    api("/footer-credits/save", {
      method: "POST",
      body: JSON.stringify(footerCreditsData)
    })
  ]).then(function() {
    toast("✓ Author & Footer credits saved!");
    updateAdminSidebarFooter();
  }).catch(function(e) {
    toast("Save failed: " + e.message);
  });
}

// ── ⚡ Stream Cache & Deduplication Tab ─────────────────────────────────────
var cacheConfigData = null;
var cacheInspectResult = null;
var cacheConfigLoading = false;

function loadCacheConfig() {
  cacheConfigLoading = true;
  api("/cache/config").then(function(cfg) {
    cacheConfigData = cfg || {};
    cacheConfigLoading = false;
    if (tab === "cache") renderCacheTab();
  }).catch(function(e) {
    cacheConfigLoading = false;
    toast("Failed to load cache configuration: " + e.message);
  });
}

function updateCacheTelemetryInPlace() {
  if (!summary || !summary.cacheStats) return;
  var cs = summary.cacheStats;
  var rateEl = el("cache-metric-hitrate");
  var hitsEl = el("cache-metric-hits");
  var missesEl = el("cache-metric-misses");
  var savedEl = el("cache-metric-saved");
  var ramEl = el("cache-metric-ram");
  var evictedEl = el("cache-metric-evicted");
  var meterEl = el("cache-meter-bar");
  var statusBadge = el("cache-status-badge");

  if (rateEl) rateEl.textContent = cs.hitRate + "%";
  if (hitsEl) hitsEl.textContent = Number(cs.hits).toLocaleString();
  if (missesEl) missesEl.textContent = Number(cs.misses).toLocaleString();
  if (savedEl) savedEl.textContent = Number(cs.requestsSaved).toLocaleString();
  if (ramEl) ramEl.textContent = Number(cs.activeRamEntries).toLocaleString() + " / " + Number(cs.maxRamEntries).toLocaleString();
  if (evictedEl) evictedEl.textContent = Number(cs.totalEvicted).toLocaleString();
  if (meterEl) meterEl.style.width = Math.min(100, Math.max(0, cs.hitRate)) + "%";

  if (statusBadge) {
    if (cs.enabled) {
      statusBadge.innerHTML = '<span class="dot green"></span> ENGINE ACTIVE &bull; SUB-MS RAM CACHE';
      statusBadge.className = "badge green";
    } else {
      statusBadge.innerHTML = '<span class="dot red"></span> ENGINE BYPASSED';
      statusBadge.className = "badge red";
    }
  }
}

function renderCacheTab() {
  var cs = (summary && summary.cacheStats) ? summary.cacheStats : {
    enabled: true,
    singleFlightEnabled: true,
    hits: 0,
    misses: 0,
    requestsSaved: 0,
    hitRate: 0.0,
    activeRamEntries: 0,
    totalEvicted: 0,
    defaultTtlMinutes: 360,
    maxRamEntries: 10000
  };

  var cfg = cacheConfigData || {
    enabled: cs.enabled,
    singleFlightEnabled: cs.singleFlightEnabled,
    defaultTtlMinutes: cs.defaultTtlMinutes,
    signedSafetyBufferSeconds: 60,
    minCacheableTtlMinutes: 5,
    maxRamEntries: cs.maxRamEntries,
    diskPersistenceEnabled: true,
    providerOverrides: {}
  };

  var html = '';

  // 1. Hero / Header Card
  html += '<div class="card" style="margin-bottom:16px;">';
  html += '<div class="row" style="align-items:flex-start; justify-content:space-between; flex-wrap:wrap; gap:12px; margin-bottom:12px;">';
  html += '<div>';
  html += '<div style="display:flex; align-items:center; gap:10px; margin-bottom:4px;">';
  html += '<h2 style="margin:0;">⚡ Stream Cache &amp; Deduplication Engine</h2>';
  html += '<span id="cache-status-badge" class="badge ' + (cs.enabled ? 'green' : 'red') + '">';
  html += '<span class="dot ' + (cs.enabled ? 'green' : 'red') + '"></span> ' + (cs.enabled ? 'ENGINE ACTIVE &bull; SUB-MS RAM CACHE' : 'ENGINE BYPASSED');
  html += '</span>';
  html += '</div>';
  html += '<p style="color:var(--muted); font-size:13px; margin:0; max-width:750px;">High-concurrency L1 memory cache and single-flight request coalescing. Serves millions of concurrent requests in under 1ms, eliminates upstream rate-limiting bans, and automatically inspects signed link expiration timestamps.</p>';
  html += '</div>';

  html += '<div style="display:flex; align-items:center; gap:8px; flex-wrap:wrap;">';
  html += '<button class="primary small" onclick="loadCacheConfig(); toast(\'Refreshed telemetry\');">&#x21bb; Refresh</button>';
  html += '<button class="pill" onclick="purgeExpiredCache()">⚡ Purge Expired Only</button>';
  html += '<button class="danger small" onclick="flushAllCache()">🗑 Flush Entire Cache</button>';
  html += '</div>';
  html += '</div>';

  // 2. Real-time Telemetry Metrics Grid
  html += '<div class="grid cols4" style="margin-top:16px; gap:12px;">';

  // Metric 1: Cache Hit Rate
  html += '<div style="background:var(--card2); border:1px solid var(--border); border-radius:12px; padding:14px;">';
  html += '<div style="font-size:11px; font-weight:800; text-transform:uppercase; letter-spacing:0.7px; color:var(--muted); margin-bottom:6px;">Hit Rate %</div>';
  html += '<div id="cache-metric-hitrate" style="font-size:32px; font-weight:800; color:var(--green); letter-spacing:-1px;">' + cs.hitRate + '%</div>';
  html += '<div style="height:6px; background:var(--surface); border-radius:3px; overflow:hidden; margin-top:8px;">';
  html += '<div id="cache-meter-bar" style="height:100%; width:' + Math.min(100, Math.max(0, cs.hitRate)) + '%; background:var(--green); transition:width 0.3s;"></div>';
  html += '</div>';
  html += '<div style="font-size:11px; color:var(--muted); margin-top:6px;">Instant RAM retrievals (&lt; 1ms)</div>';
  html += '</div>';

  // Metric 2: Hits vs Misses
  html += '<div style="background:var(--card2); border:1px solid var(--border); border-radius:12px; padding:14px;">';
  html += '<div style="font-size:11px; font-weight:800; text-transform:uppercase; letter-spacing:0.7px; color:var(--muted); margin-bottom:6px;">Cache Hits / Misses</div>';
  html += '<div style="display:flex; align-items:baseline; gap:8px;">';
  html += '<span id="cache-metric-hits" style="font-size:26px; font-weight:800; color:var(--text); letter-spacing:-0.5px;">' + Number(cs.hits).toLocaleString() + '</span>';
  html += '<span style="color:var(--muted); font-size:12px;">hits / </span>';
  html += '<span id="cache-metric-misses" style="font-size:14px; font-weight:700; color:var(--muted);">' + Number(cs.misses).toLocaleString() + ' misses</span>';
  html += '</div>';
  html += '<div style="margin-top:8px; font-size:11px; color:var(--accent); font-weight:700;">+<span id="cache-metric-saved">' + Number(cs.requestsSaved).toLocaleString() + '</span> duplicate requests saved</div>';
  html += '</div>';

  // Metric 3: Active Streams in RAM
  html += '<div style="background:var(--card2); border:1px solid var(--border); border-radius:12px; padding:14px;">';
  html += '<div style="font-size:11px; font-weight:800; text-transform:uppercase; letter-spacing:0.7px; color:var(--muted); margin-bottom:6px;">Active Hot Streams in RAM</div>';
  html += '<div id="cache-metric-ram" style="font-size:26px; font-weight:800; color:var(--blue); letter-spacing:-0.5px;">' + Number(cs.activeRamEntries).toLocaleString() + ' <span style="font-size:14px; color:var(--muted); font-weight:600;">/ ' + Number(cs.maxRamEntries).toLocaleString() + '</span></div>';
  html += '<div style="font-size:11px; color:var(--muted); margin-top:8px;">L1 Bounded LRU in-memory store</div>';
  html += '</div>';

  // Metric 4: Auto-Evicted & Disk
  html += '<div style="background:var(--card2); border:1px solid var(--border); border-radius:12px; padding:14px;">';
  html += '<div style="font-size:11px; font-weight:800; text-transform:uppercase; letter-spacing:0.7px; color:var(--muted); margin-bottom:6px;">Cleaned / Evicted</div>';
  html += '<div id="cache-metric-evicted" style="font-size:26px; font-weight:800; color:var(--text2); letter-spacing:-0.5px;">' + Number(cs.totalEvicted).toLocaleString() + '</div>';
  html += '<div style="font-size:11px; color:var(--muted); margin-top:8px;">Expired links purged automatically</div>';
  html += '</div>';

  html += '</div>'; // close metrics grid
  html += '</div>'; // close hero card

  // 3. Middle Section: Global Settings (Left) & URL Inspector (Right)
  html += '<div class="grid cols2" style="margin-bottom:16px; gap:16px;">';

  // Left: Global Configuration Card
  html += '<div class="card">';
  html += '<div style="display:flex; align-items:center; gap:8px; margin-bottom:14px;">';
  html += '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z"/></svg>';
  html += '<h3 style="margin:0;">Global Engine Settings</h3>';
  html += '</div>';

  // Toggle: Master cache
  html += '<label style="display:flex; align-items:flex-start; gap:10px; margin-bottom:14px; cursor:pointer;">';
  html += '<input type="checkbox" id="cache-cfg-enabled" ' + (cfg.enabled ? 'checked' : '') + ' style="margin-top:3px;">';
  html += '<div>';
  html += '<div style="font-weight:700; color:var(--text); font-size:13.5px;">Master Stream Caching (RAM L1 + Disk L2)</div>';
  html += '<div style="font-size:11.5px; color:var(--muted);">Intercepts stream lookups and returns cached links in sub-milliseconds without triggering upstream network calls.</div>';
  html += '</div>';
  html += '</label>';

  // Toggle: Single flight
  html += '<label style="display:flex; align-items:flex-start; gap:10px; margin-bottom:14px; cursor:pointer;">';
  html += '<input type="checkbox" id="cache-cfg-singleflight" ' + (cfg.singleFlightEnabled ? 'checked' : '') + ' style="margin-top:3px;">';
  html += '<div>';
  html += '<div style="font-weight:700; color:var(--text); font-size:13.5px;">Single-Flight Request Deduplication</div>';
  html += '<div style="font-size:11.5px; color:var(--muted);">Coalesces concurrent user requests for the same media into 1 scrape job. Completely prevents upstream bans and thundering herd spikes.</div>';
  html += '</div>';
  html += '</label>';

  // Toggle: Disk Persistence
  html += '<label style="display:flex; align-items:flex-start; gap:10px; margin-bottom:16px; cursor:pointer;">';
  html += '<input type="checkbox" id="cache-cfg-disk" ' + (cfg.diskPersistenceEnabled ? 'checked' : '') + ' style="margin-top:3px;">';
  html += '<div>';
  html += '<div style="font-weight:700; color:var(--text); font-size:13.5px;">Crash &amp; Reboot Disk Persistence</div>';
  html += '<div style="font-size:11.5px; color:var(--muted);">Persists warm unexpired cache to disk every 30s. Automatically restored on server start.</div>';
  html += '</div>';
  html += '</label>';

  // TTL & Margins
  html += '<div class="row" style="gap:12px; margin-bottom:12px; flex-wrap:wrap;">';
  html += '<div style="flex:1; min-width:140px;">';
  html += '<label style="display:block; font-size:11px; font-weight:700; color:var(--muted); text-transform:uppercase; margin-bottom:4px;">Default Static TTL</label>';
  html += '<select id="cache-cfg-default-ttl" style="width:100%; padding:6px 8px; border-radius:8px; background:var(--card2); border:1px solid var(--border); color:var(--text); font-size:12.5px;">';
  var ttlOptions = [
    { val: 60, lbl: "1 Hour" },
    { val: 120, lbl: "2 Hours" },
    { val: 240, lbl: "4 Hours" },
    { val: 360, lbl: "6 Hours (Recommended)" },
    { val: 720, lbl: "12 Hours" },
    { val: 1440, lbl: "24 Hours" }
  ];
  ttlOptions.forEach(function(o) {
    html += '<option value="' + o.val + '" ' + (cfg.defaultTtlMinutes === o.val ? 'selected' : '') + '>' + o.lbl + '</option>';
  });
  html += '</select>';
  html += '</div>';

  html += '<div style="flex:1; min-width:140px;">';
  html += '<label style="display:block; font-size:11px; font-weight:700; color:var(--muted); text-transform:uppercase; margin-bottom:4px;">Signed Safety Buffer</label>';
  html += '<div style="display:flex; align-items:center; gap:6px;">';
  html += '<input type="number" id="cache-cfg-margin" value="' + (cfg.signedSafetyBufferSeconds || 60) + '" min="10" max="600" style="width:70px; padding:6px 8px; border-radius:8px; background:var(--card2); border:1px solid var(--border); color:var(--text); font-size:12.5px;">';
  html += '<span style="font-size:12px; color:var(--muted);">sec</span>';
  html += '</div>';
  html += '</div>';

  html += '<div style="flex:1; min-width:140px;">';
  html += '<label style="display:block; font-size:11px; font-weight:700; color:var(--muted); text-transform:uppercase; margin-bottom:4px;">Max RAM Capacity</label>';
  html += '<select id="cache-cfg-max-ram" style="width:100%; padding:6px 8px; border-radius:8px; background:var(--card2); border:1px solid var(--border); color:var(--text); font-size:12.5px;">';
  var ramOptions = [2500, 5000, 10000, 25000, 50000];
  ramOptions.forEach(function(r) {
    html += '<option value="' + r + '" ' + (cfg.maxRamEntries === r ? 'selected' : '') + '>' + Number(r).toLocaleString() + ' Streams</option>';
  });
  html += '</select>';
  html += '</div>';
  html += '</div>';

  html += '<button class="primary" style="margin-top:10px;" onclick="saveCacheConfig()">💾 Save Cache Configuration</button>';
  html += '</div>'; // close global config card

  // Right: Live Stream Link Classifier & URL Inspector
  html += '<div class="card">';
  html += '<div style="display:flex; align-items:center; gap:8px; margin-bottom:8px;">';
  html += '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg>';
  html += '<h3 style="margin:0;">Live Link Inspector &amp; Classifier</h3>';
  html += '</div>';
  html += '<p style="color:var(--muted); font-size:12.5px; margin:0 0 12px 0;">Paste any stream URL to test the automated classifier. It checks host patterns, detects signed expiration tokens (expires=, exp=, token=, etc.), and computes remaining safe TTL in real time.</p>';

  html += '<div style="display:flex; gap:8px; margin-bottom:12px;">';
  html += '<input type="text" id="cache-inspect-input" placeholder="Paste stream URL (e.g. https://...)" style="flex:1; padding:7px 10px; border-radius:8px; background:var(--card2); border:1px solid var(--border); color:var(--text); font-size:12.5px;" onkeydown="if(event.key===\'Enter\')inspectStreamUrl()">';
  html += '<button class="primary small" id="cache-inspect-btn" onclick="inspectStreamUrl()">Inspect</button>';
  html += '</div>';

  html += '<div id="cache-inspect-result" style="background:var(--card2); border:1px dashed var(--border); border-radius:10px; padding:12px; min-height:130px; font-size:12.5px; color:var(--muted); display:flex; align-items:center; justify-content:center; text-align:center;">';
  html += '<span>Enter a streaming URL above and click <b>Inspect</b> to see live dynamic classification results.</span>';
  html += '</div>';

  html += '</div>'; // close inspector card
  html += '</div>'; // close grid cols2

  // 4. Per-Provider Cache Rules Table
  html += '<div class="card">';
  html += '<div style="display:flex; align-items:center; justify-content:space-between; flex-wrap:wrap; gap:10px; margin-bottom:12px;">';
  html += '<div>';
  html += '<h3 style="margin:0 0 4px 0;">Per-Provider Cache Override Rules</h3>';
  html += '<p style="color:var(--muted); font-size:12.5px; margin:0;">Configure custom TTL overrides or selectively bypass cache for specific scrapers/sources.</p>';
  html += '</div>';
  html += '<button class="pill" onclick="saveCacheConfig()">Apply Provider Rules</button>';
  html += '</div>';

  var pluginsList = (summary && summary.installedPlugins) ? summary.installedPlugins : [];
  if (pluginsList.length === 0) {
    html += '<div style="text-align:center; padding:20px; color:var(--muted); font-style:italic;">No extensions currently installed. Install extensions from the Extensions tab to configure per-provider rules.</div>';
  } else {
    html += '<div style="overflow-x:auto;">';
    html += '<table style="width:100%; border-collapse:collapse; font-size:13px; text-align:left;">';
    html += '<thead>';
    html += '<tr style="border-bottom:1px solid var(--border); color:var(--muted); font-size:11px; text-transform:uppercase; letter-spacing:0.6px;">';
    html += '<th style="padding:8px 10px;">Extension</th>';
    html += '<th style="padding:8px 10px;">Internal Key</th>';
    html += '<th style="padding:8px 10px;">Cache Mode</th>';
    html += '<th style="padding:8px 10px;">Custom TTL Override</th>';
    html += '</tr>';
    html += '</thead>';
    html += '<tbody>';

    var overrides = cfg.providerOverrides || {};
    pluginsList.forEach(function(p) {
      var ov = overrides[p.internalName] || { enabled: true, customTtlMinutes: null };
      var isCacheOn = (ov.enabled !== false);
      var customTtl = ov.customTtlMinutes;

      html += '<tr style="border-bottom:1px solid var(--divider);">';
      html += '<td style="padding:10px; font-weight:700; color:var(--text);">';
      html += '<div style="display:flex; align-items:center; gap:8px;">';
      if (p.iconUrl) {
        html += '<img src="' + esc(p.iconUrl) + '" style="width:20px; height:20px; border-radius:4px; object-fit:contain;" onerror="this.style.display=\'none\'">';
      }
      html += '<span>' + esc(p.displayName || p.internalName) + '</span>';
      html += '</div>';
      html += '</td>';

      html += '<td style="padding:10px; color:var(--muted); font-family:monospace; font-size:11.5px;">' + esc(p.internalName) + '</td>';

      html += '<td style="padding:10px;">';
      html += '<label style="display:flex; align-items:center; gap:6px; cursor:pointer;">';
      html += '<input type="checkbox" id="cache-p-on-' + esc(p.internalName) + '" ' + (isCacheOn ? 'checked' : '') + ' onchange="onProviderCacheToggle(\'' + jsa(p.internalName) + '\', this.checked)">';
      html += '<span style="font-weight:600; font-size:12px; color:' + (isCacheOn ? 'var(--green)' : 'var(--red)') + ';">' + (isCacheOn ? 'Cached' : 'Bypass') + '</span>';
      html += '</label>';
      html += '</td>';

      html += '<td style="padding:10px;">';
      html += '<select id="cache-p-ttl-' + esc(p.internalName) + '" style="padding:4px 8px; border-radius:6px; background:var(--card2); border:1px solid var(--border); color:var(--text); font-size:12px;" onchange="onProviderTtlChange(\'' + jsa(p.internalName) + '\', this.value)">';
      html += '<option value="" ' + (customTtl === null || customTtl === undefined ? 'selected' : '') + '>Default Engine TTL (' + cfg.defaultTtlMinutes + 'm)</option>';
      html += '<option value="15" ' + (customTtl === 15 ? 'selected' : '') + '>15 Minutes (Short)</option>';
      html += '<option value="30" ' + (customTtl === 30 ? 'selected' : '') + '>30 Minutes</option>';
      html += '<option value="45" ' + (customTtl === 45 ? 'selected' : '') + '>45 Minutes (Tokenized)</option>';
      html += '<option value="60" ' + (customTtl === 60 ? 'selected' : '') + '>1 Hour</option>';
      html += '<option value="120" ' + (customTtl === 120 ? 'selected' : '') + '>2 Hours</option>';
      html += '<option value="360" ' + (customTtl === 360 ? 'selected' : '') + '>6 Hours</option>';
      html += '<option value="720" ' + (customTtl === 720 ? 'selected' : '') + '>12 Hours (Static)</option>';
      html += '<option value="1440" ' + (customTtl === 1440 ? 'selected' : '') + '>24 Hours (Long)</option>';
      html += '</select>';
      html += '</td>';

      html += '</tr>';
    });

    html += '</tbody>';
    html += '</table>';
    html += '</div>';
  }

  html += '</div>'; // close provider rules card

  el("view").innerHTML = html;
}

function onProviderCacheToggle(internalName, isEnabled) {
  if (!cacheConfigData) cacheConfigData = {};
  if (!cacheConfigData.providerOverrides) cacheConfigData.providerOverrides = {};
  if (!cacheConfigData.providerOverrides[internalName]) {
    cacheConfigData.providerOverrides[internalName] = { enabled: isEnabled, customTtlMinutes: null };
  } else {
    cacheConfigData.providerOverrides[internalName].enabled = isEnabled;
  }
}

function onProviderTtlChange(internalName, ttlStr) {
  if (!cacheConfigData) cacheConfigData = {};
  if (!cacheConfigData.providerOverrides) cacheConfigData.providerOverrides = {};
  var ttl = ttlStr ? parseInt(ttlStr, 10) : null;
  if (!cacheConfigData.providerOverrides[internalName]) {
    cacheConfigData.providerOverrides[internalName] = { enabled: true, customTtlMinutes: ttl };
  } else {
    cacheConfigData.providerOverrides[internalName].customTtlMinutes = ttl;
  }
}

function saveCacheConfig() {
  if (!cacheConfigData) cacheConfigData = {};
  var enEl = el("cache-cfg-enabled");
  var sfEl = el("cache-cfg-singleflight");
  var dkEl = el("cache-cfg-disk");
  var ttlEl = el("cache-cfg-default-ttl");
  var mgEl = el("cache-cfg-margin");
  var ramEl = el("cache-cfg-max-ram");

  if (enEl) cacheConfigData.enabled = enEl.checked;
  if (sfEl) cacheConfigData.singleFlightEnabled = sfEl.checked;
  if (dkEl) cacheConfigData.diskPersistenceEnabled = dkEl.checked;
  if (ttlEl) cacheConfigData.defaultTtlMinutes = parseInt(ttlEl.value, 10) || 360;
  if (mgEl) cacheConfigData.signedSafetyBufferSeconds = parseInt(mgEl.value, 10) || 60;
  if (ramEl) cacheConfigData.maxRamEntries = parseInt(ramEl.value, 10) || 10000;

  api("/cache/config", {
    method: "POST",
    body: JSON.stringify(cacheConfigData)
  }).then(function() {
    toast("✓ Cache configuration saved");
    loadCacheConfig();
  }).catch(function(e) {
    toast("Failed to save configuration: " + e.message);
  });
}

function purgeExpiredCache() {
  api("/cache/purge-expired", { method: "POST" }).then(function(res) {
    toast(res.message || "Expired entries purged");
    poll();
  }).catch(function(e) {
    toast("Purge failed: " + e.message);
  });
}

function flushAllCache() {
  if (!confirm("Are you sure you want to flush all stream caches in RAM and on disk?")) return;
  api("/cache/clear", { method: "POST" }).then(function(res) {
    toast("All stream caches flushed");
    poll();
  }).catch(function(e) {
    toast("Flush failed: " + e.message);
  });
}

function inspectStreamUrl() {
  var input = el("cache-inspect-input");
  var url = input ? input.value.trim() : "";
  if (!url) { toast("Please enter a URL to inspect"); return; }
  var btn = el("cache-inspect-btn");
  if (btn) btn.disabled = true;

  api("/cache/inspect?url=" + encodeURIComponent(url)).then(function(res) {
    if (btn) btn.disabled = false;
    renderInspectResult(res);
  }).catch(function(e) {
    if (btn) btn.disabled = false;
    toast("Inspection failed: " + e.message);
  });
}

function renderInspectResult(res) {
  var cont = el("cache-inspect-result");
  if (!cont) return;

  var isCacheable = res.isCacheable;
  var safeTtlMin = Math.round(res.computedTtlMs / 60000);
  var html = '<div style="text-align:left; width:100%;">';

  html += '<div style="display:flex; align-items:center; justify-content:space-between; margin-bottom:8px; flex-wrap:wrap; gap:6px;">';
  html += '<div style="font-weight:800; font-size:14px; color:var(--text);">' + esc(res.host || 'Unknown Host') + '</div>';
  html += '<span class="badge ' + (isCacheable ? 'green' : 'red') + '">';
  html += '<span class="dot ' + (isCacheable ? 'green' : 'red') + '"></span> ' + (isCacheable ? 'CACHEABLE' : 'UNCACHEABLE / EPHEMERAL');
  html += '</span>';
  html += '</div>';

  html += '<div class="grid cols2" style="gap:8px; margin-bottom:8px; font-size:12px;">';
  html += '<div><span style="color:var(--muted);">Category:</span> <b style="color:var(--text);">' + esc(res.category) + '</b></div>';
  html += '<div><span style="color:var(--muted);">Signed Parameters:</span> <b style="color:' + (res.isSigned ? 'var(--amber)' : 'var(--text2)') + ';">' + (res.isSigned ? 'Detected' : 'None') + '</b></div>';
  if (res.detectedExpiryTime) {
    var expDate = new Date(res.detectedExpiryTime);
    var remainingMin = Math.round((res.remainingValidityMs || 0) / 60000);
    html += '<div><span style="color:var(--muted);">Token Expiration:</span> <b style="color:var(--text);">' + expDate.toLocaleTimeString() + ' (' + remainingMin + 'm left)</b></div>';
  }
  html += '<div><span style="color:var(--muted);">Safe Computed TTL:</span> <b style="color:var(--accent);">' + safeTtlMin + ' minutes</b></div>';
  html += '</div>';

  html += '<div style="background:var(--surface); border-radius:6px; padding:6px 10px; font-size:11.5px; color:var(--text2);">';
  html += '<span style="color:var(--muted); font-weight:700;">Reason:</span> ' + esc(res.reason);
  html += '</div>';

  html += '</div>';
  cont.innerHTML = html;
}

// ── Stream Health Tab ────────────────────────────────────────────────────────
var streamHealthData = [];
var streamHealthFilter = "all";
var streamHealthSearch = "";
var probingAll = false;          // true while benchmark-all is running
var probingIds = {};             // internalName -> true while that single probe runs
var probingDoneCount = 0;
var probingTotalCount = 0;

function loadStreamHealth() {
  api("/stream-health").then(function(data) {
    streamHealthData = data || [];
    if (tab === "health") renderStreamHealth();
  }).catch(function(e) {
    if (tab === "health") toast("Failed to load stream health: " + e.message);
  });
}

function disableDeadPlugins() {
  var deadCount = streamHealthData.filter(function(p) {
    return p.totalRequests > 0 && p.successRequests === 0;
  }).length;
  if (!deadCount) { toast("No dead plugins found."); return; }
  if (!confirm("Disable all " + deadCount + " dead plugin(s) that returned 0 streams?")) return;
  api("/stream-health/disable-dead", { method: "POST", body: "{}" })
    .then(function(res) {
      toast(res.message || "Disabled dead plugins");
      loadStreamHealth();
      poll();
    })
    .catch(function(e) { toast("Error: " + e.message); });
}

function uninstallAllDead() {
  var dead = streamHealthData.filter(function(p) {
    return p.totalRequests > 0 && p.successRequests === 0;
  });
  if (!dead.length) { toast("No dead plugins to uninstall."); return; }
  var deadIds = dead.map(function(p) { return p.internalName; });
  toast("Uninstalling " + dead.length + " dead plugin(s)…");
  api("/plugins/uninstall-batch", { method: "POST", body: JSON.stringify({ internalNames: deadIds }) })
    .then(function(res) {
      toast(res.message || ("✓ Uninstalled " + dead.length + " dead plugin(s)"));
      loadStreamHealth();
      poll();
    })
    .catch(function(e) { toast("Uninstall error: " + e.message); });
}

// ── Benchmark modal ───────────────────────────────────────────────────────────
function openBenchmarkModal() {
  var inp = document.getElementById("benchmark-query-input");
  if (inp) inp.value = "Avatar";
  document.getElementById("benchmark-modal").classList.add("open");
  setTimeout(function() { var i = document.getElementById("benchmark-query-input"); if (i) i.focus(); }, 80);
}
function closeBenchmarkModal() {
  document.getElementById("benchmark-modal").classList.remove("open");
}
function submitBenchmark() {
  var inp = document.getElementById("benchmark-query-input");
  var q = (inp ? inp.value : "").trim() || "Avatar";
  var btn = document.getElementById("benchmark-submit-btn");
  if (btn) { btn.disabled = true; btn.textContent = "Running…"; }
  closeBenchmarkModal();
  probingAll = true;
  probingIds = {};
  probingTotalCount = streamHealthData.filter(function(p) { return p.enabled; }).length || streamHealthData.length;
  if (tab === "extensions") renderExtensions();
  api("/stream-health/probe?query=" + encodeURIComponent(q), { method: "POST", body: "{}" })
    .then(function(res) { toast(res.message || "Benchmark complete ✓"); })
    .catch(function(e) { toast("Probe error: " + e.message); })
    .finally(function() {
      probingAll = false;
      probingIds = {};
      if (btn) { btn.disabled = false; btn.textContent = "Run Benchmark"; }
      loadStreamHealth();
      poll();
    });
}

// Alias for button references that still call old name
function probeAllSources() { openBenchmarkModal(); }

// ── Single probe modal ────────────────────────────────────────────────────────
var _pendingProbeId = null;
function openProbeModal(internalName, displayName) {
  _pendingProbeId = internalName;
  var nameEl = document.getElementById("probe-plugin-name");
  if (nameEl) nameEl.textContent = displayName || internalName;
  var inp = document.getElementById("probe-query-input");
  if (inp) inp.value = "Avatar";
  document.getElementById("probe-modal").classList.add("open");
  setTimeout(function() { var i = document.getElementById("probe-query-input"); if (i) { i.focus(); i.select(); } }, 80);
}
function closeProbeModal() {
  document.getElementById("probe-modal").classList.remove("open");
}
function submitProbe() {
  var id = _pendingProbeId;
  if (!id) return;
  var inp = document.getElementById("probe-query-input");
  var q = (inp ? inp.value : "").trim() || "Avatar";
  var btn = document.getElementById("probe-submit-btn");
  if (btn) { btn.disabled = true; btn.textContent = "Testing…"; }
  closeProbeModal();
  probingIds[id] = true;
  if (tab === "extensions") renderExtensions();
  api("/stream-health/probe?internalName=" + encodeURIComponent(id) + "&query=" + encodeURIComponent(q), { method: "POST", body: "{}" })
    .then(function(res) { toast(res.message || "Probe completed ✓"); })
    .catch(function(e) { toast("Probe error: " + e.message); })
    .finally(function() {
      delete probingIds[id];
      if (btn) { btn.disabled = false; btn.textContent = "Test Now"; }
      loadStreamHealth();
      poll();
    });
}

// Alias for old callers
function probeSingleSource(internalName, displayName) {
  openProbeModal(internalName, displayName || internalName);
}

function clearStreamStats() {
  toast("Resetting stream stats…");
  api("/stream-health/clear", { method: "POST", body: "{}" })
    .then(function() { toast("Stream stats reset ✓"); loadStreamHealth(); })
    .catch(function(e) { toast("Error: " + e.message); });
}

function togglePluginHealth(internalName) {
  api("/plugins/toggle", { method: "POST", body: JSON.stringify({ internalName: internalName }) })
    .then(function() {
      toast("Plugin status updated");
      loadStreamHealth();
      poll();
    })
    .catch(function(e) { toast("Error: " + e.message); });
}

function renderStreamHealth() {
  var html = "";

  var totalInstalled = streamHealthData.length;
  var streamingOk = 0, deadCount = 0, untestedCount = 0, disabledCount = 0;
  streamHealthData.forEach(function(p) {
    if (!p.enabled) disabledCount++;
    if (p.successRequests > 0) streamingOk++;
    else if (p.totalRequests > 0 && p.successRequests === 0) deadCount++;
    else untestedCount++;
  });

  // ── Live probing banner ──────────────────────────────────────────────────
  if (probingAll) {
    html += '<div style="display:flex;align-items:center;gap:10px;padding:12px 16px;background:rgba(99,102,241,0.12);border:1.5px solid var(--accent);border-radius:10px;margin-bottom:14px;">';
    html += '<span style="width:10px;height:10px;border-radius:50%;background:var(--accent);display:inline-block;animation:pulse 1s ease-in-out infinite;"></span>';
    html += '<span style="font-weight:700;color:var(--accent);">Benchmarking all sources…</span>';
    html += '<span style="color:var(--text-dim);font-size:12px;flex:1;">Testing each plugin against your query. Results will appear when done.</span>';
    html += '<button class="ghost small" onclick="probingAll=false;renderStreamHealth();">Cancel View</button>';
    html += '</div>';
  }

  // ── Top stat cards ───────────────────────────────────────────────────────
  html += '<div class="stat-cards-grid">';
  html += '<div class="stat-card"><div class="stat-label">Installed</div><div class="stat-val">' + totalInstalled + '</div><div class="stat-sub">Tracked by Bridge</div></div>';
  html += '<div class="stat-card"><div class="stat-label">Streaming OK</div><div class="stat-val green">' + streamingOk + '</div><div class="stat-sub">Returning links</div></div>';
  html += '<div class="stat-card" style="cursor:pointer" onclick="setStreamHealthFilter(\'dead\')">' +
    '<div class="stat-label">Dead / 0 Streams</div>' +
    '<div class="stat-val' + (deadCount > 0 ? ' red' : '') + '">' + deadCount + '</div>' +
    '<div class="stat-sub">' + (deadCount > 0 ? 'Click to filter' : 'All clear') + '</div></div>';
  html += '<div class="stat-card"><div class="stat-label">Untested</div><div class="stat-val">' + untestedCount + '</div><div class="stat-sub">Run Benchmark to test</div></div>';
  html += '</div>';

  // ── Main card ────────────────────────────────────────────────────────────
  html += '<div class="card" style="margin-top:14px">';
  html += '<div class="row" style="align-items:flex-start;justify-content:space-between;margin-bottom:12px;flex-wrap:wrap;gap:10px;">';
  html += '<div><h2><svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M22 12h-4l-3 9L9 3l-3 9H2"/></svg> Plugin Stream Health Tracker</h2>';
  html += '<div class="hint" style="margin-bottom:0">Live health of every installed plugin. Benchmark to check which ones actually return streams.</div></div>';

  // ── Action buttons ───────────────────────────────────────────────────────
  html += '<div class="row" style="gap:8px;flex-wrap:wrap;">';
  html += '<button class="primary small" onclick="probeAllSources()" ' + (probingAll ? 'disabled' : '') + '>';
  html += '<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><polygon points="5 3 19 12 5 21 5 3"/></svg>';
  html += (probingAll ? ' Benchmarking…' : ' Benchmark All') + '</button>';

  if (deadCount > 0) {
    html += '<button class="danger small" onclick="disableDeadPlugins()" title="Disable all dead plugins">';
    html += '<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/><line x1="4.93" y1="4.93" x2="19.07" y2="19.07"/></svg>';
    html += ' Disable ' + deadCount + ' Dead</button>';

    html += '<button class="danger small" onclick="uninstallAllDead()" title="Permanently remove all dead plugins">';
    html += svgTrash + ' Uninstall All Dead</button>';
  }
  html += '<button class="ghost small" onclick="clearStreamStats()">Reset Stats</button>';
  html += '<button class="ghost small" onclick="loadStreamHealth()">' + svgRefresh + ' Refresh</button>';
  html += '</div>';
  html += '</div>'; // close row

  // ── Filter pills ─────────────────────────────────────────────────────────
  html += '<div class="pillrow">';
  html += '<span class="pill' + (streamHealthFilter === "all" ? " active" : "") + '" onclick="setStreamHealthFilter(\'all\')">All (' + totalInstalled + ')</span>';
  html += '<span class="pill' + (streamHealthFilter === "ok" ? " active" : "") + '" onclick="setStreamHealthFilter(\'ok\')">✓ Streaming OK (' + streamingOk + ')</span>';
  html += '<span class="pill' + (streamHealthFilter === "dead" ? " active" : "") + '" onclick="setStreamHealthFilter(\'dead\')">✗ Dead (' + deadCount + ')</span>';
  html += '<span class="pill' + (streamHealthFilter === "disabled" ? " active" : "") + '" onclick="setStreamHealthFilter(\'disabled\')">Disabled (' + disabledCount + ')</span>';
  html += '<span class="pill' + (streamHealthFilter === "untested" ? " active" : "") + '" onclick="setStreamHealthFilter(\'untested\')">Untested (' + untestedCount + ')</span>';
  html += '</div>';

  // ── Search ───────────────────────────────────────────────────────────────
  html += '<div style="position:relative;margin-bottom:14px;">';
  html += '<input type="text" id="health-search" placeholder="Search plugins by name or error…" value="' + esc(streamHealthSearch) + '" oninput="setStreamHealthSearch(this.value)" style="padding-right:32px">';
  if (streamHealthSearch) {
    html += '<button onclick="setStreamHealthSearch(\'\')" style="position:absolute;right:8px;top:50%;transform:translateY(-50%);background:none;border:none;color:var(--muted);padding:4px;cursor:pointer;font-size:14px;line-height:1;" title="Clear">&times;</button>';
  }
  html += '</div>';

  // ── Plugin list ──────────────────────────────────────────────────────────
  var filtered = streamHealthData.filter(function(p) {
    if (streamHealthFilter === "ok" && p.successRequests <= 0) return false;
    if (streamHealthFilter === "dead" && (p.totalRequests === 0 || p.successRequests > 0)) return false;
    if (streamHealthFilter === "disabled" && p.enabled) return false;
    if (streamHealthFilter === "untested" && p.totalRequests > 0) return false;
    if (streamHealthSearch) {
      var q = streamHealthSearch.toLowerCase();
      if ((p.pluginName || "").toLowerCase().indexOf(q) < 0 &&
          (p.internalName || "").toLowerCase().indexOf(q) < 0 &&
          (p.lastError || "").toLowerCase().indexOf(q) < 0) return false;
    }
    return true;
  });

  if (!filtered.length) {
    html += '<div class="empty">No plugins match your filter.</div>';
  } else {
    html += '<div class="health-grid">';
    filtered.forEach(function(p) {
      var isProbing = probingAll || !!probingIds[p.internalName];
      var isDead = p.totalRequests > 0 && p.successRequests === 0;

      // Status badge
      var statusBadge;
      if (isProbing) {
        statusBadge = '<span class="badge" style="background:var(--accent-light);color:var(--accent);border:1px solid var(--accent);animation:pulse 1s ease-in-out infinite;">&#9899; Testing…</span>';
      } else if (!p.enabled) {
        statusBadge = '<span class="badge gray">Disabled</span>';
      } else if (p.successRequests > 0) {
        statusBadge = '<span class="badge green">&#9679; OK &mdash; ' + p.lastStreamCount + ' links</span>';
      } else if (isDead) {
        statusBadge = '<span class="badge red">&#10005; Dead &mdash; 0 streams</span>';
      } else {
        statusBadge = '<span class="badge gray">&#9675; Untested</span>';
      }

      var ratePct = p.totalRequests > 0 ? Math.round((p.successRequests / p.totalRequests) * 100) : 0;
      var lastStr = p.lastSuccessTime > 0 ? fmtTimeAgo(p.lastSuccessTime) : "never";

      var borderStyle = "";
      if (isProbing) borderStyle = "border-color:var(--accent);";
      else if (isDead && p.enabled) borderStyle = "border-color:rgba(239,68,68,0.4);";
      else if (p.successRequests > 0) borderStyle = "border-color:rgba(16,185,129,0.35);";

      html += '<div class="health-card" style="' + borderStyle + '">';

      // Header: Avatar + Title + Status Badge
      html += '<div style="display:flex;align-items:flex-start;justify-content:space-between;gap:8px;">';
      var iconSrc = p.iconUrl;
      if (!iconSrc && Array.isArray(plugins)) {
        var foundPlg = plugins.find(function(x) { return x.internalName === p.internalName; });
        if (foundPlg && foundPlg.iconUrl) iconSrc = foundPlg.iconUrl;
      }

      html += '<div style="display:flex;align-items:center;gap:8px;min-width:0;">';
      if (iconSrc) {
        html += '<img class="ricon" src="' + esc(iconSrc) + '" onerror="this.style.display=\'none\';this.nextElementSibling.style.display=\'flex\'" alt="" style="width:34px;height:34px;border-radius:8px;object-fit:cover;flex:0 0 34px;">';
        html += '<div class="rletter" style="display:none;width:34px;height:34px;border-radius:8px;font-size:13px;' + (isDead && p.enabled ? 'background:rgba(239,68,68,0.2);color:var(--red);' : isProbing ? 'background:var(--accent-light);color:var(--accent);' : '') + '">' + esc((p.pluginName || p.internalName || '?').charAt(0).toUpperCase()) + '</div>';
      } else {
        html += '<div class="rletter" style="width:34px;height:34px;border-radius:8px;font-size:13px;' + (isDead && p.enabled ? 'background:rgba(239,68,68,0.2);color:var(--red);' : isProbing ? 'background:var(--accent-light);color:var(--accent);' : '') + '">' + esc((p.pluginName || p.internalName || '?').charAt(0).toUpperCase()) + '</div>';
      }
      html += '<div style="min-width:0;">';
      html += '<div class="rname" style="font-size:13.5px;font-weight:700;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;">' + esc(p.pluginName || p.internalName) + '</div>';
      html += '<div style="font-size:10px;color:var(--muted);white-space:nowrap;overflow:hidden;text-overflow:ellipsis;">' + esc(p.internalName) + '</div>';
      html += '</div>';
      html += '</div>';
      html += statusBadge;
      html += '</div>';

      // Middle: Health stats
      html += '<div style="margin-top:6px;">';
      if (isProbing) {
        html += '<div class="muted" style="font-size:11px;">Probing streams… please wait</div>';
      } else if (p.totalRequests > 0) {
        html += '<div style="display:flex;align-items:center;gap:8px;margin-bottom:4px;">';
        html += '<div style="flex:1;height:4px;background:var(--border);border-radius:2px;overflow:hidden;">';
        html += '<div style="height:100%;border-radius:2px;background:' + (ratePct > 50 ? 'var(--green)' : ratePct > 0 ? 'var(--amber)' : 'var(--red)') + ';width:' + ratePct + '%;"></div>';
        html += '</div>';
        html += '<span style="font-size:11px;font-weight:700;">' + ratePct + '%</span>';
        html += '</div>';
        html += '<div class="muted" style="font-size:10.5px;">' + p.successRequests + '/' + p.totalRequests + ' calls &middot; ' + p.totalStreams + ' streams &middot; ' + lastStr + '</div>';
      } else {
        html += '<div class="muted" style="font-size:11px;">Not yet tested</div>';
      }
      if (p.lastError && !isProbing) {
        html += '<div style="font-size:10.5px;color:var(--red);margin-top:4px;word-break:break-all;background:var(--red-bg);padding:4px 6px;border-radius:6px;">&#x26A0; ' + esc(p.lastError) + '</div>';
      }
      html += '</div>';

      // Footer: Action buttons
      html += '<div style="display:flex;align-items:center;justify-content:space-between;padding-top:8px;border-top:1px solid var(--divider);margin-top:auto;gap:8px;">';
      if (isProbing) {
        html += '<button class="pill small" disabled style="opacity:0.6;min-height:28px;">Testing…</button>';
      } else {
        html += '<button class="pill small" onclick="probeSingleSource(\'' + jsa(p.internalName) + '\',\'' + jsa(p.pluginName||p.internalName) + '\')" style="min-height:28px;" title="Test this source now">Test</button>';
      }
      html += '<div class="row" style="gap:6px;align-items:center;">';
      if (!isProbing && (isDead || !p.enabled)) {
        html += '<button class="iconbtn small danger-btn" onclick="uninstallPlugin(\'' + jsa(p.internalName) + '\',\'' + jsa(p.pluginName||p.internalName) + '\')" title="Uninstall plugin">' + svgTrash + '</button>';
      }
      html += '<label class="switch" title="' + (p.enabled ? "Disable" : "Enable") + ' provider">';
      html += '<input type="checkbox" ' + (p.enabled ? "checked" : "") + ' onchange="togglePluginHealth(\'' + jsa(p.internalName) + '\')">';
      html += '<span class="track"></span>';
      html += '</label>';
      html += '<span style="font-size:10.5px;font-weight:700;' + (p.enabled ? 'color:var(--green)' : 'color:var(--muted)') + '">' + (p.enabled ? 'Active' : 'Off') + '</span>';
      html += '</div>';
      html += '</div>';

      html += '</div>'; // close health-card
    });
    html += '</div>'; // close health-grid
  }

  html += '</div>'; // close main card
  el("view").innerHTML = html;
}

function setStreamHealthFilter(f) { streamHealthFilter = f; renderStreamHealth(); }
function setStreamHealthSearch(q) {
  // The view is rebuilt on each keystroke: put focus and caret back into the search box
  var active = document.activeElement;
  var wasTyping = active && active.id === "health-search";
  var caret = wasTyping ? active.selectionStart : null;
  streamHealthSearch = q;
  renderStreamHealth();
  var box = el("health-search");
  if (box && wasTyping) {
    box.focus();
    var pos = caret === null ? box.value.length : Math.min(caret, box.value.length);
    box.setSelectionRange(pos, pos);
  }
}

function fmtTimeAgo(ts) {
  if (!ts) return "never";
  var diffSec = Math.floor((Date.now() - ts) / 1000);
  if (diffSec < 60) return diffSec + "s ago";
  var diffMin = Math.floor(diffSec / 60);
  if (diffMin < 60) return diffMin + "m ago";
  var diffHour = Math.floor(diffMin / 60);
  if (diffHour < 24) return diffHour + "h ago";
  return Math.floor(diffHour / 24) + "d ago";
}

function loadPlugins() {
  api("/plugins").then(function(list) {
    plugins = list;
    if (tab === "extensions") renderExtensions();
  }).catch(function() {});
}

function loadGoalStats() {
  fetch("/api/community-stats")
    .then(function(r) { if (!r.ok) throw new Error("Proxy error"); return r.json(); })
    .catch(function() { return fetch("https://cncverse.pages.dev/api/stats").then(function(r) { return r.json(); }); })
    .then(function(data) {
      if (!data) return;
      var numRaised = (data.totalUsd !== undefined && data.totalUsd !== null) ? Number(data.totalUsd) :
                      ((data.total_raised !== undefined && data.total_raised !== null) ? Number(data.total_raised) :
                      ((data.totalRaised !== undefined && data.totalRaised !== null) ? Number(data.totalRaised) : 0));
      var numTarget = (data.targetGoalUsd !== undefined && data.targetGoalUsd !== null) ? Number(data.targetGoalUsd) :
                      ((data.monthly_target !== undefined && data.monthly_target !== null) ? Number(data.monthly_target) :
                      ((data.monthlyTarget !== undefined && data.monthlyTarget !== null) ? Number(data.monthlyTarget) : 100));
      var pct = (data.percent !== undefined && data.percent !== null) ? Math.round(Number(data.percent)) :
                (numTarget > 0 ? Math.round((numRaised / numTarget) * 100) : 0);
      var raisedStr = (numRaised % 1 === 0) ? numRaised.toString() : numRaised.toFixed(2);
      var targetStr = (numTarget % 1 === 0) ? numTarget.toString() : numTarget.toFixed(2);
      var dollar = String.fromCharCode(36);
      var pctEl = document.getElementById("goal-pct");
      if (pctEl) pctEl.textContent = pct + "%";
      var textEl = document.getElementById("goal-text");
      if (textEl) textEl.innerHTML = dollar + raisedStr + " raised of " + dollar + targetStr + " goal";
      var fillEl = document.getElementById("goal-fill");
      if (fillEl) fillEl.style.width = Math.min(100, Math.max(0, pct)) + "%";
    })
    .catch(function() {});
}

// ── Boot ──────────────────────────────────────────────────────────────────────
// Drawer buttons already carry onclick="openTab(...); toggleDrawer(false);" —
// no extra listener here, or every tab click would run openTab (and its fetches) twice.

poll();
loadPlugins();
pollLogs();
loadGoalStats();

// Goal stats poll: every 30s
setInterval(loadGoalStats, 30000);

// Main data poll: every 3s
pollTimer = setInterval(poll, 3000);

// Log poll: every 3s when on logs tab; also keeps logsData fresh in background
logTimer = setInterval(function() { pollLogs(); }, 3000);

// Plugin refresh: every 3s when on extensions tab (picks up install state changes)
setInterval(function() { if (tab === "extensions") loadPlugins(); }, 3000);

// 30-min client-side refresh trigger (belt-and-suspenders alongside server-side check)
setTimeout(function() {
  setInterval(function() {
    api("/repos/refresh", {method:"POST", body:"{}"})
      .then(function() { loadPlugins(); })
      .catch(function() {});
  }, 30 * 60 * 1000);
}, 30 * 60 * 1000);
</script>
</body>
</html>"""
}
