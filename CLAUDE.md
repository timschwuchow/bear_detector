# CLAUDE.md

## Project Overview

Bear Detector is a native Android baby monitor app (Kotlin + Jetpack Compose). Phones on the same WiFi discover each other via UDP broadcast and exchange sound alerts and live audio over LAN. No backend, no cloud, no third-party dependencies.

## Architecture

- **Single-module Android project** — package `com.beardetector`, min SDK 26, compile SDK 35
- **No navigation library** — screen state managed via `mutableStateOf("home" | "listen" | "monitor")` in `BearDetectorApp.kt`
- **Two foreground services** — `ListenService.kt` (mic, `microphone` type) and `MonitorService.kt` (audio playback, `mediaPlayback` type). Both hold a wake lock and multicast lock so they survive screen-off.
- **UDP protocol** — discovery on port 9877, alerts on port 9878, audio stream on port 9879. Discovery/alerts are plain ASCII; audio is raw little-endian PCM bytes (no serialization).
- **Live audio streaming** — Listen continuously sends every mic chunk to all MONITOR peers; Monitor receives continuously but only writes to `AudioTrack` while unmuted (parent taps "Listen"). Alerts fire regardless of mute state.
- **Boot recovery** — `BootReceiver` posts a "tap to resume" notification after a reboot if Listen was running (Android 14+ blocks restarting the mic from a background-started service). State tracked via a `SharedPreferences` flag owned by `BootReceiver`.
- **All networking** uses `java.net.DatagramSocket` — no HTTP, no libraries
- **State sharing** between each service and its UI via `companion object` `MutableStateFlow` fields (`ListenService`, `MonitorService`)

## Key Files

- `ListenService.kt` — core of the Listen side. Foreground service: mic input → RMS threshold check → UDP alert + continuous audio streaming. Holds wake lock and multicast lock; sets the `listen_was_running` flag.
- `MonitorService.kt` — core of the Monitor side. Foreground service: Discovery (MONITOR) + `AlertReceiver` + `AudioPlayer`. Holds wake lock and multicast lock. `setListening()` companion toggles playback.
- `SoundMeter.kt` — wraps `AudioRecord`. `readChunk()` returns one PCM chunk (`CHUNK_SAMPLES`); static `rms()` computes amplitude. Must run on background thread.
- `AudioStreamer.kt` — persistent-socket PCM sender (port 9879). Converts shorts → little-endian, caches `InetAddress`. One instance owned by `ListenService`.
- `AudioPlayer.kt` — UDP receiver + `AudioTrack` (port 9879). Muted by default; writes raw packet bytes only when playing.
- `Discovery.kt` — UDP broadcast/listen for peer discovery. Maintains peer map with 15s expiry.
- `AlertSender.kt` / `AlertReceiver.kt` — send and receive `BEAR_ALERT` packets.
- `BootReceiver.kt` — `BOOT_COMPLETED` → resume notification. Owns the shared `SharedPreferences` name/key constants.
- `NotificationHelper.kt` — three channels: low-priority for each service notification (listen, monitor), high-priority for alerts. Also `showResumePrompt()`.
- `AndroidManifest.xml` — permissions, two service declarations, and the boot receiver. Each service must declare its `foregroundServiceType`.

## Build & Test

```bash
# Build debug APK (requires Android Studio JDK or JAVA_HOME set)
./gradlew assembleDebug

# Install on connected device
adb install app/build/outputs/apk/debug/app-debug.apk
```

Open in Android Studio for the easiest build experience — it bundles its own Gradle and JDK.

## Common Gotchas

- `foregroundServiceType` must be declared in BOTH the manifest AND passed to `ServiceCompat.startForeground()` — missing either crashes on Android 14+ (`microphone` for `ListenService`, `mediaPlayback` for `MonitorService`)
- `WifiManager.MulticastLock` must be acquired or UDP broadcast packets are silently dropped on Android 9+
- `AudioRecord.read()`, `AudioTrack.write()`, and `DatagramSocket.receive()` are all blocking — always use `Dispatchers.IO`
- Bound socket ports (9877, 9878, 9879) must use `reuseAddress = true` before binding, and sockets must be closed in `onDestroy`
- Wake lock is needed to keep the CPU alive when screen is off — without it, `AudioRecord` stops reading
- Audio params must be **identical** on both sides: 44100 Hz, mono, `PCM_16BIT`, little-endian. `AudioStreamer` writes one ~23 ms chunk per datagram; `AudioPlayer` writes raw bytes straight to `AudioTrack` (no short reassembly)
- A 2048-byte audio datagram exceeds WiFi MTU and IP-fragments — fine on LAN; drop `CHUNK_SAMPLES` to 640 if drops occur
- Don't silently restart the mic from `BootReceiver` — Android 14+ blocks background mic access from a boot-started service; post a notification instead
- Only one mode runs per phone (single-mode UI), so `ListenService` and `MonitorService` never bind 9879 at once

## Conventions

- Kotlin + Jetpack Compose only, no XML layouts
- No third-party dependencies — use only what the Compose template provides plus standard Android/Java APIs
- Keep file count minimal — currently ~16 Kotlin source files
- UDP control messages are plain ASCII: `BEAR_<MODE>:<payload>` (discovery, alerts). The audio stream (9879) is raw binary PCM, not ASCII.
