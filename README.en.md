# HomePod + — AirPlay 2 Smart Multi/Dual Audio Streamer for Android (v2.0.0)

> **Stream any audio from Android to Apple HomePod / HomePod mini / Apple TV with 1~4 multi-speaker smart sync, wireless stereo pairing, independent channel routing, slave volume tracking, and scheduled anti-crackling auto-reconnect.**

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android%2010%2B-green.svg)](https://developer.android.com/about/versions/10)
[![Language](https://img.shields.io/badge/Language-Kotlin-purple.svg)](https://kotlinlang.org/)
[![Version](https://img.shields.io/badge/Version-2.0.0-black.svg)](https://github.com)

**Language / 语言：**
[English](README.en.md) | [简体中文](README.md)

---

## 📖 Introduction

**HomePod +** is a high-performance **AirPlay 2 audio sender and multi-speaker synchronizer** built specifically for Android 10+. It is built upon the clean-room open-source [AirBridge](https://github.com/kerrybob46644-commits/AirBridge) sender engine, with deep multi-speaker synergy and modern architectural refactoring inspired by [home-pod-link](https://gitee.com/hello_unibody/home-pod-link).

Due to Apple's walled garden, HomePod and HomePod mini only natively support AirPlay 2, making wireless multi-speaker pairing unavailable outside Apple's ecosystem. **HomePod +** breaks this platform barrier: without needing Root, firmware modification, or any Apple hardware, you can cast any audio from your Android phone (Apple Music, Spotify, YouTube Music, Podcasts, games, etc.) with low latency and pristine fidelity to **1, 2, or up to 4 synchronized HomePods**.

---

## ✨ Core Features

### 1. 🎵 1~4 Multi-Speaker Smart Sync & Concurrent Streaming
- **Single Capture, Multi Dispatch**: Authorize system audio capture once; the background engine concurrently handles up to 4 independent AirPlay 2 RTSP sessions and RTP audio streams.
- **Microsecond Clock Alignment**: Sub-millisecond synchronization powered by NTP timestamp alignment eliminates echo and acoustic phase cancellation between speakers.

### 2. 🔊 Independent Channel Routing
- **Single Speaker**: Automatically outputs full-channel stereo with zero setup required.
- **Multi-Speaker (2~4 Devices)**: Assign each speaker independently to:
  - **Stereo**: Full acoustic field, great for multi-room fill;
  - **Left / Right Channel**: Packet-level PCM splitting to build a true physical wireless stereo soundstage.

### 3. 🎚️ Master-Slave Volume Tracking
- **Master Reference**: Device 1 serves as the master volume baseline (100%).
- **Slave Protection**: Secondary speakers are strictly constrained to $\le$ master volume (offset range -100% ~ 0%), preventing secondary speakers from blaring abruptly.
- **Dual Tracking Modes**: Supports both **Fixed Delta Mode** (e.g. Master - 10%) and **Ratio Mode** (e.g. 0.8x multiplier).

### 4. ⏱️ Anti-Crackling Engine & Scheduled Smooth Reconnect
- **Adaptive Clock Sync**: Closed-loop waterline negative feedback + 0xD4 NTP timing completely prevents accumulated clock drift during long playback.
- **Scheduled Smooth Reconnect**: Configurable timer in minutes (e.g., 50 mins):
  - Automatically resets AirPlay links upon reaching the threshold;
  - **System audio capture remains active—no Android permission dialog is ever shown again**;
  - A brief 1-second silence resets the hardware audio buffer and synchronization clock;
  - Real-time display of current session duration and previous session duration.

### 5. 📳 Full-Spectrum Linear Haptics (AppHaptics)
- Rich haptic sensations across the application:
  - TabBar switching with lightweight tactile ticks;
  - Button presses and chips with distinct crisp feedback;
  - Volume sliders with subtle step-by-step vibration textures.

### 6. 🪟 Immersive Status Bar with Gradient Mask
- Full Edge-to-Edge immersion from top to bottom;
- Subtle top gradient white mask prevents scrolling content from overlapping or clashing with system status bar text and icons;
- Floating pill TabBar with real-time Gaussian blur (**Haze Frosted Glass**).

### 7. 📱 Redesigned Device Cards & Bottom Sheet Picker
- **Two-Row Device Card Layout**: Row 1 presents the full device name; Row 2 displays IP address and Port;
- **Half-Screen Device Picker**: Tap the prominent "Select Device" button to bring up a bottom sheet with real-time mDNS scan results and manual IP entry;
- **Access Control Detection**: Automatically alerts and guides the user if HomePod access permission is restricted in the Apple Home app.

### 8. 🔇 Auto-Mute & Auto-Pause Media Protection
- Automatically mutes phone speaker on stream start;
- Simulates hardware `KEYCODE_MEDIA_PAUSE` on stream stop or disconnect to automatically pause music players.

### 9. 🎛️ Physical Keys & Quick Settings
- **Hardware Volume Keys**: Adjust HomePod volume directly with physical phone buttons.
- **Quick Settings Tile**: Tap to start/stop stream; **long-press to open app directly**.
- **One-Tap Log Copy**: Copy full system diagnostics logs in one click.

---

## 🛠️ How It Works

```
[ Android System Audio ]
           │ (AudioPlaybackCapture API, 44.1kHz 16-bit PCM)
           ▼
   [ CastService Background Service ]
     ├── Auto phone speaker mute / Disconnect auto-pause
     ├── Sub-sample PCM stereo/channel splitter
     ├── Real-time LPC adaptive ALAC compression
     ├── Scheduled smooth reconnect engine
     └── 1~4 Concurrent AirPlay 2 RaopClients
           │
     ┌─────┴──────────────┬──────────────┬──────────────┐
     ▼                    ▼              ▼              ▼
[ Device 1 (Master) ] [ Device 2 ]   [ Device 3 ]   [ Device 4 ]
  • SRP-6a Handshake    • SRP-6a Handshake • SRP-6a Handshake • SRP-6a Handshake
  • ChaCha20 Encryption • ChaCha20 Encrypt • ChaCha20 Encrypt • ChaCha20 Encrypt
  • NTP Clock Alignment • NTP Clock Align  • NTP Clock Align  • NTP Clock Align
  • Baseline Volume     • Follower Volume  • Follower Volume  • Follower Volume
```

---

## 🚀 Quick Start

### 1. Download Release APK
Get the latest official release APK from the GitHub [Releases](../../releases) tab:
```bash
adb install -r HomePodPlus-release.apk
```

### 2. Configure & Stream
1. Connect Android phone and HomePods to the **same Wi-Fi network** (5GHz recommended, AP isolation disabled).
2. Open **HomePod +** and navigate to the **Config** tab:
   - Select number of speakers (1 to 4);
   - Tap "Select Device" to bind each speaker;
   - Configure channels (Stereo / Left / Right) and slave volume offsets;
   - Optionally toggle on "Scheduled Auto-Reconnect" (e.g. 50 mins) for marathon listening sessions;
   - Tap "Save Settings" at the bottom.
3. Switch to the **Cast** tab and tap **Start Casting**.
4. Grant the Android screen/audio capture permission.
5. Play your favorite music on Apple Music, Spotify, or any player!

---

## 🔨 Building From Source

```bash
git clone https://github.com/your-username/HomePod-Plus.git
cd HomePod-Plus
./gradlew assembleRelease
```
APK output located at: `app/build/outputs/apk/release/app-release.apk`.

---

## 📜 License

Distributed under the [Apache License 2.0](LICENSE).
