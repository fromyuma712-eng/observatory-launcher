# ObservatoryLauncher

Android向けの自作ホームランチャー（Kotlin / Jetpack Compose）。

## 設計方針

既存ランチャーの良い点を要素分解し、独自に組み合わせて実装した。

- **Niagara Launcher**: アイコン格子を廃し、縦一列・下寄せ（親指の可動域）のリスト表示
- **iOS Spotlight**: 検索を第一の操作導線に配置
- **Pixel Launcher**: predictive back・状態保持
- **NixOS**: レイアウトをJSON宣言として持ち、変更のたびに世代（generation）を残す
- **Vivaldi**: Quick Commands型のコマンドライン風操作

ホーム画面を「台帳（アプリの一覧）」、全アプリを「コマンドライン（あいまい検索）」の二層構造とし、アイコンは検索結果側にのみ配置する。

## 主な機能

- あいまい検索（Migemo方式の読み辞書＋fzf/fzy系のスコアリング、半角カタカナ・骨格一致・打ち間違い許容に対応）
- レイアウトのJSON宣言化と世代管理（変更履歴・ロールバック）
- プライベートスペース対応（LauncherApps方式）
- 情報カード（天気・カレンダー・使用時間・電池健全度など）
- ウィジェット配置（四分割グリッド＋可変高さ）
- ロック画面壁紙のカスタム描画

## ビルド

```
./gradlew assembleDebug -PcliBuild
```

Android Studioと同時にビルドする場合、出力先の競合を避けるため `-PcliBuild` を必ず付与する（プロジェクト外の一時ディレクトリへ出力される）。

## 技術スタック

Kotlin, Jetpack Compose, minSdk 26, LauncherApps API, Health Connect, Open-Meteo API

## ライセンス

MIT License（[LICENSE](LICENSE)）
