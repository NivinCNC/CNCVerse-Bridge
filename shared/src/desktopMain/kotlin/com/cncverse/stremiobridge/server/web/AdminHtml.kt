package com.cncverse.stremiobridge.server.web

/**
 * The single-page web admin UI served at `/admin`. Pure HTML + CSS + vanilla
 * JS (no build step, no external assets) so it can be embedded as a string in
 * both the desktop app and the headless server jar. Mirrors the Compose
 * desktop UI: Server (status/URLs/tunnel), Extensions (repos + plugins),
 * Settings (per-plugin, discovered at runtime) and Logs.
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
<style>
:root{
  --bg:#000000; --surface:#0a0a0a; --card:#111111; --card2:#181818;
  --border:#222222; --divider:#1f1f1f;
  --violet:#8b5cf6; --violet400:#a78bfa; --violet300:#c4b5fd; --violet-glow:rgba(139,92,246,.2);
  --text:#ffffff; --text2:#9ca3af; --muted:#6b7280;
  --green:#4ade80; --red:#f87171; --amber:#fbbf24; --blue:#60a5fa;
}
*{box-sizing:border-box;margin:0;padding:0}
html,body{background:var(--bg);color:var(--text)}
body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Ubuntu,'Noto Sans',sans-serif;font-size:14px;line-height:1.5}
a{color:var(--violet400);text-decoration:none}
button{font:inherit;cursor:pointer;border:none;border-radius:10px;padding:9px 16px;background:var(--card2);color:var(--text);transition:background .15s,opacity .15s}
button:hover{background:#202020}
button:disabled{opacity:.45;cursor:default}
button.primary{background:var(--violet);color:#fff;font-weight:600}
button.primary:hover{background:#7c3aed}
button.danger{background:rgba(248,113,113,.12);color:var(--red);border:1px solid rgba(248,113,113,.35)}
button.ghost{background:transparent;border:1px solid var(--border);color:var(--text2)}
button.ghost:hover{border-color:var(--violet);color:var(--text)}
button.small{padding:5px 12px;border-radius:8px;font-size:12.5px}
input[type=text],input[type=password],input[type=number]{font:inherit;background:var(--card2);border:1px solid var(--border);border-radius:10px;color:var(--text);padding:9px 12px;outline:none;width:100%}
input:focus{border-color:var(--violet)}
.switch{position:relative;display:inline-block;width:44px;height:24px;flex:0 0 auto}
.switch input{opacity:0;width:0;height:0}
.switch .track{position:absolute;inset:0;background:var(--card2);border:1px solid var(--border);border-radius:999px;transition:.2s}
.switch .track:before{content:"";position:absolute;height:18px;width:18px;left:2px;top:2px;background:var(--muted);border-radius:50%;transition:.2s}
.switch input:checked + .track{background:var(--violet);border-color:var(--violet)}
.switch input:checked + .track:before{transform:translateX(20px);background:#fff}
header{display:flex;align-items:center;gap:14px;padding:16px 22px;border-bottom:1px solid var(--divider);position:sticky;top:0;background:rgba(0,0,0,.92);backdrop-filter:blur(8px);z-index:50}
.logo{width:34px;height:34px;border-radius:10px;background:linear-gradient(135deg,#7c3aed,#a78bfa);display:flex;align-items:center;justify-content:center;font-weight:800;font-size:11px;color:#fff;letter-spacing:-.5px}
header h1{font-size:17px;font-weight:700}
header .sub{font-size:12px;color:var(--muted)}
.statuspill{margin-left:auto;display:flex;align-items:center;gap:8px;background:var(--card);border:1px solid var(--border);border-radius:999px;padding:6px 14px;font-size:12.5px;font-weight:600}
.dot{width:9px;height:9px;border-radius:50%;display:inline-block}
.dot.green{background:var(--green);box-shadow:0 0 8px var(--green)}
.dot.amber{background:var(--amber);box-shadow:0 0 8px var(--amber);animation:pulse 1.2s infinite}
.dot.red{background:var(--red);box-shadow:0 0 8px var(--red)}
.dot.gray{background:var(--muted)}
@keyframes pulse{50%{opacity:.4}}
nav{display:flex;gap:4px;padding:10px 22px 0;border-bottom:1px solid var(--divider);overflow-x:auto}
nav button{background:transparent;color:var(--text2);border-radius:10px 10px 0 0;padding:10px 18px;font-weight:600;border-bottom:2px solid transparent}
nav button.active{color:var(--text);border-bottom-color:var(--violet)}
nav button:hover{color:var(--text)}
main{padding:22px;max-width:1060px;margin:0 auto}
.grid{display:grid;gap:16px}
.cols2{grid-template-columns:1fr 1fr}
@media(max-width:820px){.cols2{grid-template-columns:1fr}}
.card{background:var(--card);border:1px solid var(--border);border-radius:16px;padding:18px 20px}
.card h2{font-size:15px;font-weight:700;display:flex;align-items:center;gap:8px;margin-bottom:4px}
.card .hint{font-size:12.5px;color:var(--text2);margin-bottom:14px}
.row{display:flex;align-items:center;gap:12px;flex-wrap:wrap}
.spacer{flex:1}
.muted{color:var(--text2);font-size:12.5px}
.urlbox{display:flex;align-items:center;gap:8px;background:var(--card2);border:1px solid var(--border);border-radius:10px;padding:8px 12px;margin-top:8px}
.urlbox code{font-family:ui-monospace,'Cascadia Code',Menlo,Consolas,monospace;font-size:12.5px;color:var(--violet300);word-break:break-all;flex:1}
.iconbtn{background:transparent;padding:6px 10px;color:var(--text2)}
.progress{height:8px;border-radius:99px;background:var(--surface);overflow:hidden;margin-top:10px}
.progress > div{height:100%;background:var(--violet);border-radius:99px;transition:width .3s}
.badge{display:inline-block;font-size:11px;font-weight:700;padding:3px 10px;border-radius:999px;letter-spacing:.3px}
.badge.green{background:rgba(74,222,128,.13);color:var(--green)}
.badge.red{background:rgba(248,113,113,.13);color:var(--red)}
.badge.amber{background:rgba(251,191,36,.13);color:var(--amber)}
.badge.violet{background:var(--violet-glow);color:var(--violet400)}
.badge.gray{background:#1a1a1a;color:var(--text2)}
.badge.blue{background:rgba(96,165,250,.13);color:var(--blue)}
.pillrow{display:flex;gap:8px;flex-wrap:wrap;margin:12px 0}
.pill{font-size:12px;padding:5px 13px;border-radius:999px;background:var(--card2);border:1px solid var(--border);color:var(--text2);cursor:pointer}
.pill.active{background:var(--violet-glow);border-color:var(--violet);color:var(--violet300);font-weight:600}
.plugins{display:grid;gap:12px;grid-template-columns:repeat(auto-fill,minmax(320px,1fr))}
.pcard{background:var(--card);border:1px solid var(--border);border-radius:14px;padding:16px;display:flex;flex-direction:column;gap:10px}
.pcard .top{display:flex;gap:12px;align-items:flex-start}
.picon{width:46px;height:46px;border-radius:12px;object-fit:cover;background:var(--card2);border:1px solid var(--border);flex:0 0 auto}
.pletter{width:46px;height:46px;border-radius:12px;background:linear-gradient(135deg,#312e81,#7c3aed);display:flex;align-items:center;justify-content:center;font-size:19px;font-weight:800;color:#fff;flex:0 0 auto}
.pcard .name{font-weight:700;font-size:14.5px}
.pcard .meta{font-size:11.5px;color:var(--muted);margin-top:2px}
.pcard .desc{font-size:12.5px;color:var(--text2);display:-webkit-box;-webkit-line-clamp:3;-webkit-box-orient:vertical;overflow:hidden}
.pcard .actions{display:flex;gap:8px;margin-top:auto;flex-wrap:wrap;align-items:center}
.category{margin:22px 0 10px;color:var(--violet400);font-size:11px;font-weight:800;letter-spacing:1.2px;text-transform:uppercase;border-bottom:1px solid rgba(139,92,246,.15);padding-bottom:6px}
.setting{background:var(--card2);border:1px solid rgba(34,34,34,.6);border-radius:12px;padding:14px 16px}
.setting .label{font-weight:600;font-size:14px}
.setting .desc2{font-size:12px;color:var(--text2);margin:2px 0 10px}
.checkgrid{display:grid;grid-template-columns:1fr 1fr;gap:4px 16px}
.checkrow{display:flex;align-items:center;gap:9px;padding:4px 0;font-size:13px}
.logs{background:#050505;border:1px solid var(--border);border-radius:14px;padding:14px;font-family:ui-monospace,Menlo,Consolas,monospace;font-size:12px;max-height:62vh;overflow-y:auto}
.logline{padding:2.5px 6px;border-radius:6px;white-space:pre-wrap;word-break:break-word}
.logline .t{color:var(--muted);margin-right:8px}
.logline.INFO{color:#c7c7d0}
.logline.WARN{color:var(--amber);background:rgba(251,191,36,.05)}
.logline.ERROR{color:var(--red);background:rgba(248,113,113,.06)}
.toast{position:fixed;bottom:22px;left:50%;transform:translateX(-50%);background:#161616;border:1px solid var(--violet);color:var(--text);padding:10px 20px;border-radius:12px;font-size:13px;box-shadow:0 8px 30px rgba(124,58,237,.35);opacity:0;transition:opacity .25s;pointer-events:none;z-index:99}
.toast.show{opacity:1}
.banner{background:var(--violet-glow);border:1px solid rgba(139,92,246,.4);border-radius:12px;padding:12px 16px;font-size:12.5px;color:var(--violet300);margin-bottom:14px}
.loader{display:inline-block;width:14px;height:14px;border:2px solid var(--violet);border-top-color:transparent;border-radius:50%;animation:spin .8s linear infinite;vertical-align:-2px}
@keyframes spin{to{transform:rotate(360deg)}}
.pluginpick{display:flex;gap:10px;flex-wrap:wrap;margin-bottom:16px}
.pluginpick .pill{display:flex;align-items:center;gap:8px}
.pluginpick img{width:18px;height:18px;border-radius:5px;object-fit:cover}
.kv{display:grid;grid-template-columns:130px 1fr;gap:6px 14px;font-size:13px}
.kv .k{color:var(--muted)}
.empty{padding:40px 20px;text-align:center;color:var(--text2);font-size:13px}
select{font:inherit;background:var(--card2);border:1px solid var(--border);border-radius:10px;color:var(--text);padding:9px 12px;width:100%;outline:none}
</style>
</head>
<body>
<header>
  <div class="logo">CNC</div>
  <div>
    <h1>CNCVerse Bridge</h1>
    <div class="sub" id="subtitle">web admin</div>
  </div>
  <div class="statuspill" id="statuspill"><span class="dot gray"></span><span id="statustext">connecting…</span></div>
</header>
<nav id="tabs">
  <button data-tab="server" class="active">Server</button>
  <button data-tab="extensions">Extensions</button>
  <button data-tab="settings">Settings</button>
  <button data-tab="logs">Logs</button>
  <button data-tab="about">About</button>
</nav>
<main id="view"></main>
<div class="toast" id="toast"></div>
<script>
"use strict";

var TOKEN = new URLSearchParams(window.location.search).get("token") || "";
var BASE = "/api/admin";
var tab = "server";
var summary = null;
var plugins = null;
var logs = null;
var settingsData = null;
var settingsPluginId = null;
var settingsDirty = false;
var repoFilter = "all";
var searchQuery = "";
var pollTimer = null;
var logTimer = null;

// ── utilities ────────────────────────────────────────────────────────────────

function api(path, opts) {
  opts = opts || {};
  var url = BASE + path;
  if (TOKEN) url += (url.indexOf("?") >= 0 ? "&" : "?") + "token=" + encodeURIComponent(TOKEN);
  opts.headers = Object.assign({"Content-Type": "application/json"}, opts.headers || {});
  return fetch(url, opts).then(function (r) {
    if (r.status === 401) { window.location.href = "/admin?token=" + encodeURIComponent(TOKEN) + "&auth=0"; throw new Error("unauthorized"); }
    if (!r.ok) throw new Error("HTTP " + r.status);
    return r.json();
  });
}

function esc(s) {
  if (s === null || s === undefined) return "";
  return String(s).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");
}

function toast(msg) {
  var el = document.getElementById("toast");
  el.textContent = msg;
  el.classList.add("show");
  clearTimeout(el._t);
  el._t = setTimeout(function () { el.classList.remove("show"); }, 2200);
}

function copyText(text) {
  if (navigator.clipboard && navigator.clipboard.writeText) {
    navigator.clipboard.writeText(text).then(function () { toast("Copied to clipboard"); });
  } else {
    var ta = document.createElement("textarea");
    ta.value = text; document.body.appendChild(ta); ta.select();
    document.execCommand("copy"); document.body.removeChild(ta);
    toast("Copied to clipboard");
  }
}

function el(id) { return document.getElementById(id); }

function fmtTime(ts) {
  var d = new Date(ts);
  return d.toLocaleTimeString([], {hour12:false});
}

// ── polling ───────────────────────────────────────────────────────────────────

function poll() {
  api("/summary").then(function (s) {
    summary = s;
    renderStatusPill();
    render();
  }).catch(function () {
    var pill = el("statuspill");
    if (pill) pill.innerHTML = '<span class="dot red"></span><span id="statustext">connection lost</span>';
  });
}

function pollLogs() {
  api("/logs").then(function (l) {
    logs = l;
    if (tab === "logs") renderLogsAppend();
  }).catch(function () {});
}

// ── header status ────────────────────────────────────────────────────────────

function renderStatusPill() {
  if (!summary) return;
  var s = summary.server;
  var dot = "gray", txt = s.status;
  if (s.status === "Running") { dot = "green"; txt = "Running · port " + s.port; }
  else if (s.status === "Starting") { dot = "amber"; txt = "Starting…"; }
  else if (s.status === "Error") { dot = "red"; txt = "Error"; }
  el("statuspill").innerHTML = '<span class="dot ' + dot + '"></span><span id="statustext">' + esc(txt) + "</span>";
  el("subtitle").textContent =
    (summary.headless ? "server (headless) · " : "desktop · ") +
    "v" + summary.version + (summary.tokenRequired ? " · token protected" : "");
}

// ── render dispatch ───────────────────────────────────────────────────────────

function render() {
  if (!summary) return;
  if (tab === "server") renderServer();
  else if (tab === "extensions") renderExtensions();
  else if (tab === "settings") renderSettings();
  else if (tab === "logs") renderLogs();
  else if (tab === "about") renderAbout();
}

// ── Server tab ───────────────────────────────────────────────────────────────

function urlBox(label, url) {
  return '<div class="urlbox"><span class="muted">' + esc(label) + '</span><code>' + esc(url) +
    '</code><button class="small ghost" onclick="copyText(\'' + esc(url).replace(/'/g, "&#39;") + '\')">Copy</button></div>';
}

function renderServer() {
  var s = summary.server;
  var t = summary.tunnel;
  var html = "";

  html += '<div class="grid cols2">';
  html += '<div class="card"><h2>Server</h2><div class="hint">The Stremio addon server and its endpoints.</div>';

  if (s.status === "Running") {
    html += '<div class="row"><span class="badge green">Running</span><span class="muted">' + s.pluginCount + " plugin(s) loaded · " + esc(summary.platform) + '</span></div>';
    html += urlBox("Addon URL", s.lanUrl || ("http://" + s.ipAddress + ":" + s.port + "/manifest.json"));
    if (s.localhostUrl) html += urlBox("Localhost", s.localhostUrl);
    if (summary.tunnel.stremioMode && s.stremioModeUrl) html += urlBox("Stremio mode", s.stremioModeUrl);
    html += '<div class="row" style="margin-top:14px">';
    html += '<button class="primary" onclick="act(\'/server/restart\',{},\'Restarting server…\')">Restart</button>';
    html += '<button class="danger" onclick="act(\'/server/stop\',{},\'Stopping server…\')">Stop</button>';
    html += '<a class="pill" style="text-decoration:none" href="/manifest.json">Open manifest</a>';
    html += '</div>';
  } else if (s.status === "Starting") {
    html += '<div class="row"><span class="badge amber"><span class="loader"></span>&nbsp; ' + esc(s.message || "Starting…") + '</span></div>';
  } else if (s.status === "Error") {
    html += '<div class="row"><span class="badge red">Error</span></div>';
    html += '<div class="banner">' + esc(s.message) + '</div>';
    html += '<div class="row"><button class="primary" onclick="act(\'/server/start\',{},\'Starting server…\')">Start server</button></div>';
  } else {
    html += '<div class="row"><span class="badge gray">Stopped</span></div>';
    html += '<div class="muted">The addon server is not running. The web admin stays reachable on this port.</div>';
    html += '<div class="row" style="margin-top:14px"><button class="primary" onclick="act(\'/server/start\',{},\'Starting server…\')">Start server</button></div>';
  }
  html += '</div>';

  // ── Stremio mode + tunnel card ──
  html += '<div class="card"><h2>Stremio Mode &amp; Tunnel</h2><div class="hint">Stremio Web needs an HTTPS Cloudflare tunnel for smooth playback. Exposes your bridge on a public URL.</div>';
  html += '<div class="row"><span class="muted">Stremio mode</span><div class="spacer"></div>';
  html += '<label class="switch"><input type="checkbox" ' + (t.stremioMode ? "checked" : "") + ' onchange="toggleStremioMode(this.checked)"><span class="track"></span></label></div>';

  if (t.downloadProgress !== null && t.downloadProgress !== undefined) {
    html += '<div class="progress"><div style="width:' + Math.round((t.downloadProgress || 0) * 100) + '%"></div></div>';
    html += '<div class="muted" style="margin-top:6px">Downloading cloudflared… ' + Math.round((t.downloadProgress || 0) * 100) + "%</div>";
  } else if (t.activeUrl) {
    html += urlBox("Tunnel URL", t.activeUrl);
    html += '<div class="row" style="margin-top:12px"><span class="badge green">Tunnel active</span><span class="badge ' + (t.cloudflaredInstalled ? "green" : "gray") + '">' + (t.cloudflaredInstalled ? "cloudflared installed" : "cloudflared missing") + '</span></div>';
    html += '<div class="row" style="margin-top:12px"><button class="ghost" onclick="act(\'/tunnel/stop\',{},\'Stopping tunnel…\')">Stop tunnel</button></div>';
  } else {
    html += '<div class="row" style="margin-top:12px"><span class="badge ' + (t.cloudflaredInstalled ? "green" : "gray") + '">' + (t.cloudflaredInstalled ? "cloudflared installed" : "cloudflared not installed") + '</span></div>';
    html += '<div class="row" style="margin-top:12px"><button class="primary" onclick="act(\'/tunnel/start\',{},\'Starting tunnel…\')">' + (t.cloudflaredInstalled ? "Start tunnel" : "Download &amp; start tunnel") + '</button></div>';
  }
  html += '</div></div>';

  el("view").innerHTML = html;
}

function toggleStremioMode(enabled) {
  act("/stremio-mode", {method:"POST", body: JSON.stringify({enabled: enabled})},
      enabled ? "Enabling Stremio mode…" : "Disabling Stremio mode…");
}

// ── Extensions tab ────────────────────────────────────────────────────────────

function renderExtensions() {
  var html = "";
  html += '<div class="card"><h2>Repositories</h2><div class="hint">CloudStream extension repositories. Refresh to pick up newly published plugins.</div>';

  html += '<div class="row"><input type="text" id="repoinput" placeholder="https://raw.githubusercontent.com/…/repo.json" style="flex:1;min-width:240px" onkeydown="if(event.key===\'Enter\')addRepo()">';
  html += '<button class="primary" onclick="addRepo()">Add repo</button>';
  html += '<button class="ghost" onclick="act(\'/repos/refresh\',{},\'Refreshing repos…\')">' + (summary.refreshing ? '<span class="loader"></span> ' : "") + "Refresh</button></div>";

  if (summary.repos.length) {
    html += '<div class="grid" style="margin-top:14px">';
    summary.repos.forEach(function (r) {
      html += '<div class="setting" style="display:flex;gap:12px;align-items:center">';
      if (r.iconUrl) html += '<img class="picon" style="width:34px;height:34px" src="' + esc(r.iconUrl) + '" onerror="this.outerHTML=\'<div class=\\\'pletter\\\' style=\\\'width:34px;height:34px;font-size:14px\\\'>' + esc((r.name || "?").charAt(0).toUpperCase()) + '</div>\'">';
      else html += '<div class="pletter" style="width:34px;height:34px;font-size:14px">' + esc((r.name || "?").charAt(0).toUpperCase()) + "</div>";
      html += '<div style="flex:1;min-width:0"><div style="font-weight:600">' + esc(r.name) + '</div><div class="muted" style="font-size:11.5px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">' + esc(r.url) + "</div></div>";
      if (r.isLoading) html += '<span class="badge amber">loading…</span>';
      else if (r.error) html += '<span class="badge red" title="' + esc(r.error) + '">error</span>';
      else html += '<span class="badge gray">' + r.pluginCount + " plugins</span>";
      html += '<button class="small danger" onclick="removeRepo(\'' + esc(r.url).replace(/'/g, "%27") + '\')">Remove</button>';
      html += "</div>";
    });
    html += "</div>";
  } else {
    html += '<div class="empty">No repositories added yet.</div>';
  }
  html += "</div>";

  // ── plugin catalog ──
  html += '<div style="margin-top:18px" class="card"><h2>Extensions</h2><div class="hint">Install, update and configure CloudStream extensions.</div>';

  html += '<div class="pillrow">';
  html += '<span class="pill' + (repoFilter === "all" ? " active" : "") + '" onclick="setRepoFilter(\'all\')">All</span>';
  html += '<span class="pill' + (repoFilter === "installed" ? " active" : "") + '" onclick="setRepoFilter(\'installed\')">Installed</span>';
  summary.repos.forEach(function (r) {
    html += '<span class="pill' + (repoFilter === r.url ? " active" : "") + '" onclick="setRepoFilter(\'' + esc(r.url).replace(/'/g, "%27") + '\')">' + esc(r.name) + "</span>";
  });
  html += '</div>';

  html += '<input type="text" placeholder="Search extensions…" value="' + esc(searchQuery) + '" oninput="setSearch(this.value)" style="margin-bottom:14px">';

  if (!plugins) {
    html += '<div class="empty"><span class="loader"></span> loading extensions…</div>';
  } else {
    var list = plugins.filter(function (p) {
      if (repoFilter === "installed" && !p.installed) return false;
      if (repoFilter !== "all" && repoFilter !== "installed" && p.repoUrl !== repoFilter) return false;
      if (searchQuery) {
        var q = searchQuery.toLowerCase();
        if ((p.displayName + " " + (p.description || "") + " " + (p.language || "")).toLowerCase().indexOf(q) < 0) return false;
      }
      return true;
    });

    if (!list.length) {
      html += '<div class="empty">No extensions match. Add a repository above to browse its catalog.</div>';
    } else {
      html += '<div class="plugins">';
      list.forEach(function (p) {
        var inst = null;
        for (var i = 0; i < summary.installedPlugins.length; i++) {
          if (summary.installedPlugins[i].internalName === p.internalName) { inst = summary.installedPlugins[i]; break; }
        }
        html += '<div class="pcard">';
        html += '<div class="top">';
        if (p.iconUrl) html += '<img class="picon" src="' + esc(p.iconUrl) + '" onerror="this.outerHTML=\'<div class=\\\'pletter\\\'>' + esc(p.displayName.charAt(0).toUpperCase()) + '</div>\'">';
        else html += '<div class="pletter">' + esc(p.displayName.charAt(0).toUpperCase()) + "</div>";
        html += '<div style="min-width:0"><div class="name">' + esc(p.displayName) + "</div>";
        html += '<div class="meta">v' + p.version + (p.language ? " · " + esc(p.language) : "") + (p.authors && p.authors.length ? " · " + esc(p.authors.join(", ")) : "") + " · " + esc(p.repoName) + "</div></div></div>";
        if (p.description) html += '<div class="desc">' + esc(p.description) + "</div>";

        html += '<div class="actions">';
        if (p.installState === "Installing") {
          html += '<span class="badge amber"><span class="loader"></span>&nbsp; ' + esc(p.installProgress || "Installing…") + "</span>";
        } else if (p.installState === "Failed") {
          html += '<span class="badge red" title="' + esc(p.error || "") + '">Install failed</span>';
        } else if (p.installed && p.updateAvailable) {
          html += '<span class="badge amber">Update → v' + p.version + "</span>";
          html += '<button class="small primary" onclick="installPlugin(\'' + esc(p.internalName) + '\')">Update</button>';
        } else if (p.installed) {
          html += '<span class="badge green">v' + p.installedVersion + " installed</span>";
        } else {
          html += '<button class="small primary" onclick="installPlugin(\'' + esc(p.internalName) + '\')">Install</button>';
        }

        if (p.installed) {
          if (inst && inst.hasSettings) html += '<button class="small ghost" onclick="openTab(\'settings\');openPluginSettings(\'' + esc(p.internalName) + '\')">⚙ Settings</button>';
          if (inst) {
            var dis = !inst.enabled;
            html += '<label class="switch" title="' + (dis ? "Enable this plugin" : "Disable this plugin") + '"><input type="checkbox" ' + (inst.enabled ? "checked" : "") + ' onchange="togglePlugin(\'' + esc(p.internalName) + '\')"><span class="track"></span></label>';
          }
          html += '<button class="small danger" onclick="uninstallPlugin(\'' + esc(p.internalName) + '\')">Uninstall</button>';
        }
        html += "</div></div>";
      });
      html += "</div>";
    }
  }
  html += "</div>";
  el("view").innerHTML = html;
}

function setRepoFilter(f) { repoFilter = f; renderExtensions(); }
function setSearch(q) { searchQuery = q; renderExtensions(); }

function addRepo() {
  var input = el("repoinput");
  var url = (input.value || "").trim();
  if (!url) return;
  act("/repos/add", {method:"POST", body: JSON.stringify({url: url})}, "Adding repo…");
}

function removeRepo(url) {
  act("/repos/remove", {method:"POST", body: JSON.stringify({url: url})}, "Removing repo…");
}

function installPlugin(id) { act("/plugins/install", {method:"POST", body: JSON.stringify({internalName: id})}, "Installing…"); }
function uninstallPlugin(id) {
  if (!confirm("Uninstall this extension?")) return;
  act("/plugins/uninstall", {method:"POST", body: JSON.stringify({internalName: id})}, "Uninstalling…");
}
function togglePlugin(id) { act("/plugins/toggle", {method:"POST", body: JSON.stringify({internalName: id})}, null); }

// ── Settings tab ─────────────────────────────────────────────────────────────

function renderSettings() {
  var html = "";
  html += '<div class="card"><h2>Extension Settings</h2><div class="hint">Settings are discovered at runtime — open a plugin to enumerate what it reads.</div>';

  var installed = summary.installedPlugins;
  if (!installed.length) {
    html += '<div class="empty">No extensions installed yet. Install one from the Extensions tab first.</div></div>';
    el("view").innerHTML = html;
    return;
  }

  html += '<div class="pluginpick">';
  installed.forEach(function (p) {
    var active = settingsPluginId === p.internalName;
    html += '<span class="pill' + (active ? " active" : "") + '" onclick="openPluginSettings(\'' + esc(p.internalName) + '\')">';
    if (p.iconUrl) html += '<img src="' + esc(p.iconUrl) + '" onerror="this.style.display=\'none\'">';
    html += esc(p.displayName);
    if (!p.enabled) html += ' <span style="color:var(--red)">(disabled)</span>';
    if (p.updateAvailable) html += ' <span class="badge amber">update</span>';
    html += "</span>";
  });
  html += "</div>";

  if (!settingsPluginId) {
    html += '<div class="empty">Pick an extension above to manage its settings.</div></div>';
    el("view").innerHTML = html;
    return;
  }

  if (!settingsData) {
    html += '<div class="empty"><span class="loader"></span> discovering settings…</div></div>';
    el("view").innerHTML = html;
    return;
  }

  if (settingsDirty) {
    html += '<div class="banner">✓ Changes saved. Reload the plugin to apply the new provider configuration in real-time. <button class="small primary" style="margin-left:8px" onclick="applySettings()">Apply &amp; Reload</button></div>';
  }

  var sets = settingsData.settings;
  if (!sets.length) {
    html += '<div class="empty">This plugin has not exposed any configurable options yet.<br>Settings appear here automatically once the plugin reads them — press Discover again after using the plugin.</div>';
    html += '<div class="row" style="margin-top:12px"><button class="ghost" onclick="openPluginSettings(\'' + esc(settingsPluginId) + '\')">Discover again</button></div>';
  } else {
    var byCat = {};
    var order = [];
    sets.forEach(function (st) {
      if (!byCat[st.category]) { byCat[st.category] = []; order.push(st.category); }
      byCat[st.category].push(st);
    });
    order.forEach(function (cat) {
      html += '<div class="category">' + esc(cat) + "</div>";
      byCat[cat].forEach(function (st) { html += renderSetting(st); });
    });
    html += '<div class="row" style="margin-top:18px"><button class="primary" onclick="applySettings()">Apply &amp; Reload</button>';
    html += '<button class="ghost" onclick="openPluginSettings(\'' + esc(settingsPluginId) + '\')">Re-read values</button></div>';
  }
  html += "</div>";
  el("view").innerHTML = html;
}

function renderSetting(st) {
  var html = '<div class="setting">';
  html += '<div class="label">' + esc(st.friendlyName) + "</div>";
  if (st.description) html += '<div class="desc2">' + esc(st.description) + "</div>";

  var sv = saveValue.bind(null, st.storageKey);

  if (st.options) {
    html += '<div style="margin-top:4px">';
    Object.keys(st.options).forEach(function (label) {
      var val = st.options[label];
      var checked = (st.currentValue || st.defaultValue || "") === val;
      html += '<label class="checkrow"><input type="radio" name="opt-' + esc(st.storageKey) + '" ' + (checked ? "checked" : "") + ' onchange="saveValue(\'' + esc(st.storageKey) + '\',\'' + esc(val).replace(/'/g, "%27") + '\')"><span>' + esc(label) + "</span></label>";
    });
    html += "</div>";
  } else if (st.type === "StringSet") {
    var opts = st.defaultSet || [];
    var cur = st.currentSet || [];
    html += '<div class="row" style="margin-bottom:6px">';
    html += '<button class="small ghost" onclick="setAll(\'' + esc(st.storageKey) + '\',' + (st.isDisabledStyle ? "false" : "true") + "," + JSON.stringify(opts).replace(/"/g, "&quot;") + ')">' + (st.isDisabledStyle ? "Enable All" : "Select All") + "</button>";
    html += '<button class="small ghost" onclick="setAll(\'' + esc(st.storageKey) + '\',' + (st.isDisabledStyle ? "true" : "false") + ",[])" + '">' + (st.isDisabledStyle ? "Disable All" : "Deselect All") + "</button></div>";
    if (opts.length) {
      html += '<div class="checkgrid">';
      opts.forEach(function (o) {
        var checked = st.isDisabledStyle ? cur.indexOf(o) < 0 : cur.indexOf(o) >= 0;
        html += '<label class="checkrow"><input type="checkbox" ' + (checked ? "checked" : "") + ' onchange="toggleSetValue(\'' + esc(st.storageKey) + '\',\'' + esc(o).replace(/'/g, "%27") + '\',' + (st.isDisabledStyle ? "true" : "false") + ')"><span>' + esc(o.replace("API", "").replace("Api", "")) + "</span></label>";
      });
      html += "</div>";
    } else {
      html += '<input type="text" value="' + esc((st.currentSet || []).join(", ")) + '" onchange="saveValue(\'' + esc(st.storageKey) + '\', this.value.split(\',\').map(function(x){return x.trim()}).filter(Boolean).join(\'\\n\'))">';
    }
  } else if (st.isBooleanLike) {
    var on = st.currentValue === "true" || (st.currentValue == null && (st.defaultValue === "true" || st.defaultValue === true));
    html += '<div class="row" style="margin-top:4px"><span class="muted">' + (on ? "Enabled" : "Disabled") + '</span><div class="spacer"></div>';
    html += '<label class="switch"><input type="checkbox" ' + (on ? "checked" : "") + ' onchange="saveValue(\'' + esc(st.storageKey) + '\', this.checked ? \'true\' : \'false\')"><span class="track"></span></label></div>';
  } else {
    var isNum = st.type === "Int" || st.type === "Long" || st.type === "Float";
    html += '<input ' + (isNum ? 'type="number" step="any"' : 'type="text"') + ' value="' + esc(st.currentValue != null ? st.currentValue : (st.defaultValue != null ? st.defaultValue : "")) + '" onchange="saveValue(\'' + esc(st.storageKey) + '\', this.value || null)">';
  }
  html += "</div>";
  return html;
}

function openPluginSettings(id) {
  settingsPluginId = id;
  settingsData = null;
  settingsDirty = false;
  if (tab !== "settings") openTab("settings");
  else renderSettings();
  api("/plugins/" + encodeURIComponent(id) + "/settings/discover", {method: "POST", body: "{}"})
    .then(function () {
      return new Promise(function (res) { setTimeout(res, 700); });
    })
    .then(function () {
      return api("/plugins/" + encodeURIComponent(id) + "/settings");
    })
    .then(function (data) {
      if (settingsPluginId !== data.internalName) return;
      settingsData = data;
      if (tab === "settings") renderSettings();
    })
    .catch(function (e) { toast("Failed to load settings: " + e.message); });
}

function saveValue(storageKey, value) {
  settingsDirty = true;
  api("/plugins/" + encodeURIComponent(settingsPluginId) + "/settings/value", {
    method: "POST",
    body: JSON.stringify({storageKey: storageKey, value: value === undefined ? null : value})
  }).then(function () {
    if (settingsData) {
      settingsData.settings.forEach(function (st) {
        if (st.storageKey === storageKey) {
          st.currentValue = (value === undefined || value === null) ? null : String(value);
          if (st.type === "StringSet") {
            try { st.currentSet = String(value || "").split("\n").filter(Boolean); } catch (e) {}
          }
        }
      });
    }
    renderSettings();
  }).catch(function (e) { toast("Save failed: " + e.message); });
}

function toggleSetValue(storageKey, item, disabledStyle) {
  var st = null;
  settingsData.settings.forEach(function (x) { if (x.storageKey === storageKey) st = x; });
  if (!st) return;
  var cur = (st.currentSet || []).slice();
  var idx = cur.indexOf(item);
  // checkbox now reflects the opposite membership
  var wantIn = idx < 0;
  if (disabledStyle) {
    // stored set = OFF providers; membership toggles OFF
    if (wantIn) cur.push(item); else if (idx >= 0) cur.splice(idx, 1);
  } else {
    if (wantIn) cur.push(item); else if (idx >= 0) cur.splice(idx, 1);
  }
  saveValue(storageKey, cur.join("\n"));
}

function setAll(storageKey, selectAll, optsJson) {
  var opts = (typeof optsJson === "string") ? JSON.parse(optsJson) : optsJson;
  var val = selectAll ? opts.join("\n") : "";
  saveValue(storageKey, val || null);
}

function applySettings() {
  act("/plugins/" + encodeURIComponent(settingsPluginId) + "/settings/apply", {method: "POST", body: "{}"}, "Applying settings — reloading plugins…");
  settingsDirty = false;
}

// ── Logs tab ────────────────────────────────────────────────────────────────

function renderLogs() {
  var html = '<div class="card"><h2>Logs</h2><div class="hint">Live application log (most recent 100 entries).</div>';
  html += '<div class="row" style="margin-bottom:12px">';
  html += '<button class="ghost" onclick="copyLogs()">Copy all</button>';
  html += '<button class="ghost" onclick="act(\'/logs/clear\',{method:\'POST\',body:\'{}\'},\'Logs cleared\')">Clear</button>';
  html += '<label class="row" style="gap:6px;font-size:12.5px;color:var(--text2)"><input type="checkbox" id="autoscroll" checked> auto-scroll</label>';
  html += '<div class="spacer"></div></div><div class="logs" id="logsbox">';
  html += renderLogLines();
  html += "</div></div>";
  el("view").innerHTML = html;
}

function renderLogLines() {
  if (!logs || !logs.length) return '<div class="empty">No log entries yet.</div>';
  var html = "";
  logs.forEach(function (l) {
    html += '<div class="logline ' + esc(l.level) + '"><span class="t">' + fmtTime(l.timestamp) + "</span>" + esc(l.message) + "</div>";
  });
  return html;
}

function renderLogsAppend() {
  var box = el("logsbox");
  if (!box) return;
  box.innerHTML = renderLogLines();
  var auto = el("autoscroll");
  if (!auto || auto.checked) box.scrollTop = box.scrollHeight;
}

function copyLogs() {
  if (!logs) return;
  var text = logs.map(function (l) { return new Date(l.timestamp).toISOString() + " " + l.level + " " + l.message; }).join("\n");
  copyText(text);
}

// ── About tab ────────────────────────────────────────────────────────────────

function renderAbout() {
  var u = summary.update;
  var html = '<div class="grid cols2">';
  html += '<div class="card"><h2>About</h2><div class="hint">CNCVerse Bridge — run CloudStream extensions on Stremio, Nuvio and every Stremio-supported platform.</div>';
  html += '<div class="kv">';
  html += '<div class="k">Version</div><div>' + esc(summary.version) + "</div>";
  html += '<div class="k">Mode</div><div>' + (summary.headless ? "Headless server (no UI)" : "Desktop") + "</div>";
  html += '<div class="k">Platform</div><div>' + esc(summary.platform) + "</div>";
  html += '<div class="k">Loaded plugins</div><div>' + summary.server.pluginCount + "</div>";
  html += '<div class="k">Repositories</div><div>' + summary.repos.length + "</div>";
  html += "</div>";
  html += '<div class="row" style="margin-top:14px">';
  html += '<a class="pill" href="https://github.com/NivinCNC/CNCVerse-Bridge" target="_blank" rel="noreferrer">GitHub</a>';
  html += '<a class="pill" href="https://t.me/cncverse" target="_blank" rel="noreferrer">Telegram</a>';
  html += '<a class="pill" href="https://cncverse.pages.dev" target="_blank" rel="noreferrer">Support the project</a>';
  html += "</div></div>";

  html += '<div class="card"><h2>Updates</h2><div class="hint">OTA update check against GitHub releases.</div>';
  html += '<div class="row"><button class="primary" onclick="checkUpdate()">Check for updates</button></div>';
  if (u && u.tagName) {
    html += '<div class="banner" style="margin-top:14px">New version available: <b>' + esc(u.tagName) + "</b></div>";
    if (u.body) html += '<div class="muted" style="margin-top:6px;white-space:pre-wrap;max-height:180px;overflow:auto">' + esc(u.body) + "</div>";
    if (u.downloadProgress !== null && u.downloadProgress !== undefined) {
      html += '<div class="progress"><div style="width:' + Math.round((u.downloadProgress || 0) * 100) + '%"></div></div>';
      html += '<div class="muted" style="margin-top:6px">Downloading… ' + Math.round((u.downloadProgress || 0) * 100) + "%</div>";
    } else {
      html += '<div class="row" style="margin-top:12px"><button class="primary" onclick="act(\'/update/apply\',{method:\'POST\',body:\'{}\'},\'Downloading update…\')">Download &amp; Install</button></div>';
      html += '<div class="muted" style="margin-top:8px">Installs via dpkg when running as root (systemd/docker); otherwise the package is saved and logged.</div>';
    }
  } else if (u) {
    html += '<div class="banner" style="margin-top:14px">You are on the latest version.</div>';
  }
  html += "</div></div>";
  el("view").innerHTML = html;
}

function checkUpdate() {
  api("/update/check").then(function (u) {
    summary.update = u.tagName ? u : {tagName: "", htmlUrl: "", body: "", assetName: "", assetUrl: "", downloadProgress: null};
    toast(u.tagName ? "Update available: " + u.tagName : "No update available");
    renderAbout();
  }).catch(function (e) { toast("Update check failed: " + e.message); });
}

// ── shared actions ──────────────────────────────────────────────────────────

function act(path, opts, msg) {
  api(path, opts).then(function (r) {
    if (msg) toast(r && r.message ? r.message : msg);
    poll();
  }).catch(function (e) { toast("Action failed: " + e.message); });
}

function openTab(t) {
  tab = t;
  document.querySelectorAll("#tabs button").forEach(function (b) {
    b.classList.toggle("active", b.getAttribute("data-tab") === t);
  });
  if (t === "logs") { pollLogs(); }
  if (t === "extensions") { loadPlugins(); }
  render();
}

function loadPlugins() {
  api("/plugins").then(function (list) {
    plugins = list;
    if (tab === "extensions") renderExtensions();
  }).catch(function () {});
}

// ── boot ────────────────────────────────────────────────────────────────────

document.querySelectorAll("#tabs button").forEach(function (b) {
  b.addEventListener("click", function () { openTab(b.getAttribute("data-tab")); });
});

poll();
loadPlugins();
pollTimer = setInterval(poll, 2500);
logTimer = setInterval(function () { if (tab === "logs") pollLogs(); }, 2000);
</script>
</body>
</html>"""
}
