# Faster Downloads

An Android app that downloads **one file over two networks at the same time** — Wi-Fi *and*
mobile data — by splitting the file into HTTP `Range` chunks and pinning each group of
sockets to a different radio with Android's `Network` API. It also opens **magnet links**
and pulls them from the BitTorrent swarm.

This is the Plexo idea (multipath downloads over bonded links, see
[anmolkapil/plexo](https://github.com/anmolkapil/plexo)) rebuilt for Android, where apps
cannot use `SO_BINDTODEVICE` and instead ask `ConnectivityManager` for two live `Network`
objects and route each connection through one of them.

![Android CI](https://github.com/owaiskhan-ash/Faster-Downloads/actions/workflows/android.yml/badge.svg)

**[Download the latest APK](https://github.com/owaiskhan-ash/Faster-Downloads/releases/latest)**

---

## Install

1. Open the [latest release](https://github.com/owaiskhan-ash/Faster-Downloads/releases/latest).
2. Download `FasterDownloads-<version>.apk` on the phone.
3. Allow *install unknown apps* for your browser/file manager, then open the APK.

Requirements: Android 8.0 (API 26) or newer.

## How it works

```mermaid
flowchart LR
    U[URL] --> P[Probe: size + Range support]
    P --> S[Split into byte ranges]
    S --> Q[(Shared chunk queue)]
    CM[ConnectivityManager] -->|requestNetwork WIFI| NW[Network A]
    CM -->|requestNetwork CELLULAR| NC[Network B]
    Q --> W1[Wi-Fi workers]
    Q --> W2[Mobile-data workers]
    NW -->|pooled client, Network socket factory| W1
    NC -->|pooled client, Network socket factory| W2
    W1 --> F[RandomAccessFile / FileChannel positional writes]
    W2 --> F
    F --> V[SHA-256 + size check]
    V --> D[MediaStore Downloads]
```

1. **Two networks at once.** `ConnectivityManager.requestNetwork()` is called once for
   `TRANSPORT_WIFI` and once for `TRANSPORT_CELLULAR`. Both callbacks stay registered for
   the whole transfer, so the platform keeps the cellular link up even though Wi-Fi is the
   default network.
2. **Sockets bound per radio, and kept alive.** Each radio gets its own OkHttp client whose
   `SocketFactory` and DNS come from its `Network` — Android's equivalent of
   `SO_BINDTODEVICE`: DNS and TCP traffic leave through that network only. That client also
   owns a connection pool, so a finished range request hands its socket back and the next
   chunk on the same radio reuses it instead of paying a TCP + TLS handshake again.
3. **Range splitting.** The file is probed with `Range: bytes=0-0` to learn its size and
   whether the server honours ranges. It is then cut into 1 MB – 16 MB chunks that all land
   in one shared work queue.
4. **Weighted, adaptive workers.** Each radio gets a share of the worker threads
   (default 70 % Wi-Fi / 30 % mobile). Every 4 s the app compares measured throughput
   against the requested split and hands the faster radio an extra worker if it is
   under-used. A stalled link never blocks the other one, because work is pulled, not
   pushed.
5. **Reassembly and verification.** Chunks are written with positional `FileChannel`
   writes, so no lock is needed and threads never collide. The finished file is checked
   against the expected length and hashed with SHA-256 before being published to
   `Download/FasterDownloads/` through MediaStore.
6. **Resumable.** Completed chunk indexes are journalled to disk every few seconds, so a
   cancelled or interrupted transfer continues from where it stopped instead of starting
   over.
7. **Foreground service.** The transfer runs in a `dataSync` foreground service with a
   progress notification, cancel action and a partial wake lock.

More detail: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Features

- Highlighter-neon UI with hairline outlines, black ink and thin type
- **Day / night switch in the header**: inverts the palette (black canvas, neon elements) and
  remembers the choice; until it is used, the app follows the system setting
- **Magnet links**: `magnet:?xt=urn:btih:...` downloads over BitTorrent (DHT + trackers),
  and the app registers as a magnet handler so links open straight from the browser
- Windowed (3 s) speed and ETA readout, so the number is readable instead of jittery
- Bonded Wi-Fi + mobile-data download with a configurable Wi-Fi share (50–90 %)
- 1–8 parallel connections per network, shared work queue with retry
- Adaptive weighting driven by real per-link throughput
- Chunk-level resume after cancellation, process death or network loss
- SHA-256 of the finished file plus exact size verification
- Saves to `Download/FasterDownloads` (MediaStore on Android 10+, direct file on 8–9)
- Foreground service with progress notification and in-notification cancel
- Automatic fallback to a single connection when the server ignores `Range`

## Torrents (magnet links)

Paste a `magnet:` URI — or tap one in a browser / share it to the app — and the BitTorrent
engine takes over, powered by [atomashpolskiy/bt](https://github.com/atomashpolskiy/bt):

- magnet links are parsed and *decoded* by the app before the engine sees them: `bt` 1.10
  never percent-decodes `tr` parameters, so a standard link (`tr=udp%3A%2F%2F...`) would die
  with "Invalid URL" and lose every tracker. HTML-escaped (`&amp;`) and autolinked
  (`[2Ftracker](http://2Ftracker)`) links are repaired too
- metadata is fetched from the swarm first (BEP-9), then the file name and size appear
- DHT (BEP-5), HTTP/UDP trackers, PEX and encryption are handled by the library
- progress, peers and ETA show in the same card; the live notification is shared with HTTP jobs
- single-file torrents are published to `Download/FasterDownloads`; multi-file torrents stay
  together in the app's torrent folder
- torrents always use the device's default network — bonding two radios only applies to
  HTTP range downloads, because thousands of peer sockets cannot be split meaningfully

Download only content you have the right to.

## Caveats

- **Range support is required** for the multipath path. Hosts that ignore `Range` fall
  back to one connection (still 1 stream, no speedup).
- **OEM skins vary.** A few Android skins restrict concurrent Wi-Fi + cellular use or tear
  down the secondary network. Behaviour was designed against stock Android 8–15; test on
  your device.
- **Mobile data is really consumed** while bonded — typically `100 - wifiShare` percent of
  the bytes. Watch your data cap. Carrier billing and tethering rules apply.
- **Battery.** Two radios plus several sockets drain faster than a normal download.
- **Torrent speed** depends on the swarm: a magnet with no seeders will stall until the
  metadata watchdog reports it.
- The release APK is **debug-signed** unless you provide signing secrets (see below), which
  is fine for sideloading but not for publishing to Play.

## Troubleshooting

**`HTTP 403 Forbidden`** - the host refused the request. The app already sends browser-shaped
headers (`User-Agent`, `Accept`, `Referer`), keeps one cookie jar across both radios, and
falls back to a plain `GET` on a single connection when a server rejects ranged requests.
If a 403 still appears, the URL is almost certainly not a direct file link: it needs a
login/session cookie, is an expiring one-time link, or sits behind a Cloudflare-style
challenge. Open the link in the browser and copy the real download target. The error card
shows the status line, the `Server` header, a snippet of the server's message and a hint, so
you can tell those cases apart.

**`HTTP 416` or "The server ignored the Range header"** - the file changed size on the
server, or ranges are not really supported. Start the download again.

**A link row turns red and "Bonded" becomes "Single link"** - that radio was retired after
repeated failures (blocked IP, no data left, carrier 403) so the transfer could still finish
on the other radio.

## Build from source

```bash
git clone https://github.com/owaiskhan-ash/Faster-Downloads.git
cd Faster-Downloads
./gradlew :app:assembleDebug      # app/build/outputs/apk/debug/
./gradlew :app:testDebugUnitTest  # chunk-planning and formatting tests
```

Requires JDK 17 and the Android SDK (compileSdk 35). Opening the project in Android Studio
works as-is.

### Signed releases

CI builds an installable APK on every push. To produce a properly signed release, add these
repository secrets and push a `v*` tag (or a commit whose message contains `[release]`):

| Secret | Purpose |
| --- | --- |
| `KEYSTORE_BASE64` | `base64 -w0 release.keystore` |
| `KEYSTORE_PASSWORD` | keystore password |
| `KEY_ALIAS` | key alias |
| `KEY_PASSWORD` | key password |

Without them the release job signs with the debug key and says so in the release notes.

## Project layout

```
app/src/main/java/com/fasterdownloads/
  core/NetworkLinks.kt        ConnectivityManager → two bound Network handles
  core/HttpUtil.kt            network-pinned connections, probe, redirects, filenames
  core/ChunkPlan.kt           pure chunk-size / worker-split math (unit tested)
  core/MultiPathDownloader.kt the engine: queue, workers, retries, resume, verify
  core/Storage.kt             MediaStore publishing (and pre-Q fallback)
  core/DownloadController.kt  StateFlow bridge between service and UI
  service/DownloadService.kt  foreground service, notification, wake lock
  ui/HomeScreen.kt            Compose UI
.github/workflows/android.yml tests → APKs → optional release
```

## Roadmap

- [ ] Per-request bandwidth caps and a data-usage budget for mobile
- [ ] Multiple queued downloads
- [ ] Share-sheet / intent receiver for URLs
- [ ] Optional server-side checksum verification (ETag / `Digest` header)
- [ ] Wi-Fi + Wi-Fi (dual-band) bonding on supported devices

## License

MIT — see [LICENSE](LICENSE).
