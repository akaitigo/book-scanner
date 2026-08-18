# セキュリティポリシー

## 脆弱性の報告

秘密情報、利用者データ、または悪用手順を含む内容を公開Issueへ投稿しないで
ください。GitHubの
[Private Vulnerability Reporting](https://github.com/akaitigo/book-scanner/security/advisories/new)
から非公開で報告してください。

報告には、影響するversion/commit、再現条件、想定される影響、既知の回避策を
含めてください。書籍ページや個人情報そのものは添付せず、必要なら合成入力で
再現してください。

## 対象

最新の`main`を対象に受け付けます。署名付きproduction releaseはまだないため、
過去のdebug APKに対する長期security supportは提供していません。

アプリのtrust boundaryと禁止変更は
[docs/security-boundary.md](docs/security-boundary.md)を参照してください。
