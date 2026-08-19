# AirBridge — AirPlay 2 Audio Sender for Android

> **Stream any audio from your Android phone to HomePod / HomePod mini / Apple TV and other AirPlay 2 devices over Wi-Fi.**

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android%2010%2B-green.svg)](https://developer.android.com/about/versions/10)
[![Language](https://img.shields.io/badge/Language-Kotlin-purple.svg)](https://kotlinlang.org/)

**Language / 语言 / 言語:**
[中文](README.md) | [English](README.en.md) | [日本語](README.ja.md)

---

## What is this?

AirBridge is an **AirPlay 2 audio sender** that runs on Android.

Apple's HomePod / HomePod mini only speaks AirPlay 2, and neither Windows nor Android ships a native AirPlay sender. AirBridge fills that gap:

- No root required, works on Android 10+
- Streams **any app's audio** (music, video, games, podcasts) to a HomePod
- Fully self-implemented AirPlay 2 protocol stack
- Completely open source (Apache-2.0)

## Features

- 🔍 **Automatic discovery**: dual-channel mDNS scanning (NsdManager + raw multicast)
- 📶 **Manual add**: enter the device IP and port directly if discovery fails
- ⚡ **AirPlay 2 realtime streaming**: 44.1 kHz / 16-bit / stereo, compressed ALAC (adaptive LPC with per-packet self-verification) + NTP clock sync
- 🎚️ **Four latency modes** (high to low):
  - Network-safe (~2 s): built for weak networks, most resilient with delayed redundancy
  - Stable (~1.5 s): recommended for everyday use
  - Low (~250 ms): everyday listening, nearly imperceptible
  - Ultra-low (~100 ms): lip-sync for videos, best on a good Wi-Fi
- 🛡️ **Network robustness mode**: delayed redundant packets + Wi-Fi/CPU wake locks to reduce dropouts when the HomePod is far from the router
- 🔧 **Weak-network engine**: non-blocking send queues, hardware-clock pacing, 0x55 loss retransmission (dedup + rate limit), automatic loss-storm shedding
- 🎚️ **Compressed ALAC toggle**: on by default; turn it off for troubleshooting
- 🔇 **Volume control**: throttled updates, smooth slider without disconnects
- 🎵 **440 Hz test tone**: verify the whole chain in one tap
- 🔔 **Foreground service**: persistent notification with a stop button
- 📱 **Non-invasive**: no system modification, no virtual sound card, no root

## Quick start

### 1. Install

Download the latest APK from [Releases](../../releases), or install via adb:

```bash
adb install -r AirBridge-v1.0.0.apk
```

### 2. Connect

1. Make sure the phone and the HomePod are on the **same Wi-Fi**
2. Open AirBridge and wait for discovery (or tap "Add manually" and enter the HomePod IP, port 7000)
3. Tap "Connect" next to the device
4. Grant the system screen/audio capture permission
5. Play any music or video on the phone — audio comes out of the HomePod

### 3. Tuning

- **Latency mode**: open the "Latency & Network" card and pick a mode (applies on next connection)
- **Network robustness**: enable when the HomePod is far from the router and you hear dropouts
- **Compressed ALAC**: on by default; disable and reconnect if you hear anomalies
- **Test tone**: tap "Play test tone (440 Hz)" while casting to verify the chain

## How it works

AirBridge implements the AirPlay 2 sender protocol end-to-end:

1. **Discovery**: mDNS queries for `_airplay._tcp` / `_raop._tcp`
2. **Pairing**: HomePod transient pairing (SRP-6a 3072-bit / SHA-512, fixed PIN 3939, M1-M4)
3. **Encrypted control channel**: every RTSP request is wrapped in ChaCha20-Poly1305 frames (`[2-byte LE length][cipher][16-byte tag]`)
4. **Session setup**: `GET /info` → `SETUP session` (with timingPort) → event channel → `RECORD` → `SETUP stream`
5. **Audio streaming**: RTP/UDP, 352 samples per packet, compressed ALAC frames (falls back to uncompressed when not beneficial), NTP clock sync, 0x55 loss retransmission + delayed redundancy
6. **Keep-alive**: event-channel 200 OK replies + `POST /feedback` every 2 seconds

> Three hard-won bugs are fixed in the code: the HomePod probes the sender's UDP ports during SETUP (ignore it and you get a 500 after 30 s); transient pairing must derive the event-channel keys (otherwise the session is killed in ~2 s); and volume requests must not run on the UI thread and must be throttled (otherwise the connection drops).

## Build from source

Requirements: JDK 17, Android SDK (compileSdk 36), Gradle 8.14+

```bash
cd AirBridge
gradle :app:assembleDebug        # debug APK
gradle :app:assembleRelease      # release APK (signed with the debug key, installable)
```

Output: `app/build/outputs/apk/debug/app-debug.apk` or `app/build/outputs/apk/release/app-release.apk`

## FAQ

**Q: Why can't I find my HomePod?**
Make sure the phone and HomePod are on the same Wi-Fi; try adding the IP manually (port 7000); some routers enable AP isolation, which blocks discovery.

**Q: Why is there latency?**
AirPlay requires receiver buffering. Choose "Low" or "Ultra-low" latency in the app to reduce it, but weak networks may occasionally stutter.

**Q: Why do I hear pops when the HomePod is far from the router?**
Wi-Fi packet loss causes audio glitches. Enable "Network robustness" (redundant packets) and move both devices closer to the router.

**Q: Why is some video app silent?**
DRM-protected content cannot be captured on Android — a system limitation that no app can bypass.

## Known limitations

- DRM content cannot be captured (system restriction)
- Some OEM ROMs require allowing AirBridge to run in the background
- Apple TV 4K PIN pairing is not implemented yet (HomePod needs no PIN)
- Multi-room sync / stereo pairs are planned but not yet available

## Roadmap

- [x] AirPlay 2 realtime audio streaming (verified on HomePod)
- [x] Four latency modes (including the weak-network "Network-safe" mode)
- [x] Network robustness mode (delayed redundancy + wake locks)
- [x] Compressed ALAC encoding (adaptive LPC + per-packet self-verification)
- [x] Weak-network adaptivity: non-blocking sends / retransmit rate limit / storm shedding
- [x] Unit tests and GitHub Actions CI
- [ ] Windows version (C#/.NET system-audio casting)
- [ ] Apple TV PIN pairing
- [ ] Device memory & auto-connect
- [ ] Cover art / now-playing metadata (DMAP)
- [ ] Multi-room / stereo pairs

## Credits

- Written by **Deepseek-v4-flash integrated with Codex** (attribution chosen by the author)
- Wire formats referenced from clean-room open-source projects:
  - [airplay2-sender-cpp](https://github.com/akustikrausch/airplay2-sender-cpp) (Apache-2.0)
  - [pair_ap](https://github.com/ejurgensen/pair_ap) (MIT)
  - [owntone-server](https://github.com/owntone/owntone-server) (protocol behavior reference)
  - [FFmpeg](https://github.com/FFmpeg/FFmpeg) (alacenc / alac / lpc; LGPL-2.1+, bitstream and LPC algorithms referenced by AlacCodec.kt)

## License

Licensed under the [Apache License 2.0](LICENSE).

> License note: `AlacCodec.kt` ports the ALAC bitstream and LPC algorithms from FFmpeg's LGPL-2.1+ implementation and is distributed under LGPL-2.1+; all other code is under Apache-2.0.

> Disclaimer: This project is not affiliated with Apple Inc. AirPlay, HomePod and Apple TV are trademarks of Apple Inc., used here only to describe compatibility.
