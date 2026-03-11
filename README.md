# WalkieTalkie

A native Android push-to-talk (PTT) app that lets multiple users on the same local network communicate in real time — just like physical walkie-talkies. No internet, no servers, no accounts required.

---

## What It Does

- **Press and hold** the PTT button (or a volume key) to transmit your voice
- **Release** to listen
- Everyone in the same **group/channel** on the same Wi-Fi network hears you instantly
- A live user list shows who is active in your group, updated every 2 seconds via presence pings

---

## Features

| Feature | Detail |
|---|---|
| Push-to-talk modes | Screen button, Volume Up key, or Volume Down key |
| Group channels | Users only hear others in the same named group |
| Live user list | Shows active users; removes them after 5 s of silence |
| No infrastructure | Peer-to-peer over UDP multicast — works fully offline |
| Tablet support | Larger UI elements on screens ≥ 600 dp wide |

---

## Technologies Used

### Platform & Language
- **Kotlin** — primary language
- **Android SDK** — min API 26 (Android 8.0), target API 34 (Android 14)
- **Gradle 8.13.2** with the Kotlin Gradle plugin 1.9.22

### Audio
- **`AudioRecord`** — captures microphone input using the `VOICE_COMMUNICATION` audio source, optimized for voice with echo cancellation
- **`AudioTrack`** — streams received PCM audio to the speaker in real time
- Audio format: **PCM 16-bit, 8 kHz mono** — voice-optimized and low-bandwidth
- Captured in **20 ms chunks** (320 bytes) for minimal latency

### Networking
- **UDP Multicast** via `java.net.MulticastSocket` — broadcasts packets to all devices on the LAN that have joined the multicast group
- Multicast address: `239.255.42.99`, port `9876`
- **`WifiManager.MulticastLock`** — required on Android to allow multicast packets through the Wi-Fi driver

### Custom Binary Protocol
Each packet has a 71-byte header:

```
Bytes  0–3   Magic "WTKT"
Byte   4     Type: 0=AUDIO, 1=PING, 2=LEAVE
Bytes  5–36  Username (32 bytes, null-padded UTF-8)
Bytes 37–68  Group    (32 bytes, null-padded UTF-8)
Bytes 69–70  Payload length (unsigned short, big-endian)
Bytes 71+    Payload (raw PCM audio, or empty for PING/LEAVE)
```

### UI
- **ConstraintLayout** — responsive layout that adapts to screen size
- **Material Design** components (`com.google.android.material`)
- Dark theme with red/orange PTT button feedback
- `SharedPreferences` for persisting username, group, and PTT mode settings

### Security & Stability
- Input validated with regex allowlist `^[A-Za-z0-9 _-]{1,20}$` — enforced in both the UI and the network layer
- Rate limiting: packets from any single username are dropped if they arrive faster than every 20 ms
- Hard cap of 50 tracked users to prevent memory exhaustion
- Audio payload capped at 2,560 bytes (~160 ms); PING/LEAVE packets must carry zero payload
- Magic byte check and type validation on every received packet

---

## Project Structure

```
app/src/main/java/com/walkietalkie/
├── MainActivity.kt          # UI, PTT logic, user list
├── SettingsActivity.kt      # Username / group / PTT mode configuration
├── audio/
│   └── AudioStreamer.kt     # Microphone capture & speaker playback
└── network/
    └── UdpMulticastManager.kt  # UDP multicast send/receive & packet protocol
```

---

## Building

Requirements: Android Studio or the Android SDK with Gradle.

```bash
# Debug APK
./gradlew assembleDebug

# Install directly to a connected device
./gradlew installDebug
```

---

## Permissions

| Permission | Reason |
|---|---|
| `RECORD_AUDIO` | Microphone access for PTT transmission |
| `INTERNET` | UDP socket communication |
| `CHANGE_WIFI_MULTICAST_STATE` | Join multicast group on Wi-Fi |
| `ACCESS_WIFI_STATE` | Acquire the multicast lock |
| `ACCESS_NETWORK_STATE` | Network availability checks |
| `WAKE_LOCK` | Keep CPU active during transmission |

---

## How It Works (Flow)

1. App starts → requests microphone permission
2. User sets username and group in Settings
3. `UdpMulticastManager` joins the multicast group and starts receiving packets
4. A ping thread broadcasts presence every 2 seconds so others see you in the user list
5. User holds PTT → `AudioRecord` captures mic → 320-byte chunks sent as UDP AUDIO packets
6. Remote devices receive packets → `AudioTrack` plays PCM data in real time
7. On app close → a LEAVE packet is sent so peers remove the user immediately
