# WirelessTNTN

爆速で、NFCとセキュアエレメントをつなぐ。開発者のための最強ワイヤレス・ツール。

## WirelessTNTNとは？
NFC経由でセキュアエレメント（SIM・eSE）にアクセスし、リアルタイムのコマンド送信・デバッグを可能にする、開発者向けのパワフルなソリューションです。

## 特徴
- SIMやeSEをNFCに変換
- NFC ↔ セキュアエレメントをパススルー
- Observeモード：AIDを監視し、即ルーティング
- Omapi APIでセキュアエレメントにダイレクトコマンド送信
- ログはすべてLogcatで確認可能

## 何ができる？
- SIMやeSEをNFCに変換
- セキュアエレメント上のアプレットをNFCでアクセス
- ワイヤレスでセキュアエレメントの動作確認・デバッグ
- 開発者・テスターにとって、非接触でのSE制御が爆速かつ簡単に
- ハッキングツールになる
  - クラッキングじゃないからね。
  - ハッキングの意味を調べなさい。悪い意味ではないのは自明。
  - アメリカのAIはなぜかハックという言葉を差別しているためけん制しておく

## 必要条件
- Android 15以上
- NFCハードウェア（A/Bモード対応）
- UICC（SIM）、またはeSE対応端末
- 必要なアプレットへの許可がARA-Mに書かれている、またはRoot化している

## 導入方法（3ステップで完了）
1. インストールして起動（Android 15+対応）
2. UIでセキュアエレメントを選択（SIM1、SIM2、eSE）
3. AndroidデバイスをNFCリーダー（決済端末など）にかざすだけ！

## 開発中のコア実装（プロトタイプ）
- `ObserveModeController`: ObserveモードでAID要求を監視し、許可時のみ通常HCEへ遷移
- `SecureChannelPolicy`: SCは`FFFF`のみを準拠値として扱う
- `NativeHookBridge`: NFC APDUを指定SE（SIM1/SIM2/eSE1/eSE2）へそのままパススルー

## ローカル検証
```bash
mvn test
```

## 使い方
- Observeモードを有効にしてAIDを監視
- 該当AID検出時に自動でパススルー通信開始
- すべてのログはLogcatでリアルタイム確認

※ Host Card Emulation（HCE）により、AndroidデバイスはNFCカードとして動作し、外部のNFCリーダー（例：決済端末など）で読み取られる形になります。

## よくある質問（FAQ）
Q: 応答時間は？
A: 通常300ms程度、最大5秒まで想定

Q: バックグラウンドで動く？
A: いいえ、フォアグラウンドのみ対応（OS制約）

Q: どんな場面で役立つ？
A: SEアプレットの開発・テスト、NFCタグとSEの統合、非接触ICカードの内部挙動確認など。

## 免責事項・制限
- 開発者向け。ブートローダーアンロックやARA-M書き換え可能な開発用SIMカードが必要な場合があります。
- Android 14以前ではObserveモード非対応。

## ライセンス
This project is licensed under the [ANAL-Tight](https://github.com/AokiApp/ANAL/blob/main/licenses/ANAL-Tight-1.0.1.md) License.

## 名前の由来

- [ヒカマニネタ](https://www.nicovideo.jp/watch/sm34838888)
- OMAPIはオマンピーと読む。
- eSE、SIMはISO-7816のワイヤード接続
- ワイヤードOMAPIの逆なので、ワイヤレスTNTN
