# ANAS

Android 端末の **本体ストレージ・SD カード・USB ドライブ** を、同じ LAN の PC からブラウザで使える NAS にするアプリです。
ストレージ直下の `.名前` フォルダに HTML / CSS / JS を置くと、そのフォルダ専用の Web 画面（**拡張機能**）も作れます。
普段使いのスマホに入れて **クライアント**（見るだけ）にすると、サーバーの QR を読むだけでアプリの中の WebView から NAS を開けます。

- サイト: https://bunnmei.github.io/ANAS/
- 拡張機能の仕様書: [spec/EXTENSION_SPEC.md](spec/EXTENSION_SPEC.md)（AI に渡して拡張機能を作ってもらえる形）
- サンプル拡張機能（自炊本棚・KiCad ライブラリ・ノート）: `~/dev/00_sandbox/anas-extensions/`

```
[PC ブラウザ] ──HTTP (LAN のみ)──▶ [Ktor サーバー（前景サービス）]
                                     ├─ /                NAS の Web UI
                                     ├─ /api/v1/...      ファイル API（一覧・Range 対応 DL・フォルダの ZIP DL・UP・mkdir・rename・delete）
                                     ├─ /x/<id>/         拡張機能の画面（/.<id>/app/）
                                     ├─ /x/<id>/api/...  拡張機能のファイル API（/.<id>/data/ の中だけ）・外部サイトへのアクセス
                                     ├─ /dav/<volume>/   WebDAV（設定で有効にしたときだけ。PC にネットワークドライブとしてマウント）
                                     └─ /_anas/...       SDK・共通 CSS・仕様書
                                            │
                                     StorageBackend ── SAF（本体・SD・USB）
```

## モジュール

| モジュール | 種類 | 中身 |
|---|---|---|
| `:core:model` | Kotlin/JVM | `SafePath`（`..` などを通さないパス）、`ExtensionManifest`（anas.json）、`ByteRange`、`StorageBackend` の interface、テスト用の `LocalFileBackend` |
| `:core:storage` | Android lib | `SafStorageBackend`（DocumentsContract で読み書き）、`VolumeStore`（選んだフォルダを DataStore に保存） |
| `:feature:server` | Kotlin/JVM | Ktor 3（CIO）のサーバー、拡張機能の検出・zip インストール、パスワード認証、LAN 以外の拒否 |
| `:app` | Android | Jetpack Compose（ホーム・拡張・ブラウズ・設定）、Hilt、前景サービス（specialUse）、クライアント（LAN だけを開く WebView、QR の表示は ZXing・読み取りは Google のコードスキャナー） |
| `web/` | TypeScript | NAS の Web UI と拡張機能用 SDK（フレームワークなし）。`tsc` だけでビルドし、Gradle の `webBuild` タスクが APK の assets に入れる |
| `spec/` | | 拡張機能の仕様書と anas.json の JSON Schema |
| `docs/` | | GitHub Pages のサイト |

## ビルド

JDK 25 と Node.js 22 以降が必要です。

```sh
./gradlew assembleDebug                       # web/ のビルド（npm ci + tsc）も自動で行う
./gradlew test testDebugUnitTest lintDebug    # 単体テストと lint
cd web && npm run typecheck                   # Web UI の型チェックだけ
```

## Android のストレージの制約と対応

| 制約 | 対応 |
|---|---|
| Android 11 以降、SAF で本体ストレージの一番上・Download を選べない | 本体は「NAS」などのサブフォルダを選んでもらう（アプリと docs で案内） |
| SD / USB は抜くとアクセスできない | URI の権限を永続化し、抜いているあいだは「接続されていません」・API は 503 |
| DocumentFile は遅い | `DocumentsContract` に直接問い合わせ、本体・SD・USB ではパスからドキュメント ID を組み立てる |
| `/storage/<UUID>` を statfs できない | SAF で開いたファイルの fd に `fstatvfs` して容量を出す |
| FAT32 は 4GB まで、`: ? *` などは使えない | ファイル名を検証して分かりやすいエラーにする |
| dataSync の前景サービスは Android 15 以降 1 日 6 時間まで | `specialUse` の前景サービスにする（Play で配布するときは用途の申告が必要） |

