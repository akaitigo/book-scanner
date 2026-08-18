# セキュリティ境界

## 保護対象

- 撮影・インポートした書籍ページ
- セッション名、ページ順、クロップ・回転情報
- Storage Access Framework（SAF）で利用者が保存したPDF

スキャンには著作物や個人情報が含まれ得る。アカウント機能がなくても、
すべてのページを利用者の非公開データとして扱う。

## 信頼境界

```text
カメラ / Photo Picker
        |
        v
Android decoder -> アプリ専用領域 -> 端末内処理
                                      |
                                      v
                         利用者が選んだSAF文書
```

- 基本機能はネットワークなしで動作し、ページをプロセス外へ送信しない。
- セッションはアプリ専用領域に置き、メディアスキャンや広範なストレージ
  権限へ公開しない。
- SAFへのexportだけが外部境界を越える。取消・失敗時は部分ファイルを削除する。
- カメラ・Picker入力は信頼しない。decode不能なページはdocument orderへ
  commitしない。

## 自動的に守る不変条件

- merged APKに`INTERNET`と`ACCESS_NETWORK_STATE`を含めない。
- `READ_MEDIA_*`などの広範な読取権限を含めない。
- commit済み原画像は変更せず、編集情報はmanifestへ保存する。
- manifestはatomicに置換し、不完全なstaging fileはrecoveryで無視する。
- 自動重複判定が不確実なら保存する。利用者に見えないページ欠落を避ける。
- 秘密、API key、署名鍵、非公開スキャンをGitへ入れない。

`PrivacyManifestTest`、repository recovery、ingest、export cancellationの
各testが境界を検証する。依存またはmanifest変更時は`./scripts/verify`を実行し、
merged manifestも確認する。

## ADRと明示レビューが必要な変更

- network処理、telemetry、crash upload、remote model
- Android権限の追加
- 他アプリとのsession共有
- schema migration、削除・復元semanticsの変更
- parser・archive importなど攻撃面を広げる機能
- runtimeまたはmodelの再配布条件を変える依存

秘密情報や悪用可能な未公開脆弱性は公開Issueへ投稿せず、
[Security Advisories](https://github.com/akaitigo/book-scanner/security/advisories/new)
から非公開で報告する。
