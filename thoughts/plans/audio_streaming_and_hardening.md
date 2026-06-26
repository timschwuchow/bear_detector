# Plan: Live Audio Streaming + Overnight Hardening

> Companion to `thoughts/research/codebase_overview.md`. Read that first for the current
> architecture; this doc is the concrete, ordered implementation plan.

## Context

Bear Detector currently only sends **threshold alerts** (`BEAR_ALERT` packets) from the
Listen phone (baby's room) to the Monitor phone (parent). The mic's raw PCM is computed
into an RMS amplitude and then **thrown away**. The Monitor side has no service at all —
`Discovery` + `AlertReceiver` run inline in the `MonitorScreen` composable, so they die
when the screen turns off or the composable leaves composition. Neither side recovers from
a reboot.

This change adds two capabilities:

1. **Live audio streaming** — the Listen phone streams raw PCM over LAN UDP; the Monitor
   phone plays it through `AudioTrack` in real time.
2. **Hardening** — a real foreground service + wake lock on the Monitor side so it survives
   overnight with the screen off, plus a boot receiver that posts a "tap to resume"
   notification after a reboot.

### Confirmed product decisions
- **Streaming model: always-on continuous** from Listen → all Monitor peers. No control protocol — the Listen phone streams every mic chunk to every discovered MONITOR peer whenever the service is running.
- **Monitor playback: muted by default.** The Monitor service receives the stream continuously but only writes to `AudioTrack` while the parent has tapped "Listen". Alerts still fire regardless of mute state.
- **Boot behavior: notification to resume.** A service started from `BOOT_COMPLETED` cannot access the microphone in the background on Android 14+, so instead of silently restarting we post a high-priority notification prompting the user to reopen the app (which starts the mic service with full permission).

### Audio parameters
- 44100 Hz, mono, `ENCODING_PCM_16BIT` — **identical** on sender (`AudioRecord`) and receiver (`AudioTrack`).
- Chunk size: **1024 shorts = 2048 bytes ≈ 23 ms**, one UDP datagram per chunk (~43 packets/s, ~88 KB/s raw).
  A 2048-byte datagram exceeds WiFi MTU (~1472) so IP fragments it, but it is delivered atomically on LAN — acceptable.
  *Tunable:* drop to 640 shorts (1280 bytes) if fragmentation causes audible drops.
- Endianness: convert shorts → **little-endian** bytes on send; `AudioTrack` PCM_16BIT reads native (little-endian on Android) → consistent. No app-level reassembly of shorts on the receiver — write the raw bytes straight to `AudioTrack`.
- New port **9879** (UDP unicast) for the audio stream. Existing ports unchanged: 9877 discovery, 9878 alerts.

| Port | Protocol | Purpose |
|------|----------|---------|
| 9877 | UDP broadcast | Discovery (`BEAR_LISTEN` / `BEAR_MONITOR`) |
| 9878 | UDP unicast | Alerts (`BEAR_ALERT`) |
| 9879 | UDP unicast | Audio stream (raw little-endian PCM bytes) — **new** |

---

## Implementation steps (ordered so the project compiles at each stage)

### A. Listen / streaming side

**1. `util/SoundMeter.kt` — expose raw PCM chunks**
- Add `const val CHUNK_SAMPLES = 1024` (companion or top-level const).
- Add `fun readChunk(): ShortArray?` — blocking `recorder.read(buf, 0, CHUNK_SAMPLES)` into a fresh
  `ShortArray(CHUNK_SAMPLES)`; return the array when `read == CHUNK_SAMPLES`, else `null`.
  The blocking read paces the caller's loop naturally — no `delay()` needed.
- Add `fun rms(buffer: ShortArray, length: Int = buffer.size): Double` — extract the existing RMS math out of `getAmplitude()`.
- Remove `getAmplitude()` (sole caller is `ListenService`, updated in step 3). The `AudioRecord` internal buffer (`getMinBufferSize*2`, ~3528 shorts) ≥ CHUNK_SAMPLES, so reads are safe.

**2. `network/AudioStreamer.kt` — NEW, persistent-socket PCM sender**
- A **class** (not an `object` like `AlertSender`), instantiated by `ListenService`, holding **one persistent
  `DatagramSocket`** reused across all sends. `AlertSender` opens a fresh socket per call — fine for occasional
  alerts, but at ~43 sends/sec that would churn ephemeral ports.
- Port `9879`.
- `fun send(chunk: ShortArray, length: Int, targets: List<String>)`:
  - Convert shorts → little-endian `ByteArray` via `ByteBuffer.allocate(length*2).order(ByteOrder.LITTLE_ENDIAN)`.
  - Send one `DatagramPacket` per target IP.
  - Cache `InetAddress` per IP string (small map) to avoid `InetAddress.getByName` on every packet.
  - Wrap sends in try/catch + log (mirror `AlertSender`'s fire-and-forget style).
- `fun close()` — close the socket; called from `ListenService.onDestroy`.

**3. `service/ListenService.kt` — continuous read loop + streaming + was-running flag**
- Replace the 200 ms poll in `startListening()` with a tight loop paced by `soundMeter.readChunk()`:
  ```
  while (isActive) {
      val chunk = soundMeter.readChunk() ?: continue
      val amplitude = SoundMeter.rms(chunk)
      currentAmplitude.value = amplitude
      // existing threshold + 5s cooldown + AlertSender.sendAlert(monitors) — UNCHANGED
      audioStreamer.send(chunk, chunk.size, discovery.getPeers("MONITOR"))  // every chunk, always
  }
  ```
  (Drop the trailing `delay(200)`; the blocking read provides cadence.)
- Add `private val audioStreamer = AudioStreamer()`; call `audioStreamer.close()` in `onDestroy`.
- **Was-running flag** for the boot receiver: in `onStartCommand`, set `SharedPreferences` key
  `listen_was_running = true`. (Cleared by `ListenScreen` on explicit user Stop — step 7. START_STICKY
  restarts intentionally keep it true so an OS-kill-then-reboot still prompts.)

### B. Monitor / playback side

**4. `network/AudioPlayer.kt` — NEW, UDP receiver + AudioTrack with mute control**
- Class with `start()`, `stop()`, and `fun setPlaying(on: Boolean)`.
- Bind `DatagramSocket(null){ reuseAddress = true; bind(InetSocketAddress(9879)) }` (mirror `AlertReceiver`).
- `AudioTrack` MODE_STREAM, 44100, `CHANNEL_OUT_MONO`, `ENCODING_PCM_16BIT`,
  buffer = `AudioTrack.getMinBufferSize(...) * 2` (a few chunks of jitter headroom).
- Receive loop on `Dispatchers.IO` (`ByteArray(4096)` recv buffer):
  - if `playing` → `audioTrack.write(packet.data, 0, packet.length)`
  - if muted → discard the packet (keep `AudioTrack` paused).
- `setPlaying(true)` → `audioTrack.play()`; `setPlaying(false)` → `audioTrack.pause()` + `flush()`.
- Optional: track `lastPacketTime` for a "stream available" indicator on the UI.
- `AudioTrack.write()` is blocking — must stay on `Dispatchers.IO`.

**5. `service/MonitorService.kt` — NEW foreground service (mirror `ListenService` structure)**
- `START_STICKY`, `PARTIAL_WAKE_LOCK`, `WifiManager.MulticastLock` (required to receive broadcasts on Android 9+).
- Runs `Discovery.startBroadcasting("MONITOR")` + `startListening { … }` updating `listenerCount`.
- Runs existing `AlertReceiver` → on alert: `NotificationHelper.showAlert(this)`, bump `alertCount`,
  set `alertActive` with the existing ~5 s reset pattern.
- Runs `AudioPlayer` bound on 9879.
- `companion object` `MutableStateFlow`s: `isRunning`, `alertActive`, `alertCount`, `listenerCount`, `isListening`.
- Toggle playback: a companion method (or a flow the screen sets) wired to `audioPlayer.setPlaying(...)` and `isListening`.
- Foreground notification built inline (as `ListenService` does) on a new low-priority channel;
  `ServiceCompat.startForeground(..., FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)`.
- `onDestroy`: stop `Discovery` + `AlertReceiver` + `AudioPlayer`, release wake/multicast locks, reset flows.

**6. `notification/NotificationHelper.kt` — monitor channel + resume prompt**
- Add `MONITOR_CHANNEL_ID` low-priority channel ("Monitoring baby's room") inside `createChannel`.
- Add `fun showResumePrompt(context)` — high-priority notification on the existing alert channel,
  title "Tap to resume monitoring", with a `PendingIntent.getActivity(...)` to `MainActivity`. Used by the boot receiver.

**7. `ui/MonitorScreen.kt` — wire to `MonitorService` (and `ListenScreen.kt` flag cleanup)**
- Delete the `DisposableEffect` that creates `Discovery` + `AlertReceiver` inline.
- Start/stop `MonitorService` via `startForegroundService` / `stopService` (mirror `ListenScreen`),
  including the **battery-optimization exemption prompt** copied from `ListenScreen` (~lines 143–150).
- Observe `MonitorService` companion flows with `collectAsState()` (`alertActive`, `alertCount`, `listenerCount`, `isListening`).
- Add a **"Listen" / "Mute" toggle** button calling the service toggle (drives `AudioPlayer.setPlaying`).
- On **Back**: `stopService(MonitorService)` (mirror `ListenScreen`'s back behavior).
- In **`ui/ListenScreen.kt`**: on explicit user **Stop** (and Back-while-running), clear the
  `listen_was_running` SharedPreferences flag so a later reboot does **not** prompt to resume.

### C. Boot recovery + manifest

**8. `service/BootReceiver.kt` — NEW `BroadcastReceiver`**
- Handle `ACTION_BOOT_COMPLETED` (optionally `LOCKED_BOOT_COMPLETED`).
- If `SharedPreferences` `listen_was_running == true` → `NotificationHelper.showResumePrompt(context)`.
  Do **not** start the mic service directly (Android 14+ blocks background mic access from a boot-started service).
- Define the prefs file name + key as shared constants once (e.g. `BootReceiver` companion), referenced by `ListenService` and `ListenScreen`.

**9. `AndroidManifest.xml`**
- Add permissions:
  `android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK` and `android.permission.RECEIVE_BOOT_COMPLETED`.
- Declare:
  `<service android:name=".service.MonitorService" android:foregroundServiceType="mediaPlayback" android:exported="false" />`
- Declare:
  `<receiver android:name=".service.BootReceiver" android:exported="true">` with an
  `<intent-filter>` for `android.intent.action.BOOT_COMPLETED`.

### D. Optional (low priority, skippable for v1)
- `MainActivity` / Monitor window: `window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)` while in
  Monitor mode so the parent can glance at status without unlocking.

---

## Files summary

**New (4):** `network/AudioStreamer.kt`, `network/AudioPlayer.kt`, `service/MonitorService.kt`, `service/BootReceiver.kt`
**Modified:** `util/SoundMeter.kt`, `service/ListenService.kt`, `notification/NotificationHelper.kt`, `ui/MonitorScreen.kt`, `ui/ListenScreen.kt`, `AndroidManifest.xml`
**Reused as-is:** `network/Discovery.kt`, `network/AlertReceiver.kt`, `network/AlertSender.kt`

Stays within project conventions: Compose only, **no third-party deps** (`AudioTrack`, `DatagramSocket`,
`ByteBuffer` are all stdlib/Android), file count grows modestly (+4).

---

## Risks / gotchas
- **FGS media-playback type** must be declared in *both* the manifest *and* the `ServiceCompat.startForeground()`
  call (`FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK`), or Android 14+ crashes the Monitor service. (Same dual-declaration
  rule the Listen service already follows for `microphone`.)
- **IP fragmentation** of 2048-byte datagrams: fine on LAN; if drops occur, lower `CHUNK_SAMPLES` to 640.
- **AudioTrack underruns** from jittery packet arrival cause glitches; start simple, add a small queue / jitter
  buffer only if needed. A baby monitor tolerates 200–500 ms latency.
- **MulticastLock** must be acquired in `MonitorService` (currently the inline `MonitorScreen` path never needed it,
  but a backgrounded service does on Android 9+).
- **Persistent send socket** in `AudioStreamer` (not per-call) to avoid ephemeral-port churn.
- **Blocking calls** (`AudioRecord.read`, `AudioTrack.write`, `DatagramSocket.receive`) all stay on `Dispatchers.IO`.
- Both services bind 9877/9879 — only a conflict if one phone runs Listen *and* Monitor at once, which the
  single-mode UI prevents.

---

## Verification
1. **Build:** `./gradlew assembleDebug` (or Android Studio, which bundles Gradle + JDK).
2. **Two phones, same WiFi.** Phone A = Listen, Phone B = Monitor. (Emulator mic/UDP is unreliable — use real devices.)
3. **Streaming:** Listen → amplitude moves, "Connected monitors" ≥ 1. Monitor → "Connected listeners" ≥ 1;
   tap **Listen** → hear room audio in real time; tap **Mute** → silence while the service keeps running.
4. **Alerts:** make a loud sound → Monitor gets the high-priority `BEAR_ALERT` notification even while muted.
5. **Overnight / screen off:** lock both phones → audio (when unmuted) and alerts continue (wake locks + FGS).
   Confirm the Monitor process survives: `adb shell dumpsys activity services | grep -i monitor`.
6. **Boot:** with Listen running, reboot Phone A → "Tap to resume monitoring" notification appears; tapping opens
   the app so the mic service starts with full permission. Stop Listen first, then reboot → **no** prompt (flag cleared).
7. **Logcat tags to watch:** `ListenService`, `AudioStreamer`, `AudioPlayer`, `MonitorService`, `Discovery`.

---

## Suggested implementation order recap
1 → 2 → 3  (Listen/stream send path)
→ 4 → 6 → 5 → 7  (Monitor receive/playback path)
→ 8  (boot recovery)
→ 9  (manifest wiring)
→ build + two-device verification.
