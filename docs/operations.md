# 運用

## 正規検証

CIと同じgateを実行する。

```bash
./scripts/verify
```

format、debug APK assemble、JVM/Robolectric testを検証する。カメラ、SAF provider、
accessibility gesture、実peak memoryは実機項目であり、CI済みと報告しない。

## Build artifact

CIは`app-debug.apk`を公開する。debug署名であり、開発・実機受け入れ専用で、
production releaseやPlay Store署名手順はまだ存在しない。

実データ入り端末へは`adb install -r`で更新する。署名不一致を、明示承認と
検証済みbackupなしのuninstallで解決してはならない。test cleanupがアプリと
データを消し得るため、その端末でconnected instrumentationを実行しない。

## データとrollback

sessionはアプリ専用`files/sessions`配下にある。現在はDB migrationを持たない。
将来manifestを変更する場合、実装前に前方・後方互換性、backup、restoreを
定義しない限り旧版へrollbackできるとみなさない。

uninstall、migration、破壊的recoveryの前には次を実行する。

1. package、version、device、session/page件数を記録する。
2. `run-as`でmanifestとpageを退避する。
3. `/tmp`以外にも複製し、件数とhashを確認する。
4. 対象操作を実行する。
5. 復元後のsession/page inventoryを比較してから成功とする。

## CIと公開

- `main`とPull Requestは`.github/workflows/ci.yml`を実行する。
- push後は`scripts/wait-for-ci.sh <full-head-sha>`を使い、無関係な最新runを
  新commitの結果と誤認しない。
- local greenを、対象SHAのGitHub Actions成功の代用にしない。
- source commitがAPK build時刻より新しければ、既存APKはstaleである。

## 障害時の縮退

- capture/import失敗: 既存sessionを保持し、読めないpageをappendしない。
- export取消・失敗: 部分SAF文書を削除する。
- manifest破損: commit済みpageから復元し、順序確認が必要と表示する。
- cropped 12MP exportのOOMは未測定リスクである。sessionを保持し、成功扱いしない。