## クライアント（ブラウズ）

普段使いのスマホでは「クライアント」（見るだけ）を選ぶと、サーバーは動かさず、登録した NAS を WebView で開くだけのアプリになります。

- 接続先の登録: サーバーのホーム画面で URL の QR（ZXing で生成）を出し、クライアントで Google Play 開発者サービスのコードスキャナー（カメラ権限不要）で読む。`/api/v1/info` で ANAS か確かめてから DataStore に保存する
- WebView はプライベート IP のページだけを開く（`LanUrls`）。外部へのページ移動は普段のブラウザに渡し、外部へのサブリソースは `shouldInterceptRequest` で止める
- ダウンロードはクッキーを付けて `DownloadManager` へ。`blob:` のダウンロード（KiCad のシンボル切り出しなど）は拡張機能の CSP で `fetch` できないので、注入した JS が `URL.createObjectURL` に渡された Blob を覚えておき、`ACTION_CREATE_DOCUMENT` で保存する
- デバッグビルドでは WebView のリモートデバッグ（`chrome://inspect`）が有効

## WebDAV（ネットワークドライブ）

アプリの「設定 → ネットワークドライブ（WebDAV）」で有効にすると、`http://<端末の IP>:<ポート>/dav/` を PC にマウントできます（既定は無効）。

- 実装は `feature/server/.../WebDavRoutes.kt`。OPTIONS・PROPFIND（Depth 0 / 1）・GET / HEAD（Range 対応）・PUT・DELETE・MKCOL・MOVE・COPY・PROPPATCH（受け付けるだけ）・LOCK / UNLOCK（Windows・macOS 向けの形だけのロック）
- 中身は `StorageBackend` に流すので、書き込みは検索の索引にもその場で反映される。別フォルダへの MOVE はコピーして削除（SAF に移動がないため）
- 認証はログインの Cookie か Basic 認証（ユーザー名は任意・パスワードはアクセスパスワード）。「WebDAV は読み取り専用」も選べる
- Linux では root 不要の `rclone mount` で確認済み（Claude Code の Read / Edit / Write もマウント先で動く）

## 拡張機能を作る

[spec/EXTENSION_SPEC.md](spec/EXTENSION_SPEC.md) を読んでください（アプリが動いていれば `http://<端末>:8080/_anas/spec.md` でも読めます）。
最小構成は `/.hello/anas.json`（`{"anas":1,"id":"hello","name":"Hello"}`）と `/.hello/app/index.html` の 2 ファイルです。

## ライセンス

[0BSD](LICENSE)。アプリのアイコンの元データは `icons/` にあります。

## 自動ビルド (GitHub Actions)

| ワークフロー | タイミング | 内容 |
|---|---|---|
| `CI`（`.github/workflows/ci.yml`） | push / PR | Web UI の型チェック・lint・単体テスト・デバッグ APK |
| `Release`（`.github/workflows/release.yml`） | `v*` タグの push | 署名済み AAB・APK を GitHub Release に公開。Play の認証情報があれば Google Play にもアップロード |

CI の結果は GitHub の「Actions」タブで見られます。各実行のページの「Artifacts」から、デバッグ APK（`app-debug`）とテスト・lint の結果（`android-reports`）をダウンロードできます。

リリースするには、先にリポジトリの Settings → Secrets and variables → Actions に次を登録します。

| Secret | 中身 |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | 署名鍵（.jks）を `base64 -w0 upload-keystore.jks` した文字列 |
| `ANDROID_KEYSTORE_PASSWORD` / `ANDROID_KEY_ALIAS` / `ANDROID_KEY_PASSWORD` | 署名鍵のパスワードと別名 |
| `PLAY_SERVICE_ACCOUNT_JSON`（任意） | Google Play へのアップロード用。未登録なら Play へのアップロードは省略 |

```sh
# app/build.gradle.kts の versionCode を 1 増やしてコミット・push してから
git tag v1.0.0
git push origin v1.0.0     # タグ名（v を除く）が versionName になる
```

Dependabot（`.github/dependabot.yml`）が Gradle・npm・Actions の更新 PR を週 1 回まとめて作ります。CI が通れば取り込むだけです。
