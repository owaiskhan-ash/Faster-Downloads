# Changelog

All notable changes to this project are documented here.
This project follows [Semantic Versioning](https://semver.org/).

## [0.8.0] - 2026-10-30

### Added
- **Magnet links are bonded across both radios.** libtorrent's peer connections now go through
  a loopback **SOCKS5 proxy** (`BondingProxy`) that the app runs; each `CONNECT` is dialled on
  Wi-Fi or on mobile data, weighted by what each radio is actually delivering (re-measured every
  4 s, bounded 20-80 %). A swarm keeps dozens of connections open, so half use each radio and
  the torrent's throughput becomes the sum of the two links - the HTTP split applied per
  connection instead of per byte range. IPv4, IPv6 and domain targets, DNS resolved through the
  chosen radio, no authentication.
- The download card shows the split: `bonded: Wi-Fi 12 conns · mobile 9 conns`.
- Safety valve: a torrent that sees **no peers for 25 s** while bonded turns bonding off for the
  session (`proxy_type = none` applied live) and says so, so the proxy can never leave a
  download stranded. DHT and UDP trackers were never proxied, so discovery keeps working.

### Changed
- **Everything is published to the app's folder, `Download/FasterDownloads`.** Multi-file
  torrents used to stay in app-private storage; they are now copied into
  `Download/FasterDownloads/<torrent name>/...` with their structure intact, and the private
  copies are removed.
- The Torrent card no longer says bonding is HTTP-only.

### Notes
- Bonding needs two radios; with a single link the proxy is never started and torrents use the
  default network as before.
- uTP gives way to TCP for peer connections while bonded (SOCKS5 carries TCP only).

## [0.7.0] - 2026-10-30

### Added
- **Pause and resume.** Every download card has Pause / Resume. Pausing drops the sockets and
  keeps the partial file plus its chunk journal, so resuming continues from the same byte;
  torrents keep the pieces they already have. Paused jobs no longer need the service, so they
  survive with the list and can be resumed much later.
- **Several downloads at once.** The app is now a download list, not a single job: add as many
  links as you like, three run in parallel and the rest sit in `QUEUED` until a slot frees up.
  Each card carries its own pause/resume/cancel/remove and its own progress, speed, ETA, peers
  and saved-file block. "Clear finished" tidies the list.
- **Faster metadata.** The libtorrent session is now shared by the whole app and pre-warmed
  when the app opens, so DHT bootstrap happens once instead of once per magnet - that bootstrap
  was most of the "looking for peers to fetch the metadata from" wait. Each torrent also
  force-announces to every tracker in every tier the moment it is added (and again every 15 s
  while metadata is missing), and a link carrying fewer than three trackers gets a few
  long-lived open ones appended.
- Engine settings for peer discovery: announce to all trackers/tiers, prefer UDP trackers,
  4 MB metadata limit, room for 10 active torrents.

### Changed
- **Always bonded, no settings.** The Links section (bond toggle, Wi-Fi share slider,
  connections slider) is gone: HTTP downloads always use Wi-Fi and mobile data together, split
  50/50, with the maximum of 8 parallel range requests per network.
- The download form never blocks: a new link can be added while others are running.
- One notification covers the batch ("3 downloads running", aggregated progress and speed)
  with a Cancel-all action; single jobs still show their own name and percentage.

### Notes
- The list lives in memory, so a force-stop or a reboot clears it (finished files are already
  in Downloads, partial files resume when the same link is added again).

## [0.6.0] - 2026-10-30

### Changed
- **The torrent engine is now libtorrent.** Magnet downloads run on
  [libtorrent](https://github.com/arvidn/libtorrent) through libtorrent4j `2.1.0-38` - the
  native C++ engine that LibreTorrent, FrostWire and TorrDroid ship - instead of the
  Java-only `bt` library. This replaces the whole discovery problem rather than patching it:
  `bt` could only speak HTTP to trackers, so a link whose one `http://` tracker is dead and
  whose other trackers are `udp://` (the norm) had no path to the swarm. libtorrent speaks
  **UDP trackers (BEP-15)**, DHT, PeX, LSD, µTP and encryption natively.
  On device this was the reported failure: `0/1 HTTP trackers answered · 1 failed
  (InterruptedIOException: timeout) · 17 UDP left to the engine`, stuck on "looking for
  peers to fetch the metadata from".
- Removed: `bt-core`, `bt-dht`, `bt-http-tracker-client` and the Guice pins they needed
  (`4.2.3:no_aop` + `guice-multibindings`), the HTTP tracker probe and the public-tracker
  fallback list. All of that existed to work around the Java engine's missing UDP support.

### Added
- Real engine telemetry under the progress card: libtorrent state, DHT node count, peers,
  seeds and tracker count, updated twice a second.
- Three honest failure paths so a magnet can never spin forever: no torrent handle after 15 s,
  no metadata after 180 s, metadata but zero payload after 8 minutes. Each names the live
  numbers and what they mean.
- A missing or mismatched native library (`LinkageError` / `UnsatisfiedLinkError`) is reported
  as "the torrent engine could not start on this device", and CI now checks that
  `lib/<abi>/libtorrent4j.so` is inside the APK for every ABI declared in `abiFilters`.
- `MagnetLink.toMagnetUri()`: the sanitised, re-percent-encoded URI that is handed to
  libtorrent, with round-trip tests.

### Notes
- The APK grows from 9.1 MB to 18.9 MB (two ABIs: `arm64-v8a`, `armeabi-v7a`); the native
  library is extracted on install (`useLegacyPackaging`), the arrangement the Android
  libtorrent clients use.
- Kotlin/AGP/Gradle, Compose and the HTTP engine are unchanged.

## [0.5.1] - 2026-10-30

### Added
- **Public HTTP(S) tracker fallback.** A magnet link that lists only `udp://` trackers (the
  norm) plus one dead `http://` tracker has no reachable path to the swarm on a network that
  drops UDP, which is exactly what the diagnostics line reported on device:
  `0/1 HTTP trackers answered … 17 UDP left to the engine`. The tracker probe now also asks a
  short list of long-lived public **HTTPS** trackers (ports 80/443 - the traffic no network
  blocks), announced once each, and feeds whatever peers they know into the magnet via `x.pe`.
  They are not written into the magnet, so nothing keeps announcing to them afterwards, and
  the diagnostics line says how many were included.
- The Torrent card states this explicitly, so the extra announces are not a surprise.

### Changed
- Tracker probe budget: 16 trackers, 6 threads, 12 s deadline (5 s connect / 6 s read), so
  the pre-flight check cannot delay the download for long.

## [0.5.0] - 2026-10-30

### Added
- **Tracker probe and peer injection for magnets.** Before building the client the app now
  announces once to the link's HTTP(S) trackers itself (raw-benconded `info_hash`, compact
  peer parsing), shows what came back in the progress card, and hands any peers it found to
  the engine through `x.pe`. That rescues swarms whose metadata fetch would otherwise
  depend on DHT alone - which is exactly the "stuck on fetching metadata" case.
- The card now has a diagnostics line under the torrent row (for example
  `1/2 HTTP trackers answered · 37 peers · 5 UDP left to the engine`), and a timeout
  explains *why* it timed out: every tracker refused (network blocking peer traffic) versus
  trackers answered but know no peers (dead release).

### Fixed
- **Cleartext HTTP was blocked.** `targetSdk 35` plus Android 9+ network security defaults
  refuse `http://` traffic, which silently killed plain-HTTP tracker announces *and* any
  download from an `http://` URL (`UnknownServiceException: CLEARTEXT communication not
  permitted`). The manifest now sets `usesCleartextTraffic="true"`, which a downloader that
  must reach arbitrary hosts needs anyway.

## [0.4.2] - 2026-10-30

### Fixed
- **`java.lang.IllegalStateException: Unable to load cache item` on every magnet.** With
  Guice 4.x the next wall is cglib: to speed up `@Provides` methods Guice generates a
  `FastClass` at runtime, and Android's class loader refuses the generated class
  (`can't load this type of class file`), which Guice reports as
  `Unable to load cache item`. Guice therefore cannot be used on Android in its normal
  build at *any* version: 5.x dies on `Class.getAnnotatedSuperclass()`, 4.x dies on cglib.
  The fix is the **`no_aop` build** (`com.google.inject:guice:4.2.3:no_aop`) — the same
  artifact Maven ships and runs on: its bundled cglib is stripped at build time, so
  `@Provides` falls back to reflection.
  Guice is now excluded from all three bt artifacts (so the transitive copy cannot return),
  `guice-multibindings` is declared with the same exclusion, and CI fails the build if
  either `BindingSourceRestriction` or `com/google/inject/internal/cglib` appears in the
  release APK dex.
- Engine failures now report the exception **and its two nearest causes**
  (`Unable to load cache item -> ...CodeGenerationException -> ...`), so the real reason is
  visible in the card instead of the outermost wrapper.

## [0.4.1] - 2026-10-30

### Fixed
- **Magnet downloads still failed on device with
  `No virtual method getAnnotatedSuperclass()Ljava/lang/reflect/AnnotatedType;`.** `bt` 1.10
  depends on Guice 5.0.1, and Guice 5's `BindingSourceRestriction.getPermits()` calls
  `Class.getAnnotatedSuperclass()`, which Android's `java.lang.Class` does not implement at
  *any* API level. Creating the torrent client therefore died before the swarm was joined,
  and the engine's error surfaced verbatim in the download card.
  Guice is now pinned to **4.2.3** (which contains no such class), with
  `guice-multibindings:4.2.3` added because `Multibinder`/`MapBinder` live in a separate
  artifact before 5.0. The exclusion plus a resolution `force` means no transitive request
  can pull Guice 5 back in, and CI now fails the build if `BindingSourceRestriction` appears
  in the release APK's dex.
- Engine start-up failures that arrive as `LinkageError` (a missing JVM method) are reported
  as "The torrent engine could not start on this device: ..." rather than as a raw JVM
  message.

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
