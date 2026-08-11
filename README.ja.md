# AirBridge — Android 向け AirPlay 2 オーディオ送信アプリ

> **Android スマホのあらゆる音声を、Wi-Fi 経由で HomePod / HomePod mini / Apple TV などの AirPlay 2 対応機器に飛ばします。**

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android%2010%2B-green.svg)](https://developer.android.com/about/versions/10)
[![Language](https://img.shields.io/badge/Language-Kotlin-purple.svg)](https://kotlinlang.org/)

**言語 / Language / 语言:**
[中文](README.md) | [English](README.en.md) | [日本語](README.ja.md)

---

## これは何？

AirBridge は、Android スマホ上で動作する **AirPlay 2 オーディオ送信アプリ**です。

Apple の HomePod / HomePod mini は AirPlay 2 プロトコルにしか対応しておらず、Windows や Android には標準の AirPlay 送信機能がありません。AirBridge はそのギャップを埋めます:

- root 不要、Android 10+ で動作
- **あらゆるアプリの音声**（音楽・動画・ゲーム・ポッドキャスト）を HomePod へ
- AirPlay 2 プロトコルを独自実装（Apple 製品・越獄不要）
- Apache-2.0 で完全オープンソース

## 主な機能

- 🔍 **自動検出**: NsdManager + 独自 mDNS の二重経路で AirPlay 機器をスキャン
- 📶 **手動追加**: 検出できない場合は IP とポート（7000）を直接入力
- ⚡ **AirPlay 2 リアルタイム配信**: 44.1kHz / 16bit / ステレオ、非圧縮 ALAC フレーム + NTP 同期
- 🎚️ **3 段階のレイテンシー**:
  - 安定（約 1.5 秒）: 弱い Wi-Fi でも途切れにくい
  - 低遅延（約 250ms）: 日常の音楽視聴に最適
  - 超低遅延（約 100ms）: 動画の口元合わせ向け、良好な Wi-Fi 環境向け
- 🛡️ **ネットワーク堅牢モード**: 冗長パケット送信 + Wi-Fi/CPU スリープロック。ルーターから離れた HomePod でも音切れを低減
- 🔇 **音量調整**: 自動スロットル付き。スライダー操作で接続が切れません
- 🎵 **440Hz テストトーン**: 配信経路をワンタップで確認
- 🔔 **フォアグラウンドサービス**: 常駐通知と停止ボタン
- 📱 **非侵襲**: システム変更・仮想サウンドカード・root は一切不要

## クイックスタート

### 1. インストール

[Releases](../../releases) から最新 APK をダウンロード、または adb でインストール:

```bash
adb install -r AirBridge-v0.1.0.apk
```

### 2. 接続

1. スマホと HomePod を**同じ Wi-Fi** に接続
2. AirBridge を開き、自動検出を待つ（見つからなければ「手動追加」で IP・ポート 7000 を入力）
3. デバイス横の「接続」をタップ
4. システムの画面/音声キャプチャ許可を**許可**する
5. スマホで音楽や動画を再生すると、HomePod から音が出ます

### 3. 調整

- **レイテンシーモード**: メイン画面の「遅延とネットワーク」カードから選択（次回接続時に反映）
- **ネットワーク堅牢モード**: HomePod がルーターから遠く音切れする場合に有効化
- **テストトーン**: 配信中に「テストトーン (440Hz)」を押して経路を確認

## 仕組み

AirPlay 2 送信側プロトコルを一から実装しています:

1. **検出**: `_airplay._tcp` / `_raop._tcp` を mDNS で探索
2. **ペアリング**: HomePod の一時ペアリング（SRP-6a 3072bit / SHA-512、固定 PIN 3939、M1-M4）
3. **暗号化制御チャネル**: すべての RTSP リクエストを ChaCha20-Poly1305 フレームで暗号化
4. **セッション確立**: `GET /info` → `SETUP session` → イベントチャネル → `RECORD` → `SETUP stream`
5. **オーディオ配信**: RTP/UDP、352 サンプル/パケット、非圧縮 ALAC、NTP 同期、再送対応
6. **キープアライブ**: イベントチャネルへの 200 OK 応答 + 2 秒ごとの `POST /feedback`

> 開発中に踏んだ 3 つの落とし穴はコードに反映済みです: SETUP 時に送信側 UDP ポートを応答しないと 30 秒後に 500 になる / 一時ペアリングではイベントチャネル鍵の導出が必要（忘れると約 2 秒で切断）/ 音量リクエストは UI スレッド禁止＆スロットル必須（でないと接続断）。

## ソースからのビルド

必要環境: JDK 17、Android SDK（compileSdk 36）、Gradle 8.14+

```bash
cd AirBridge
gradle :app:assembleDebug        # デバッグ APK
gradle :app:assembleRelease      # リリース APK（デバッグキー署名・インストール可）
```

出力: `app/build/outputs/apk/debug/app-debug.apk` または `app/build/outputs/apk/release/app-release.apk`

## よくある質問

**Q: HomePod が見つかりません。**
同じ Wi-Fi に接続されているか確認し、手動追加（IP、ポート 7000）も試してください。ルーターの AP 分離が原因のこともあります。

**Q: 遅延が気になります。**
AirPlay は受信側バッファが必要です。「低遅延」「超低遅延」を選ぶと改善しますが、弱いネットワークでは稀に途切れます。

**Q: HomePod がルーターから遠いと音が途切れます。**
Wi-Fi のパケットロスが原因です。「ネットワーク堅牢モード」を有効にし、両方をルーターに近づけてください。

**Q: 一部の動画アプリで音が出ません。**
DRM 保護コンテンツは Android の仕様上キャプチャできません。どのアプリでも回避不可です。

## 既知の制限

- DRM コンテンツはキャプチャ不可（システム制限）
- 一部の国産 ROM ではバックグラウンド実行の許可が必要
- Apple TV 4K の PIN ペアリングは未実装（HomePod は PIN 不要）
- マルチルーム同期 / ステレオペアは今後対応予定

## ロードマップ

- [x] AirPlay 2 リアルタイム配信（HomePod で実証済み）
- [x] 3 段階レイテンシーモード
- [x] ネットワーク堅牢モード（冗長パケット + スリープロック）
- [ ] Windows 版（C#/.NET）
- [ ] Apple TV PIN ペアリング
- [ ] デバイス記憶と自動接続
- [ ] ジャケット / 再生中メタデータ（DMAP）
- [ ] マルチルーム / ステレオペア

## クレジット

- 開発: **Deepseek-v4-flash と Codex の統合**（作者指定の表記）
- プロトコル線形式の参考: [airplay2-sender-cpp](https://github.com/akustikrausch/airplay2-sender-cpp)（Apache-2.0）、[pair_ap](https://github.com/ejurgensen/pair_ap)（MIT）、[owntone-server](https://github.com/owntone/owntone-server)（動作参考）

## ライセンス

[Apache License 2.0](LICENSE) で公開しています。

> 免責: 本プロジェクトは Apple Inc. とは無関係です。AirPlay、HomePod、Apple TV は Apple Inc. の商標であり、互換性の説明のみに使用しています。
