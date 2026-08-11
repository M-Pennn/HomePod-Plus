# AirBridge（安卓 → AirPlay 2 音频投射）

把安卓手机上的任意应用声音（音乐、视频、游戏）推送到 HomePod / HomePod mini / Apple TV 等 AirPlay 2 设备，无需 root（Android 10+）。

## 已实现

- 局域网自动发现 AirPlay 设备（NsdManager + 原生 mDNS 双通道）
- 手动添加设备（IP + 端口，默认 7000）
- HomePod 瞬时配对（SRP-6a + ChaCha20-Poly1305 加密控制通道）
- AirPlay 2 实时音频流（RTP/UDP + 未压缩 ALAC 帧 + NTP 时钟同步）
- 事件通道保活、断线重连、重传请求、NTP timing 应答
- 任意 App 音频捕获（MediaProjection + AudioPlaybackCapture）
- 音量控制（自动节流，避免高频请求导致 HomePod 断开）
- 延迟模式可选：稳定（约 1.5s）/ 低延迟（约 250ms）/ 极低延迟（约 100ms）
- 网络稳健模式：冗余发包 + Wi-Fi/CPU 休眠锁，弱 Wi-Fi 下降低爆音
- 测试音（440Hz，快速验证链路）
- 前台服务 + 通知栏停止按钮

## 安装

直接安装编译好的 APK：

```
adb install -r AirBridge-v0.1.0.apk
```

或者从源码构建：

```
cd outputs/AirBridge
gradle :app:assembleDebug
```

## 使用

1. 手机和 HomePod 连接同一个 Wi-Fi。
2. 打开 AirBridge，等待自动发现设备（或点右下角“手动添加”，输入 HomePod 的 IP，端口 7000）。
3. 点设备右侧“连接”，系统会弹出屏幕/音频捕获授权，点允许。
4. 在手机上播放任意音乐/视频，声音即从 HomePod 输出。
5. 投射期间可调节音量；点“播放测试音 (440Hz)”可快速验证链路。

## 已知限制

- DRM 版权音频（部分视频 App）系统不允许捕获。
- 部分国产 ROM 需要在后台管理里允许 AirBridge 自启动/后台运行。
- Apple TV 4K 的 PIN 配对路径暂未实现（HomePod 无需 PIN，瞬时配对即可）。
- 当前为 AirPlay 2 发送端；AirPlay 1 兼容路径已内置为回退（自动尝试）。

## 技术说明

协议实现参考了开源 clean-room 实现 airplay2-sender-cpp（Apache-2.0）、owntone-server 和 pair_ap（MIT）的线格式，未包含苹果专有代码。核心流程：

1. mDNS 发现 `_airplay._tcp` / `_raop._tcp`
2. HomePod 瞬时配对（SRP-6a 3072/SHA-512，固定 PIN 3939，M1-M4）
3. 加密 RTSP 控制通道（ChaCha20-Poly1305 帧：[2 字节 LE 长度][密文][16 字节 tag]）
4. GET /info → SETUP session（含 timingPort）→ 事件通道 → RECORD → SETUP stream
5. RTP/UDP 音频：44.1kHz 16bit 立体声，352 样本/包，未压缩 ALAC 帧，NTP 时钟同步
6. 事件通道 200 OK 保活 + 每 2 秒 POST /feedback

## 致谢与署名

- 本项目由 **Deepseek-v4-flash 接入 Codex** 编写（署名由作者指定）。
- 协议线格式参考了 clean-room 开源项目
  [airplay2-sender-cpp](https://github.com/akustikrausch/airplay2-sender-cpp)（Apache-2.0）、
  [pair_ap](https://github.com/ejurgensen/pair_ap)（MIT）与
  [owntone-server](https://github.com/owntone/owntone-server)（协议行为参考）。
- 本项目以 Apache-2.0 许可开源。

## 目录

- `app/src/main/java/com/airbridge/app/` — UI、服务、状态
- `.../airplay/` — AirPlay 2 协议核心（加密、mDNS、RAOP 状态机）
- `.../audio/` — 系统音频捕获
