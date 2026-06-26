# Bear Detector — Codebase Research
## Features to implement
1. Audio streaming: the monitor (baby's room) streams live audio over LAN; the receiver (parent's phone) plays it in real time.
2. Hardening: the receiver can wake up the monitor, and both sides survive long overnight runs without dying.

---

## Current architecture summary

### Roles
- **Listen mode** = baby's room phone. Runs mic, detects sound threshold, sends `BEAR_ALERT` UDP packets.
- **Monitor mode** = parent's phone. Receives `BEAR_ALERT` packets, shows a notification.

The naming is confusing for the new feature (the "listener" is the sender, the "monitor" is the receiver), so take care when reading code comments.

### Audio capture — `SoundMeter.kt`
- Uses `AudioRecord` with `MediaRecorder.AudioSource.MIC`.
- Sample rate: **44100 Hz**, mono, `ENCODING_PCM_16BIT`.
- Buffer: `AudioRecord.getMinBufferSize(...) * 2` (typically ~3500–8800 bytes on most devices).
- `getAmplitude()` does a **blocking read** (must be on `Dispatchers.IO`), computes RMS of the Short buffer, returns it as a `Double`.
- Currently the raw PCM shorts are **thrown away** after computing RMS — this is the data we need to stream.

### Foreground service — `ListenService.kt`
- `START_STICKY`: Android will restart it if killed.
- Holds a `PARTIAL_WAKE_LOCK` (CPU stays on even with screen off).
- Holds a `WifiManager.MulticastLock` (UDP broadcast not dropped by Android WiFi driver).
- Broadcasts its existence via Discovery every 3 s as `BEAR_LISTEN`.
- Polls `SoundMeter.getAmplitude()` every **200 ms** in a coroutine loop.
- On threshold cross (with 5 s cooldown): sets `alertActive`, sends `BEAR_ALERT` to all known `MONITOR` peers via `AlertSender`.
- `companion object` exposes `MutableStateFlow` fields so the UI composable can observe state without binding the service.

### Network — Discovery (`Discovery.kt`)
- Port **9877** (UDP broadcast).
- Broadcast interval: 3 s. Peer expiry: 15 s.
- Payload format: `BEAR_<MODE>` (e.g. `BEAR_LISTEN`, `BEAR_MONITOR`).
- Peer IP is taken from `packet.address` (not payload) — avoids spoofing.
- Both sides broadcast their mode and listen for peers of the opposite mode.
- `getPeers(mode)` returns a `List<String>` of IPs for that mode.

### Network — AlertSender (`AlertSender.kt`)
- Port **9878** (UDP unicast to each discovered peer).
- Opens a fresh `DatagramSocket` per send call (fire-and-forget).
- Payload: `BEAR_ALERT:<timestamp>`.

### Network — AlertReceiver (`AlertReceiver.kt`)
- Port **9878** (UDP listen).
- Single blocking `receive()` loop on `Dispatchers.IO`.
- On `BEAR_ALERT:` prefix match: invokes `onAlert()` callback.
- Lives in `MonitorScreen` composable via `DisposableEffect` — **not a service**. Dies when the screen is off or the composable leaves composition.

### UI — `BearDetectorApp.kt`
- Simple `mutableStateOf("home" | "listen" | "monitor")` drives which composable is shown.
- No navigation library.
- Permissions requested on launch: `RECORD_AUDIO` + `POST_NOTIFICATIONS` (API 33+).

### UI — `MonitorScreen.kt`
- Creates `Discovery` and `AlertReceiver` instances inside a `DisposableEffect`.
- No service — networking runs directly on a coroutine scope tied to the composable's lifecycle.
- Shows a green/red circle, connected listener count, alert count.

### UI — `ListenScreen.kt`
- Starts/stops `ListenService` via `Context.startForegroundService` / `stopService`.
- Shows amplitude, threshold slider, peer count.
- On "Start" tap: checks battery optimization exemption and prompts if not exempt (good, keep this).

