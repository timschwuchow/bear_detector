# Bear Detector

A simple Android baby monitor app that turns old phones into sound-activated monitors over your local WiFi network. No cloud services, no accounts — just two phones on the same network.

## How It Works

- **Listen Mode** (baby's room): Uses the mic to detect sound. When noise exceeds a threshold, it sends a UDP alert to all connected monitor phones. It also streams live room audio to every monitor continuously.
- **Monitor Mode** (parent's phone): Shows a notification with vibration when sound is detected, and can play the live audio stream on demand. Audio starts **muted** — tap **Listen** to hear the room, **Mute** to silence it while monitoring continues.

Phones discover each other automatically via UDP broadcast on the LAN. The protocol is dead simple:
- Discovery on port 9877: `BEAR_LISTEN` / `BEAR_MONITOR` every 3 seconds (peer IP taken from the packet source)
- Alerts on port 9878: `BEAR_ALERT:<timestamp>` unicast to each monitor
- Audio on port 9879: raw little-endian PCM (44100 Hz, mono, 16-bit), one ~23 ms chunk per UDP datagram, streamed continuously to each monitor

Both sides run a foreground service with a wake lock so they keep working with the screen off overnight. If the Listen phone reboots while it was running, it posts a **"tap to resume"** notification (Android 14+ blocks restarting the mic in the background, so reopening the app is required).

## Requirements

- Two or more Android phones (API 26+ / Android 8.0+)
- Same WiFi network
- Android Studio (to build and install)

## Building & Installing

1. Open this project in Android Studio
2. Connect a phone via USB (with Developer Options and USB Debugging enabled)
3. Click Run (green play button)
4. Repeat for a second phone

Or via command line:
```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Usage

1. **Phone A** (baby's room): Open app → Listen Mode → Start Listening
2. **Phone B** (with you): Open app → Monitor Mode → Start Monitoring
3. Wait ~5 seconds for discovery
4. Sound near Phone A triggers a notification on Phone B
5. On Phone B, tap **Listen** to hear live room audio; tap **Mute** to silence it (monitoring and alerts continue either way)

The sensitivity slider adjusts how loud a sound needs to be before triggering an alert. There's a 5-second cooldown between alerts.

## Permissions

| Permission | Why |
|---|---|
| RECORD_AUDIO | Mic access for sound detection and audio streaming |
| FOREGROUND_SERVICE + MICROPHONE type | Keep listening with screen off |
| FOREGROUND_SERVICE_MEDIA_PLAYBACK | Keep playing the audio stream on the monitor with screen off |
| RECEIVE_BOOT_COMPLETED | Post a "tap to resume" notification after a reboot |
| WAKE_LOCK | Keep CPU active while listening/monitoring |
| INTERNET, WIFI_STATE, NETWORK_STATE | UDP communication on LAN |
| CHANGE_WIFI_MULTICAST_STATE | Receive UDP broadcast packets |
| POST_NOTIFICATIONS | Alert notifications on monitor phone |
| REQUEST_IGNORE_BATTERY_OPTIMIZATIONS | Prevent Android from killing the service |

## Project Structure

```
app/src/main/java/com/beardetector/
├── MainActivity.kt              # Entry point
├── ui/
│   ├── BearDetectorApp.kt       # Home screen, navigation, permissions
│   ├── ListenScreen.kt          # Listen mode UI
│   ├── MonitorScreen.kt         # Monitor mode UI
│   └── theme/                   # Colors and Material3 theme
├── service/
│   ├── ListenService.kt         # Foreground service (mic + alerts + audio streaming)
│   ├── MonitorService.kt        # Foreground service (discovery + alerts + audio playback)
│   └── BootReceiver.kt          # Posts "tap to resume" notification after reboot
├── network/
│   ├── Discovery.kt             # UDP broadcast peer discovery
│   ├── AlertSender.kt           # Sends alert packets
│   ├── AlertReceiver.kt         # Receives alert packets
│   ├── AudioStreamer.kt         # Streams raw PCM chunks to monitors
│   └── AudioPlayer.kt           # Receives PCM and plays it via AudioTrack
├── notification/
│   └── NotificationHelper.kt    # Notification channels, alerts, resume prompt
└── util/
    └── SoundMeter.kt            # AudioRecord wrapper, PCM chunks + RMS amplitude
```

## Tech Stack

- Kotlin + Jetpack Compose
- Native Android APIs only (AudioRecord, AudioTrack, DatagramSocket, NotificationManager)
- Zero third-party dependencies beyond the standard Compose template
