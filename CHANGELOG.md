# Changelog

All notable changes to this project are documented here.
This project follows [Semantic Versioning](https://semver.org/).

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
