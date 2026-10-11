# Changelog

## 1.4.2

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
