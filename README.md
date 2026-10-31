# Faster Downloads

An Android app that downloads **one file over two networks at the same time** — Wi-Fi *and*
mobile data — by splitting the file into HTTP `Range` chunks and pinning each group of sockets
to a radio with Android's `Network` API. It also opens **magnet links** and pulls them from the
BitTorrent swarm, over both radios.

![Android CI](https://github.com/owaiskhan-ash/Faster-Downloads/actions/workflows/android.yml/badge.svg)
[![Downloads](https://img.shields.io/badge/dynamic/json?url=https%3A%2F%2Fraw.githubusercontent.com%2Fowaiskhan-ash%2FFaster-Downloads%2Fmain%2Fstats%2Fdownloads.json&query=%24.all&label=downloads&color=00e5a0)](https://github.com/owaiskhan-ash/Faster-Downloads/releases)

**[Download the latest APK](https://github.com/owaiskhan-ash/Faster-Downloads/releases/latest)**

---

## Install

1. Open the [latest release](https://github.com/owaiskhan-ash/Faster-Downloads/releases/latest).
2. Download `FasterDownloads-<version>.apk` on the phone.
3. Allow *install unknown apps* for your browser/file manager, then open the APK.

Requires Android 8.0 (API 26) or newer.

## Downloads counter

Every release download is counted once a day and stored **in the repository**:
[`stats/downloads.json`](stats/downloads.json) holds the running total (`all`) and the per-version
numbers behind it. The merge keeps the highest count ever seen per version, so the counter never
goes down - removing a release or re-uploading an APK cannot reset it, and `git log` on that file
is the full history. The app shows the same number next to the day / night switch, and the badge
above reads the file directly.

## How it works

- **Always bonded.** `ConnectivityManager.requestNetwork()` is called once for Wi-Fi and once
  for mobile data; each radio gets its own OkHttp client whose `SocketFactory` and DNS come
  from its `Network` (Android's equivalent of `SO_BINDTODEVICE`), with a connection pool, so
  the 50/50 split never pays for a handshake twice. There is no setting to turn it down.
- **Ranged chunks, pulled from a shared queue.** The file is probed with `Range: bytes=0-0`,
  cut into 1–16 MB chunks, and each radio's workers pull from one queue — a stalled link never
  blocks the other. Every 4 s the split is re-weighted by measured throughput.
- **Verified and resumable.** Chunks are written with positional `FileChannel` writes, the
  finished file is checked for exact length and SHA-256, and completed chunk indexes are
  journalled so an interrupted transfer continues from the same byte.
- **Torrents are bonded too.** libtorrent's peer connections go through a loopback SOCKS5 proxy
  the app runs, which dials half of them on each radio; DHT and UDP trackers stay on the
  default network, so discovery keeps working either way.
- **Foreground service** with a progress notification, a cancel action and a partial wake lock.

More detail: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Features

- Highlighter-neon UI, hairline outlines, black ink, thin type
- **Day / night switch in the header** — inverts the palette and remembers the choice; until
  it is used the app follows the system theme
- **Magnet links**: `magnet:` downloads on libtorrent (UDP/HTTP trackers, DHT, PeX, µTP), and
  the app registers as a magnet handler so links open straight from the browser
- **Per-radio speed readout** — Wi-Fi and mobile data each show their own live speed and share
  of the total, for HTTP downloads and bonded torrents alike
- Windowed (3 s) speed and ETA, so numbers are readable instead of jittery
- **Several downloads at once**: three run in parallel, the rest queue; every card has its own
  pause / resume / cancel / remove buttons
- **Pause and resume** — the partial file and its journal survive, torrents keep what they got
- Automatic fallback to a single connection when a server ignores `Range`
- Everything lands in `Download/FasterDownloads` (MediaStore on Android 10+, direct file on 8–9)

## Torrents (magnet links)

Magnet links run on **[libtorrent](https://github.com/arvidn/libtorrent)** through
[libtorrent4j](https://github.com/aldenml/libtorrent4j) — the native engine behind LibreTorrent,
FrostWire and TorrDroid. UDP trackers (BEP-15), DHT (BEP-5), peer exchange (BEP-11), local
discovery (BEP-14), µTP (BEP-29) and encryption all work, and metadata (BEP-9) is fetched from
whichever peer answers first.

- Links are **parsed and repaired by the app first** (percent-decoding, `&amp;` repair, autolink
  removal, base32 info-hashes), so a link mangled by a chat app never reaches the engine
- The card shows **real engine numbers** — peers, seeds, DHT nodes, trackers and libtorrent's
  own smoothed rate — and a torrent that cannot find peers says so after three minutes instead
  of spinning forever
- **Bonding is retried, not one-shot**: it is attempted for every torrent and re-attempted every
  20 s for two minutes, because the session is created at launch when mobile data is often not
  up yet. It only steps aside when the proxy itself opened **no** peer connection across at
  least three attempts — a swarm that is merely quiet does not disable it — and the card always
  says which of those is the case
- **while bonded the session is TCP-only.** uTP is UDP, and libtorrent neither proxies it nor
  lets it alone: it picks uTP for every peer that supports it, and those connections would leave
  on the default network and never touch the second radio. Bonding therefore turns uTP off and
  hands it back when bonding is switched off

Files land in **`Download/FasterDownloads`**: single files go straight in, a multi-file torrent
gets its own folder with the structure preserved. The native engine is most of the download
size — the APK is **~18 MB** (two ABIs: `arm64-v8a`, `armeabi-v7a`).

Download only content you have the right to.

## Caveats

- **Range support is required** for the multipath path; hosts that ignore `Range` fall back to
  one connection.
- **OEM skins vary.** A few restrict concurrent Wi-Fi + cellular use or tear down the secondary
  network. Built and tested against stock Android 8–15.
- **Mobile data is really consumed** while bonded — typically `100 − wifiShare` percent of the
  bytes. Watch your data cap; carrier and tethering rules apply.
- **Battery.** Two radios plus several sockets drain faster than a normal download.
- **Torrent speed** depends on the swarm; the card names the reason (peers, seeds, DHT nodes)
  after three minutes rather than waiting forever.
- The release APK is **debug-signed** unless you provide signing secrets (see below): fine for
  sideloading, not for publishing to Play.

## Troubleshooting

**`HTTP 403 Forbidden`** — the host refused the request. The app already sends browser-shaped
headers, keeps one cookie jar across both radios and retries as a plain `GET` on a single
connection. If it still appears, the URL is not a direct file link: it needs a login cookie, is
an expiring one-time link, or sits behind a challenge. The error card shows the status line, the
`Server` header and a snippet of the response.

**`HTTP 416` / "The server ignored the Range header"** — the file changed on the server, or
ranges are not really supported. Start the download again.

**`bonding: unavailable - ...`** — the rest of the line is the reason: mobile data was not
reachable (no SIM, data off, carrier refused), so the torrent runs on the radio it has.
`bonding: off - the proxy opened no peers` is the deliberate fallback: the proxy failed to open
a single peer connection, so the torrent was moved back to the default network.

**A link row turns red and "Bonded" becomes "Single link"** — that radio was retired after
repeated failures (blocked IP, no data left, carrier 403) so the transfer could still finish.

## Build from source

```bash
git clone https://github.com/owaiskhan-ash/Faster-Downloads.git
cd Faster-Downloads
./gradlew :app:assembleDebug      # app/build/outputs/apk/debug/
./gradlew :app:testDebugUnitTest  # unit tests
```

Requires JDK 17 and the Android SDK (compileSdk 35). Opening the project in Android Studio works
as-is.

### Signed releases

CI builds an installable APK on every push. For a properly signed release, add these repository
secrets and push a `v*` tag (or a commit whose message contains `[release]`):

| Secret | Purpose |
| --- | --- |
| `KEYSTORE_BASE64` | `base64 -w0 release.keystore` |
| `KEYSTORE_PASSWORD` | keystore password |
| `KEY_ALIAS` | key alias |
| `KEY_PASSWORD` | key password |

Without them the release job signs with the debug key and says so in the notes.

## Project layout

```
app/src/main/java/com/fasterdownloads/
  core/NetworkLinks.kt        ConnectivityManager → two bound Network handles
  core/BondingProxy.kt        loopback SOCKS5 proxy that spreads peers over both radios
  core/BondingStatus.kt       pure state machine behind the card's bonding line
  core/HttpUtil.kt            network-pinned connections, probe, redirects, filenames
  core/ChunkPlan.kt           pure chunk-size / worker-split math (unit tested)
  core/MultiPathDownloader.kt HTTP engine: queue, workers, retries, resume, verify
  core/TorrentEngine.kt       libtorrent session, magnet adds, bonding retries
  core/MagnetDownloader.kt    magnet polling, progress, diagnostics, cancellation
  core/Storage.kt             MediaStore publishing (and pre-Q fallback)
  core/DownloadController.kt  StateFlow bridge between service and UI
  service/DownloadService.kt  foreground service, notification, wake lock
  ui/HomeScreen.kt            Compose UI
.github/workflows/android.yml tests → APKs → optional release
```

## License

MIT — see [LICENSE](LICENSE).
