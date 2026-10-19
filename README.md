# HomePod + — 安卓 AirPlay 2 双机/单机智能音频投送器

> **让安卓手机自由投送音频至 Apple HomePod / HomePod mini / Apple TV，支持双音箱无线立体声配对、声场分离、智能差值跟随与沉浸式交互。**

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android%2010%2B-green.svg)](https://developer.android.com/about/versions/10)
[![Language](https://img.shields.io/badge/Language-Kotlin-purple.svg)](https://kotlinlang.org/)
[![UI](https://img.shields.io/badge/UI-Modern%20Minimalist-black.svg)](https://github.com)

**语言 / Language：**
[简体中文](README.md) | [English](README.en.md)

---

## 📖 项目简介

**HomePod +** 是一款专为 Android 10+ 深度定制的高性能 **AirPlay 2 音频发送端与双音箱协同投送器**。本项目基于 clean-room 开源项目 [AirBridge](https://github.com/kerrybob46644-commits/AirBridge) 发送端引擎构建，并参考 [home-pod-link](https://gitee.com/hello_unibody/home-pod-link) 进行了深度的双箱协同与架构重构。

由于苹果生态的封闭性，HomePod 与 HomePod mini 仅原生支持 AirPlay 2 协议，且在非 Apple 生态下无法实现双音箱立体声配对组。**HomePod +** 彻底打破平台壁垒：无需 Root、无需破解固件、无需依赖任何苹果硬件，即可将安卓手机上的任意音频（Apple Music、网易云音乐、QQ音乐、Bilibili、YouTube、播客、游戏音效等）低延迟、高保真地推流至单台或**成对的双台 HomePod**。

---

## ✨ 核心特性

### 1. 🎵 双 HomePod 智能协同与同步推流
- **单次捕获，双路并发**：只需一次系统音频捕获授权，后台并发拉起两条独立的 AirPlay 2 RTSP 会话与 RTP 音频流。
- **高精度时钟对齐**：基于 NTP 纳秒级授时与时间戳严格校准，消除双箱之间的物理回声与微小相位延迟。

### 2. 🎚️ 智能音量差值联动规则
- 摆脱多设备调音繁琐痛点，以主音箱为基准，副音箱自动计算并实时跟随：
  - **固定差值模式**：例如设定副音箱为 `主音箱 - 10%`，当主音箱调节为 50% 时，副音箱即刻联动为 40%。
  - **相对比例模式**：支持按倍率自动缩放。
- 支持平滑拖动与限速节流，避免高频请求阻塞 HomePod 控制通道。

### 3. 🔊 声场与声道智能分配（立体声拆分）
- **完整立体声投送**：两台音箱同时输出全景立体声，适合空间不同区域扩音。
- **分离左右声道投送**：通过底层 PCM 样本级精准拆分，将左声道数据推流给指定的左音箱，右声道数据推流给右音箱，打造真正物理意义上的 HomePod 无线立体声对。

### 4. 🔇 投送静音保护与断开自动暂停
- **外放自动静音**：投送开始瞬间，自动将手机扬声器媒体音量静音（设为 0），杜绝手机喇叭与音箱同时发声的“双重外放”尴尬；
- **断开自动暂停播放**：无论用户主动点击停止投送、音箱掉电还是 Wi-Fi 异常断开，App 均会模拟发送硬件 `KEYCODE_MEDIA_PAUSE` 媒体键并短暂申请系统音频焦点，联动手机当前正在播放的音乐 App（如 Apple Music、网易云音乐等）自动暂停，同时优雅恢复手机原有音量，防止突发爆音。

### 5. 📲 Android 16 / HyperOS 规范实时动态通知（Live Updates）
- 深度适配 Android 16 与各大厂商（小米澎湃 HyperOS、ColorOS、OriginOS 等）的实时动态（Live Updates）灵动胶囊规范；
- 仅在音频通道握手完毕、正式稳定推流后弹出；
- 界面纯粹克制：仅呈现核心音量与 `-10%`、`+10%`、`停止投送` 快捷按键，状态栏胶囊即点即控。

### 6. 🎛️ 物理按键与快捷控制
- **物理音量按键联动**：投送时按压手机侧边物理音量增减键，可无缝同步调节 HomePod 音量（可在设置中随时开关）。
- **控制中心快捷开关（TileService）**：下拉状态栏即可添加专属快捷磁贴，一键触发双箱后台投送。

### 7. 🪟 全新现代极简界面与毛玻璃悬浮 Dock
- 采用纯粹现代极简设计风格；
- 沉浸式全屏 Edge-to-Edge，底部悬浮式胶囊 TabBar 融入实时高斯模糊（Haze Frosted Glass）与半透明磨砂质感；
- 列表向上滑动时从 Dock 底部自然穿透透视；
- 拥有细腻的滑块滑动过渡与未保存拦截确认弹窗，防止误操作丢失参数。

### 8. 📶 工业级弱网抗抖动引擎
- 提供**网络稳定 (2s)**、**稳定 (1.5s)**、**低延迟 (250ms)**、**极低延迟 (100ms)** 四档延迟调度；
- 延时冗余发包机制 + Wi-Fi / CPU 唤醒锁，解决设备距离路由器较远时的爆音卡顿；
- 发送线程去阻塞队列 + 0x55 丢包重传与风暴自动熔断。

---

## 🛠️ 工作原理

```
[ Android 系统媒体音频 ]
           │ (AudioPlaybackCapture API, 44.1kHz 16-bit PCM)
           ▼
   [ CastService 核心服务 ]
     ├── 投送外放自动静音 / 断开模拟暂停媒体保护
     ├── PCM 样本级立体声/声道分离处理 (Channel Splitter)
     ├── 自适应 LPC 压缩 ALAC 编码器 (AlacCodec)
     └── 双路并发独立推流引擎 (RaopClient 1 & 2)
           │
     ┌─────┴─────────────────────────┐
     ▼                               ▼
[ 主 HomePod (RTSP/RTP) ]      [ 副 HomePod (RTSP/RTP) ]
  • SRP-6a 瞬时认证              • SRP-6a 瞬时认证
  • ChaCha20-Poly1305 加密        • ChaCha20-Poly1305 加密
  • NTP 时钟对齐                  • NTP 时钟对齐
  • 音量基准                      • 音量差值自动跟随
```

1. **设备发现**：结合 Android `NsdManager` 与底层 Raw Multicast 双通道探测局域网内的 `_airplay._tcp` 与 `_raop._tcp` 服务。
2. **瞬时配对认证**：实现完整的 HomePod 免 PIN 瞬时配对（基于 SRP-6a 3072-bit / SHA-512，固定 PIN 3939，M1-M4 交换）并派生会话密钥。
3. **加密控制信道**：全套 RTSP 指令使用 ChaCha20-Poly1305 进行对称帧加密传输（格式：`[2 字节小端密文长度][密文][16 字节 Auth Tag]`）。
4. **会话协商与探活**：`GET /info` → `SETUP session`（含 timingPort 反向探测响应）→ 事件监听通道 200 OK 应答 → `RECORD` → `SETUP stream`，配合每 2 秒的心跳保活机制。
5. **音频推流与时钟对齐**：将立体声 PCM 实时分帧（352 样本/包），经 ALAC 压缩打包为 RTP/UDP 数据包，通过 NTP 协议对齐设备硬件时钟，保障音画同步与双箱相位一致。

---

## 🚀 快速上手

### 1. 下载与安装
从 GitHub [Releases](../../releases) 页面获取最新发布的正式版 APK：
```bash
adb install -r HomePodPlus-release.apk
```

### 2. 配置与连接
1. 将安卓手机与两台 HomePod 连接至**同一个局域网 Wi-Fi**（建议 5GHz Wi-Fi 并关闭路由器 AP 隔离）。
2. 打开 **HomePod +**，进入底部「配置」选项卡：
   - 在扫描列表中点选「设为主箱」与「设为副箱」（或手动填入 IP，默认端口 7000）；
   - 根据摆位需求选择「固定差值」或「相对比例」联动幅度；
   - 根据偏好选择「完整立体声」或「分离左右声道」，并指定左声道音箱；
   - 点击底部的「保存设置」。
3. 切换回「投送」主页，点击醒目的「开始投送」胶囊按钮。
4. 在系统弹出的「录制或投射音频」弹窗中点击**允许**。
5. 打开任意音乐软件（如 Apple Music、网易云等），高品质音乐即刻从两台 HomePod 倾泻而出！

---

## 🔨 从源码编译

- **环境要求**：
  - Android Studio Hedgehog (2023.1.1) 或更高版本
  - JDK 17（推荐使用 Android Studio 内置 JBR 17+）
  - Android SDK（`compileSdk 36`, `minSdk 29`）
  - Gradle 8.14+

- **编译正式版 Release APK**：
  ```bash
  # 克隆仓库
  git clone https://github.com/your-username/HomePod-Plus.git
  cd HomePod-Plus

  # 编译生成 Release APK
  ./gradlew assembleRelease
  ```
  输出产物位于：`app/build/outputs/apk/release/app-release.apk`。

---

## ❓ 常见问题 (FAQ)

<details>
<summary><b>Q1: 为什么搜索不到我的 HomePod？</b></summary>
1. 确保手机和 HomePod 处于同一局域网下的同一个网段；<br/>
2. 部分家庭路由器（或光猫自带 Wi-Fi）开启了“AP 隔离”或禁用了多播（Multicast/mDNS），请在路由器后台关闭 AP 隔离并开启 IGMP/多播；<br/>
3. 可以在「配置」页面的“局域网设备扫描”处点击「+ 手动添加」，直接输入 HomePod 的固定 IP（端口填 7000）。
</details>

<details>
<summary><b>Q2: 为什么有的视频或特定应用没有声音？</b></summary>
Android 系统的 <code>AudioPlaybackCapture</code> 安全规范严格限制捕获带有 DRM（数字版权保护，如某些特定受保护视频流）的音频。这是操作系统底层出于安全防盗版的硬性限制，任何免 Root 软件均无法绕过。主流音乐播放器（Apple Music、网易云、QQ音乐、Spotify、播客等）均完全支持捕获。
</details>

<details>
<summary><b>Q3: 遇到爆音、卡顿或断音如何优化？</b></summary>
1. 尽量使用 5GHz Wi-Fi 频段连接手机与音箱；<br/>
2. 在「配置」页面的“音频传输与延迟调优”中，将延迟模式切换为「网络稳定 (2s)」或「稳定 (1.5s)」；<br/>
3. 开启「网络稳健模式」（启用冗余差错包传输并锁定高频 Wi-Fi/CPU 休眠锁）。
</details>

<details>
<summary><b>Q4: 为什么 Release 发行版 APK 比 Debug 调试版小这么多？</b></summary>
Release 构建开启了完整的 <b>R8 代码压缩（Tree Shaking）</b>、<b>无用资源裁剪（Resource Shrinking）</b>、调试符号剥离以及高强度代码混淆优化。Compose 与 Material3 库中未被使用的海量类和方法被全部剔除，因此安装包体积从 Debug 版本的 60+ MB 骤减至约 7.8 MB。
</details>

---

## 🤝 致谢与署名 (Credits & Attribution)

**HomePod +** 的诞生离不开开源社区与先驱项目的技术铺垫与启发：

- **核心协议引擎与架构基础**：
  - 基于 clean-room AirPlay 2 发送协议引擎开源项目 [**AirBridge**](https://github.com/kerrybob46644-commits/AirBridge)，实现了自研免越狱的非逆向干净重现。
- **双箱配对与立体声思路参考**：
  - 特别致谢 Gitee 项目 [**home-pod-link**](https://gitee.com/hello_unibody/home-pod-link)（由 **hello_unibody** 编写）。本项目关于双 HomePod 独立通道并发建立、声道拆分及音量主副跟随的设计灵感深受其开源实现的启发，特此致以诚挚的敬意！
- **开源协议与算法参考**：
  - [airplay2-sender-cpp](https://github.com/akustikrausch/airplay2-sender-cpp) (Apache-2.0)：AirPlay 2 协议线格式与时钟协商参考；
  - [pair_ap](https://github.com/ejurgensen/pair_ap) (MIT)：HomePod 瞬时认证与 SRP 算法流程；
  - [owntone-server](https://github.com/owntone/owntone-server)：多流音频传输行为参考；
  - [FFmpeg](https://github.com/FFmpeg/FFmpeg) (LGPL-2.1+)：`AlacCodec.kt` 的 ALAC 位流打包与 LPC 线性自适应预测编码算法参考；
  - [Haze](https://github.com/chrisbanes/haze) (Chris Banes, Apache-2.0)：现代化 Jetpack Compose 实时动态毛玻璃模糊效果库。

---

## 📜 开源许可证 (License)

本项目遵循 [Apache License 2.0](LICENSE) 开源协议。

> **补充说明**：
> 1. `app/src/main/java/com/homepodplus/app/airplay/AlacCodec.kt` 中的 ALAC 压缩位流与 LPC 预测算法基于 FFmpeg 的开源实现移植，该单文件遵循 [LGPL-2.1+](https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html) 许可证分发；项目其余部分均遵循 Apache-2.0 许可证分发。
> 2. **免责声明**：本项目属于个人爱好者独立开发的开源学习与兼容性探索工具，与 Apple Inc.（苹果公司）无任何关联或商业合作。`Apple`、`AirPlay`、`HomePod`、`HomePod mini`、`Apple TV` 等均为 Apple Inc. 在美国及其他国家/地区的注册商标，文档中提及仅用于技术兼容性与客观功能描述。
