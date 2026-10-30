# Architecture

Faster Downloads moves one file across two radios. This document explains the moving
parts, the concurrency model, and what happens when things fail.

## 1. Getting two networks at the same time

Android routes all traffic through a single default network, but an app may hold
additional networks for its own sockets. `NetworkLinks` does exactly that:

```kotlin
val request = NetworkRequest.Builder()
    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    .build()
connectivityManager.requestNetwork(request, callback, timeoutMs)
```

- One request for `TRANSPORT_WIFI`, one for `TRANSPORT_CELLULAR`.
- The callbacks stay registered while the download runs. Releasing them early lets the
  platform drop the secondary network mid-transfer.
- `onUnavailable`/timeout degrades gracefully: with one link the engine still runs, it
  just has one radio's worth of bandwidth.
- On devices where the OEM blocks a second network, the cellular request simply never
  delivers a `Network`; the UI reports "single link" instead of failing.

This is the Android replacement for `SO_BINDTODEVICE` used by Linux multipath tools such as
[Plexo](https://github.com/anmolkapil/plexo): we do not bind an interface, we bind a
`Network`.

## 2. Pinning sockets

Every HTTP request is created with

```kotlin
val connection = network.openConnection(URL(url)) as HttpURLConnection
```

`Network.openConnection` returns a `URLConnection` whose sockets — including DNS — are bound
to that network. `HttpUtil.open` additionally handles redirects manually (so a redirect
cannot silently hop back to the default network), sets `Accept-Encoding: identity` (ranges
address wire bytes, never gzipped bytes), and applies sane timeouts.

## 3. Splitting the file

`HttpUtil.probe` issues `GET` with `Range: bytes=0-0`:

- `206` + `Content-Range: bytes 0-0/<total>` → size known, ranges supported.
- `200` → server ignored the range; ranges unsupported.
- Anything else → fall back to `HEAD`.

`ChunkPlan` then picks a chunk size:

```
targetChunks = max(workers * 6, 12)
chunkSize    = clamp(total / targetChunks, 1 MB, 16 MB)    # and <= 4000 chunks/file
```

Chunks are deliberately few and large: every range request opens a fresh connection (so a
fresh TLS handshake), and 256 KB chunks made handshake latency a visible part of the
transfer. Six chunks per worker still lets a fast radio drain work the slow radio has not
claimed, while the tail stays at one chunk per worker.

## 4. Concurrency model

```
                       ┌──────────────────────────┐
 pending: ArrayDeque ──▶│ nextChunk()  (locked)    │
 (Chunk: index,s,e)    └──────────▲───────────────┘
                                  │ requeue() on failure
      ┌───────────────────────────┴────────────────────────┐
      │                                                    │
 ┌────▼─────┐  ┌──────────┐                        ┌───────▼──┐  ┌──────────┐
 │ wifi w0  │  │ wifi w1  │  ...                   │ cell  w0 │  │ cell  w1 │
 └────┬─────┘  └──────────┘                        └───────┬──┘  └──────────┘
      │ fetchChunk(range=start-end)                        │
      └────────────► FileChannel.write(buffer, position) ◀──┘   (positional, lock-free)
```

- **Shared queue, not static partitions.** Workers pull the next chunk when they finish
  one. A fast link naturally does more chunks; a stalled link never holds up the rest.
- **Thread-per-connection.** Range downloads are I/O bound and each worker blocks on its
  own socket, so plain threads keep the code (and the stack traces) simple.
- **Positional writes.** `FileChannel.write(ByteBuffer, position)` does not move the
  channel's file pointer, so concurrent workers cannot corrupt each other's offsets and no
  write lock is needed.
- **Weighting.** `ChunkPlan.workerCounts(totalWorkers, wifiShare)` starts 70/30 by default.
  Every 4 s `adaptWorkerAllocation` compares each link's measured bytes to the requested
  share and spawns an extra worker for an under-used radio (capped at
  `connectionsPerNetwork + 2`). Spawned workers exit as soon as the queue empties.
- **Supervisor.** The run thread watches `workersAlive` and the queue. If every worker dies
  with chunks still pending it revives one on the healthiest link (max 8 times) or fails the
  download with a reason.
- **Retries.** A failing chunk is retried up to 4 times with linear backoff; after that the
  run is marked fatal, because a silently missing range would corrupt the file.

## 5. Progress accounting

- `doneBytes` is incremented as bytes hit the disk and rolled back when a chunk attempt
  fails, so retries never inflate progress.
- A daemon ticker emits a `DownloadUiState` snapshot every 450 ms. Speeds come from
  `SpeedMeter`, a 3 s sliding window: instantaneous deltas swing wildly with chunk
  boundaries and write flushes, whereas the windowed average is stable and still reacts to
  real changes within a few seconds. ETA is derived from that smoothed speed. The service turns that into throttled notification updates; Compose observes the
  same `StateFlow`.

## 6. Resume journal

While downloading, `<name>.fdstate` holds:

```json
{ "url": "...", "total": 104857600, "chunkSize": 1048576,
  "done": [0,1,2,5,6], "updated": 1750000000000 }
```

It is written every ~3 s and on cancellation/failure. On start, the journal is only trusted
when `url`, `total`, `chunkSize` and the on-disk `.part` file length all match; otherwise it
is discarded. Completed chunks keep their bytes, unfinished ones are re-requested and
overwritten in place — resume needs no re-download of the whole file.

## 7. Finish and publish

1. `fsync` the channel, close file handles.
2. Verify `file.length() == totalBytes` and that every chunk index is accounted for.
3. SHA-256 the file (optional but on by default).
4. Publish:
   - Android 10+: `MediaStore.Downloads` insert with `IS_PENDING=1`, stream copy, clear
     `IS_PENDING` → `Download/FasterDownloads/<name>`.
   - Android 8–9: copy into the public Downloads directory and notify `MediaScanner`.
5. Delete the `.part` and journal files, emit `COMPLETED` with path, URI and hash.

## 8. Foreground service

`DownloadService` (type `dataSync`) is started with `startForegroundService`, immediately
calls `startForeground`, and holds a partial wake lock for at most an hour. It owns the
engine, forwards snapshots to `DownloadController`, throttles notification updates to whole
percent changes, and exposes a `Cancel` action that disconnects in-flight sockets so a
blocked read fails instantly instead of waiting for its timeout.

## 9. Failure modes

| Situation | Behaviour |
| --- | --- |
| No second radio / OEM restriction | Single-link download, UI says so |
| Server ignores `Range` | Single-stream fallback, no speedup |
| Server reports no size at all | Single-stream fallback; size discovered from `Content-Length` |
| Link dies mid-transfer | Chunks in flight fail, retry on the surviving link, engine continues |
| Chunk fails 4× | Download aborts with the byte range and reason |
| Disk full | Positional write throws, abort with the failing offset |
| User cancels | Sockets dropped, journal written, `CANCELLED` state, partial kept for resume |
| Process killed | Journal + `.part` survive; a new run resumes |
