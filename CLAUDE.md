# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

WalkieTalkie is a native Android push-to-talk app that enables real-time voice communication over a local network using UDP multicast. Multiple users on the same network and group can communicate like physical walkie-talkies.

## Build Commands

This is a Gradle-based Android project. Use the Gradle wrapper:

```bash
./gradlew assembleDebug         # Build debug APK
./gradlew assembleRelease       # Build release APK
./gradlew installDebug          # Build and install debug APK on connected device
./gradlew build                 # Full build (all variants)
./gradlew test                  # Run unit tests
./gradlew test --tests "com.walkietalkie.SomeTest"  # Run a single test class
./gradlew lint                  # Run Android lint checks
```

To open in Android Studio: File > Open > select project root.

## Architecture

**Package:** `com.walkietalkie`
**Min SDK:** 26, **Target SDK:** 34
**Language:** Kotlin

### Core Components

| File | Responsibility |
|------|---------------|
| `MainActivity.kt` | UI, PTT button handling, user list management, lifecycle orchestration |
| `SettingsActivity.kt` | Username/group/PTT-mode configuration with input validation |
| `audio/AudioStreamer.kt` | Microphone capture and speaker playback |
| `network/UdpMulticastManager.kt` | UDP multicast send/receive, packet protocol, presence (PING) |

### Audio

- PCM 16-bit, 8 kHz mono, 20ms chunks (320 bytes/frame)
- Daemon threads for record and playback; volatile flags for thread-safe control
- `AudioTrack` (playback) is initialized once at startup via `initPlayback()` and stays open; `AudioRecord` (capture) is created and released on each PTT press/release cycle
- `AudioStreamer` takes `UdpMulticastManager` as a constructor dependency and calls `sendAudio()` directly from the record thread

### Network Protocol

- Multicast address: `239.255.42.99:9876`
- Binary packets with 4-byte magic header `"WTKT"` + type byte + 32-byte username + 32-byte group + 2-byte payload length + payload
- Packet types: `AUDIO (0)`, `PING (1)`, `LEAVE (2)`
- PING sent every 2 seconds for presence; users removed from list after 5 seconds of inactivity

### Threading

- `UdpMulticastManager.onPacketReceived` is invoked on the network receive thread — any UI update from this callback must be dispatched via `handler.post { ... }` in `MainActivity`
- All daemon threads are named (`wt-receive`, `wt-ping`, `wt-record`) for easy identification in debuggers

### Settings Persistence

- Stored in SharedPreferences under key `"wt_prefs"` (constant `MainActivity.PREF_NAME`)
- `SettingsActivity` reads PTT mode constants directly from `MainActivity.companion` — no duplication
- On first launch (empty username/group), `MainActivity.onResume` automatically redirects to `SettingsActivity`

### Security & Resource Limits

- Username/group validated with regex `^[A-Za-z0-9 _-]{1,20}$` in both `SettingsActivity` and `UdpMulticastManager`
- Rate limiting: 20ms minimum between packets per user (~50 pkt/s max)
- Max audio payload: 2560 bytes; PING/LEAVE must have 0-byte payload
- Max 50 active users tracked in both `MainActivity` and `UdpMulticastManager`

### PTT Modes

Three modes selectable in Settings: screen button, volume-up key, volume-down key. Volume key interception happens in `MainActivity.onKeyDown/onKeyUp`.

### UI

- Dark theme (`#121212` background), red/orange PTT button (`#C62828` idle, `#FF6F00` active)
- Responsive: `res/values/` for phones, `res/values-sw600dp/` for tablets

## Required Android Permissions

`RECORD_AUDIO`, `INTERNET`, `CHANGE_WIFI_MULTICAST_STATE`, `ACCESS_WIFI_STATE`, `ACCESS_NETWORK_STATE`, `WAKE_LOCK`

The app must acquire a `WifiManager.MulticastLock` at runtime for multicast to work on Android WiFi.
