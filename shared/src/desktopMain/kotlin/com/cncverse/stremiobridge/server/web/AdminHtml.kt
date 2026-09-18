package com.cncverse.stremiobridge.server.web

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
:root {
  --bg: #090a10;
  --surface: #10121a;
  --surface-active: #171a26;
  --card: #131622;
  --card2: #191d2c;
  --border: #1e2335;
  --border-focus: #333a54;
  --divider: rgba(255, 255, 255, 0.07);
  --accent: #6366f1;
  --accent-hover: #4f46e5;
  --accent-light: rgba(99, 102, 241, 0.14);
  --text: #f1f3f8;
  --text2: #94a0b8;
  --muted: #64708a;
  --green: #10b981;
  --green-bg: rgba(16, 185, 129, 0.12);
  --red: #f43f5e;
  --red-bg: rgba(244, 63, 94, 0.12);
  --amber: #f59e0b;
  --amber-bg: rgba(245, 158, 11, 0.12);
  --blue: #38bdf8;
  --blue-bg: rgba(56, 189, 248, 0.12);
  --shadow: 0 4px 20px rgba(0, 0, 0, 0.35);
}
[data-theme="light"] {
  --bg: #f8fafc;
  --surface: #ffffff;
  --surface-active: #f1f5f9;
  --card: #ffffff;
  --card2: #f8fafc;
  --border: #e2e8f0;
  --border-focus: #cbd5e1;
  --divider: rgba(0, 0, 0, 0.08);
  --accent: #4f46e5;
  --accent-hover: #4338ca;
  --accent-light: rgba(79, 70, 229, 0.1);
  --text: #0f172a;
  --text2: #475569;
  --muted: #94a3b8;
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
  border: 1px solid rgba(99, 102, 241, 0.3);
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
.btn-icon-sq {
  width: 32px; height: 32px; padding: 0;
  border-radius: 8px; background: var(--card);
  border: 1px solid var(--border); color: var(--text2);
  display: inline-flex; align-items: center; justify-content: center;
}
.btn-icon-sq:hover { color: var(--text); border-color: var(--border-focus); background: var(--surface-active); }

@media (max-width: 680px) {
  header { padding: 0 12px; height: 48px; }
  .hdr-sub { display: none; }
  .statuspill { padding: 3px 8px; font-size: 11px; }
}

/* ── Navigation Tabs ─────────────────────────────────── */
nav#tabs {
  display: flex;
  background: var(--surface);
  border-top: 1px solid var(--divider);
  padding: 0 12px;
  overflow-x: auto;
  scrollbar-width: none;
  gap: 4px;
  width: 100%;
}
nav#tabs::-webkit-scrollbar { display: none; }
nav#tabs button {
  background: transparent;
  border: none;
  border-radius: 0;
  border-bottom: 2px solid transparent;
  color: var(--text2);
  padding: 10px 14px;
  font-size: 13px;
  font-weight: 600;
  white-space: nowrap;
  flex-shrink: 0;
  gap: 6px;
  transition: all 0.15s ease;
}
nav#tabs button:hover { color: var(--text); background: transparent; }
nav#tabs button.active {
  color: var(--accent);
  border-bottom-color: var(--accent);
}
@media (max-width: 680px) {
  nav#tabs { padding: 0 8px; }
  nav#tabs button { padding: 9px 10px; font-size: 12px; gap: 4px; }
}

