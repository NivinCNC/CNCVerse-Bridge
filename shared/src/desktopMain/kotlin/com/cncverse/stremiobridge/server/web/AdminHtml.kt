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
 *  - Hourly update check triggered client-side after 1 hour
 *
 * NOTE: JavaScript below intentionally avoids template literals and any '$'
 * characters so it can live inside a Kotlin raw string without escaping.
 */
object AdminHtml {

    val page: String = """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>CNCVerse Bridge — Admin</title>
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700;800&display=swap" rel="stylesheet">
<style>
:root{
  --bg:#030306; --surface:#07070f; --card:#0e0e1a; --card2:#14141f;
  --border:#1e1e2e; --divider:#181828;
  --violet:#7c3aed; --violet400:#a78bfa; --violet300:#c4b5fd; --violet200:#ddd6fe;
  --violet-glow:rgba(124,58,237,.18); --violet-border:rgba(124,58,237,.35);
  --text:#f0f0ff; --text2:#9b9bba; --muted:#5c5c7a;
  --green:#4ade80; --red:#f87171; --amber:#fbbf24; --blue:#60a5fa;
  --green-glow:rgba(74,222,128,.15); --red-glow:rgba(248,113,113,.12);
}
*{box-sizing:border-box;margin:0;padding:0}
html,body{background:var(--bg);color:var(--text);min-height:100vh}
body{font-family:'Inter',-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;font-size:14px;line-height:1.5;-webkit-font-smoothing:antialiased}
a{color:var(--violet400);text-decoration:none}
a:hover{color:var(--violet200)}
button{font:inherit;cursor:pointer;border:none;border-radius:10px;padding:9px 16px;background:var(--card2);color:var(--text);transition:background .15s,opacity .15s,box-shadow .15s}
button:hover{background:#1c1c2e}
button:disabled{opacity:.4;cursor:default}
button.primary{background:linear-gradient(135deg,#7c3aed,#6d28d9);color:#fff;font-weight:600;box-shadow:0 0 18px rgba(124,58,237,.28)}
button.primary:hover:not(:disabled){background:linear-gradient(135deg,#8b5cf6,#7c3aed);box-shadow:0 0 28px rgba(124,58,237,.4)}
button.danger{background:var(--red-glow);color:var(--red);border:1px solid rgba(248,113,113,.3)}
button.danger:hover:not(:disabled){background:rgba(248,113,113,.2)}
button.ghost{background:transparent;border:1px solid var(--border);color:var(--text2)}
button.ghost:hover:not(:disabled){border-color:var(--violet);color:var(--text)}
button.success{background:var(--green-glow);color:var(--green);border:1px solid rgba(74,222,128,.3);font-weight:600}
button.success:hover:not(:disabled){background:rgba(74,222,128,.22)}
button.small{padding:5px 12px;border-radius:8px;font-size:12.5px}
input[type=text],input[type=password],input[type=number]{font:inherit;background:var(--card2);border:1px solid var(--border);border-radius:10px;color:var(--text);padding:9px 12px;outline:none;width:100%;transition:border-color .15s}
input:focus{border-color:var(--violet);box-shadow:0 0 0 3px rgba(124,58,237,.12)}
::placeholder{color:var(--muted)}
.switch{position:relative;display:inline-block;width:44px;height:24px;flex:0 0 auto}
.switch input{opacity:0;width:0;height:0}
.switch .track{position:absolute;inset:0;background:var(--card2);border:1px solid var(--border);border-radius:999px;transition:.2s;cursor:pointer}
.switch .track:before{content:"";position:absolute;height:18px;width:18px;left:2px;top:2px;background:var(--muted);border-radius:50%;transition:.2s}
.switch input:checked + .track{background:var(--violet);border-color:var(--violet)}
.switch input:checked + .track:before{transform:translateX(20px);background:#fff}

/* ── Header ── */
header{display:flex;align-items:center;gap:14px;padding:14px 20px;border-bottom:1px solid var(--divider);position:sticky;top:0;background:rgba(3,3,6,.92);backdrop-filter:blur(16px);z-index:100}
.logo{width:36px;height:36px;border-radius:11px;background:linear-gradient(135deg,#6d28d9,#a78bfa);display:flex;align-items:center;justify-content:center;font-weight:900;font-size:10px;color:#fff;letter-spacing:-.5px;flex:0 0 auto;box-shadow:0 0 20px rgba(124,58,237,.35)}
.hdr-text h1{font-size:16px;font-weight:700;letter-spacing:-.3px}
.hdr-text .sub{font-size:11.5px;color:var(--muted)}
.statuspill{margin-left:auto;display:flex;align-items:center;gap:8px;background:var(--card);border:1px solid var(--border);border-radius:999px;padding:5px 13px;font-size:12px;font-weight:600;white-space:nowrap}
.dot{width:8px;height:8px;border-radius:50%;display:inline-block;flex:0 0 auto}
.dot.green{background:var(--green);box-shadow:0 0 8px var(--green)}
.dot.amber{background:var(--amber);box-shadow:0 0 8px var(--amber);animation:pulse 1.2s infinite}
.dot.red{background:var(--red);box-shadow:0 0 8px var(--red)}
.dot.gray{background:var(--muted)}
@keyframes pulse{50%{opacity:.35}}

/* ── Nav ── */
nav{display:flex;gap:2px;padding:8px 20px 0;border-bottom:1px solid var(--divider);overflow-x:auto;-webkit-overflow-scrolling:touch;scrollbar-width:none}
nav::-webkit-scrollbar{display:none}
nav button{background:transparent;color:var(--text2);border-radius:10px 10px 0 0;padding:9px 16px;font-weight:600;font-size:13.5px;border-bottom:2px solid transparent;white-space:nowrap;flex:0 0 auto}
nav button.active{color:var(--text);border-bottom-color:var(--violet)}
nav button:hover{color:var(--text);background:rgba(255,255,255,.03)}

/* ── Main ── */
main{padding:20px;max-width:1100px;margin:0 auto}
.grid{display:grid;gap:14px}
.cols2{grid-template-columns:1fr 1fr}
@media(max-width:800px){.cols2{grid-template-columns:1fr}}
.card{background:var(--card);border:1px solid var(--border);border-radius:16px;padding:18px 20px}
.card h2{font-size:14.5px;font-weight:700;display:flex;align-items:center;gap:8px;margin-bottom:3px;letter-spacing:-.2px}
.card .hint{font-size:12px;color:var(--muted);margin-bottom:14px}
.row{display:flex;align-items:center;gap:10px;flex-wrap:wrap}
.spacer{flex:1 1 0}
.muted{color:var(--text2);font-size:12.5px}

/* ── URL box ── */
.urlbox{display:flex;align-items:center;gap:8px;background:var(--surface);border:1px solid var(--border);border-radius:10px;padding:8px 12px;margin-top:8px}
.urlbox code{font-family:ui-monospace,'Cascadia Code',Menlo,Consolas,monospace;font-size:12px;color:var(--violet300);word-break:break-all;flex:1}
.iconbtn{background:transparent;padding:5px 9px;color:var(--text2);border-radius:7px}
.iconbtn:hover{background:var(--card2);color:var(--text)}

/* ── Progress ── */
.progress{height:6px;border-radius:99px;background:var(--surface);overflow:hidden;margin-top:10px}
.progress > div{height:100%;background:linear-gradient(90deg,var(--violet),var(--violet400));border-radius:99px;transition:width .4s}

/* ── Badges ── */
.badge{display:inline-flex;align-items:center;gap:5px;font-size:11px;font-weight:700;padding:3px 9px;border-radius:999px;letter-spacing:.2px}
.badge.green{background:var(--green-glow);color:var(--green)}
.badge.red{background:var(--red-glow);color:var(--red)}
.badge.amber{background:rgba(251,191,36,.12);color:var(--amber)}
.badge.violet{background:var(--violet-glow);color:var(--violet400)}
.badge.gray{background:#111120;color:var(--text2)}
.badge.blue{background:rgba(96,165,250,.12);color:var(--blue)}
.badge.local{background:rgba(251,191,36,.1);color:var(--amber);border:1px solid rgba(251,191,36,.25)}

/* ── Pills / filters ── */
.pillrow{display:flex;gap:7px;flex-wrap:wrap;margin:12px 0}
.pill{font-size:12px;padding:5px 13px;border-radius:999px;background:var(--card2);border:1px solid var(--border);color:var(--text2);cursor:pointer;transition:all .15s}
.pill:hover{border-color:var(--violet);color:var(--text)}
.pill.active{background:var(--violet-glow);border-color:var(--violet-border);color:var(--violet300);font-weight:600}

/* ── Plugin grid ── */
.plugins{display:grid;gap:12px;grid-template-columns:repeat(auto-fill,minmax(300px,1fr))}
@media(max-width:600px){.plugins{grid-template-columns:1fr}}
.pcard{background:var(--card);border:1px solid var(--border);border-radius:14px;padding:15px;display:flex;flex-direction:column;gap:10px;transition:border-color .15s}
.pcard:hover{border-color:#2a2a40}
.pcard.loading{opacity:.65}
.pcard .top{display:flex;gap:11px;align-items:flex-start}
.picon{width:44px;height:44px;border-radius:11px;object-fit:cover;background:var(--card2);border:1px solid var(--border);flex:0 0 auto}
.pletter{width:44px;height:44px;border-radius:11px;background:linear-gradient(135deg,#1e1b4b,#6d28d9);display:flex;align-items:center;justify-content:center;font-size:18px;font-weight:800;color:#fff;flex:0 0 auto}
.pcard .name{font-weight:700;font-size:14px;letter-spacing:-.2px}
.pcard .meta{font-size:11px;color:var(--muted);margin-top:2px}
.pcard .desc{font-size:12.5px;color:var(--text2);display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden}
.pcard .actions{display:flex;gap:7px;margin-top:auto;flex-wrap:wrap;align-items:center}

/* ── Settings modal popup ── */
.modal-overlay{position:fixed;inset:0;background:rgba(0,0,0,.55);backdrop-filter:blur(3px);z-index:1000;display:none;align-items:center;justify-content:center;padding:16px}
.modal-overlay.open{display:flex}
.modal-box{background:var(--card);border:1px solid var(--border);border-radius:18px;padding:22px 24px;max-width:560px;width:100%;max-height:85vh;overflow-y:auto;box-shadow:0 24px 64px rgba(0,0,0,.5);animation:modalIn .18s ease}
@keyframes modalIn{from{opacity:0;transform:translateY(18px) scale(.97)}to{opacity:1;transform:none}}
.modal-title{font-size:16px;font-weight:700;margin-bottom:16px;display:flex;align-items:center;gap:8px}
.modal-close{margin-left:auto;background:none;border:none;color:var(--text2);font-size:20px;cursor:pointer;line-height:1;padding:2px 6px;border-radius:6px}
.modal-close:hover{background:var(--border);color:var(--text)}
.setting{background:var(--card2);border:1px solid rgba(30,30,46,.8);border-radius:11px;padding:13px 15px;margin-bottom:10px}
.setting .label{font-weight:600;font-size:13.5px}
.setting .desc2{font-size:11.5px;color:var(--text2);margin:2px 0 9px}
.checkgrid{display:grid;grid-template-columns:1fr 1fr;gap:3px 14px}
.checkrow{display:flex;align-items:center;gap:8px;padding:4px 0;font-size:12.5px;cursor:pointer}
.checkrow input{accent-color:var(--violet);width:14px;height:14px}
.category{margin:16px 0 8px;color:var(--violet400);font-size:10.5px;font-weight:800;letter-spacing:1.4px;text-transform:uppercase;border-bottom:1px solid rgba(124,58,237,.12);padding-bottom:5px}
select{font:inherit;background:var(--card2);border:1px solid var(--border);border-radius:10px;color:var(--text);padding:9px 12px;width:100%;outline:none}
select:focus{border-color:var(--violet)}

/* ── Repo row ── */
.reporow{background:var(--card2);border:1px solid var(--border);border-radius:12px;padding:12px 15px;display:flex;gap:11px;align-items:center}
.reporow:hover{border-color:#2a2a40}
.ricon{width:32px;height:32px;border-radius:9px;object-fit:cover;flex:0 0 auto}
.rletter{width:32px;height:32px;border-radius:9px;background:linear-gradient(135deg,#1e1b4b,#6d28d9);display:flex;align-items:center;justify-content:center;font-size:13px;font-weight:800;color:#fff;flex:0 0 auto}
.rinfo{flex:1;min-width:0}
.rname{font-weight:600;font-size:13.5px}
.rurl{font-size:11px;color:var(--muted);overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.raction{display:flex;gap:7px;align-items:center;flex-wrap:wrap}

/* ── Logs ── */
.logs-wrap{background:#02020a;border:1px solid var(--border);border-radius:14px;overflow:hidden;max-height:65vh;display:flex;flex-direction:column}
.logs-toolbar{display:flex;align-items:center;gap:8px;padding:10px 14px;background:var(--card);border-bottom:1px solid var(--border);flex-shrink:0}
.loglist{padding:10px 14px;overflow-y:auto;flex:1;font-family:ui-monospace,'Cascadia Code',Menlo,Consolas,monospace;font-size:12px}
.loglist::-webkit-scrollbar{width:6px}
.loglist::-webkit-scrollbar-thumb{background:#1e1e2e;border-radius:3px}
.logline{padding:2px 6px;border-radius:5px;white-space:pre-wrap;word-break:break-word;margin-bottom:1px;line-height:1.55}
.logline .t{color:var(--muted);margin-right:7px;user-select:none}
.logline.INFO{color:#c0c0d8}
.logline.WARN{color:var(--amber);background:rgba(251,191,36,.04)}
.logline.ERROR{color:var(--red);background:rgba(248,113,113,.05)}

/* ── Profile manifest section ── */
.manifest-box{background:var(--surface);border:1px solid var(--violet-border);border-radius:12px;padding:14px 16px;margin-top:14px}
.manifest-box h3{font-size:13px;font-weight:700;color:var(--violet400);margin-bottom:8px;display:flex;align-items:center;gap:7px}
.manifest-ext-list{display:grid;gap:7px;margin-top:10px}
.mext-row{display:flex;align-items:center;gap:10px;padding:8px 12px;background:var(--card2);border:1px solid var(--border);border-radius:9px}
.mext-row .mname{flex:1;font-size:13px;font-weight:500}
.mext-row .mbadge{font-size:10.5px}

/* ── Toast ── */
.toast{position:fixed;bottom:24px;left:50%;transform:translateX(-50%);background:#111124;border:1px solid var(--violet-border);color:var(--text);padding:10px 22px;border-radius:12px;font-size:13px;box-shadow:0 8px 40px rgba(124,58,237,.3);opacity:0;transition:opacity .22s;pointer-events:none;z-index:999;max-width:90vw;text-align:center}
.toast.show{opacity:1}

/* ── Banner / alert ── */
.banner{background:var(--violet-glow);border:1px solid var(--violet-border);border-radius:11px;padding:11px 15px;font-size:12.5px;color:var(--violet300);margin-bottom:13px}
.banner.warn{background:rgba(251,191,36,.08);border-color:rgba(251,191,36,.3);color:var(--amber)}
.banner.err{background:var(--red-glow);border-color:rgba(248,113,113,.3);color:var(--red)}

/* ── Spinner ── */
.loader{display:inline-block;width:13px;height:13px;border:2px solid var(--violet);border-top-color:transparent;border-radius:50%;animation:spin .75s linear infinite;vertical-align:-2px}
@keyframes spin{to{transform:rotate(360deg)}}

/* ── KV table ── */
.kv{display:grid;grid-template-columns:130px 1fr;gap:5px 12px;font-size:13px}
.kv .k{color:var(--muted)}
.empty{padding:40px 20px;text-align:center;color:var(--text2);font-size:13px}

/* ── Mobile overrides ── */
@media(max-width:600px){
  header{padding:10px 14px;gap:10px}
  .hdr-text .sub{display:none}
  nav{padding:6px 14px 0}
  nav button{padding:8px 12px;font-size:13px}
  main{padding:14px}
  .card{padding:14px}
  .plugins{grid-template-columns:1fr}
  .statuspill{padding:5px 10px;font-size:11.5px}
  .raction{flex-direction:column;align-items:flex-start;gap:5px}
}
</style>
</head>
<body>
<header>
  <div class="logo">CNC</div>
  <div class="hdr-text">
    <h1>CNCVerse Bridge</h1>
    <div class="sub" id="subtitle">web admin</div>
  </div>
  <div class="statuspill" id="statuspill"><span class="dot gray"></span><span id="statustext">connecting&hellip;</span></div>
</header>
<nav id="tabs">
  <button data-tab="server" class="active">Server</button>
  <button data-tab="extensions">Extensions</button>
  <button data-tab="logs">Logs</button>
  <button data-tab="about">About</button>
</nav>
<main id="view"></main>
<div class="toast" id="toast"></div>
<div class="modal-overlay" id="settings-modal" onclick="if(event.target===this)closeSettingsModal()">
  <div class="modal-box" id="settings-modal-box">
    <div class="muted" style="text-align:center;padding:20px"><span class="loader"></span> Loading…</div>
  </div>
</div>
<div class="modal-overlay" id="add-repo-modal" onclick="if(event.target===this)closeAddRepoModal()">
  <div class="modal-box" style="max-width:480px">
    <div class="modal-title">➕ Add Repository<button class="modal-close" onclick="closeAddRepoModal()" title="Close">&times;</button></div>
    <div class="hint" style="margin-bottom:14px">Enter a full URL, GitHub shorthand, or cutt.ly shortcode.</div>
    <input type="text" id="add-repo-input" placeholder="https://raw.githubusercontent.com/…/repo.json" style="width:100%;box-sizing:border-box;margin-bottom:6px" onkeydown="if(event.key===\'Enter\')submitAddRepo()">
    <div class="muted" style="font-size:11.5px;margin-bottom:14px">Shortcuts: <code>user/repo</code> &middot; <code>user/repo/branch</code> &middot; <code>Hexated</code> (cutt.ly) &middot; <code>!pymd</code> (py.md)</div>
    <div class="row" style="gap:10px">
      <button class="primary" onclick="submitAddRepo()" id="add-repo-btn">Add</button>
      <button class="ghost" onclick="closeAddRepoModal()">Cancel</button>
    </div>
  </div>
</div>
<script>
"use strict";

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

function copyText(text) {
  if (navigator.clipboard && navigator.clipboard.writeText) {
    navigator.clipboard.writeText(text).then(function() { toast("Copied!"); });
  } else {
    var ta = document.createElement("textarea");
    ta.value = text; document.body.appendChild(ta); ta.select();
    document.execCommand("copy"); document.body.removeChild(ta);
    toast("Copied!");
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
  return '<div class="urlbox"><span class="muted">' + esc(label) + '</span><code>' + esc(url) +
    '</code><button class="iconbtn small" onclick="copyText(\'' + esc(url).replace(/\x27/g,"&#39;") + '\')">&#128203;</button></div>';
}

function renderServer() {
  var s = summary.server;
  var t = summary.tunnel;
  var html = '<div class="grid cols2">';

  // Server card
  html += '<div class="card"><h2>&#127916; Server</h2><div class="hint">Stremio addon server and network endpoints.</div>';
  if (s.status === "Running") {
    html += '<div class="row"><span class="badge green">&#9679; Running</span><span class="muted">' + s.pluginCount + ' plugin(s)</span></div>';
    html += urlBox("LAN", s.lanUrl || ("http://" + s.ipAddress + ":" + s.port + "/manifest.json"));
    if (s.localhostUrl) html += urlBox("Localhost", s.localhostUrl);
    if (t.stremioMode && s.stremioModeUrl) html += urlBox("Tunnel URL", s.stremioModeUrl);
    html += '<div class="row" style="margin-top:14px">';
    html += '<button class="primary" onclick="act(\'/server/restart\',{method:\'POST\',body:\'{}\'}, \'Restarting\u2026\')">Restart</button>';
    html += '<button class="danger" onclick="act(\'/server/stop\',{method:\'POST\',body:\'{}\'}, \'Stopping\u2026\')">Stop</button>';
    html += '<a class="pill" href="/manifest.json" target="_blank">Manifest</a>';
    html += '</div>';
  } else if (s.status === "Starting") {
    html += '<div class="row"><span class="badge amber"><span class="loader"></span>&nbsp; ' + esc(s.message || "Starting\u2026") + '</span></div>';
  } else if (s.status === "Error") {
    html += '<div class="banner err">' + esc(s.message) + '</div>';
    html += '<button class="primary" onclick="act(\'/server/start\',{method:\'POST\',body:\'{}\'}, \'Starting\u2026\')">Start server</button>';
  } else {
    html += '<div class="badge gray">Stopped</div><div class="muted" style="margin-top:8px">The web admin stays reachable while the addon server is off.</div>';
    html += '<div style="margin-top:14px"><button class="primary" onclick="act(\'/server/start\',{method:\'POST\',body:\'{}\'}, \'Starting\u2026\')">Start server</button></div>';
  }
  html += '</div>';

  // Tunnel card
  html += '<div class="card"><h2>&#9925; Stremio Mode &amp; Tunnel</h2><div class="hint">Stremio Web needs HTTPS. Cloudflare tunnel exposes your bridge publicly.</div>';
  html += '<div class="row"><span class="muted">Stremio mode</span><div class="spacer"></div>';
  html += '<label class="switch"><input type="checkbox" ' + (t.stremioMode ? "checked" : "") + ' onchange="toggleStremioMode(this.checked)"><span class="track"></span></label></div>';
  if (t.downloadProgress !== null && t.downloadProgress !== undefined) {
    html += '<div class="progress"><div style="width:' + Math.round((t.downloadProgress||0)*100) + '%"></div></div>';
    html += '<div class="muted" style="margin-top:6px">Downloading cloudflared\u2026 ' + Math.round((t.downloadProgress||0)*100) + '%</div>';
  } else if (t.activeUrl) {
    html += urlBox("Tunnel", t.activeUrl);
    html += '<div class="row" style="margin-top:10px"><span class="badge green">Tunnel active</span></div>';
    html += '<div style="margin-top:10px"><button class="ghost" onclick="act(\'/tunnel/stop\',{method:\'POST\',body:\'{}\'}, \'Stopping tunnel\u2026\')">Stop tunnel</button></div>';
  } else {
    html += '<div class="row" style="margin-top:10px"><span class="badge ' + (t.cloudflaredInstalled ? "green" : "gray") + '">' + (t.cloudflaredInstalled ? "cloudflared ready" : "cloudflared not installed") + '</span></div>';
    html += '<div style="margin-top:10px"><button class="primary" onclick="act(\'/tunnel/start\',{method:\'POST\',body:\'{}\'}, \'Starting tunnel\u2026\')">' + (t.cloudflaredInstalled ? "Start tunnel" : "Download &amp; start tunnel") + '</button></div>';
  }
  html += '</div></div>';


  el("view").innerHTML = html;
}


function toggleStremioMode(enabled) {
  act("/stremio-mode", {method:"POST", body: JSON.stringify({enabled: enabled})},
    enabled ? "Enabling Stremio mode\u2026" : "Disabling Stremio mode\u2026");
}

// ── Extensions tab ────────────────────────────────────────────────────────────

function renderExtensions() {
  var html = "";

  // ── Repos section ──
  html += '<div class="card"><h2>&#128230; Repositories</h2><div class="hint">CloudStream extension repos. All repos added here are saved globally.</div>';
  html += '<div class="row" style="margin-bottom:12px">';
  html += '<button class="primary small" onclick="openAddRepoModal()">&#43; Add repo</button>';

  html += '<button class="ghost small" onclick="act(\'/repos/refresh\',{method:\'POST\',body:\'{}\'}, \'Refreshing\u2026\')">' + (summary.refreshing ? '<span class="loader"></span> ' : "") + 'Refresh all</button>';
  html += '</div>';

  if (summary.repos.length) {
    html += '<div class="grid" style="gap:9px">';
    summary.repos.forEach(function(r) {
      html += '<div class="reporow">';
      if (r.iconUrl) {
        html += '<img class="ricon" src="' + esc(r.iconUrl) + '" onerror="this.style.display=\'none\';this.nextElementSibling.style.display=\'flex\'" alt="">';
        html += '<div class="rletter" style="display:none">' + esc((r.name||'?').charAt(0).toUpperCase()) + '</div>';
      } else html += '<div class="rletter">' + esc((r.name||'?').charAt(0).toUpperCase()) + '</div>';
      html += '<div class="rinfo"><div class="rname">' + esc(r.name||r.url) + '</div><div class="rurl">' + esc(r.url) + '</div></div>';
      html += '<div class="raction">';
      if (r.isLoading) html += '<span class="badge amber"><span class="loader"></span></span>';
      else if (r.error) html += '<span class="badge red" title="' + esc(r.error) + '">error</span>';
      else html += '<span class="badge gray">' + r.pluginCount + '</span>';
      if (!r.isLoading && !r.error) {
        html += '<button class="small success" onclick="installAllFromRepo(\'' + esc(r.url).replace(/\'/g,"%27") + '\')">Install all</button>';
      }
      html += '<button class="small danger" onclick="removeRepo(\'' + esc(r.url).replace(/\'/g,"%27") + '\')">' + '&#x2715; Remove</button>';
      html += '</div></div>';




    });
    html += '</div>';
  } else {
    html += '<div class="empty">No repositories added yet.</div>';
  }
  html += '</div>';

  // ── Plugin catalog ──
  html += '<div class="card" style="margin-top:14px"><h2>&#129490; Extensions</h2><div class="hint">Install, update and configure extensions from your repos.</div>';
  html += '<div class="pillrow">';
  html += '<span class="pill' + (repoFilter === "all" ? " active" : "") + '" onclick="setRepoFilter(\'all\')">All</span>';
  html += '<span class="pill' + (repoFilter === "installed" ? " active" : "") + '" onclick="setRepoFilter(\'installed\')">Installed</span>';
  summary.repos.forEach(function(r) {
    html += '<span class="pill' + (repoFilter === r.url ? " active" : "") + '" onclick="setRepoFilter(\'' + esc(r.url).replace(/\x27/g,"%27") + '\')">' + esc(r.name || r.url) + '</span>';
  });
  html += '</div>';
  html += '<input type="text" placeholder="Search extensions\u2026" value="' + esc(searchQuery) + '" oninput="setSearch(this.value)" style="margin-bottom:14px">';

  if (!plugins) {
    html += '<div class="empty"><span class="loader"></span> loading\u2026</div>';
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
      html += '<div class="empty">No extensions match. Add a repository above.</div>';
    } else {
      html += '<div class="plugins">';
      list.forEach(function(p) {
        html += renderPluginCard(p);
      });
      html += '</div>';
    }
  }
  html += '</div>';
  el("view").innerHTML = html;
}

function renderPluginCard(p) {
  var inst = null;
  if (summary) {
    for (var i = 0; i < summary.installedPlugins.length; i++) {
      if (summary.installedPlugins[i].internalName === p.internalName) { inst = summary.installedPlugins[i]; break; }
    }
  }
  var html = '<div class="pcard" id="pc-' + esc(p.internalName) + '">';
  html += '<div class="top">';
  if (p.iconUrl) {
    html += '<img class="picon" src="' + esc(p.iconUrl) + '" onerror="this.style.display=\'none\';this.nextElementSibling.style.display=\'flex\'" alt="">';
    html += '<div class="pletter" style="display:none">' + esc(p.displayName.charAt(0).toUpperCase()) + '</div>';
  } else html += '<div class="pletter">' + esc(p.displayName.charAt(0).toUpperCase()) + '</div>';
  html += '<div style="min-width:0"><div class="name">' + esc(p.displayName) + '</div>';
  html += '<div class="meta">v' + p.version + (p.language ? " \xb7 " + esc(p.language) : "") + (p.authors && p.authors.length ? " \xb7 " + esc(p.authors.join(", ")) : "") + " \xb7 " + esc(p.repoName) + '</div></div></div>';
  if (p.description) html += '<div class="desc">' + esc(p.description) + '</div>';

  html += '<div class="actions">';
  if (p.installState === "Installing") {
    html += '<span class="badge amber"><span class="loader"></span>&nbsp; ' + esc(p.installProgress || "Installing\u2026") + '</span>';
  } else if (p.installState === "Failed") {
    html += '<span class="badge red" title="' + esc(p.error||"") + '">Failed</span>';
    html += '<button class="small primary" onclick="installPlugin(\'' + esc(p.internalName) + '\')">Retry</button>';
  } else if (p.installed && p.updateAvailable) {
    html += '<span class="badge amber">Update \u2192 v' + p.version + '</span>';
    html += '<button class="small primary" onclick="installPlugin(\'' + esc(p.internalName) + '\')">Update</button>';
  } else if (p.installed) {
    html += '<span class="badge green">v' + p.installedVersion + ' installed</span>';
  } else {
    html += '<button class="small primary" onclick="installPlugin(\'' + esc(p.internalName) + '\')">Install</button>';
  }

  if (p.installed) {
    if (inst && inst.hasSettings) {
      html += '<button class="small ghost" onclick="toggleSettingsDrawer(\'' + esc(p.internalName) + '\')">&#9881; Settings</button>';
    }
    if (inst) {
      html += '<label class="switch" title="' + (inst.enabled ? "Disable" : "Enable") + '"><input type="checkbox" ' + (inst.enabled ? "checked" : "") + ' onchange="togglePlugin(\'' + esc(p.internalName) + '\')"><span class="track"></span></label>';
    }
    html += '<button class="small danger" onclick="uninstallPlugin(\'' + esc(p.internalName) + '\')">Uninstall</button>';
  }
  html += '</div>';

  // No inline drawer — settings open as a modal popup
  html += '</div>';
  return html;
}

function setRepoFilter(f) { repoFilter = f; renderExtensions(); }
function setSearch(q) { searchQuery = q; renderExtensions(); }

function normalizeRepoUrl(raw) {
  raw = (raw || "").trim();
  if (!raw) return null;

  // Single-word shortcode like "Hexated" or "!pymd" — no slashes, dots, or protocol.
  // Pass through unchanged; the server resolves via cutt.ly / py.md.
  if (!raw.includes("/") && !raw.includes(".") && !raw.includes(":")) {
    return raw;
  }

  // GitHub shorthand: "user/repo" or "user/repo/branch" — no dots, no protocol
  if (!raw.includes("://") && raw.indexOf(".") < 0) {
    var parts = raw.split("/").filter(Boolean);
    if (parts.length === 2) {
      return "https://raw.githubusercontent.com/" + parts[0] + "/" + parts[1] + "/builds/repo.json";
    } else if (parts.length >= 3) {
      return "https://raw.githubusercontent.com/" + parts[0] + "/" + parts[1] + "/" + parts[2] + "/repo.json";
    }
  }

  // github.com/user/repo → raw.githubusercontent.com
  if (raw.indexOf("github.com") >= 0 && raw.indexOf("raw.githubusercontent.com") < 0 && raw.indexOf(".json") < 0) {
    var stripped = raw.replace(/^https?:\/\//, "").replace(/^\/+/, "");
    var seg = stripped.replace(/^github\.com\//, "").split("/").filter(Boolean);
    if (seg.length >= 2) {
      return "https://raw.githubusercontent.com/" + seg[0] + "/" + seg[1] + "/builds/repo.json";
    }
  }

  // Add https:// if protocol missing
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
  // Show resolved URL briefly in the input so user sees what was sent
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
      // Reload plugin list immediately to show Installing states
      loadPlugins();
      poll();
    }).catch(function(e) { toast("Action failed: " + e.message); });
}

function installPlugin(id) {
  // Optimistically mark as Installing in UI
  updatePluginCardState(id, "Installing", null, null);
  api("/plugins/install", {method:"POST", body: JSON.stringify({internalName: id})})
    .then(function() {
      // Start polling aggressively to pick up install state
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
      // Backend returns updated plugin list directly
      if (Array.isArray(newPlugins)) {
        plugins = newPlugins;
        if (tab === "extensions") renderExtensions();
      }
      poll();
    }).catch(function(e) { toast("Toggle failed: " + e.message); });
}

function updatePluginCardState(id, state, progress, error) {
  // Optimistic in-place card update without full re-render
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
  // If already open for same plugin, close it
  if (modal.classList.contains("open") && settingsModalPluginId === id) {
    closeSettingsModal();
    return;
  }
  settingsModalPluginId = id;
  // Find plugin name for title
  var pluginName = id;
  if (plugins) {
    var pm = plugins.find(function(x) { return x.internalName === id; });
    if (pm) pluginName = pm.name || id;
  }
  box.innerHTML = '<div class="modal-title">' + esc(pluginName) + ' Settings<button class="modal-close" onclick="closeSettingsModal()" title="Close">&times;</button></div>' +
    '<div class="muted" style="text-align:center;padding:20px"><span class="loader"></span> Loading settings…</div>';
  modal.classList.add("open");
  // Discover then load settings
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

// Close modals on Escape key
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
  // Re-read current set from DOM (since we don't track st state in drawer)
  // Just serialize based on checkbox state
  var form = checkbox.closest(".checkgrid");
  if (!form) return;
  var selected = [];
  form.querySelectorAll("input[type=checkbox]").forEach(function(cb) {
    var label = cb.nextElementSibling ? cb.nextElementSibling.textContent : "";
    if (disabledStyle ? !cb.checked : cb.checked) selected.push(cb.value || label.trim());
  });
  // Since we lost the option labels, just use the item directly
  // Simpler approach: just call saveSettingValue from checked state
  var allChecks = form.querySelectorAll("input[type=checkbox]");
  var cur = [];
  allChecks.forEach(function(cb) {
    if (disabledStyle ? !cb.checked : cb.checked) {
      // get the item value from the onchange attr - approximate
    }
  });
  // Simplest correct approach: read checked items from siblings, item is already known
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

function renderLogs() {
  var html = '<div class="card"><h2>&#128203; Logs</h2><div class="hint">Live application log \u2014 auto-updates every 3s.</div>';
  html += '<div class="logs-wrap"><div class="logs-toolbar">';
  html += '<button class="ghost small" onclick="copyLogs()">Copy all</button>';
  html += '<button class="ghost small" onclick="clearLogs()">Clear</button>';
  html += '<label class="row" style="gap:6px;font-size:12px;color:var(--text2)"><input type="checkbox" id="autoscrollcb" ' + (autoScroll?"checked":"") + ' onchange="autoScroll=this.checked"> auto-scroll</label>';
  html += '</div><div class="loglist" id="logsbox">';
  html += renderAllLogLines();
  html += '</div></div></div>';
  el("view").innerHTML = html;
  // Track the last rendered timestamp so appendNewLogLines only adds truly new entries
  lastLogTimestamp = logsData.length > 0 ? (logsData[logsData.length - 1].timestamp || 0) : 0;
  var box = el("logsbox");
  if (box && autoScroll) box.scrollTop = box.scrollHeight;
}

function renderAllLogLines() {
  if (!logsData.length) return '<div class="empty">No log entries yet.</div>';
  return logsData.map(function(l) { return logLine(l); }).join("");
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
  // Detect new entries by timestamp (works even with rolling server-side buffer)
  var newLines = logsData.filter(function(l) { return (l.timestamp || 0) > lastLogTimestamp; });
  if (!newLines.length) return;
  // Remove empty placeholder if present
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
  lastLogTimestamp = newLines[newLines.length - 1].timestamp || lastLogTimestamp;
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
  html += '<div class="card"><h2>&#8505; About</h2><div class="hint">CNCVerse Bridge \u2014 run CloudStream extensions on Stremio and every Stremio-compatible client.</div>';
  html += '<div class="kv">';
  html += '<div class="k">Version</div><div>' + esc(summary.version) + '</div>';
  html += '<div class="k">Mode</div><div>' + (summary.headless ? "Headless server" : "Desktop") + '</div>';
  html += '<div class="k">Platform</div><div>' + esc(summary.platform) + '</div>';
  html += '<div class="k">Loaded plugins</div><div>' + summary.server.pluginCount + '</div>';
  html += '<div class="k">Repositories</div><div>' + summary.repos.length + '</div>';
  html += '</div>';
  html += '<div class="row" style="margin-top:14px">';
  html += '<a class="pill" href="https://github.com/NivinCNC/CNCVerse-Bridge" target="_blank" rel="noreferrer">GitHub</a>';
  html += '<a class="pill" href="https://t.me/cncverse" target="_blank" rel="noreferrer">Telegram</a>';
  html += '<a class="pill" href="https://cncverse.pages.dev" target="_blank" rel="noreferrer">Support</a>';
  html += '</div></div>';

  html += '<div class="card"><h2>&#8635; Updates</h2><div class="hint">OTA update from GitHub releases. Extensions are also checked hourly automatically.</div>';
  html += '<button class="primary" onclick="checkUpdate()">Check for updates</button>';
  if (u && u.tagName) {
    html += '<div class="banner" style="margin-top:12px">New version: <b>' + esc(u.tagName) + '</b></div>';
    if (u.body) html += '<div class="muted" style="margin-top:6px;white-space:pre-wrap;max-height:160px;overflow:auto">' + esc(u.body) + '</div>';
    if (u.downloadProgress !== null && u.downloadProgress !== undefined) {
      html += '<div class="progress" style="margin-top:10px"><div style="width:' + Math.round((u.downloadProgress||0)*100) + '%"></div></div>';
      html += '<div class="muted" style="margin-top:6px">Downloading\u2026 ' + Math.round((u.downloadProgress||0)*100) + '%</div>';
    } else {
      html += '<div style="margin-top:10px"><button class="primary" onclick="act(\'/update/apply\',{method:\'POST\',body:\'{}\'}, \'Downloading update\u2026\')">Download &amp; Install</button></div>';
    }
  } else if (u) {
    html += '<div class="banner" style="margin-top:12px">You are on the latest version.</div>';
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

// Plugin refresh: every 5s when on extensions tab (to pick up install states)
setInterval(function() { if (tab === "extensions") loadPlugins(); }, 5000);

// Hourly client-side refresh trigger (belt-and-suspenders alongside server-side check)
setTimeout(function() {
  setInterval(function() {
    api("/repos/refresh", {method:"POST", body:"{}"})
      .then(function() { loadPlugins(); })
      .catch(function() {});
  }, 60 * 60 * 1000);
}, 60 * 60 * 1000);
</script>
</body>
</html>"""
}
