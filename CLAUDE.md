# CLAUDE.md

## Project Overview

Bear Detector is a native Android baby monitor app (Kotlin + Jetpack Compose). Phones on the same WiFi discover each other via UDP broadcast and exchange sound alerts over LAN. No backend, no cloud, no third-party dependencies.

## Architecture

- **Single-module Android project** — package `com.beardetector`, min SDK 26, compile SDK 35
- **No navigation library** — screen state managed via `mutableStateOf("home" | "listen" | "monitor")` in `BearDetectorApp.kt`
- **Foreground service** (`ListenService.kt`) runs mic recording in background with a wake lock and multicast lock
- **UDP protocol** — discovery on port 9877, alerts on port 9878. Plain ASCII strings, no serialization
- **All networking** uses `java.net.DatagramSocket` — no HTTP, no libraries
- **State sharing** between service and UI via `companion object` `MutableStateFlow` fields on `ListenService`

## Key Files

- `ListenService.kt` — the core of the app. Foreground service that ties mic input → sound threshold check → UDP alert sending. Holds wake lock and multicast lock.
- `SoundMeter.kt` — wraps `AudioRecord`, computes RMS amplitude. Must run on background thread.
- `Discovery.kt` — UDP broadcast/listen for peer discovery. Maintains peer map with 15s expiry.
- `AlertSender.kt` / `AlertReceiver.kt` — send and receive `BEAR_ALERT` packets.
- `NotificationHelper.kt` — two channels: low-priority for the ongoing service notification, high-priority for alerts.
- `AndroidManifest.xml` — permissions and service declaration. Must declare `foregroundServiceType="microphone"`.

## Build & Test

```bash
# Build debug APK (requires Android Studio JDK or JAVA_HOME set)
./gradlew assembleDebug

# Install on connected device
adb install app/build/outputs/apk/debug/app-debug.apk
```

Open in Android Studio for the easiest build experience — it bundles its own Gradle and JDK.

## Common Gotchas

- `foregroundServiceType="microphone"` must be declared in BOTH the manifest AND passed to `ServiceCompat.startForeground()` — missing either crashes on Android 14+
- `WifiManager.MulticastLock` must be acquired or UDP broadcast packets are silently dropped on Android 9+
- `AudioRecord.read()` and `DatagramSocket.receive()` are blocking — always use `Dispatchers.IO`
- Socket ports (9877, 9878) must use `reuseAddress = true` before binding, and sockets must be closed in `onDestroy`
- Wake lock is needed to keep the CPU alive when screen is off — without it, `AudioRecord` stops reading

## Conventions

- Kotlin + Jetpack Compose only, no XML layouts
- No third-party dependencies — use only what the Compose template provides plus standard Android/Java APIs
- Keep file count minimal — currently ~12 Kotlin source files
- UDP protocol messages are plain ASCII: `BEAR_<MODE>:<payload>`