/* ── Buttons ─────────────────────────────────────────── */
button {
  font: inherit;
  cursor: pointer;
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 7px 13px;
  background: var(--card2);
  color: var(--text);
  font-weight: 600;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  transition: all 0.15s ease;
  line-height: 1.2;
  white-space: nowrap;
  -webkit-tap-highlight-color: transparent;
}
button:hover:not(:disabled) { background: var(--surface-active); border-color: var(--border-focus); }
button:disabled { opacity: 0.4; cursor: not-allowed; }
button.primary { background: var(--accent); border-color: var(--accent); color: #ffffff; }
button.primary:hover:not(:disabled) { background: var(--accent-hover); border-color: var(--accent-hover); }
button.danger { background: var(--red-bg); color: var(--red); border-color: rgba(244, 63, 94, 0.28); }
button.danger:hover:not(:disabled) { background: rgba(244, 63, 94, 0.2); }
button.ghost { background: transparent; color: var(--text2); border-color: var(--border); }
button.ghost:hover:not(:disabled) { background: var(--card2); color: var(--text); border-color: var(--border-focus); }
button.success { background: var(--green-bg); color: var(--green); border-color: rgba(16, 185, 129, 0.28); }
button.success:hover:not(:disabled) { background: rgba(16, 185, 129, 0.2); }
button.small { padding: 5px 10px; border-radius: 6px; font-size: 12px; }

input[type=text], input[type=password], input[type=number] {
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
}
input:focus { border-color: var(--accent); }
::placeholder { color: var(--muted); }

/* Switch control */
.switch { position: relative; display: inline-block; width: 36px; height: 20px; flex: 0 0 36px; }
.switch input { opacity: 0; width: 0; height: 0; }
.switch .track {
  position: absolute; inset: 0;
  background: var(--card2); border: 1px solid var(--border);
  border-radius: 999px; transition: 0.2s; cursor: pointer;
}
.switch .track:before {
  content: ""; position: absolute; height: 14px; width: 14px;
  left: 2px; top: 2px; background: var(--muted);
  border-radius: 50%; transition: 0.2s;
}
.switch input:checked + .track { background: var(--accent); border-color: var(--accent); }
.switch input:checked + .track:before { transform: translateX(16px); background: #ffffff; }

/* ── Main View Container ─────────────────────────────── */
main#view {
  max-width: 1120px;
  width: 100%;
  margin: 0 auto;
  padding: 18px 16px 48px;
  display: flex;
  flex-direction: column;
  gap: 14px;
  min-width: 0;
  box-sizing: border-box;
  overflow: hidden;
}
@media (max-width: 680px) {
  main#view { padding: 12px 10px 48px; gap: 11px; }
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

/* ── URL Box ─────────────────────────────────────────── */
.urlbox {
  display: flex;
  align-items: center;
  gap: 8px;
  background: var(--card2);
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 7px 11px;
  margin-top: 8px;
  width: 100%;
  box-sizing: border-box;
  overflow: hidden;
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
  .urlbox { padding: 6px 9px; }
  .urlbox code { font-size: 11px; }
}
.iconbtn {
  background: transparent;
  border: none;
  padding: 5px 7px;
  color: var(--text2);
  border-radius: 6px;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  transition: all 0.15s;
  flex-shrink: 0;
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
  gap: 6px;
  overflow-x: auto;
  scrollbar-width: none;
  margin: 10px 0 14px;
  padding-bottom: 4px;
  max-width: 100%;
  width: 100%;
  box-sizing: border-box;
  -webkit-overflow-scrolling: touch;
}
.pillrow::-webkit-scrollbar { display: none; }
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
  flex-shrink: 0;
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
.empty { padding: 32px 16px; text-align: center; color: var(--text2); font-size: 13px; }
</style>
</head>
<body>

<div class="sticky-nav-header">
  <header>
    <div class="hdr-brand">
      <div class="logo-box">
        <img src="/logo.png" alt="CNCVerse Logo" class="logo-box-img" onerror="this.style.display='none'; this.parentElement.innerText='CNC';">
      </div>
      <div class="hdr-title-wrap">
        <div class="hdr-title">CNCVerse Bridge</div>
        <div class="hdr-sub" id="subtitle">Admin Panel</div>
      </div>
    </div>
    <div class="hdr-actions">
      <div class="statuspill" id="statuspill">
        <span class="dot gray"></span>
        <span id="statustext">connecting...</span>
      </div>
      <button class="btn-icon-sq" id="theme-btn" onclick="toggleTheme()" title="Toggle Theme">
        <svg id="theme-icon" width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z"/></svg>
      </button>
    </div>
  </header>

  <nav id="tabs">
    <button data-tab="server" class="active">
      <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="2" y="2" width="20" height="8" rx="2"/><rect x="2" y="14" width="20" height="8" rx="2"/><line x1="6" y1="6" x2="6.01" y2="6"/><line x1="6" y1="18" x2="6.01" y2="18"/></svg>
      Server
    </button>
    <button data-tab="extensions">
      <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M19 11V4a1 1 0 0 0-1-1h-7a1 1 0 0 0-1 1v1a2 2 0 0 1-4 0V4a1 1 0 0 0-1-1H2a1 1 0 0 0-1 1v7a1 1 0 0 0 1 1h1a2 2 0 0 1 0 4H2a1 1 0 0 0-1 1v7a1 1 0 0 0 1 1h7a1 1 0 0 0 1-1v-1a2 2 0 0 1 4 0v1a1 1 0 0 0 1 1h7a1 1 0 0 0 1-1v-7a1 1 0 0 0-1-1h-1a2 2 0 0 1 0-4h1a1 1 0 0 0 1-1Z"/></svg>
      Extensions
    </button>
    <button data-tab="logs">
      <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="4 17 10 11 4 5"/><line x1="12" y1="19" x2="20" y2="19"/></svg>
      Logs
    </button>
    <button data-tab="about">
      <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/><path d="M12 16v-4"/><path d="M12 8h.01"/></svg>
      About
    </button>
  </nav>
</div>

<main id="view"></main>

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

<script>
"use strict";

function applyTheme(t) {
  document.documentElement.setAttribute("data-theme", t);
  localStorage.setItem("cnc_theme", t);
  var icon = document.getElementById("theme-icon");
  if (icon) {
    if (t === "light") {
      icon.innerHTML = '<circle cx="12" cy="12" r="5"/><line x1="12" y1="1" x2="12" y2="3"/><line x1="12" y1="21" x2="12" y2="23"/><line x1="4.22" y1="4.22" x2="5.64" y2="5.64"/><line x1="18.36" y1="18.36" x2="19.78" y2="19.78"/><line x1="1" y1="12" x2="3" y2="12"/><line x1="21" y1="12" x2="23" y2="12"/><line x1="4.22" y1="19.78" x2="5.64" y2="18.36"/><line x1="18.36" y1="5.64" x2="19.78" y2="4.22"/>';
    } else {
      icon.innerHTML = '<path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z"/>';
    }
  }
}
function toggleTheme() {
  var cur = document.documentElement.getAttribute("data-theme") || "dark";
  applyTheme(cur === "dark" ? "light" : "dark");
}
var savedTheme = localStorage.getItem("cnc_theme") || "dark";
applyTheme(savedTheme);

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

function toast(msg) {
  var el = document.getElementById("toast");
  el.textContent = msg;
  el.classList.add("show");
  clearTimeout(el._t);
  el._t = setTimeout(function() { el.classList.remove("show"); }, 2500);
}

function copyText(text, btn) {
  function onDone() {
    if (btn) {
      var orig = btn.innerHTML;
      btn.innerHTML = '<svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="var(--green)" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><polyline points="20 6 9 17 4 12"/></svg>';
      setTimeout(function() { btn.innerHTML = orig; }, 1600);
    }
    toast("Copied!");
  }
  if (navigator.clipboard && navigator.clipboard.writeText) {
    navigator.clipboard.writeText(text).then(onDone);
  } else {
    var ta = document.createElement("textarea");
    ta.value = text; document.body.appendChild(ta); ta.select();
    document.execCommand("copy"); document.body.removeChild(ta);
    onDone();
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
  else if (tab === "logs") { /* append-only, don't full re-render */ }
  else if (tab === "about") renderAbout();
}

// ── Server tab ────────────────────────────────────────────────────────────────
function urlBox(label, url) {
  return '<div class="urlbox"><span class="muted" style="font-weight:600">' + esc(label) + '</span><code>' + esc(url) +
    '</code><button class="iconbtn small" title="Copy URL" onclick="copyText(\'' + esc(url).replace(/\x27/g,"&#39;") + '\', this)">' + svgCopy + '</button></div>';
}

function renderServer() {
  var s = summary.server;
  var t = summary.tunnel;
  var html = '';

  // Quick Stat Cards (pengu.uk inspired)
  html += '<div class="stat-cards-grid">';
  html += '<div class="stat-card"><div class="stat-label">Server Status</div><div class="stat-val' + (s.status === "Running" ? ' green' : '') + '">' + esc(s.status) + '</div><div class="stat-sub">Port :' + s.port + '</div></div>';
  html += '<div class="stat-card"><div class="stat-label">Active Plugins</div><div class="stat-val">' + (s.pluginCount || 0) + '</div><div class="stat-sub">Ready in manifest</div></div>';
  html += '<div class="stat-card"><div class="stat-label">Bridge Mode</div><div class="stat-val">' + (summary.headless ? 'Server' : 'Desktop') + '</div><div class="stat-sub">v' + esc(summary.version) + '</div></div>';
  html += '<div class="stat-card"><div class="stat-label">Tunnel &amp; HTTPS</div><div class="stat-val' + (t && t.activeUrl ? ' green' : '') + '">' + (t && t.activeUrl ? 'Active' : (t && t.stremioMode ? 'Ready' : 'Off')) + '</div><div class="stat-sub">' + (t && t.cloudflaredInstalled ? 'cloudflared' : 'Local only') + '</div></div>';
  html += '</div>';

  html += '<div class="grid cols2">';

  // Server card
  html += '<div class="card"><h2>' + svgServer + ' Addon Gateway</h2><div class="hint">Network endpoints exposing the Stremio protocol.</div>';
  if (s.status === "Running") {
    html += '<div class="row" style="margin-bottom:8px"><span class="badge green">Running</span><span class="muted">' + s.pluginCount + ' active plugin(s)</span></div>';
    html += urlBox("LAN", s.lanUrl || ("http://" + s.ipAddress + ":" + s.port + "/manifest.json"));
    if (s.localhostUrl) html += urlBox("Localhost", s.localhostUrl);
    if (t.stremioMode && s.stremioModeUrl) html += urlBox("Tunnel", s.stremioModeUrl);
    html += '<div class="row" style="margin-top:14px;gap:8px">';
    html += '<button class="primary small" onclick="act(\'/server/restart\',{method:\'POST\',body:\'{}\'}, \'Restarting server...\')">Restart</button>';
    html += '<button class="danger small" onclick="act(\'/server/stop\',{method:\'POST\',body:\'{}\'}, \'Stopping server...\')">Stop</button>';
    html += '<a class="pill" href="/manifest.json" target="_blank">View Manifest</a>';
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
  html += '</div></div>';

  el("view").innerHTML = html;
}

function toggleStremioMode(enabled) {
  act("/stremio-mode", {method:"POST", body: JSON.stringify({enabled: enabled})},
    enabled ? "Enabling Stremio mode..." : "Disabling Stremio mode...");
}

// ── Extensions tab ────────────────────────────────────────────────────────────
function renderExtensions() {
  var html = "";

  // Repositories section
  html += '<div class="card"><h2>' + svgBox + ' Repositories</h2><div class="hint">CloudStream extension repos. Added repos are saved globally and all their extensions download automatically. Disabling an extension keeps it installed but hides it from the global manifest — users can still add it to their own profile.</div>';
  html += '<div class="row" style="margin-bottom:12px">';
  html += '<button class="primary small" onclick="openAddRepoModal()">Add Repository</button>';
  html += '<button class="ghost small" onclick="act(\'/repos/refresh\',{method:\'POST\',body:\'{}\'}, \'Refreshing repositories...\')">' + (summary.refreshing ? '<span class="loader"></span> ' : svgRefresh + ' ') + 'Refresh All</button>';
  html += '</div>';

  if (summary.repos && summary.repos.length) {
    html += '<div class="grid" style="gap:8px">';
    summary.repos.forEach(function(r) {
      html += '<div class="reporow">';
      html += '<div class="repo-main">';
      if (r.iconUrl) {
        html += '<img class="ricon" src="' + esc(r.iconUrl) + '" onerror="this.style.display=\'none\';this.nextElementSibling.style.display=\'flex\'" alt="">';
        html += '<div class="rletter" style="display:none">' + esc((r.name||'?').charAt(0).toUpperCase()) + '</div>';
      } else {
        html += '<div class="rletter">' + esc((r.name||'?').charAt(0).toUpperCase()) + '</div>';
      }
      html += '<div class="rinfo"><div class="rname">' + esc(r.name||r.url) + '</div><div class="rurl" title="' + esc(r.url) + '">' + esc(r.url) + '</div></div>';
      html += '</div>';

      html += '<div class="repo-actions">';
      if (r.isLoading) html += '<span class="badge amber"><span class="loader"></span></span>';
      else if (r.error) html += '<span class="badge red" title="' + esc(r.error) + '">Error</span>';
      else html += '<span class="badge gray">' + r.pluginCount + ' extensions</span>';
      if (!r.isLoading && !r.error) {
        html += '<button class="small success" onclick="installAllFromRepo(\'' + esc(r.url).replace(/\'/g,"%27") + '\')">Install All</button>';
      }
      html += '<button class="small danger" onclick="removeRepo(\'' + esc(r.url).replace(/\'/g,"%27") + '\')">Remove</button>';
      html += '</div>';
      html += '</div>';
    });
    html += '</div>';
  } else {
    html += '<div class="empty">No repositories connected yet.</div>';
  }
  html += '</div>';

  // Plugin catalog
  html += '<div class="card" style="margin-top:14px">';
  html += '<div class="row" style="align-items:flex-start;margin-bottom:10px">';
  html += '<div><h2>' + svgPuzzle + ' Extension Catalog</h2><div class="hint" style="margin-bottom:0">Install, update and configure provider extensions.</div></div>';
  html += '<button class="ghost small" style="margin-left:auto;flex:0 0 auto" onclick="loadPlugins();toast(\'Refreshing extensions...\')">' + svgRefresh + ' Refresh</button>';
  html += '</div>';
  
  var totalPlugins = plugins ? plugins.length : 0;
  var installedCount = 0;
  if (plugins) {
    plugins.forEach(function(p) { if (p.installed) installedCount++; });
  }

  html += '<div class="pillrow">';
  html += '<span class="pill' + (repoFilter === "all" ? " active" : "") + '" onclick="setRepoFilter(\'all\')">All (' + totalPlugins + ')</span>';
  html += '<span class="pill' + (repoFilter === "installed" ? " active" : "") + '" onclick="setRepoFilter(\'installed\')">Installed (' + installedCount + ')</span>';
  if (summary.repos) {
    summary.repos.forEach(function(r) {
      var rCount = 0;
      if (plugins) {
        plugins.forEach(function(p) { if (p.repoUrl === r.url) rCount++; });
      }
      html += '<span class="pill' + (repoFilter === r.url ? " active" : "") + '" onclick="setRepoFilter(\'' + esc(r.url).replace(/\x27/g,"%27") + '\')">' + esc(r.name || r.url) + ' (' + rCount + ')</span>';
    });
  }
  html += '</div>';

  html += '<div style="position:relative;margin-bottom:14px">';
  html += '<input type="text" id="ext-search-input" placeholder="Search extensions by name, tag, or author..." value="' + esc(searchQuery) + '" oninput="setSearch(this.value)" style="padding-right:32px">';
  if (searchQuery) {
    html += '<button onclick="setSearch(\'\');var el=document.getElementById(\'ext-search-input\');if(el)el.value=\'\';" style="position:absolute;right:8px;top:50%;transform:translateY(-50%);background:none;border:none;color:var(--muted);padding:4px;cursor:pointer;font-size:14px;line-height:1;" title="Clear search">&times;</button>';
  }
  html += '</div>';

  if (!plugins) {
    html += '<div class="empty"><span class="loader"></span> Loading extensions...</div>';
  } else {
    var list = plugins.filter(function(p) {
      if (repoFilter === "installed" && !p.installed) return false;
      if (repoFilter !== "all" && repoFilter !== "installed" && p.repoUrl !== repoFilter) return false;
      if (searchQuery) {
        var q = searchQuery.toLowerCase();
        if ((p.displayName + " " + (p.description||"") + " " + (p.language||"")).toLowerCase().indexOf(q) < 0) return false;
      }
      return true;
    });
    if (!list.length) {
      html += '<div class="empty">No extensions match your filter.</div>';
    } else {
      html += '<div class="plugins">';
      list.forEach(function(p) {
        html += renderPluginCard(p);
      });
      html += '</div>';
    }
  }
  html += '</div>';
  var focusId = document.activeElement ? document.activeElement.id : null;
  var cursorStart = -1, cursorEnd = -1;
  if (focusId) {
    try {
      cursorStart = document.activeElement.selectionStart;
      cursorEnd = document.activeElement.selectionEnd;
    } catch(e) {}
  }

  el("view").innerHTML = html;

  if (focusId) {
    var newEl = document.getElementById(focusId);
    if (newEl) {
      newEl.focus();
      try {
        if (cursorStart !== undefined && cursorStart !== null && cursorStart >= 0) {
          newEl.setSelectionRange(cursorStart, cursorEnd);
        }
      } catch(e) {}
    }
  }
}

function renderPluginCard(p) {
  var inst = null;
  if (summary && summary.installedPlugins) {
    for (var i = 0; i < summary.installedPlugins.length; i++) {
      if (summary.installedPlugins[i].internalName === p.internalName) { inst = summary.installedPlugins[i]; break; }
    }
  }
  var html = '<div class="pcard" id="pc-' + esc(p.internalName) + '">';
  
  // Top: Icon + Status badge
  html += '<div class="pcard-header">';
  if (p.iconUrl) {
    html += '<img class="picon" src="' + esc(p.iconUrl) + '" onerror="this.style.display=\'none\';this.nextElementSibling.style.display=\'flex\'" alt="">';
    html += '<div class="pletter" style="display:none">' + esc((p.displayName||p.name||'?').charAt(0).toUpperCase()) + '</div>';
  } else {
    html += '<div class="pletter">' + esc((p.displayName||p.name||'?').charAt(0).toUpperCase()) + '</div>';
  }

  if (p.installState === "Installing") {
    html += '<span class="badge amber"><span class="loader"></span></span>';
  } else if (p.installState === "Failed") {
    html += '<span class="badge red">Failed</span>';
  } else if (p.installed && p.updateAvailable) {
    html += '<span class="badge amber">Update</span>';
  } else if (p.installed) {
    html += '<span class="badge green">v' + esc(p.installedVersion || p.version || "1.0") + '</span>';
  } else {
    html += '<span class="badge gray">Available</span>';
  }
  html += '</div>';

  // Body: Name, Meta, Description
  html += '<div class="pcard-body">';
  html += '<div class="name" title="' + esc(p.displayName || p.name) + '">' + esc(p.displayName || p.name) + '</div>';
  html += '<div class="meta">v' + esc(p.version || "1.0") + (p.language ? ' &middot; ' + esc(p.language) : '') + (p.repoName ? ' &middot; ' + esc(p.repoName) : '') + '</div>';
  if (p.description) {
    html += '<div class="desc" title="' + esc(p.description) + '">' + esc(p.description) + '</div>';
  }
  html += '</div>';

  // Actions footer
  html += '<div class="pcard-actions">';
  if (p.installState === "Installing") {
    html += '<button class="small ghost" disabled style="width:100%"><span class="loader"></span> ' + esc(p.installProgress || "Installing...") + '</button>';
  } else if (p.installState === "Failed") {
    html += '<button class="small primary" style="flex:1" onclick="installPlugin(\'' + esc(p.internalName) + '\')">Retry</button>';
  } else if (p.installed && p.updateAvailable) {
    html += '<button class="small primary" style="flex:1" onclick="installPlugin(\'' + esc(p.internalName) + '\')">Update v' + esc(p.version) + '</button>';
  } else if (!p.installed) {
    html += '<button class="small primary" style="width:100%" onclick="installPlugin(\'' + esc(p.internalName) + '\')">Install</button>';
  }

  if (p.installed) {
    html += '<div class="pcard-ctrls">';
    if (inst) {
      html += '<label class="switch" title="' + (inst.enabled ? "Disable" : "Enable") + '"><input type="checkbox" ' + (inst.enabled ? "checked" : "") + ' onchange="togglePlugin(\'' + esc(p.internalName) + '\')"><span class="track"></span></label>';
    }
    if (inst && inst.hasSettings) {
      html += '<button class="iconbtn small" title="Settings" onclick="toggleSettingsDrawer(\'' + esc(p.internalName) + '\')">' + svgGear + '</button>';
    }
    html += '<button class="iconbtn small danger-btn" title="Uninstall" onclick="uninstallPlugin(\'' + esc(p.internalName) + '\')">' + svgTrash + '</button>';
    html += '</div>';
  }
  html += '</div>';
  html += '</div>';
  return html;
}

function setRepoFilter(f) { repoFilter = f; renderExtensions(); }
function setSearch(q) { searchQuery = q; renderExtensions(); }

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
      var b = el("add-repo-btn"); if (b) { b.disabled = false; b.textContent = "Add"; }
    });
}

function removeRepo(url) {
  act("/repos/remove", {method:"POST", body: JSON.stringify({url: url})}, "Repo removed");
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

function uninstallPlugin(id) {
  if (!confirm("Uninstall this extension?")) return;
  act("/plugins/uninstall", {method:"POST", body: JSON.stringify({internalName: id})}, "Uninstalled");
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
      html += '<button class="primary small" onclick="applySettings(\'' + esc(id) + '\')">Apply &amp; Reload</button>';
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
      html += '<label class="checkrow"><input type="radio" name="opt-' + esc(pluginId) + '-' + esc(st.storageKey) + '" ' + (checked?"checked":"") + ' onchange="saveSettingValue(\'' + esc(pluginId) + '\',\'' + esc(st.storageKey) + '\',\'' + esc(val).replace(/\x27/g,"%27") + '\')"><span>' + esc(label) + '</span></label>';
    });
    html += '</div>';
  } else if (st.type === "StringSet") {
    var opts = st.defaultSet || [];
    var cur = st.currentSet || [];
    html += '<div class="row" style="margin-bottom:6px">';
    html += '<button class="small ghost" onclick="setAllValues(\'' + esc(pluginId) + '\',\'' + esc(st.storageKey) + '\',' + (st.isDisabledStyle?"false":"true") + ',' + JSON.stringify(opts).replace(/"/g,"&quot;") + ')">' + (st.isDisabledStyle?"Enable All":"Select All") + '</button>';
    html += '<button class="small ghost" onclick="setAllValues(\'' + esc(pluginId) + '\',\'' + esc(st.storageKey) + '\',' + (st.isDisabledStyle?"true":"false") + ',[])">' + (st.isDisabledStyle?"Disable All":"Deselect All") + '</button></div>';
    if (opts.length) {
      html += '<div class="checkgrid">';
      opts.forEach(function(o) {
        var checked = st.isDisabledStyle ? cur.indexOf(o)<0 : cur.indexOf(o)>=0;
        html += '<label class="checkrow"><input type="checkbox" ' + (checked?"checked":"") + ' onchange="toggleSetVal(\'' + esc(pluginId) + '\',\'' + esc(st.storageKey) + '\',\'' + esc(o).replace(/\x27/g,"%27") + '\',' + (st.isDisabledStyle?"true":"false") + ',this)"><span>' + esc(o.replace("API","").replace("Api","")) + '</span></label>';
      });
      html += '</div>';
    }
  } else if (st.isBooleanLike) {
    var on = st.currentValue==="true"||(st.currentValue==null&&(st.defaultValue==="true"||st.defaultValue===true));
    html += '<div class="row" style="margin-top:4px"><span class="muted">' + (on?"Enabled":"Disabled") + '</span><div class="spacer"></div>';
    html += '<label class="switch"><input type="checkbox" ' + (on?"checked":"") + ' onchange="saveSettingValue(\'' + esc(pluginId) + '\',\'' + esc(st.storageKey) + '\',this.checked?\'true\':\'false\')"><span class="track"></span></label></div>';
  } else {
    var isNum = st.type==="Int"||st.type==="Long"||st.type==="Float";
    html += '<input ' + (isNum?'type="number" step="any"':'type="text"') + ' value="' + esc(st.currentValue!=null?st.currentValue:(st.defaultValue!=null?st.defaultValue:"")) + '" onchange="saveSettingValue(\'' + esc(pluginId) + '\',\'' + esc(st.storageKey) + '\',this.value||null)">';
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
  return logsData.filter(function(l) { return l.level === logLevelFilter; });
}

function renderLogs() {
  var filtered = getFilteredLogs();
  var html = '<div class="card"><h2>' + svgTerminal + ' Live Logs</h2><div class="hint">Real-time system events, search requests, and plugin operations.</div>';
  html += '<div class="logs-wrap"><div class="logs-toolbar">';
  html += '<button class="ghost small" onclick="copyLogs()">' + svgCopy + ' Copy All</button>';
  html += '<button class="ghost small" onclick="clearLogs()">Clear</button>';
  html += '<div class="row" style="gap:4px;margin-left:6px">';
  html += '<button class="small ' + (logLevelFilter==="ALL"?"primary":"ghost") + '" onclick="setLogLevelFilter(\'ALL\')" style="padding:2px 8px;font-size:11px">All</button>';
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
  html += '<div class="muted" style="margin-top:16px;font-size:12px">Made with <span style="color:#f43f5e">&#10084;&#65039;</span> By <a href="https://t.me/NivinCNC" target="_blank" rel="noreferrer" style="color:var(--text);text-decoration:none;font-weight:600">NivinCNC</a> &bull; UI By <span style="color:var(--text);font-weight:600">sleepycat555</span></div>';
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

// ── Shared actions ────────────────────────────────────────────────────────────
function act(path, opts, msg) {
  api(path, opts).then(function(r) {
    if (msg) toast(r && r.message ? r.message : msg);
    poll();
    if (tab === "extensions") loadPlugins();
  }).catch(function(e) { toast("Action failed: "+e.message); });
}

function openTab(t) {
  tab = t;
  document.querySelectorAll("#tabs button").forEach(function(b) {
    b.classList.toggle("active", b.getAttribute("data-tab") === t);
  });
  if (t === "logs") { renderLogs(); pollLogs(); }
  else if (t === "extensions") { renderExtensions(); loadPlugins(); }
  else render();
}

function loadPlugins() {
  api("/plugins").then(function(list) {
    plugins = list;
    if (tab === "extensions") renderExtensions();
  }).catch(function() {});
}

// ── Boot ──────────────────────────────────────────────────────────────────────
document.querySelectorAll("#tabs button").forEach(function(b) {
  b.addEventListener("click", function() { openTab(b.getAttribute("data-tab")); });
});

poll();
loadPlugins();
pollLogs();

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
