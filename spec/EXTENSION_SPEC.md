# ANAS 拡張機能（特殊フォルダ）仕様書 v1

ANAS は Android 端末のストレージ（本体のフォルダ・SD カード・USB ドライブ）を、同じ LAN の PC から使える NAS にするアプリです。
**拡張機能**を使うと、特定のフォルダに専用の Web 画面を付けられます。たとえば「スキャンした本の本棚」や「KiCad のシンボル・フットプリント置き場」です。

この文書は、拡張機能を作る人（または、作ってもらう AI）が必要なことをすべてまとめたものです。
**この文書と `sdk.d.ts` を AI に渡せば、拡張機能を作ってもらえる**ことを目標にしています。

- 仕様書: `http://<端末のIP>:<ポート>/_anas/spec.md`（この文書）
- SDK の型定義: `http://<端末のIP>:<ポート>/_anas/sdk.d.ts`
- マニフェストの JSON Schema: `http://<端末のIP>:<ポート>/_anas/anas.schema.json`

---

## 1. しくみ

```
ストレージ（disk1 など）
├── 本.pdf                 ← ふつうの NAS のファイル
└── .jisui/                ← 拡張機能（「.」+ id のフォルダ）
    ├── anas.json          ← マニフェスト（必須）
    ├── app/               ← Web 画面（HTML / CSS / JS）
    │   ├── index.html     ← 入口（必須）
    │   ├── app.js
    │   └── style.css
    └── data/              ← 拡張機能のデータ（本の PDF など）
        └── ...
```