### Manifest — `AndroidManifest.xml`
Declared permissions:
- `RECORD_AUDIO`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`
- `INTERNET`, `ACCESS_WIFI_STATE`, `ACCESS_NETWORK_STATE`, `CHANGE_WIFI_MULTICAST_STATE`
- `WAKE_LOCK`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `POST_NOTIFICATIONS`

Only one service declared: `ListenService` with `foregroundServiceType="microphone"`.

No `RECEIVE_BOOT_COMPLETED`, no `BroadcastReceiver`, no WorkManager.

### Build
- Min SDK 26, compile/target SDK 35.
- No third-party deps beyond the standard Compose BOM + AndroidX.

---

## What needs to change for Feature 1: Audio Streaming

### The core problem
`SoundMeter.getAmplitude()` discards the PCM buffer. We need to expose those raw samples and ship them over UDP to the monitor phone, which plays them back via `AudioTrack`.

### Key constraints
- 44100 Hz × 2 bytes/sample mono = **88,200 bytes/sec raw PCM**.
- UDP MTU on WiFi is typically 1400–1472 bytes payload. A min-buffer read of ~3500 bytes = ~2.5 packets per chunk, at ~25 chunks/sec.
- This is well within LAN bandwidth. No compression needed (but we could add simple 8-bit mu-law or ADPCM later if needed).
- No ordering guarantees from UDP. For audio, dropping a packet is better than reordering — just play whatever arrives.

### SoundMeter changes
Option A: Add a second method `readBuffer(): ShortArray?` that returns raw samples alongside RMS. The listen loop calls both.
Option B: Replace `getAmplitude()` with a callback/flow that emits `(rms, buffer)` pairs. Cleaner but more invasive.
Option A is simpler and keeps the existing threshold logic unchanged.

The buffer size needs to be right: too small = many tiny packets with high overhead; too large = noticeable latency. A good target is 20–40 ms of audio per packet = 44100 * 0.03 * 2 = ~2600 bytes. We may want to use 1024 or 2048 samples per chunk regardless of `AudioRecord`'s preferred buffer size.

### AudioStreamer (new file)
A new class `AudioStreamer` (analogous to `AlertSender`) that:
- Holds a `DatagramSocket`.
- Accepts a `ShortArray` of PCM samples and sends them as raw bytes to each `MONITOR` peer on a new port (e.g. **9879**).
- Runs on `Dispatchers.IO`.

### AudioPlayer (new file)
A new class `AudioPlayer` that:
- Creates an `AudioTrack` in streaming (MODE_STREAM) mode.
- Sample rate 44100, mono, PCM_16BIT (must match sender).
- Binds a `DatagramSocket` on port 9879.
- Receives packets in a loop and writes raw bytes to `AudioTrack.write()`.
- Lives in a foreground service on the Monitor side (see Feature 2).

### ListenService changes
- After computing amplitude, also read the PCM buffer.
- On streaming enabled: send buffer to all MONITOR peers via `AudioStreamer`, regardless of threshold.
- `BEAR_ALERT` threshold alerts can remain as-is or be deprecated in favor of continuous audio.

### Protocol for audio packets
Simple header to distinguish from alert packets on the same socket (or use a separate port):
- Use a separate port 9879. Payload = raw PCM bytes (ShortArray as little-endian bytes).
- No sequence number needed for basic version; receiver just plays whatever arrives.

---

## What needs to change for Feature 2: Hardening the receiver (Monitor side)

### Current problem
`AlertReceiver` and `Discovery` for Monitor mode live inside `MonitorScreen` composable via `DisposableEffect`. This means:
- When the screen turns off, the activity may go to the background.
- Android can kill the process or the composable can be disposed.
- There is no wake lock on the monitor side — CPU may sleep, stopping the receive loop.
- No foreground service = Android can kill the monitor at any time under memory pressure.

### Monitor foreground service (`MonitorService.kt`) — new file
Create a `MonitorService` analogous to `ListenService`:
- `START_STICKY` to restart after kill.
- `PARTIAL_WAKE_LOCK` to keep CPU alive.
- `WifiManager.MulticastLock` (needed to receive broadcasts).
- Runs `Discovery.startBroadcasting("MONITOR")` and `Discovery.startListening(...)`.
- Runs `AlertReceiver` (and eventually `AudioPlayer`) inside the service.
- Exposes state via `companion object MutableStateFlow` fields.
- Shows a foreground notification ("Monitoring baby's room...").

Foreground service type for monitor: it needs audio output (for streaming), so declare `foregroundServiceType="mediaPlayback"`. For now (alerts only), `foregroundServiceType` may not be required but is still good practice.

### Manifest additions for MonitorService
- Declare `<service android:name=".service.MonitorService" android:foregroundServiceType="mediaPlayback" android:exported="false" />`.
- Add `<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />`.

### Waking up the monitor (receiver wakes sender)
If the monitor phone wants to remotely ensure the listen service is still alive on the baby's-room phone, there are a few approaches:

**Option A: Heartbeat + reconnect via Discovery**
- The listen-side Discovery already broadcasts `BEAR_LISTEN` every 3 s. If the monitor doesn't see a `BEAR_LISTEN` broadcast for >15 s, it knows the sender died.
- Monitor could show a UI warning ("Listener disconnected") and ring an alarm.
- This is passive — monitor detects failure but can't restart the listen service.

**Option B: Remote wake command over UDP**
- Monitor sends a `BEAR_WAKE` packet to the listen phone's IP.
- `ListenService` (or a `BroadcastReceiver` if the service is dead) receives it and restarts the service.
- Problem: if `ListenService` is dead, nothing is listening on port 9878/9877.

**Option C: Android `BroadcastReceiver` with `BOOT_COMPLETED` + persistent restart**
- Add a `BroadcastReceiver` for `BOOT_COMPLETED` to auto-start `ListenService` on device boot.
- `START_STICKY` already handles restart after kill by the system.
- Combined these make the listen phone self-healing without needing the monitor to actively wake it.

**Option D: Keep-alive notification on listen phone**
- The foreground notification on `ListenService` is already ongoing. If it disappears (user or OS killed it), the user sees it.
- We could add a periodic self-check in `ListenService` (not needed since `START_STICKY` handles this).

**Recommended approach**: Option C is the right foundation (boot receiver + `START_STICKY`). Option B (remote wake over UDP) is complex and unreliable if the service is dead. The monitor should show a "disconnected" warning when heartbeats stop.

### Screen-on lock on monitor phone
When the monitor receives audio/alerts, it may want to keep the screen on (so the parent can see it in the morning without unlocking). This can be done with:
- `WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON` on the Activity window.
- Or `PowerManager.FULL_WAKE_LOCK` (deprecated API 17+ but still functional).
- Best approach: `window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)` in `MainActivity`.

### Battery optimization on monitor side
`ListenScreen` already prompts for battery optimization exemption on the listen side. We need the same for the monitor side when starting `MonitorService`.

---

## Port allocation summary
| Port | Protocol | Purpose |
|------|----------|---------|
| 9877 | UDP broadcast | Discovery (BEAR_LISTEN / BEAR_MONITOR) |
| 9878 | UDP unicast | Alerts (BEAR_ALERT) |
| 9879 | UDP unicast | Audio stream (raw PCM bytes) — new |

---

## Key risks and gotchas

### Audio streaming latency
UDP + no buffering strategy on the receiver means jitter. Android `AudioTrack` in `MODE_STREAM` has its own buffer. If we write too slowly (gaps between packets) we hear glitches. Options:
- Use a small jitter buffer (queue incoming packets, play with slight delay).
- Or accept glitches and tune chunk size for low latency.
For a baby monitor, low latency matters less than reliability — a 200–500 ms buffer is fine.

### `AudioTrack` must be in the right thread
`AudioTrack.write()` is blocking. Must run on `Dispatchers.IO`, not main thread.

### Monitor `AudioTrack` and `foregroundServiceType`
For API 34+, playing audio in a foreground service requires `foregroundServiceType="mediaPlayback"` AND the service must call `ServiceCompat.startForeground(...)` with `FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK`. Without this, Android 14+ will crash.

### `RECEIVE_BOOT_COMPLETED` permission
Adding boot receiver requires `<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />` in manifest.

### MonitorScreen composable still needed
Even with a `MonitorService`, the composable should still show status. It should observe the service's `companion object` flows, same pattern as `ListenScreen` → `ListenService`.

### Multicast lock on monitor side
Currently `MonitorScreen` creates a `Discovery` instance without acquiring a multicast lock. This works only because the lock was never needed for the monitor (it only receives broadcast from the listen side). But once we move to `MonitorService`, we should acquire the lock there too for reliability.

---

## File change summary

### New files
- `app/.../service/MonitorService.kt` — foreground service for monitor mode
- `app/.../network/AudioStreamer.kt` — sends PCM chunks over UDP
- `app/.../network/AudioPlayer.kt` — receives PCM chunks and plays via AudioTrack

### Modified files
- `SoundMeter.kt` — expose raw PCM buffer alongside RMS
- `ListenService.kt` — call AudioStreamer for each buffer read
- `MonitorScreen.kt` — connect to MonitorService instead of running Discovery/AlertReceiver inline
- `AndroidManifest.xml` — add MonitorService, FOREGROUND_SERVICE_MEDIA_PLAYBACK permission, RECEIVE_BOOT_COMPLETED permission, boot BroadcastReceiver
- `BearDetectorApp.kt` — possibly add screen-keep-on flag

### Potentially new
- `BootReceiver.kt` — `BroadcastReceiver` for `BOOT_COMPLETED` to auto-start `ListenService`
