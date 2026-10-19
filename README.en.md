# HomePod + — AirPlay 2 Smart Dual/Single Audio Streamer for Android

> **Stream any audio from Android to Apple HomePod / HomePod mini / Apple TV with dual-speaker stereo pairing, channel splitting, volume delta following, and an immersive modern minimalist interface.**

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android%2010%2B-green.svg)](https://developer.android.com/about/versions/10)
[![Language](https://img.shields.io/badge/Language-Kotlin-purple.svg)](https://kotlinlang.org/)
[![UI](https://img.shields.io/badge/UI-Modern%20Minimalist-black.svg)](https://github.com)

**Language / 语言：**
[English](README.en.md) | [简体中文](README.md)

---

## 📖 Introduction

**HomePod +** is a high-performance **AirPlay 2 audio sender and dual-speaker synchronizer** built specifically for Android 10+. It is built upon the clean-room open-source [AirBridge](https://github.com/kerrybob46644-commits/AirBridge) sender engine, with deep dual-speaker synergy and architectural refactoring inspired by [home-pod-link](https://gitee.com/hello_unibody/home-pod-link).

Due to Apple's walled garden, HomePod and HomePod mini only natively support AirPlay 2, making wireless dual-speaker stereo pairing unavailable outside Apple's ecosystem. **HomePod +** breaks this platform barrier: without needing Root, firmware modification, or any Apple hardware, you can cast any audio from your Android phone (Apple Music, Spotify, YouTube Music, Podcasts, games, etc.) with low latency and pristine fidelity to a single HomePod or a **synchronized dual-HomePod stereo pair**.

---

## ✨ Core Features

### 1. 🎵 Dual HomePod Synchronized Streaming
- **Single Capture, Dual Dispatch**: Authorize system audio capture once; the background engine concurrently handles two independent AirPlay 2 RTSP sessions and RTP audio streams.
- **Microsecond Clock Alignment**: Sub-millisecond synchronization powered by NTP timestamp alignment eliminates echo and acoustic phase cancellation between speakers.

### 2. 🎚️ Smart Volume Delta Follower
- Forget the hassle of adjusting each speaker separately. With the master speaker as the baseline, the secondary speaker tracks automatically:
  - **Fixed Delta Mode**: e.g., keep the secondary speaker `Master - 10%`. When the master volume is adjusted to 50%, the secondary speaker instantaneously adjusts to 40%.
  - **Ratio Mode**: Scale proportionally with a custom multiplier.
- Built-in rate limiting and debouncing prevent overwhelming the HomePod RTSP control socket.

### 3. 🔊 Stereo Field & Channel Splitting
- **Full Stereo Mirroring**: Both speakers play full-channel stereo, ideal for multi-room coverage or wide-area acoustic fill.
- **Dedicated Left / Right Channel Split**: Deconstructs 16-bit stereo PCM samples at the packet level to feed the left channel to the designated speaker and the right channel to the other, creating a true wireless stereo stage.

### 4. 🔇 Speaker Auto-Mute & Disconnect Auto-Pause Protection
- **Phone Speaker Auto-Mute**: Upon starting a session, the phone's media volume is muted (set to 0), avoiding jarring dual playback from both phone and HomePods;
- **Disconnect Auto-Pause**: Whether manually stopped, unplugged, or disconnected by Wi-Fi fluctuations, the app simulates `KEYCODE_MEDIA_PAUSE` hardware key events and briefly requests audio focus, instructing running media apps (Apple Music, Spotify, etc.) to immediately pause while restoring original speaker volume.

### 5. 📲 Android 16 / HyperOS Live Updates Notification
- Deeply adapted to the Live Updates notification specification in Android 16 and custom OEM skins (Xiaomi HyperOS, ColorOS, OriginOS, etc.);
- Appears only after the audio pipeline is fully negotiated and streaming stably;
- Distraction-free design: displays current volume with `-10%`, `+10%`, and `Stop Casting` quick action buttons.

### 6. 🎛️ Hardware Keys & Quick Settings
- **Hardware Volume Key Mirroring**: Press your phone's physical volume up/down buttons to smoothly adjust HomePod volume during streaming (can be toggled in settings).
- **Quick Settings Tile**: Add the tile to your Android notification drawer to start/stop dual streaming in one tap without opening the app.

### 7. 🪟 Modern Minimalist UI & Frosted Glass Dock
- Modern minimalist mobile design style with clean typography and spacing;
- Immersive edge-to-edge layout with a floating pill TabBar featuring real-time Gaussian blur (**Haze Frosted Glass**) and subtle translucency;
- Scrollable content flows smoothly under the floating dock;
- Interactive confirmation dialogs intercept navigation when unsaved configuration changes are detected.

### 8. 📶 Industrial-Grade Network Resilience Engine
- **Four Latency Profiles**: Safe (2 s), Stable (1.5 s), Low Latency (250 ms), and Ultra-Low Latency (100 ms).
- Delayed redundant packet transmission combined with Wi-Fi and CPU wake locks to prevent dropouts even when the HomePod is far from the router.
- Non-blocking send queues + 0x55 loss retransmission with storm-shedding protection.

---

## 🛠️ Architecture & How It Works

```
[ Android System Media Audio ]
           │ (AudioPlaybackCapture API, 44.1kHz 16-bit PCM)
           ▼
   [ CastService Foreground Service ]
     ├── Auto-mute phone speaker / Pause media on disconnect
     ├── PCM sample-level stereo/channel splitter
     ├── Adaptive LPC compressed ALAC encoder (AlacCodec)
     └── Concurrent dual streaming engine (RaopClient 1 & 2)
           │
     ┌─────┴─────────────────────────┐
     ▼                               ▼
[ Primary HomePod (RTSP/RTP) ]  [ Secondary HomePod (RTSP/RTP) ]
  • SRP-6a Transient Auth         • SRP-6a Transient Auth
  • ChaCha20-Poly1305 Cipher      • ChaCha20-Poly1305 Cipher
  • NTP Clock Alignment           • NTP Clock Alignment
  • Master Volume Reference       • Auto Volume Delta Follower
```

1. **Discovery**: Dual-channel discovery leveraging Android `NsdManager` and raw UDP multicast queries for `_airplay._tcp` and `_raop._tcp`.
2. **Transient Pairing**: Implementation of HomePod PIN-less transient pairing (SRP-6a 3072-bit / SHA-512, fixed PIN 3939, M1-M4 exchange) deriving session encryption keys.
3. **Encrypted Control Socket**: All RTSP traffic is enveloped in ChaCha20-Poly1305 symmetric frames (`[2-byte LE payload length][ciphertext][16-byte auth tag]`).
4. **Session Negotiation & Health Checks**: `GET /info` → `SETUP session` (with reverse timing port probe handling) → Event channel 200 OK responses → `RECORD` → `SETUP stream`, with 2-second heartbeat keep-alives.
5. **Streaming & Pacing**: Stereo PCM is framed into 352-sample packets, compressed via ALAC into RTP/UDP payloads, and synchronized against receiver hardware clocks via NTP.

---

## 🚀 Quick Start

### 1. Download & Install
Download the latest signed release APK from [Releases](../../releases):
```bash
adb install -r HomePodPlus-release.apk
```

### 2. Configure & Cast
1. Connect your Android phone and both HomePods to the **same Wi-Fi network** (5GHz recommended; ensure router AP isolation is disabled).
2. Open **HomePod +** and go to the **Config** tab:
   - Select your speakers as "Set as Master" and "Set as Slave" from the discovery list (or add manually with port 7000);
   - Configure your volume delta mode (Fixed offset vs. Ratio);
   - Choose your soundstage mode (Full Stereo vs. Split Channels) and designate the Left speaker;
   - Tap **Save Settings** at the bottom.
3. Return to the **Cast** tab and tap **Start Casting**.
4. Grant the system **Screen/Audio Capture** prompt.
5. Play music in any app (Apple Music, Spotify, etc.) — synchronized sound will play from both HomePods!

---

## 🔨 Building from Source

- **Requirements**:
  - Android Studio Hedgehog (2023.1.1) or later
  - JDK 17 (Android Studio embedded JBR 17+ recommended)
  - Android SDK (`compileSdk 36`, `minSdk 29`)
  - Gradle 8.14+

- **Build Release APK**:
  ```bash
  git clone https://github.com/your-username/HomePod-Plus.git
  cd HomePod-Plus

  ./gradlew assembleRelease
  ```
  The resulting APK is generated at: `app/build/outputs/apk/release/app-release.apk`.

---

## ❓ FAQ

<details>
<summary><b>Q1: Why can't the app find my HomePod?</b></summary>
1. Ensure the phone and HomePod are connected to the same subnet.<br/>
2. Some home routers enable "AP Isolation" or block mDNS/multicast by default. Check your router administration page and enable IGMP / Multicast.<br/>
3. You can manually enter the HomePod's IP under "Network Device Scan" -> "+ Add Manually" (port 7000).
</details>

<details>
<summary><b>Q2: Why is there no sound in certain video apps?</b></summary>
Android's <code>AudioPlaybackCapture</code> strictly prohibits capturing DRM-protected audio streams (e.g. Netflix, Disney+). This is an enforced operating system security restriction that cannot be bypassed without Root. Standard music and podcast apps (Apple Music, Spotify, YouTube Music, etc.) are fully supported.
</details>

<details>
<summary><b>Q3: How can I minimize audio dropouts or pops?</b></summary>
1. Connect via 5GHz Wi-Fi.<br/>
2. Switch Latency Mode to "Network Safe (2s)" or "Stable (1.5s)" in the Config tab.<br/>
3. Enable "Network Robustness Mode" to activate redundant packet dispatching and aggressive Wi-Fi/CPU wake locks.
</details>

<details>
<summary><b>Q4: Why is the Release APK significantly smaller than the Debug APK?</b></summary>
Release builds enable aggressive <b>R8 code shrinking (tree shaking)</b>, <b>resource stripping</b>, debug symbol stripping, and bytecode minification. Tens of thousands of unused classes and methods from Jetpack Compose and Material3 libraries are removed, shrinking the APK from over 60 MB down to ~7.8 MB.
</details>

---

## 🤝 Credits & Attribution

**HomePod +** builds upon the dedication and insights of the open-source community:

- **Core Protocol Engine**:
  - Based on the clean-room AirPlay 2 sender protocol implementation from the original open-source project [**AirBridge**](https://github.com/kerrybob46644-commits/AirBridge).
- **Dual-Speaker Synergy & Inspiration**:
  - Heartfelt thanks to the Gitee project [**home-pod-link**](https://gitee.com/hello_unibody/home-pod-link) by **hello_unibody**. The dual-HomePod connection architecture, channel split logic, and master/slave volume tracking concepts in this project were inspired by their pioneering work.
- **Open-Source Protocol & Code References**:
  - [airplay2-sender-cpp](https://github.com/akustikrausch/airplay2-sender-cpp) (Apache-2.0): AirPlay 2 wire format and timing reference;
  - [pair_ap](https://github.com/ejurgensen/pair_ap) (MIT): HomePod transient pairing & SRP flow;
  - [owntone-server](https://github.com/owntone/owntone-server): Multi-stream protocol behaviors;
  - [FFmpeg](https://github.com/FFmpeg/FFmpeg) (LGPL-2.1+): ALAC bitstream framing and adaptive LPC linear prediction in `AlacCodec.kt`;
  - [Haze](https://github.com/chrisbanes/haze) (Chris Banes, Apache-2.0): High-performance runtime frosted-glass blur for Jetpack Compose.

---

## 📜 License

This project is licensed under the [Apache License 2.0](LICENSE).

> **License Note**:
> 1. `app/src/main/java/com/homepodplus/app/airplay/AlacCodec.kt` incorporates ALAC framing and LPC algorithms derived from FFmpeg's open-source implementation and is distributed under [LGPL-2.1+](https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html); all other source files are licensed under Apache-2.0.
> 2. **Disclaimer**: This project is an independent open-source educational utility and is not affiliated with, endorsed by, or associated with Apple Inc. `Apple`, `AirPlay`, `HomePod`, `HomePod mini`, and `Apple TV` are registered trademarks of Apple Inc. in the U.S. and other countries, cited here solely for technical compatibility description.
