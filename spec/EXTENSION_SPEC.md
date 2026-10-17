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
| `/x/<id>/api/...` | `data/` を読み書きする API（[4章](#4-http-api)） |
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
  "externalOrigins": []
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
| `fetch` / XHR / WebSocket | **自分の `/x/<id>/` 以下**と `externalOrigins`（`scope: "all"` が許可されていれば同じサーバー全体） |
| `<iframe>` / `<embed>` / `<object>` | 自分の `/x/<id>/` 以下（`blob:` も可） |
| Web Worker | 同じサーバー、`blob:` |

- `eval` / `new Function` は使えません。
- `/x/<id>/api/file?path=...` で返すファイルは、ブラウザで開いてもスクリプトが動かないように `CSP: sandbox` が付きます（PDF や画像の表示は問題ありません）。
- ログイン（パスワード設定時）はブラウザの Cookie で行われるので、拡張機能側で認証を書く必要はありません。

> **注意**: 拡張機能は NAS 本体と同じオリジンで動くので、CSP とサーバー側の検査で守っていますが、完全な隔離ではありません。**信頼できる拡張機能だけを入れてください。**

### 2.6 ファイル名の制限

SD カードや USB ドライブ（FAT32 / exFAT）でも保存できるよう、次の名前は使えません（API は 400 を返します）。

- `/ \ : * ? " < > |` と制御文字を含む名前
- 末尾が空白か `.` の名前、`.` と `..`
- UTF-8 で 255 バイトを超える名前

また、FAT32 では **1 ファイル 4GB まで**です。大文字小文字は区別されないことがあります（`A.pdf` と `a.pdf` は同じファイルになる）。

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
| `anas.readText(path)` / `readJson(path)` / `readBlob(path)` | ファイルを読む |
| `anas.upload(path, blobOrString, options)` | ファイルを書く。`path` は**書き込み先のファイル**のパス。途中のフォルダは自動で作る |
| `anas.writeText(path, text, options)` / `writeJson(path, value, options)` | テキスト・JSON を書く |
| `anas.mkdir(path)` | フォルダを作る（途中も作る、既存なら何もしない） |
| `anas.rename(path, newName)` | 名前を変える（同じフォルダ内だけ） |
| `anas.remove(path)` | 削除（フォルダは中身ごと） |

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
| PUT | `/x/<id>/api/file?path=/a.pdf[&overwrite=1][&mkdirs=1]` | ファイルの中身そのまま | `201` + `Entry` |
| POST | `/x/<id>/api/mkdir` | `{"path": "/books"}` | `Entry` |
| POST | `/x/<id>/api/rename` | `{"path": "/a.pdf", "newName": "b.pdf"}` | `Entry` |
| POST | `/x/<id>/api/delete` | `{"path": "/a.pdf"}` | `{"ok": true}` |

エラーは `{"error": "メッセージ", "code": "..."}` で返ります。

| status | code | 意味 |
|---|---|---|
| 400 | `invalid_path` / `bad_request` / `type` | パスやファイル名が不正、フォルダとファイルの取り違え |
| 401 | `unauthorized` | ログインが必要（パスワード設定時） |
| 403 | `read_only` / `scope` | 読み取り専用、または許可されていない API |
| 404 | `not_found` | ない |
| 409 | `exists` | 同名のものがある |
| 416 | `range` | Range が不正 |
| 503 | `offline` | ストレージが取り外されている |

`scope: "all"` が許可された拡張機能は、NAS 本体の API（`/api/v1/volumes`、`/api/v1/fs/<volume>/list?path=` など、パスはストレージのルートから）も使えます。形は上の表と同じです。

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
  <meta name="viewport" content="width=device-width, initial-scale=1">
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
- [ ] 外部のライブラリは `app/` に同梱した（または `externalOrigins` に書いた）
- [ ] ファイル名に `: * ? " < > |` などを使っていない
- [ ] 置いたあとに「再スキャン」した
