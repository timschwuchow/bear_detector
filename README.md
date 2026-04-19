# Bear Detector

A simple Android baby monitor app that turns old phones into sound-activated monitors over your local WiFi network. No cloud services, no accounts — just two phones on the same network.

## How It Works

- **Listen Mode** (baby's room): Uses the mic to detect sound. When noise exceeds a threshold, it sends a UDP alert to all connected monitor phones.
- **Monitor Mode** (parent's phone): Listens for alerts and shows a notification with vibration when sound is detected.

Phones discover each other automatically via UDP broadcast on the LAN. The protocol is dead simple:
- Discovery on port 9877: `BEAR_LISTEN:<ip>` / `BEAR_MONITOR:<ip>` every 3 seconds
- Alerts on port 9878: `BEAR_ALERT:<timestamp>` unicast to each monitor

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
2. **Phone B** (with you): Open app → Monitor Mode
3. Wait ~5 seconds for discovery
4. Sound near Phone A triggers a notification on Phone B

The sensitivity slider adjusts how loud a sound needs to be before triggering an alert. There's a 5-second cooldown between alerts.

## Permissions

| Permission | Why |
|---|---|
| RECORD_AUDIO | Mic access for sound detection |
| FOREGROUND_SERVICE + MICROPHONE type | Keep listening with screen off |
| WAKE_LOCK | Keep CPU active while listening |
| INTERNET, WIFI_STATE, NETWORK_STATE | UDP communication on LAN |
| CHANGE_WIFI_MULTICAST_STATE | Receive UDP broadcast packets |
| POST_NOTIFICATIONS | Alert notifications on monitor phone |
| REQUEST_IGNORE_BATTERY_OPTIMIZATIONS | Prevent Android from killing the listener |

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
│   └── ListenService.kt         # Foreground service (mic + alerts)
├── network/
│   ├── Discovery.kt             # UDP broadcast peer discovery
│   ├── AlertSender.kt           # Sends alert packets
│   └── AlertReceiver.kt         # Receives alert packets
├── notification/
│   └── NotificationHelper.kt    # Notification channels and alerts
└── util/
    └── SoundMeter.kt            # AudioRecord wrapper, RMS amplitude
```

## Tech Stack

- Kotlin + Jetpack Compose
- Native Android APIs only (AudioRecord, DatagramSocket, NotificationManager)
- Zero third-party dependencies beyond the standard Compose template