| URL | 中身 |
|---|---|
| `/x/<id>/` | `app/index.html` |
| `/x/<id>/<パス>` | `app/<パス>`（JS・CSS・画像など） |
| `/x/<id>/api/...` | `data/` を読み書きする API と、外部サイトへのアクセス（[4章](#4-http-api)） |
| `/_anas/sdk.js` | API を簡単に使うための JS モジュール（[3章](#3-sdk)） |
| `/_anas/base.css` | ANAS と同じ見た目の共通 CSS（任意） |

- ANAS は、登録したストレージの**直下**にある `.` で始まるフォルダのうち、`anas.json` があるものを拡張機能として扱います。
- サーバー起動時・アプリの「拡張」タブの「再スキャン」・Web UI の「拡張機能 → 再スキャン」で探し直します。**フォルダを置いたら再スキャン**してください。
- NAS の Web UI でそのフォルダを開くと「専用画面で開く」ボタンが出ます。

## 2. 作り方の決まり

### 2.1 フォルダ名と ID

- フォルダ名は **`.` + id** にします。`id` が `jisui` なら `/.jisui/`。
- `id` は **英小文字・数字・`_`・`-`** で 64 文字まで、先頭は英小文字か数字。
- フォルダはストレージの**直下**に置きます（`/.jisui/` は OK、`/books/.jisui/` は拡張機能として扱われません）。
- 同じ `id` が複数のストレージにあると、先に見つかったものだけが使われます。

### 2.2 anas.json

```json
{
  "anas": 1,
  "id": "jisui",
  "name": "自炊本棚",
  "version": "1.0.0",
  "description": "ScanSnap でスキャンした本を読む",
  "icon": "icon.svg",
  "scope": "self",
  "externalOrigins": [],
  "network": ["https://www.amazon.co.jp", "https://*.media-amazon.com"]
}
```

| キー | 必須 | 説明 |
|---|---|---|
| `anas` | ○ | 仕様のバージョン。**`1`** 固定 |
| `id` | ○ | 上の決まりに従う。フォルダ名と一致させる |
| `name` | ○ | 表示名 |
| `version` | | 表示用のバージョン |
| `description` | | 表示用の説明 |
| `icon` | | `app/` からの相対パス。一覧に表示される |
| `scope` | | `"self"`（既定）: `data/` の中だけ。`"all"`: NAS 全体の API（`/api/v1/...`）も使う。`"all"` は**ユーザーがアプリで許可したときだけ**有効 |
| `externalOrigins` | | CDN など、読み込み・通信を許可する外部オリジン（`https://` のみ）。例: `["https://cdn.jsdelivr.net"]`。**ANAS は LAN 内で使うので、インターネットに繋がらないこともあります。ライブラリはできるだけ `app/` に同梱してください** |
| `network` | | ANAS（端末）経由でアクセスする外部サイト。`https://host`・`https://host:port`、または `https://*.example.com`（サブドメインすべて。`*.com` のような広すぎるものは不可）。**ユーザーがアプリで許可したときだけ**有効（[2.7](#27-外部サイトへのアクセスnetwork)） |
| `home` | | `true` なら、NAS の最初の画面（`/`）の代わりに使える（[2.8](#28-nas-の最初の画面を差し替えるhome)）。`"scope": "all"` も必要 |
| `opens` | | この拡張機能で開けるファイルの拡張子（例: `[".csv", ".tsv"]`。`.` で始まる英小文字）。NAS の画面でそのファイルを開くと、この拡張機能の画面に渡される（[2.9](#29-ファイルを開く拡張機能opens)）。`"scope": "all"` も必要 |

未知のキーは無視されます。不正なときは拡張機能の一覧にエラー内容が表示されます。

### 2.3 app/（Web 画面）

- 入口は **`app/index.html`** 固定です。
- ふつうの静的ファイルとして配信されます（ビルド不要）。TypeScript で書く場合は、自分で `.js` にビルドしてから置いてください。
- **相対パスで参照**してください（`<script src="app.js">`）。`index.html` は `/x/<id>/` として配信されるので、`./app.js` は `app/app.js` を指します。
- `app/api/` というフォルダは作らないでください（`/x/<id>/api/` は API が使います）。
- ES Modules（`<script type="module">`）が使えます。インラインの `<script>` / `<style>` も使えます。
- キャッシュは `no-cache` で配信されるので、ファイルを置き換えれば次の読み込みで反映されます。

### 2.4 data/（データ）

- 拡張機能の API（SDK）から読み書きできるのは **`data/` の中だけ**です。パスは `data/` からの相対で、`/` 始まりで書きます（`/books/a.pdf` は `/.jisui/data/books/a.pdf`）。
- `data/` は NAS のふつうのフォルダでもあるので、PC の NAS 画面からも見えます。
- zip でインストール・更新したときも、`data/` の中身は**消えません**。
- 設定ファイルなどを保存したいときも `data/` に置きます（例: `/.settings.json`）。

### 2.5 使える Web の機能と制限（Content-Security-Policy）

拡張機能のページには次の CSP が付きます。

| 種類 | 許可されるもの |
|---|---|
| スクリプト・CSS | 同じサーバー（`'self'`）、インライン、WebAssembly、`externalOrigins` |
| 画像・動画・音声・フォント | 同じサーバー、`data:`、`blob:`、`externalOrigins` |
| `fetch` / XHR / WebSocket | **自分の `/x/<id>/` 以下**と `externalOrigins`（`scope: "all"` が許可されていれば同じサーバー全体）。CORS で読めないサイトは `anas.fetch()`（[2.7](#27-外部サイトへのアクセスnetwork)） |
| `<iframe>` / `<embed>` / `<object>` | 自分の `/x/<id>/` 以下（`blob:` も可） |
| Web Worker | 同じサーバー、`blob:` |

- `eval` / `new Function` は使えません。
- `/x/<id>/api/file?path=...` で返すファイルは、ブラウザで開いてもスクリプトが動かないように `CSP: sandbox` が付きます（PDF や画像の表示は問題ありません）。
- ログイン（パスワード設定時）はブラウザの Cookie で行われるので、拡張機能側で認証を書く必要はありません。

> **注意**: 拡張機能は NAS 本体と同じオリジンで動くので、CSP とサーバー側の検査で守っていますが、完全な隔離ではありません。**信頼できる拡張機能だけを入れてください。**

#### ANAS アプリ（クライアント）の中で開いたとき

スマホの ANAS アプリの「ブラウズ」（WebView）で開いた場合は、PC のブラウザと次の点が違います。

- **LAN の外には一切つながりません。** `externalOrigins` に書いた CDN なども読み込めないので、ライブラリは `app/` に同梱してください。外部サイトへのリンクは端末の普段のブラウザで開きます。ただし `anas.fetch()`・`anas.fetchToFile()` は端末（ANAS のサーバー）が取りに行くので、アプリの中でも使えます。
- **ダウンロード**: `/x/<id>/api/file?...&download=1`（`anas.fileUrl(path, { download: true })`）や `zipUrl()` へのリンクは、端末の Download フォルダに保存されます。
- **ページの中で作ったファイル**（`URL.createObjectURL(blob)` や `data:` の URL）は、`<a href="blob:..." download="名前">` をクリック（または `a.click()`）したときだけ、保存先を選ぶ画面を出して保存できます（50MB まで）。`window.open(blobUrl)` や `location.href = blobUrl` では保存できません。
- `<input type="file">` は使えますが、`webkitdirectory`（フォルダごと選ぶ）は使えません。
- `window.open` で新しいウィンドウは開きません。

### 2.6 ファイル名の制限

SD カードや USB ドライブ（FAT32 / exFAT）でも保存できるよう、次の名前は使えません（API は 400 を返します）。

- `/ \ : * ? " < > |` と制御文字を含む名前
- 末尾が空白か `.` の名前、`.` と `..`
- UTF-8 で 255 バイトを超える名前

また、FAT32 では **1 ファイル 4GB まで**です。大文字小文字は区別されないことがあります（`A.pdf` と `a.pdf` は同じファイルになる）。

### 2.7 外部サイトへのアクセス（network）

ブラウザの JS からは、CORS を許可していない外部サイト（Amazon の商品ページなど）の中身は読めません。
`anas.json` の `network` に書いてユーザーがアプリで許可すると、**ANAS（端末）が代わりに取りに行く** API が使えます。

```js
import { anas, joinPath } from "/_anas/sdk.js";

// ページの HTML を読んで、タイトルと表紙の URL を取り出す（DOMParser はスクリプトを実行しない）
const res = await anas.fetch("https://www.amazon.co.jp/dp/4000000000");
if (!res.ok) throw new Error(`HTTP ${res.status}`);
const doc = new DOMParser().parseFromString(await res.text(), "text/html");
const title = doc.querySelector("#productTitle")?.textContent?.trim();
const cover = doc.querySelector("#landingImage")?.getAttribute("src");

// 画像はブラウザを通さず、そのまま data/ に保存する
if (cover) await anas.fetchToFile(cover, joinPath("/.covers", "4000000000.jpg"), { overwrite: true });
await anas.writeJson("/.meta/4000000000.json", { title }, { overwrite: true });
```

- 使えるのは `network` に書かれ、**アプリの「拡張」タブで許可された**サイトだけです。それ以外は `AnasError(403, "network")`。
  許可したあとで `network` にサイトを増やすと、増やした分は許可し直すまで使えません。
- `https` だけです。リダイレクトは 5 回までたどり、**リダイレクト先も `network` に入っている必要**があります（短縮 URL の `amzn.asia` など、途中のホストも書いてください）。
- LAN 内の機器や端末自身（プライベート IP・`localhost` など）には、名前を解決した結果で判定してアクセスしません。
- 送られるのは `init` に書いたヘッダーだけです（ブラウザの Cookie などは送られません）。`User-Agent` の既定は `ANAS/<バージョン>` で、指定もできます。
- `anas.fetch()` の応答は 64MB まで、本文を読み終わるまで 120 秒まで。`anas.fetchToFile()` は 4GB 未満まで（タイムアウトは通信が 30 秒止まったとき）。同時に処理するのは 4 件までで、それ以上は順番待ちになります。
- 端末がインターネットに繋がっていないと `AnasError(502, "upstream")` になります。
- 外部サイトの規約は守ってください。HTML の構造は予告なく変わるので、公開 API（書籍なら Google Books API や国立国会図書館サーチなど）があればそちらのほうが安定します。

### 2.8 NAS の最初の画面を差し替える（home）

NAS の URL（`/`）を開いたときの画面を、自分で作った拡張機能にできます。

```json
{ "anas": 1, "id": "myhome", "name": "わたしの NAS", "scope": "all", "home": true }
```

- アプリの「拡張」タブで **「NAS 全体へのアクセスを許可」** と **「ホーム画面にする」** をオンにすると、`/` を開いたときに `/x/<id>/` へ移ります（選べるのは 1 つだけ）。
- **標準の画面は `/?ui=default` でいつでも開けます。** 拡張機能を消した・無効にした・アクセスの許可を取り消したときは、自動で標準の画面に戻ります。
- パスワードを設定しているときは、ログインするまでは標準のログイン画面が出ます（ログイン後に移ります）。
- NAS 全体のファイルは SDK の `nas`（[3章](#3-sdk)）で扱います。見本はサンプル集の `home_simple`（ストレージとファイルの一覧・アップロード・拡張機能へのリンク）。
- 標準の画面の機能（検索・ZIP ダウンロード・拡張機能の管理など）を全部作り直す必要はありません。足りないものは `/?ui=default` へのリンクを置いてください。

### 2.9 ファイルを開く拡張機能（opens）

ブラウザで表示できない形式（KiCad のデータ、CAD、独自のログなど）は、拡張機能で開けるようにします。
NAS の画面（標準の画面）は、画像・テキスト・PDF・音声・動画を自分で表示します。それ以外の形式のための仕組みです。

```json
{ "anas": 1, "id": "csv_viewer", "name": "CSV ビューア", "scope": "all", "opens": [".csv", ".tsv"] }
```

- アプリで「NAS 全体へのアクセス」を許可すると使えるようになります（ファイルを読むため）。無効にした拡張機能は使われません。
- NAS の一覧で該当するファイルを開くと、`/x/<id>/?volume=<ボリューム>&path=<パス>` が開きます（一覧の名前の横に拡張機能の名前が出ます）。
  同じ拡張子を複数の拡張機能が `opens` に書いていたら、先に見つかったもの（ボリューム・フォルダ名の順）を使います。
- 拡張機能の側では、SDK の `openedFile()` でファイルを受け取り、`file.client`（`nas.volume(volume)` と同じ）で読みます。

```ts
import { openedFile } from "/_anas/sdk.js";
const file = openedFile();          // { volume, path, name, client } か null
if (file) {
  const text = await file.client.readText(file.path);
  // 大きなファイルは fetch(file.client.fileUrl(file.path), { headers: { Range: "bytes=0-1048575" } }) で先頭だけ読める
}
```

- 見本はサンプル集の `csv_viewer`（Shift_JIS の判定、大きなファイルは先頭 20 MB だけ読む）。

## 3. SDK

`/_anas/sdk.js` を ES Module として読み込みます。型定義は `/_anas/sdk.d.ts`。

```html
<script type="module">
  import { anas, formatBytes } from "/_anas/sdk.js";

  const entries = await anas.list("/");           // data/ 直下
  for (const e of entries) console.log(e.name, e.isDir, formatBytes(e.size));
</script>
```

`anas` は今のページの URL（`/x/<id>/...`）から拡張機能を判定して作られます。

| メソッド | 説明 |
|---|---|
| `anas.info()` | `{ extension, readOnly }`。`readOnly` が true なら書き込みはすべて失敗する |
| `anas.list(path = "/")` | フォルダの中身 `Entry[]`（フォルダが先、名前順） |
| `anas.listRecursive(path = "/", maxDepth = 8)` | サブフォルダも含めた**ファイルだけ**の一覧 |
| `anas.stat(path)` | 1 項目の `Entry`。なければ `AnasError(status 404)` |
| `anas.exists(path)` | `boolean` |
| `anas.fileUrl(path, { download? })` | ファイルの URL。`<img src>`・`<iframe src>`・`<video src>`・`<a href>` にそのまま使える。動画のシーク（Range）にも対応 |
| `anas.zipUrl(path)` | フォルダを中身ごと zip にしてダウンロードさせる URL。`<a href>` に使う（zip の中はフォルダ名から始まる。`/` なら `data/`） |
| `anas.streamUrl(path)` | 音声・動画を再生するための URL（`/x/<id>/api/stream/<path>`）。中身は `fileUrl` と同じで Range 対応。URL の最後がファイル名になるので、`<audio>`・`<video>` にも、拡張子で形式を見分ける外部のプレーヤー（VLC など）にもそのまま渡せる |
| `anas.playlistUrl(path, { recursive?, type? })` | フォルダの音声・動画の再生リスト（M3U8）の URL。`recursive: true` でサブフォルダも、`type: "audio"` / `"video"` で種類を絞れる |
| `anas.readText(path)` / `readJson(path)` / `readBlob(path)` | ファイルを読む |
| `anas.upload(path, blobOrString, options)` | ファイルを書く。`path` は**書き込み先のファイル**のパス。途中のフォルダは自動で作る |
| `anas.writeText(path, text, options)` / `writeJson(path, value, options)` | テキスト・JSON を書く |
| `anas.mkdir(path)` | フォルダを作る（途中も作る、既存なら何もしない） |
| `anas.rename(path, newName)` | 名前を変える（同じフォルダ内だけ） |
| `anas.move(path, toDir)` | ファイル・フォルダ（中身ごと）を `toDir` の中へ同じ名前で移す。同じ名前のものがあれば `AnasError(409)` |
| `anas.copy(path, toDir)` | ファイル・フォルダ（中身ごと）を `toDir` の中へ同じ名前でコピーする |
| `anas.remove(path)` | 削除（フォルダは中身ごと） |
| `anas.search(q, { limit, hidden })` | `data/` の中のファイルを文章で探す（例: `"3日以内に追加したPDF"`・`"議事録のメモ"`）。ファイル名・フォルダ名・種類・日時・サイズから探し、中身は見ない。返り値は `{ q, query, hits, total, fuzzy, status }`（[4章](#4-http-api)）。`hidden: true` で `.` 始まりも含める |
| `anas.fetch(url, init)` | 外部サイトに ANAS 経由でアクセスする（[2.7](#27-外部サイトへのアクセスnetwork)）。返り値はふつうの `Response`（`status`・`headers`・`url` は外部サイトのもの。`Set-Cookie` は除く）。`init` は `method`・`headers`・`body`（文字列・`Blob`・`ArrayBuffer`・`Uint8Array`・`URLSearchParams`）・`signal` |
| `anas.fetchToFile(url, path, options)` | 外部サイトのファイルを `data/` の `path` に保存する。`options` は `init` と同じものに加えて `overwrite`（既定 `false`）・`mkdirs`（既定 `true`）。返り値は `{ entry, url, status, contentType }`。外部サイトが 2xx 以外なら `AnasError(502, "upstream_status")` で、ファイルは作られない |

NAS 全体を扱う `nas`（`"scope": "all"` をアプリで許可された拡張機能だけ。それ以外は `AnasError(403, "scope")`）:

```ts
import { nas } from "/_anas/sdk.js";
const volumes = await nas.volumes();                 // [{ id: "disk1", name, online, writable, totalBytes, freeBytes }]
const disk = nas.volume("disk1");                    // パスはストレージのルートから
const entries = await disk.list("/写真");
img.src = disk.fileUrl("/写真/a.jpg");
```

| メソッド | 説明 |
|---|---|
| `nas.volumes()` | ストレージの一覧 |
| `nas.volume(id)` | そのストレージを扱うクライアント。`anas` と同じ `list`・`stat`・`fileUrl`・`zipUrl`・`streamUrl`・`playlistUrl`・`read*`・`upload`・`write*`・`mkdir`・`rename`・`move`・`copy`・`remove`・`search` が使える（`info`・`fetch`・`fetchToFile` は使えない）。`move`・`copy` は `{ toVolume: "disk2" }` でほかのストレージへも送れる（コピーしてから消すので時間がかかる） |
| `nas.search(q, { limit, hidden })` | すべてのストレージから探す |
| `nas.extensions()` | 入っている拡張機能の一覧（`url`・`iconUrl`・`isHome`・`opens`・`canOpen` など） |
| `openedFile()` | NAS の画面から開かれたファイル `{ volume, path, name, client }`。そうでなければ `null`（[2.9](#29-ファイルを開く拡張機能opens)） |

`options`（`UploadOptions`）:

| キー | 既定 | 説明 |
|---|---|---|
| `overwrite` | `false` | 同名のファイルがあれば上書きする。`false` で同名があると `AnasError(status 409, code "exists")` |
| `mkdirs` | `true` | 途中のフォルダを作る |
| `onProgress(loaded, total)` | | アップロードの進捗 |
| `signal` | | `AbortSignal` で中止 |

```ts
export interface Entry {
  name: string;      // ファイル名
  path: string;      // data/ からのパス（"/books/a.pdf"）
  isDir: boolean;
  size: number;      // バイト数（フォルダは 0）
  modified: number;  // 更新日時（UNIX ミリ秒、不明なら 0）
  mime: string;      // "application/pdf" など。フォルダは "inode/directory"
}
```

エラーはすべて `AnasError`（`message`・`status`・`code`）で投げられます。

そのほかのヘルパー: `joinPath(...parts)`、`parentPath(path)`、`formatBytes(bytes)`、`createClient(id)`。

### PC からのアップロードの例（ドラッグ＆ドロップ + 進捗）

```js
import { anas, joinPath } from "/_anas/sdk.js";

const drop = document.querySelector("#drop");
drop.addEventListener("dragover", (e) => { e.preventDefault(); drop.classList.add("is-over"); });
drop.addEventListener("dragleave", () => drop.classList.remove("is-over"));
drop.addEventListener("drop", async (e) => {
  e.preventDefault();
  drop.classList.remove("is-over");
  for (const file of e.dataTransfer.files) {
    await anas.upload(joinPath("/books", file.name), file, {
      onProgress: (done, total) => console.log(file.name, Math.round((done / total) * 100) + "%"),
    });
  }
});
```

## 4. HTTP API

SDK を使わずに直接呼ぶこともできます。パスはすべてクエリパラメーター `path` で、**`data/` からの相対パス**（`/` 始まり）です。応答は JSON です。

| メソッド | URL | 本文 | 応答 |
|---|---|---|---|
| GET | `/x/<id>/api/info` | | `{ extension, readOnly }` |
| GET | `/x/<id>/api/list?path=/` | | `{ volume, path, entries: Entry[] }` |
| GET | `/x/<id>/api/stat?path=/a.pdf` | | `Entry` |
| GET | `/x/<id>/api/file?path=/a.pdf[&download=1]` | | ファイルの中身（`Range` 対応） |
| GET | `/x/<id>/api/stream/<path>` | | `file` と同じ中身（`Range` 対応）。パスを URL に入れる形（各部分を URL エンコード）で、外部のプレーヤー向けに Basic 認証も受け付ける |
| GET | `/x/<id>/api/playlist.m3u8?path=/music[&recursive=1][&type=audio\|video]` | | フォルダの音声・動画の再生リスト（`audio/x-mpegurl`、UTF-8）。各行は `stream` の絶対 URL。名前順、最大 5000 件、`.` で始まるものは含めない |
| GET | `/x/<id>/api/zip?path=/books` | | フォルダを中身ごと zip にしたもの（`Content-Length` なし、Range 非対応） |
| PUT | `/x/<id>/api/file?path=/a.pdf[&overwrite=1][&mkdirs=1]` | ファイルの中身そのまま | `201` + `Entry` |
| POST | `/x/<id>/api/mkdir` | `{"path": "/books"}` | `Entry` |
| POST | `/x/<id>/api/rename` | `{"path": "/a.pdf", "newName": "b.pdf"}` | `Entry` |
| POST | `/x/<id>/api/move` | `{"path": "/a.pdf", "toDir": "/books"}` | 移動後の `Entry`。同じ名前があれば `409` |
| POST | `/x/<id>/api/copy` | `{"path": "/a.pdf", "toDir": "/books"}` | コピー後の `Entry`。同じ名前があれば `409` |
| POST | `/x/<id>/api/delete` | `{"path": "/a.pdf"}` | `{"ok": true}` |
| GET | `/x/<id>/api/search?q=...[&limit=50][&hidden=1]` | | `{ q, query, hits: SearchHit[], total, fuzzy, status }`。`query.summary` は読み取った条件（「追加: 3日以内」など）、`hits[].added` は ANAS に追加された日時（不明なら無し）、`total` は 1000 を超えると 1001、`fuzzy` は似た名前を返したとき true、`status.running` は索引を作成中のとき true |
| POST | `/x/<id>/api/fetch` | `{"url": "https://...", "method": "GET", "headers": {}, "body": "テキスト"}`（バイナリは `"bodyBase64"`） | 外部サイトの本文そのまま。ステータスは常に `200` で、外部サイトのステータスは `X-Anas-Status`、最後の URL は `X-Anas-Url`、ヘッダーは `X-Anas-Headers`（`{"名前": "値"}` の JSON を URL エンコードしたもの） |
| POST | `/x/<id>/api/fetch-to-file` | `fetch` と同じものに `"path"`・`"overwrite"`・`"mkdirs"` を足す | `201` + `{ entry, url, status, contentType }` |

`fetch` と `fetch-to-file` は `Content-Type: application/json` で送ってください（それ以外は 400）。

エラーは `{"error": "メッセージ", "code": "..."}` で返ります。

| status | code | 意味 |
|---|---|---|
| 400 | `invalid_path` / `bad_request` / `type` | パスやファイル名が不正、フォルダとファイルの取り違え |
| 401 | `unauthorized` | ログインが必要（パスワード設定時） |
| 403 | `read_only` / `scope` | 読み取り専用、または許可されていない API |
| 403 | `network` | 外部サイトへのアクセスが許可されていない（`network` にない・アプリで未許可・LAN 内のアドレス） |
| 404 | `not_found` | ない |
| 409 | `exists` | 同名のものがある |
| 413 | `too_large` | `fetch` に送った本文が大きすぎる（JSON で 12MB まで） |
| 416 | `range` | Range が不正 |
| 502 | `upstream` / `upstream_status` / `too_large` | 外部サイトに繋がらない / 外部サイトが 2xx 以外を返した（`fetch-to-file`）/ 外部サイトの応答が大きすぎる |
| 503 | `offline` | ストレージが取り外されている |
| 504 | `timeout` | 外部サイトの応答がタイムアウトした |

`scope: "all"` が許可された拡張機能は、NAS 本体の API（`/api/v1/volumes`、`/api/v1/fs/<volume>/list?path=` など、パスはストレージのルートから）も使えます。形は上の表と同じです。NAS 全体の検索は `/api/v1/search?q=`（すべてのストレージ）と `/api/v1/fs/<volume>/search?q=` です。

検索は、端末の中の索引（ファイル名・フォルダ名・種類・サイズ・日時）を使います。索引はサーバーの開始後と 12 時間ごとに作り直し、ANAS 経由の書き込み・名前の変更・削除はその場で反映します。作成中は見つからないものがあります（`status.running`）。

## 5. 配布とインストール

次のどれでも入れられます。

1. **zip**: 拡張機能のフォルダを zip にして、NAS の Web 画面の「拡張機能 → zip からインストール」でアップロード。
   - zip の中は `anas.json` が直下にあっても、`.jisui/anas.json` や `jisui/anas.json` のように1階層下にあっても構いません。
   - 展開先は常に `/.<id>/`。既存のファイルは上書きされ、`data/` の中身は残ります。
2. **フォルダごとアップロード**: NAS の Web 画面でストレージの一番上を開き、「フォルダごと」で `.jisui` フォルダを選ぶ → 「再スキャン」。
3. **アプリで作る**: アプリの「拡張」タブ →「新しく作る」でひな形（`anas.json`・`app/index.html`・`data/`）ができます。

## 6. 最小の例

`/.hello/anas.json`

```json
{ "anas": 1, "id": "hello", "name": "Hello" }
```

`/.hello/app/index.html`

```html
<!doctype html>
<html lang="ja">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1, interactive-widget=resizes-content">
  <title>Hello</title>
  <link rel="stylesheet" href="/_anas/base.css">
</head>
<body>
  <main class="anas-container">
    <h1>Hello</h1>
    <input type="file" id="file">
    <ul id="list"></ul>
  </main>
  <script type="module">
    import { anas, joinPath } from "/_anas/sdk.js";
    const list = document.getElementById("list");
    async function refresh() {
      list.replaceChildren(...(await anas.list("/")).map((e) => {
        const li = document.createElement("li");
        const a = document.createElement("a");
        a.href = anas.fileUrl(e.path);
        a.textContent = e.name;
        li.append(a);
        return li;
      }));
    }
    document.getElementById("file").addEventListener("change", async (ev) => {
      for (const f of ev.target.files) await anas.upload(joinPath("/", f.name), f, { overwrite: true });
      refresh();
    });
    refresh();
  </script>
</body>
</html>
```

## 7. 共通 CSS（/_anas/base.css）

使わなくても構いません。使うと ANAS 本体と同じ見た目になり、ライト/ダークも自動で切り替わります。

ANAS アプリ（クライアントモード）の中で開いたときは、アプリのテーマが `<html data-anas-theme="dark">`（または `"light"`）として
ページの JS より先に付きます。Android 9 の WebView は `prefers-color-scheme` がアプリの設定に従わないためです。
base.css を使わずに自分でダークモードの色を書くときは、次のようにこの属性も見てください。

```css
@media (prefers-color-scheme: dark) { :root:not([data-anas-theme="light"]) { /* ダークの色 */ } }
:root[data-anas-theme="dark"] { /* ダークの色 */ }
```

| クラス / 変数 | 用途 |
|---|---|
| `--anas-bg` `--anas-surface` `--anas-surface-2` `--anas-text` `--anas-muted` `--anas-border` `--anas-accent` `--anas-accent-text` `--anas-accent-soft` `--anas-danger` `--anas-radius` | 色などの CSS 変数 |
| `.anas-container` | 最大幅 1100px の本文 |
| `.anas-header` | 上部に固定されるヘッダー |
| `.anas-btn` `.anas-btn--tonal` `.anas-btn--text` `.anas-btn--danger` `.anas-btn--icon` | ボタン |
| `.anas-card` | カード |
| `.anas-grid` | カードを並べるグリッド |
| `.anas-toolbar` | ボタンを横に並べる |
| `.anas-dropzone`（`.is-over` でハイライト） | ドラッグ＆ドロップの受け皿 |
| `.anas-input` | テキスト入力 |
| `.anas-muted` `.anas-error` `.anas-empty` `.anas-badge` | 補助 |

## 8. AI に作ってもらうとき

次のように頼むと、そのまま置ける形で作ってもらえます。

```
ANAS の拡張機能を作ってください。仕様書（EXTENSION_SPEC.md）と SDK の型定義（sdk.d.ts）を添付します。

- id: kicad_symbol_footprint
- 名前: KiCad ライブラリ
- やりたいこと: KiCad のシンボル(.kicad_sym)とフットプリント(.kicad_mod)を PC からアップロードし、
  一覧・プレビュー(SVG)・ダウンロードできるようにする
- 制約: フレームワークは使わない。外部 CDN は使わない（LAN 内で使う）。TypeScript で書いて、
  ビルド後の .js を app/ に置く形にする。スマホでも見やすく、ダークモードに対応する
- 出力: .kicad_symbol_footprint/ フォルダの中身（anas.json と app/ 以下）を、ファイルごとに
```

## 9. チェックリスト

- [ ] フォルダ名は `.` + `id`、ストレージの直下にある
- [ ] `anas.json` に `"anas": 1`・`id`・`name` がある
- [ ] `app/index.html` がある
- [ ] JS・CSS・画像は相対パスで参照している（`/x/<id>/` 以下でも、`/_anas/` 以下でもないパスは使わない）
- [ ] データの読み書きは SDK（`/_anas/sdk.js`）か `/x/<id>/api/` 経由で、パスは `data/` からの相対
- [ ] 外部のライブラリは `app/` に同梱した（または `externalOrigins` に書いた。ANAS アプリの中では外部は読めない）
- [ ] CORS で読めない外部サイトは `anas.fetch()` / `anas.fetchToFile()` で読み、そのホスト（リダイレクト先も）を `network` に書いた
- [ ] ページで作ったファイルのダウンロードは `<a href="blob:..." download="名前">` のクリックで行っている
- [ ] ファイル名に `: * ? " < > |` などを使っていない
- [ ] 置いたあとに「再スキャン」した
- [ ] ホーム画面にする拡張機能なら、`"scope": "all"` と `"home": true` を書き、`/?ui=default`（標準の画面）へのリンクを置いた
- [ ] ファイルを開く拡張機能なら、`"scope": "all"` と `"opens": [".拡張子"]` を書き、`openedFile()` が null のとき（直接開かれたとき）の表示も用意した
- [ ] スマホで使うなら、`<meta name="viewport" content="width=device-width, initial-scale=1, interactive-widget=resizes-content">`（キーボードが出たときに画面が縮み、入力欄が隠れない）
