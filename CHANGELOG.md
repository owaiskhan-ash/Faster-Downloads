# Changelog

All notable changes to this project are documented here.
This project follows [Semantic Versioning](https://semver.org/).

## [0.4.0] - 2026-10-30

### Added
- **Day / night switch in the header.** Two-segment pill at the top right flips the whole app
  between the neon canvas and the black one, and the choice is persisted
  (`ThemePreference`); until it is used the app keeps following the system setting.

### Fixed
- **Magnet links failed with an error instead of downloading.** `bt` 1.10 never
  percent-decodes magnet `tr` parameters, and `TrackerService.getProtocol()` looks for a
  literal `://`, so a standard link (`tr=udp%3A%2F%2Ftracker...`) threw
  `Invalid URL: udp%3A%2F%2F...` before a single peer was contacted. Links are now parsed by
  `MagnetLink` — info-hash (hex *or* base32), name and trackers are decoded here, unusable
  schemes are dropped, and the engine is handed a `MagnetUri` object so its string parser
  never runs. HTTP, HTTPS and UDP trackers all work now.
- Magnet input is sanitised first: HTML escapes (`&amp;`), markdown autolinks
  (`[2Ftracker](http://2Ftracker)`), quotes and line breaks are repaired, and a link that
  still has no usable info-hash gets a plain-language error instead of a stack-trace string.
- The torrent folder is diffed before and after a download, so publishing can no longer pick
  up a file left over from an earlier torrent.
- Metadata timeout message now says what to try (seeders, Wi-Fi vs mobile data).

## [0.3.0] - 2026-10-30

### Changed
- **Connection reuse per radio.** The HTTP transport moved from a fresh
  `HttpURLConnection` per range request to one pooled OkHttp client per `Network`, so a
  finished range request returns its socket to the pool and the next chunk reuses it. Only
  the first request on each radio pays the TCP + TLS handshake; on a 60-100 ms mobile link
  that handshake was 10-30 % of the time spent on a medium chunk. Binding is unchanged
  (the client's socket factory and DNS come from the `Network`), the shared cookie jar
  still crosses radios, redirects are still followed by hand, and cancellation now goes
  through `Call.cancel()`.
- HTTP/1.1 is pinned deliberately: HTTP/2 multiplexing would collapse the parallel flows
  into one TCP connection, losing the per-flow shaping bypass that makes bonding fast.
- New launcher icon: black Wi-Fi + mobile-data glyphs on the neon field, shipped as an
  adaptive icon (neon background, ink foreground, `monochrome` layer for Android 13 themed
  icons) plus raster mipmaps for mdpi..xxxhdpi. `branding/make-icons.py` regenerates them
  from `branding/app-icon.png`.

## [0.2.0] - 2026-10-30

### Added
- **Magnet links.** The app is now a BitTorrent downloader too: paste a `magnet:` URI, or
  open/share one from another app. Built on `bt-core` 1.10 (DHT, HTTP/UDP trackers, PEX,
  MSE encryption), with metadata-first naming, peer count in the progress card, a two-minute
  metadata watchdog and publishing of single-file payloads to Downloads.
- `MainActivity` registers `magnet` (ACTION_VIEW) and `text/plain` (ACTION_SEND) intents and
  prefills the URL field through `DownloadController.incoming`.
- **Dark mode.** The palette inverts with the system setting: black canvas (`#0A0A0A`) with
  neon elements, light system-bar icons and a matching window background.

### Changed
- Shared `DownloadEngine` interface so the service can run either engine; SHA-256 helper
  moved to `Hashing`.
- Indeterminate progress while torrent metadata is still being fetched.

## [0.1.2] - 2026-10-30

### Changed
- New look: highlighter-neon canvas with black ink, hairline outlined sections, thin
  typography with wide letterspacing, black-filled primary buttons and neon system bar
  icons. The app is intentionally single-theme now.
- Removed the "Good to know" card from the home screen.

### Fixed
- Speed and ETA readouts no longer flicker or appear to drop: they are computed over a
  sliding 3 s window (`SpeedMeter`) rather than from 450 ms deltas.
- Steadier throughput on large files: chunk size floor raised to 1 MB and ceiling to 16 MB
  (~6 chunks per worker), so each range request amortises its TLS handshake instead of
  paying it every 256 KB.
- Worker adaptation is less twitchy: it runs every 6 s, needs a 15 % deviation from the
  target split, and stops adding connections once the queue is nearly drained.

## [0.1.1] - 2026-10-30

### Fixed
- `HTTP 403` failures on hosts that refuse app-shaped requests.
  - Requests now carry browser-style headers (`User-Agent`, `Accept`, `Accept-Language`,
    `Referer` derived from the file URL).
  - One shared cookie jar is used for the probe, every chunk and every redirect, so
    session cookies and anti-bot tokens survive across both radios.
  - If a host rejects rolled range requests, a plain `GET` probe now falls back to a
    single-connection download instead of failing.
  - Error messages report the status line, `Server` header, a body snippet and a hint
    (403 / 404 / 429 / 5xx) instead of a bare code.
- A radio that keeps failing is now retired mid-download and its chunks are re-queued to
  the other link, so one blocked network can no longer abort the whole transfer. The UI
  shows `Single link` when that happens.

## [0.1.0] - 2026-10-30

First public build.

### Added
- Dual-network download engine: requests Wi-Fi and cellular `Network`s at the same time and
  pins every range request to one radio via `Network.openConnection`.
- Weighted, adaptive worker split between the two links (50-90 % Wi-Fi share).
- Chunked HTTP `Range` transfer over a shared work queue with per-chunk retries.
- Chunk-level resume using an on-disk progress journal.
- SHA-256 and exact-size verification before publishing.
- Saving to `Download/FasterDownloads` via MediaStore (direct file on Android 8-9).
- Foreground `dataSync` service with progress notification, cancel action and wake lock.
- Compose UI showing overall progress, per-link speed, ETA and mode.
- Unit tests for chunk planning and formatting; CI that builds debug + release APKs.

### Known limitations
- Hosts without `Range` support fall back to a single connection.
- Some OEM skins restrict concurrent Wi-Fi + cellular use.
- Release APKs are debug-signed unless signing secrets are configured.
