# 実機検証引継ぎ — 2026-08-19

## 目的と対象

GitHub `main`へマージ済みの自動撮影改善を、実データ入りAndroid端末へ安全に更新インストールし、CameraXの実挙動を確認する。

- Repository: https://github.com/akaitigo/book-scanner
- Merged PR: https://github.com/akaitigo/book-scanner/pull/1
- Merge commit: `c800d30f26052a96feb0dbc882b7d9171d9c56af`
- Verified PR head: `207c6d548f600da5207eb810bf4c0d156b9917da`
- Successful CI: https://github.com/akaitigo/book-scanner/actions/runs/32152303734
- APK artifact: https://github.com/akaitigo/book-scanner/actions/runs/32152303734/artifacts/9330292602
- Artifact: `book-scanner-debug-apk`
- Artifact ZIP digest: `sha256:dd59695dae893d4eca8fae9b04af4c7e6b55d593f2e064f65ff8f484206411c7`

`/mnt/d/book-scanner-0.1.0-debug.apk`とlocal build outputは2026-08-12の古いAPKであり、今回の判定に使わない。

## 実装済み

- 自動撮影の静止待機を延長。
- 自動撮影した直前ページが同一らしければ保存しない。手動シャッターは常に保存する。
- 画面再表示後も保存済み最終ページから比較署名を復元。
- import後・削除後もdocument order上の最終ページへ比較対象を同期。
- 署名復元中は自動撮影を待機し、競合による重複保存を防止。
- 撮影成功時のflash/hapticと重複skipメッセージ。
- 署名計算をUI thread外へ移動し、保存ファイルを縮小decode。

CIの`./scripts/verify`、debug APK assemble、全JVM/Robolectric test、`CaptureViewModelTest` 27件は成功済み。manifest/schema/依存・network/media権限の追加なし。

## ADB条件

直近の接続先は`192.168.0.125:32809`。Wireless debuggingのpairing portとconnection portは別で、portは変わり得る。失敗時は広範なscanをせず端末画面の現在値を利用者へ確認する。複数client接続自体は通常可能だが、検証中は別セッションの書込み操作と競合させない。

## 禁止

- `adb kill-server`、reboot
- `connectedAndroidTest`、実機instrumentation
- 無断uninstall、data clear、session/page削除
- 署名不一致をuninstallで解消
- 書籍画像、session data、pairing codeのcommit・公開Issue添付
- force push、admin merge、`git reset --hard`

## 手順

### 1. 状態確認

```bash
cd /home/ryusei/code/book-scanner
git status --short --branch
adb devices -l
```

local `main`はconnector merge前の履歴・古いremote tracking refを持つ可能性がある。既存変更を保持し、resetで合わせない。

### 2. CI APK取得

```bash
mkdir -p /tmp/book-scanner-apk
gh run download 32152303734 \
  --repo akaitigo/book-scanner \
  --name book-scanner-debug-apk \
  --dir /tmp/book-scanner-apk
find /tmp/book-scanner-apk -maxdepth 2 -type f -name '*.apk' -print
sha256sum /tmp/book-scanner-apk/*.apk
```

上記GitHub digestはartifact ZIPのdigestで、APK本体hashではない。

### 3. 接続・更新

```bash
adb connect 192.168.0.125:32809
adb devices -l
adb shell pm list packages | grep bookscanner
adb install -r /tmp/book-scanner-apk/app-debug.apk
```

artifact内の実ファイル名に合わせる。`-r`で既存データを保持する。`INSTALL_FAILED_UPDATE_INCOMPATIBLE`なら停止し、uninstallしない。

### 4. 非破壊実機確認

1. 既存sessionが残っている。
2. 自動撮影ONで同じページを静止させる。
3. 落ち着いてから1回撮影され、flashと振動が知覚できる。
4. 同じページを保持しても保存数が増えず、skip表示が出る。
5. 撮影画面を離れて戻り、最終ページを映してもskipされる。
6. 公開可能な自作・合成画像をimportし、それを映すとskipされる。
7. テスト用最終ページだけを削除すると、直前ページが比較対象になる。
8. ページをめくると新ページは保存される。
9. 手動シャッターなら類似ページでも保存される。

既存利用者ページを検証目的で削除しない。安全に作れないケースはnot-runとする。

## 証跡と完了条件

device model、Android version、対象commit、APK SHA-256、install結果、9項目のpass/fail/not-run、自動撮影までの概算秒数、誤検知・見逃しを記録する。logcatは障害時刻へ限定する。書籍本文・通知・個人情報を公開しない。

対象APK由来、データ保持更新、通常自動撮影、feedback、直前ページskip、画面再表示後skipを確認して完了。実施不能項目は理由を明記する。結果を`docs/benchmark.md`へ記録するなら端末・Android・commit・入力条件を添え、著作物画像をcommitしない。

## 別セッション用プロンプト

```text
/home/ryusei/code/book-scanner の実機検証を引き継いでください。

最初に /home/ryusei/AI_DELEGATION_PLAYBOOK.md、適用されるAGENTS.md、
/home/ryusei/.agents/skills/android-device-safety/SKILL.md、
docs/handoff-device-validation-2026-08-19.md を全文読んでください。資料の順序と
安全条件を守り、私へ続行確認を挟まず、安全に実行可能なところまで進めてください。

対象はmainのc800d30f26052a96feb0dbc882b7d9171d9c56afです。古いlocal APKは
使わず、CI run 32152303734のbook-scanner-debug-apkを取得してください。
ワイヤレスADBの直近接続先は192.168.0.125:32809です。失敗時はport変更を報告し、
scanしないでください。

実データ入り端末なのでadb kill-server、reboot、connectedAndroidTest、data clear、
無断uninstallは禁止です。adb install -rでデータを維持し、署名不一致なら停止して
ください。書籍画像やpairing codeをGitHubへ公開しないでください。

自動撮影の待機、flash/haptic、同一ページskip、画面再表示後・import後・削除後の
比較対象同期、別ページ保存、手動シャッター保存を検証し、端末・Android・APK hash・
各項目のpass/fail/not-runを記録してください。修正が必要なら実装、test、対象HEAD
CIまで進め、実機結果とmock結果を混同しないでください。
```
