# Changelog

All notable changes to this project are documented here.
This project follows [Semantic Versioning](https://semver.org/).

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
