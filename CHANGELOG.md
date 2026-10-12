# Changelog

## 1.5.7

- **Movies showed no results - cause found:** one link's name contained a cut-off emoji (half of a character), which cannot be written as text, so the whole list failed with "MalformedInputException". Such broken characters are now removed from every link before the list is sent (and the movie list is no longer lost).

## 1.5.6

- **Movies showed no results:** the list was found (hundreds of links) but never sent. Preparing and sending the list is now fail-safe: a link that cannot be prepared goes out as it is, a failed send is retried without subtitles, and any error is written to the log with its cause ("[Request] ... failed").
- **Pixeldrain:** `pixeldrain.com / .dev / .net` file links (`/u/ID`, `/api/file/ID?download`) are sent as `https://cdn.pixeldrain.eu.cc/ID` (Settings -> Streams -> "Pixeldrain fast mirror", on by default).

## 1.5.5

- **Cloudflare blocks (themoviesflix.forum and similar):** many of these have no check box at all - the site just refuses requests that do not look like a browser, and a "cf_clearance" cookie never appears. Now: (1) the request is first repeated with the full header set of a Chrome page load (as Raghav's TheMoviesFlix does); (2) if that is still refused, a WebView loads the page and the check counts as solved as soon as the page is a real page (not just when a cookie shows up); (3) if the site only lets the WebView in, the extension gets the page the WebView loaded. The tap-the-box window still appears after 15 s for real checks.

## 1.5.4

- **Empty answers fixed:** 1.5.3 gave up after 24 s counted from the request, but looking up the title took the first seconds and the extensions were slow (Cloudflare checks), so the player got 0 links although 176 were found a little later. Now: the answer goes out as soon as the first links are found (waiting 4 s more so quick ones join), and otherwise keeps waiting up to 32 s from the request (hard limit 38 s) instead of answering empty. Anything found later is added to the cache; reloading shows the full list.

## 1.5.3

- **Slow / missing answers in Nuvio (LAN):** the stream search now runs on its own, so a player that gives up (or the new 24 s limit) no longer cancels it - it finishes into the cache and the next try is instant. A request is never held longer than 24 s. The quality check of HLS links is limited to 4 s. The log shows where the time went for each request ("search ..s, quality probe ..s, seek check ..s").
- **Maximum results:** the first answer still goes out early (as soon as something is found, at most 18 s), but slow extensions keep working in the background for up to 90 s (was 45 s) and add their links to the cache as they finish. Reloading the title in the player shows the longer list at once.

## 1.5.2

- **Free tunnel:** no longer tries to keep the same link. Free quick tunnels cannot keep one, and reusing the old one made the second start fail. Every start now makes a fresh tunnel (retried once if Cloudflare is slow); the new link is shown in the app. For a fixed link use your own tunnel token and domain.
- **Nuvio over Wi-Fi:** the first answer is now sent after at most 18 s (was 26 s) in case Nuvio gives up earlier; the late results are in the cache for the next try.
- **Request log:** every manifest and stream request now shows in the log ("[Request] streams for series/tt...: from <address>" and "sent N link(s)") so it is clear whether the player's request reaches the bridge and what it got back.

## 1.5.1

- **Real DNS-over-HTTPS:** the "DoH fallback" only asked the phone's own DNS again. It now really asks Cloudflare (1.1.1.1 / 1.0.0.1), Google (8.8.8.8 / 8.8.4.4) and Quad9 over HTTPS by IP when the phone's DNS fails or answers 0.0.0.0 / 127.0.0.1 (DNS-blocked sites like desicinemas.to), IPv4 first. Applies to extensions, the media server and the tunnel.
- **Cloudflare challenges (as in Raghav's extensions):** the check is first tried in a hidden WebView; if it does not pass in 15 s (a "tap the box" check), the same WebView is shown in a window over the app so you can finish it. The clearance cookie is saved with the WebView's exact User-Agent and reused for every later request to that site (6 h). The window is shown at most once per site every 5 minutes.

## 1.5.0

- **Sorting fixed at the root (Android):** the quality of each link was never passed on, so every link looked like "unknown quality" and was sorted alphabetically. Quality, link type and server are now passed on, so the list goes from the highest resolution to the lowest. Links are also read for their resolution from the name when nothing else says.
- **Minimum resolution setting:** links known to be below the chosen resolution are hidden (default 720p, still the minimum of 1.4.3) (Settings -> Streams -> "Hide links below": show all / 480p / 720p / 1080p). Links of unknown quality stay and are listed after the known ones. The list is never emptied by this filter.
- **Season checks (anime):** for a later season, the names tried include "Title Season 2", "Title 2nd Season", "Title II" and "Title 2", and the season is checked against the name. The entry found on a site must also have the right number of episodes (from TMDB for the season, or from the AniList / Kitsu entry) and, when the extension reports it, the same AniList / MyAnimeList id. A wrong-season entry is skipped and the next best entry is tried; if none fits, that extension shows nothing rather than the wrong season.
- **TMDB numbers -> AniList numbers (anime):** a TMDB season/episode (tt / tmdb ids) is translated to the AniList entry and the episode number inside it, by reading the franchise's TV entries (prequels / sequels) and comparing them with TMDB's season sizes. When both split the show the same way, season N is entry N; otherwise the running episode number decides (for example TMDB season 2 episode 14 = episode 2 of the second AniList part). The names and AniList / MyAnimeList ids of that exact entry are used to find it on each site, and a site that lists the episodes as one running list is read by running number. If the translated number is not found, TMDB's own numbers and the running number are tried.
- **Better names:** `{stream.host}` (the site a link comes from) tells apart links that looked identical; the name line shows resolution, extension and Sub / Dub / Hardsub; the default layout was reworked (an old saved default is upgraded automatically). Links from Upstream add-ons keep their own names and are no longer reformatted.
- **Free tunnel link:** the quick tunnel of the last run is reused on the next start, so the link stays the same for as long as Cloudflare keeps it alive. A new link is only made when the old one is gone. Cloudflare does not promise a fixed link for free tunnels without an account; for a link that never changes use your own tunnel token and domain (Settings -> Cloudflare Tunnel).

- **History tab:** every answered request is listed (title, season / episode, how many links were found and shown, the count per extension, how long it took). Tap one to see the exact list the player received (name and description of each link); "Copy list" copies it.
- **Results that did not show up:** a request that arrives while the extensions are still loading now waits for them (up to 20 s) instead of answering with 0 links, and the first answer is never held longer than 18 s (clients give up around 30 s); links that arrive later are still put into the cache for the next try.
- **Free tunnel, second start:** the saved link's credentials were overwritten by a new tunnel request before the saved link had been tried, so the second start always failed. The new tunnel is now only requested if the saved one did not connect.
- **AnimePahe and other extensions:** the ksoup HTML library (needed by AnimePahe, "NoClassDefFoundError ... Ksoup") is now included.

## 1.4.3

- **Free Cloudflare tunnel:** cloudflared is now started the way pencarimovie-server starts it - transport auto (QUIC, falling back to HTTP/2), a writable HOME, the system CA store (`/system/etc/security/cacerts`) on Android, an ingress config file, loopback origin (`127.0.0.1`) and many retries. The free link is read from cloudflared's local metrics endpoint (`/quicktunnel`) instead of only scraping the log. The app-created quick tunnel is still tried first, the metrics-based start is the fallback; each attempt is given up after 40 s.
- **Sorting:** whatever the settings, the list sent to Stremio / Nuvio is now ordered by resolution (highest first) as the very last step, so grouping, provider order, hardsub-first and anime-audio no longer break it. Inside one resolution the order from those settings is kept. Links with a known resolution below 720p are never shown; links whose resolution cannot be found are listed after the known ones.

## 1.4.2

- **Anime search no longer gives up:** when an extension's search errors on the first spelling of a title (AnimePahe breaks on some punctuation), the other names of the show are still tried.
- **Free tunnel on Android:** (the old link shown "api.trycloudflare.com" was an error, not a tunnel) the quick tunnel is now created by the app itself (cloudflared cannot look up Cloudflare's address on Android), then run with those credentials; if that does not connect in 50 s, cloudflared's own quick tunnel is tried. The log shows each step.
- **AnimePahe:** its subbed links are recognised as hardsub (burned-in subtitles) and listed first. Episode lists without episode numbers (as in AnimePahe) are picked by position.
- **Free https link now reachable:** "Enable Stremio Mode" on the Server screen no longer demands a tunnel token and domain first. Turn it on (the app downloads cloudflared once) and the free https link appears as the Stremio URL; add your own token in Settings -> Cloudflare Tunnel only for a fixed link.

## 1.4.1

- **Anime search (AnimePahe and others):** every known name of the show (romaji, English, synonyms from AniList / Kitsu / MyAnimeList) is searched, also without season words, without the subtitle and by its first words. The results of all spellings are pooled and the best fit wins, with the season checked against the name ("2nd Season", "S2", "II").
- **Fuzzy search for movies and series:** when no result matches the exact title, a close spelling is accepted (same year, same season), and the title is retried without a subtitle / leading "The".
- **Hardsub first (anime):** links with the subtitles burned in are listed on top; inside each group the best quality stays first.
- **Formatter:** anime episodes (kitsu / MAL / AniList ids, any type) now fill the season/episode fields, new fields `{stream.audiokind}` (Sub / Dub) and `{stream.subtype}` (Hardsub / Softsub), and a "Sub / Dub / Hardsub" line in the default layout. A saved copy of the old default layout is upgraded automatically.
- **Media server:** links that cannot seek are always downloaded completely instead of pausing the download. Headers (Referer, User-Agent, Cookie) from the extension are sent on every request of the media server - verified end to end. Sites whose Cloudflare check was solved are sent their saved cookie and the matching User-Agent.
- **Cloudflare:** a solved check is saved on the device (6 hours) and reused for later requests instead of solving again; one solver per site at a time; longer wait for the check to finish.
- **Free https link:** with no tunnel token set, Stremio Mode starts a free Cloudflare quick tunnel (random https://...trycloudflare.com link, no account). Your own token and domain still work as before.
- Streams from Upstream add-ons now read their quality from the add-on's names, so they sort properly.

## 1.4.0

- **Always the best resolution:** adaptive (auto-resolution) streams are now looked into and replaced by their highest-resolution rendition when the audio is inside the video, so they are labelled with the real maximum (e.g. 1080p) and play at it every time instead of starting low. Streams that keep audio in a separate track are left adaptive so sound is never lost. Through the media server, master playlists are narrowed to the best rendition too. Setting: Streams -> best HLS quality.
- **Telegram / pencarimovie streaming:** new "Upstream add-ons" card in Settings. Paste the link of your pencarimovie server (or any Stremio add-on) and its streams are merged into every result list. Its Telegram files already stream with seeking, so they are passed through untouched. Included in settings backups.
- AnimePahe: the earlier settings fix (1.3.0) is the intended fix; the older AnimePahe copy in the reference repository was not adopted because the installed extension is newer.

## 1.3.0

- **AnimePahe and other extensions with saved settings:** plain-text settings (domain, user agent, Cloudflare cookie, provider lists) were handed to extensions in a form they could not read, so AnimePahe found nothing and logged JSON errors. They now read correctly.
- **Media server:** new default mode "Those + all HLS", more segments fetched ahead (30), 8 downloads in parallel, retries on failed downloads, playlists behind plain links (no ".m3u8") are recognised, and the renditions of a master playlist are loaded as soon as it is opened. Aimed at hard-sub / slow-host streams that kept buffering.
- **Results shown once:** by default the full list is shown after all extensions have answered (up to 45 s) instead of an early partial list. Setting: Streams -> "Show results once, after all extensions answered".
- **Next episode in advance:** after an episode's links are loaded, the next episode's links are loaded in the background. Setting: Streams -> "Load the next episode in advance".
- **Sorting:** links of equal quality no longer change places between loads (ties are broken by provider, name and link); adaptive streams get a longer time to have their quality detected so they are not ranked last at random.
- **Anime names:** for kitsu / MAL / AniList / AniDB ids the titles of AniList, Kitsu and MyAnimeList are all collected; for tt / tmdb / tvdb ids of anime the title is looked up on AniList and all its other names are tried too.
- **Log warnings:** fixed "No virtual method authUser()", "Failed resolution of AniListApi$CoverImage" and "No base context in WebViewResolver".

## 1.2.3

- Streams whose link is exactly the same as another result are listed once (the best ranked one is kept). Links that differ in any way are never merged.

## 1.2.2

- Six repositories are added by default: CNC, phisher98, SaurabhKaperwan (CSX), KSHITIJ8473 (raghav), RVRBEAST76 (allforu) and Faisal0786 (Desi). A fresh install starts with all of them; an existing install gets the missing ones added once (a repository you remove afterwards stays removed).
- Releases and the README now carry a notice that the app is vibe coded and provided as is, with no responsibility taken by the developer.

## 1.2.1

- The app no longer shows update dialogs (update checks are switched off).
- Android: wake locks, a one-time battery-optimisation exemption request and a restart after swiping the app away, so the system stops closing the server.
- Release workflow signs with the sideload key when no signing secrets exist, and can create its own tag when started by hand.

## 1.2.0

### Anime
- Kitsu, MAL, AniList and AniDB ids are resolved to every title a show is known by (English, romaji, synonyms), so extensions such as AniPM and Anidap find more shows.
- Fuzzy title matching with season handling (e.g. "Season 2", "2nd Season") and flexible episode numbering, so the right episode is picked, including absolute numbering.
- New setting, Settings -> Streams -> *Anime audio*: subbed first, dubbed first, or hide the other kind.

### Stream quality
- Adaptive HLS links get their real resolution by reading the master playlist, instead of being listed as "Auto" at the bottom.
- Optional one-entry-per-resolution listing for adaptive links (1080p / 720p / 480p ...), with the adaptive original kept last.
- New resolution cap ("Best quality for this device"): links above it are listed last.
- New option to hide streams of unknown quality.
- Resolution tiers are detected from width and height, so letterboxed films (e.g. 1920x800) count as 1080p; playlists without a resolution fall back to their bitrate.

### Backup and import
- Settings -> Backup & import saves everything to one JSON file and restores it, also on another device: stream filters and sorting, quality and anime options, catalogs, formatter templates, stream-cache and media-server settings, repositories, enabled / disabled extensions, extension settings and the list of installed extensions.
- Option to leave logins, tokens and cookies out of the backup.
- After an import, missing extensions are reinstalled automatically.
- Android uses the system file picker; other platforms can copy and paste.

### Media server (new)
- Settings -> Media server. Links that need a Referer, cookies or other headers are downloaded by this device with those headers, kept on disk, and served to the player from there, so videos that would not play directly now work.
- Seeking works while the download is still running; the download does not run far ahead of what is being watched.
- HLS playlists and segments are cached and fetched ahead; cookies set by the source are kept per source.
- Stream links are signed, so other devices cannot use the server to fetch arbitrary URLs.
- Cache limit: never above 2 GB (adjustable between 256 MB and 2048 MB). When space is needed, previously cached items are deleted, least recently used first; items older than the "keep hours" setting are also removed. The item being played is never deleted; a file too big for the cache is relayed live instead.
- Modes: only links that need headers, or all links.

### Build
- New GitHub Actions workflow (`.github/workflows/build-apk.yml`) builds an installable APK. It uses your signing secrets if set, otherwise the sideload key in `ci/`.
- Version raised to 1.2.0 (Android version code 10).

### Notes
- This release has not been compiled by its author; the first CI run is its first build.
