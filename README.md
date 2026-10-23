# HomePod + — 安卓 AirPlay 2 多机/双机智能音频投送器 (v2.0.0)

> **让安卓手机自由投送音频至 Apple HomePod / HomePod mini / Apple TV，支持 1~4 台多音箱智能协同、无线立体声配对、独立声道路由、从属音量联动与定时防破音重连。**

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android%2010%2B-green.svg)](https://developer.android.com/about/versions/10)
[![Language](https://img.shields.io/badge/Language-Kotlin-purple.svg)](https://kotlinlang.org/)
[![Version](https://img.shields.io/badge/Version-2.0.0-black.svg)](https://github.com)

**语言 / Language：**
[简体中文](README.md) | [English](README.en.md)

---

## 📖 项目简介

**HomePod +** 是一款专为 Android 10+ 深度定制的高性能 **AirPlay 2 音频发送端与多音箱协同投送器**。本项目基于 clean-room 开源项目 [AirBridge](https://github.com/kerrybob46644-commits/AirBridge) 发送端引擎构建，并参考 [home-pod-link](https://gitee.com/hello_unibody/home-pod-link) 进行了深度的多音箱协同与现代架构重构。

由于苹果生态的封闭性，HomePod 与 HomePod mini 仅原生支持 AirPlay 2 协议，且在非 Apple 生态下无法实现多音箱组网或无线立体声配对。**HomePod +** 彻底打破平台壁垒：无需 Root、无需破解固件、无需依赖任何苹果硬件，即可将安卓手机上的任意音频（Apple Music、网易云音乐、QQ音乐、Bilibili、YouTube、播客、游戏音效等）低延迟、高保真地推流至**单台、成对或最多 4 台 HomePod**。

---

## ✨ 核心特性

### 1. 🎵 1~4 台多音箱智能协同与并发推流
- **单次捕获，多路并发**：只需一次系统音频捕获授权，后台并发拉起多达 4 条独立的 AirPlay 2 RTSP 会话与 RTP 音频流。
- **高精度时钟对齐**：基于 NTP 纳秒级授时与真实授时对齐校准，消除多台音箱之间的物理回声与微小相位延迟。

### 2. 🔊 声道独立分配与声场路由
- **单设备立体声**：连接单台音箱时默认输出完整全景立体声，无需繁琐设置。
- **多设备灵活分配**：连接 2~4 台音箱时，可为每台音箱独立分配输出声道：
  - **立体声**：输出完整声场，适合多房间或大空间立体扩音；
  - **左声道 / 右声道**：精准拆分底层 PCM 声道，自由组合打造专业物理级无线立体声对。

### 3. 🎚️ 主从音量分级与智能联动
- **主音箱基准**：默认以设备 1 作为主音箱基准音量（100%）。
- **从属跟随保护**：从属设备音量严格限制小于或等于主音箱音量（偏移范围 -100% ~ 0%），避免次要音箱声音突兀刺耳。
- **双联动模式**：
  - **固定差值模式**：例如设定副音箱为 `主音箱 - 10%`，随主音箱增减保持固定差额；
  - **相对比例模式**：按比例跟随主音箱音量缩放（例如 0.8x）。

### 4. ⏱️ 长播放防破音与定时自动平滑重连
- **底层时钟频偏自适应**：内置闭环水线负反馈算法与 0xD4 NTP 真实授时对齐，彻底消除超长播放因局域网微随时钟漂移导致的累积杂音与爆音。
- **定时平滑重连机制**：配置中提供「定时自动重连」开关，用户可自由输入分钟数（如建议 40~60 分钟）：
  - 到达设定时间后，后台毫秒级断开并快速重新建立 AirPlay RTSP 链路；
  - **系统屏幕/音频捕获授权保持活跃，全程无需重新弹窗授权**；
  - 借助约 1 秒的短暂空白彻底重置音箱物理底层缓冲与同步时钟；
  - 界面动态展示「当前连接持续时长」与「上次连接持续时长」。

### 5. 📳 全功能线性马达触觉反馈系统（AppHaptics）
- 全面接入 Android 振动系统与线性马达触感：
  - 底部 Tab 栏切换带有轻快刻度反馈；
  - 按钮点击、胶囊选择、Switch 切换具备精准段落触感；
  - 音量滑块滑动时提供细腻的步进触觉感知。

### 6. 🪟 沉浸式状态栏与柔和渐变顶层遮罩
- 顶底全面 Edge-to-Edge 沉浸式全屏布局；
- 页面顶部加入柔和的渐变白色遮罩，内容向上滚动时自然优雅淡出，杜绝页面元素与系统状态栏图标/文字冲突重叠；
- 底部悬浮胶囊 TabBar 融入实时高斯模糊（Haze Frosted Glass）质感。

### 7. 📱 设备卡片与现代半屏选择弹窗
- **设备配置两行排布**：第一行完整展示自定义「设备名称」，第二行并列排布「IP 地址」与「端口」，清晰易读；
- **现代半屏绑定弹窗**：点击醒目的「选择设备」胶囊按键即可唤起半屏弹窗，实时搜索并一键绑定音箱，支持手动添加固定 IP；
- **智能权限拦截提示**：若连接时遇设备访问限制，自动弹出直观的设置指引，提示用户在「家庭」App 中调整扬声器连接权限。

### 8. 🔇 投送静音保护与断开自动暂停
- **外放自动静音**：投送启动瞬间，自动将手机扬声器媒体音量调至静音，杜绝手机喇叭与音箱同时发声的双重外放；
- **断开自动暂停播放**：无论主动断开、音箱断电还是网络异常中断，App 均会发送硬件媒体暂停键（`KEYCODE_MEDIA_PAUSE`）联动音乐软件自动暂停，并恢复手机原有音量。

### 9. 🎛️ 物理按键与快捷控制
- **物理音量按键联动**：投送时按压手机侧边物理音量增减键，直接同步调节 HomePod 音量（可在配置中开关）。
- **控制中心磁贴（TileService）**：下拉状态栏即可添加快捷磁贴，单击即刻启停投送，**长按快捷磁贴直达 App 主页**。
- **系统日志一键复制**：运行日志顶部配备「复制全部」按钮，快速排查网络环境与设备连接状态。

---

## 🛠️ 工作原理

```
[ Android 系统媒体音频 ]
           │ (AudioPlaybackCapture API, 44.1kHz 16-bit PCM)
           ▼
   [ CastService 核心后台服务 ]
     ├── 投送外放自动静音 / 退出自动模拟暂停保护
     ├── 样本级立体声/左右声道精准拆分 (PCM Splitter)
     ├── LPC 线性自适应压缩 ALAC 编码器 (AlacCodec)
     ├── 定时平滑重连引擎 (无缝重置音箱底层缓冲)
     └── 1~4 路并发独立推流引擎 (RaopClient 1..4)
           │
     ┌─────┴──────────────┬──────────────┬──────────────┐
     ▼                    ▼              ▼              ▼
[ 设备 1 (主音箱) ]   [ 设备 2 ]     [ 设备 3 ]     [ 设备 4 ]
  • SRP-6a 瞬时认证     • SRP-6a 认证  • SRP-6a 认证  • SRP-6a 认证
  • ChaCha20 加密      • ChaCha20 加密 • ChaCha20 加密 • ChaCha20 加密
  • NTP 真实授时对齐    • NTP 授时对齐  • NTP 授时对齐  • NTP 授时对齐
  • 基准音量 (100%)    • 从属音量跟随  • 从属音量跟随  • 从属音量跟随
```

1. **设备发现**：结合 Android `NsdManager` 与底层 Raw Multicast 双通道探测局域网内 `_airplay._tcp` 与 `_raop._tcp` 服务的 HomePod 设备。
2. **免 PIN 瞬时认证**：基于 SRP-6a 3072-bit / SHA-512 与固定 PIN 3939 实现免人工干预的极速握手并派生双向会话密钥。
3. **安全加密信道**：全套 RTSP 信令使用 ChaCha20-Poly1305 进行帧加密保护。
4. **音频流与时钟对齐**：立体声 PCM 实时分帧（352 样本/帧），经 ALAC 压缩打包为 RTP 数据包，通过 0xD4 NTP 反向探针校准时钟，消除时钟频偏。

---

## 🚀 快速上手

### 1. 下载正式版 APK
从 GitHub [Releases](../../releases) 页面获取最新发布的正式版 APK：
```bash
adb install -r HomePodPlus-release.apk
```

### 2. 配置与连接
1. 将安卓手机与所有 HomePod 连接至**同一个局域网 Wi-Fi**（建议 5GHz 频段并关闭路由器的 AP 隔离）。
2. 打开 **HomePod +**，进入「配置」页面：
   - 选择投送设备数量（1~4 台）；
   - 点击各设备的「选择设备」按钮绑定音箱（或展开填入固定 IP，端口 7000）；
   - 多音箱模式下为每台音箱指定「立体声 / 左声道 / 右声道」，并调节从属音量跟随幅度；
   - 如需长时间（超 1 小时）连续播放，可开启「定时自动重连」并设置周期（如 50 分钟）；
   - 点击页面底部「保存设置」。
3. 切换回「投送」主页，点击醒目的「开始投送」胶囊按键。
4. 在系统弹出的「录制或投射音频」弹窗中点击**允许**。
5. 开启任意音乐 App（Apple Music、网易云、QQ音乐等），享受高品质多设备沉浸式音乐盛宴！

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

  # 编译生成正式版 Release APK
  ./gradlew assembleRelease
  ```
  产物输出路径：`app/build/outputs/apk/release/app-release.apk`。

---

## ❓ 常见问题 (FAQ)

<details>
<summary><b>Q1: 为什么搜索不到我的 HomePod？</b></summary>
1. 确保手机和 HomePod 处于同一局域网下的同一个网段；<br/>
2. 部分家庭路由器（或光猫自带 Wi-Fi）开启了“AP 隔离”或禁用了多播（Multicast/mDNS），请在路由器后台关闭 AP 隔离并开启 IGMP/多播；<br/>
3. 可以在「配置」页面展开卡片后直接输入 HomePod 的固定 IP（端口填 7000）。
</details>

<details>
<summary><b>Q2: 连接时提示“被设备的访问控制拒绝”怎么解决？</b></summary>
请在与音箱绑定的 iPhone / iPad 上打开「家庭」App，点击右上角「···」-「家庭设置」-「扬声器与电视」，将“允许访问扬声器和电视”调整为“同一网络上的任何人”，并关闭“需要密码”。
</details>

<details>
<summary><b>Q3: 长时间连续播放（1小时以上）为什么会出现破音？如何解决？</b></summary>
部分音箱在长时间接收流媒体时，可能由于局域网微弱时钟漂移或设备物理缓冲区耗尽引发持续杂音。在 2.0.0 版本中：<br/>
1. 我们重构了时钟同步与水线算法，提升了硬件对齐稳定性；<br/>
2. 同时在配置页提供了<b>「定时自动重连」</b>功能。开启并设定如 50 分钟，App 将在播放到达设定时间时静默快速重新握手（无需重新授权录屏），以 1 秒空白彻底刷新音箱缓冲，彻底根治长时间播放破音。
</details>

<details>
<summary><b>Q4: 为什么有的视频或特定应用没有声音？</b></summary>
Android 系统的 <code>AudioPlaybackCapture</code> 安全规范严格限制捕获带有 DRM（数字版权保护，如特定受保护视频流）的音频。这是操作系统底层出于安全防盗版的硬性限制。主流音乐播放器（Apple Music、网易云、QQ音乐、Spotify、播客等）均完全支持捕获。
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
