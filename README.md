# AirBridge — 安卓 AirPlay 2 音频投射器

> **把安卓手机上的任意声音，通过 Wi-Fi 投到 HomePod / HomePod mini / Apple TV 等 AirPlay 2 设备。**

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android%2010%2B-green.svg)](https://developer.android.com/about/versions/10)
[![Language](https://img.shields.io/badge/Language-Kotlin-purple.svg)](https://kotlinlang.org/)

**语言 / Language / 言語：**
[中文](README.md) | [English](README.en.md) | [日本語](README.ja.md)

---

## 这是什么？

AirBridge 是一款运行在 Android 手机上的 **AirPlay 2 音频发送端**。

苹果的 HomePod / HomePod mini 只支持 AirPlay 2 协议，而 Windows 和 Android 都没有原生的 AirPlay 发送能力——AirBridge 就是为了填补这个空缺而生的：

- 无需 root，Android 10+ 即可使用
- 把**任意 App 的声音**（音乐、视频、游戏、播客、通话）推送到 HomePod
- 全自研 AirPlay 2 协议实现，不依赖苹果设备、无需越狱
- 完全开源（Apache-2.0）

## 功能特性

- 🔍 **局域网自动发现**：NsdManager + 原生 mDNS 双通道扫描 AirPlay 设备
- 📶 **手动添加**：自动发现不到时，直接输入设备 IP 和端口
- ⚡ **AirPlay 2 实时推流**：44.1kHz / 16bit / 立体声，压缩 ALAC（自适应 LPC，逐包自校验）+ NTP 时钟同步
- 🎚️ **四档延迟模式**（按延迟从高到低）：
  - 网络稳定（约 2s）：弱网专用，配合延时冗余最不易断
  - 稳定（约 1.5s）：日常推荐
  - 低延迟（约 250ms）：日常听歌，基本无感
  - 极低延迟（约 100ms）：看视频对嘴型，Wi-Fi 好时很爽
- 🛡️ **网络稳健模式**：延时冗余发包 + Wi-Fi/CPU 休眠锁，HomePod 离路由器远也不易爆音
- 🔧 **弱网引擎**：发送线程去阻塞、硬件时钟锁定节奏、0x55 丢包重传（去重/限速）、丢包风暴自动熔断
- 🎚️ **压缩 ALAC 开关**：默认开启，可在设置中关闭以排查兼容性
- 🔇 **音量控制**：自动节流，拖动不卡顿、不踢断连接
- 🎵 **440Hz 测试音**：一键验证整条链路
- 🔔 **前台服务**：通知栏常驻，一键停止
- 📱 **无侵入**：不需要修改系统、不需要虚拟声卡、不需要 root

## 快速开始

### 1. 安装

从 [Releases](../../releases) 页面下载最新的 APK，或者用 adb 安装：

```bash
adb install -r AirBridge-v1.0.0.apk
```

### 2. 连接

1. 确保手机和 HomePod 连接**同一个 Wi-Fi**
2. 打开 AirBridge，等待自动发现设备（或点右下角“手动添加”，输入 HomePod 的 IP，端口 7000）
3. 点设备右侧“连接”
4. 系统弹出“屏幕/音频捕获”授权，点**允许**
5. 在手机上播放任意音乐或视频，声音就会从 HomePod 输出

### 3. 调优

- **延迟模式**：打开 App 主界面的“延迟与网络”卡片，选择想要的模式（下次连接生效）
- **网络稳健模式**：HomePod 离路由器较远、出现爆音时开启
- **压缩 ALAC**：默认开启，出现异常时关闭后重连
- **测试音**：投射期间点“播放测试音 (440Hz)”快速验证链路

## 工作原理

AirBridge 完整实现了 AirPlay 2 发送端协议（参考 clean-room 开源项目还原的线格式）：

1. **发现**：通过 mDNS 查询 `_airplay._tcp` / `_raop._tcp`
2. **配对**：HomePod 瞬时配对（SRP-6a 3072bit / SHA-512，固定 PIN 3939，M1-M4）
3. **加密控制通道**：所有 RTSP 请求用 ChaCha20-Poly1305 帧封装（`[2 字节 LE 长度][密文][16 字节 tag]`）
4. **会话建立**：`GET /info` → `SETUP session`（含 timingPort）→ 事件通道 → `RECORD` → `SETUP stream`
5. **音频推流**：RTP/UDP，352 样本/包，压缩 ALAC 帧（不划算时自动回退未压缩），NTP 时钟同步，0x55 丢包重传 + 延时冗余
6. **保活**：事件通道 200 OK 应答 + 每 2 秒 `POST /feedback`

> 调试中我们踩过并修复了三个大坑：HomePod 会在 SETUP 阶段探测发送端 UDP 端口（不响应会等 30 秒后报 500）；瞬时配对必须派生事件通道密钥（否则 2 秒被踢）；音量请求不能在主线程发且必须节流（否则连接被刷断）。这些经验都已固化在代码里。

## 从源码构建

环境要求：JDK 17、Android SDK（compileSdk 36）、Gradle 8.14+

```bash
cd AirBridge
gradle :app:assembleDebug        # 调试包
gradle :app:assembleRelease      # 发布包（使用调试签名，可直接安装）
```

APK 输出位置：`app/build/outputs/apk/debug/app-debug.apk` 或 `app/build/outputs/apk/release/app-release.apk`

## 常见问题

**Q：为什么找不到 HomePod？**
确认手机和 HomePod 在同一 Wi-Fi；尝试手动添加 IP（端口 7000）；部分路由器开启了 AP 隔离，需要关闭。

**Q：为什么有延迟？**
AirPlay 协议本身要求接收端缓冲。在“延迟与网络”里选择“低延迟”或“极低延迟”可显著降低延迟，但网络差时可能偶尔卡顿。

**Q：为什么 HomePod 离路由器远就会爆音？**
Wi-Fi 丢包会导致音频卡顿。开启“网络稳健模式”（冗余发包），并尽量让手机和 HomePod 都靠近路由器。

**Q：为什么部分视频 App 没有声音？**
DRM 版权保护内容不允许被系统捕获，这是 Android 系统的限制，任何 App 都无法绕过。

**Q：连接后为什么 HomePod 上音量/播放控制不可用？**
AirBridge 是“发送端”，HomePod 上的 Siri 和家庭 App 控制面板可能不显示第三方发送端的控制项；请在 App 内调节音量。

## 已知限制

- DRM 内容无法捕获（系统限制）
- 部分国产 ROM 需要在后台管理中允许 AirBridge 后台运行
- Apple TV 4K 的 PIN 配对路径尚未实现（HomePod 无需 PIN）
- 暂不支持多房间同步/立体声对（后续版本规划中）

## 路线图

- [x] AirPlay 2 实时音频推流（HomePod 验证通过）
- [x] 四档延迟模式（含弱网专用“网络稳定”）
- [x] 网络稳健模式（延时冗余 + 休眠锁）
- [x] 压缩 ALAC 编码（自适应 LPC + 逐包自校验）
- [x] 弱网自适应：发送去阻塞 / 重传限速 / 风暴熔断
- [x] 单元测试与 GitHub Actions CI
- [ ] Windows 版（C#/.NET，系统音频投射）
- [ ] Apple TV PIN 配对
- [ ] 设备记忆与自动连接
- [ ] 封面/歌曲信息（DMAP 元数据）
- [ ] 多房间 / 立体声对

## 致谢与署名

- 本项目由 **Deepseek-v4-flash 接入 Codex** 编写（署名由作者指定）
- 协议线格式参考了 clean-room 开源项目：
  - [airplay2-sender-cpp](https://github.com/akustikrausch/airplay2-sender-cpp)（Apache-2.0）
  - [pair_ap](https://github.com/ejurgensen/pair_ap)（MIT）
  - [owntone-server](https://github.com/owntone/owntone-server)（协议行为参考）
  - [FFmpeg](https://github.com/FFmpeg/FFmpeg)（alacenc / alac / lpc，LGPL-2.1+，AlacCodec.kt 的位流与 LPC 算法参考）

## 许可证

本项目以 [Apache License 2.0](LICENSE) 开源。

> 许可说明：`AlacCodec.kt` 的 ALAC 位流与 LPC 算法参考 FFmpeg 的 LGPL-2.1+ 实现移植，该文件按 LGPL-2.1+ 条款分发；其余代码按 Apache-2.0 分发。

> 免责声明：本项目与 Apple Inc. 无关，AirPlay、HomePod、Apple TV 均为 Apple Inc. 的商标，此处仅用于描述兼容性。
