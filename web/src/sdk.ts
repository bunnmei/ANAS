/**
 * ANAS 拡張機能 SDK
 *
 * 拡張機能のページ（/x/<id>/ 以下）から、自分の data/ フォルダを読み書きするための小さなライブラリ。
 *
 * ```ts
 * import { anas } from "/_anas/sdk.js";
 * const entries = await anas.list("/");              // data/ 直下の一覧
 * img.src = anas.fileUrl("/covers/a.jpg");           // <img> / <iframe> / <video> に使える URL
 * audio.src = anas.streamUrl("/music/01 曲.mp3");    // 音声・動画の再生用（シーク可・外部プレーヤーにも渡せる）
 * await anas.upload("/books/a.pdf", file, { onProgress: (done, total) => ... });
 *
 * // anas.json の "network" に書いて、アプリで許可した外部サイトには、ANAS（端末）経由でアクセスできる
 * const html = await (await anas.fetch("https://example.com/")).text();
 * await anas.fetchToFile("https://example.com/a.jpg", "/covers/a.jpg");
 * ```
 *
 * パスはすべて data/ フォルダからの相対パス（`/` 始まり）。data/ の外には出られない。
 *
 * `"scope": "all"` でアプリから NAS 全体へのアクセスを許可された拡張機能（ホーム画面にする拡張機能など）は、
 * `nas` で NAS 全体を扱える:
 *
 * ```ts
 * import { nas } from "/_anas/sdk.js";
 * const [disk] = await nas.volumes();
 * const files = await nas.volume(disk.id).list("/");   // ボリュームのルートからのパス
 * ```
 * 型定義は /_anas/sdk.d.ts。仕様書は /_anas/spec.md。
 */

/** フォルダの中の1項目 */
export interface Entry {
  /** ファイル名 */
  name: string;
  /** data/ からのパス（`/books/a.pdf`） */
  path: string;
  isDir: boolean;
  /** バイト数（フォルダは 0） */
  size: number;
  /** 更新日時（UNIX ミリ秒）。不明なら 0 */
  modified: number;
  /** MIME タイプ（フォルダは `inode/directory`） */
  mime: string;
}

export interface ExtensionInfo {
  id: string;
  name: string;
  version: string;
  description: string;
  /** 拡張機能があるストレージの ID（`disk1` など） */
  volume: string;
  /** ストレージ内のフォルダ（`/.jisui`） */
  folder: string;
  /** 専用 UI の URL（`/x/jisui/`） */
  url: string;
  iconUrl?: string;
  scope: "self" | "all";
  enabled: boolean;
  fullAccess: boolean;
  /** anas.json の `network`（ANAS 経由でアクセスしたい外部サイト） */
  network: string[];
  /** network のうち、アプリで許可されているもの。anas.fetch() で使えるのはこれだけ */
  networkAllowed: string[];
  /** anas.json に `"home": true`（NAS の最初の画面の代わりに使える） */
  homeCapable: boolean;
  /** アプリで「ホーム画面にする」を選んでいて、今 `/` を開くとこの拡張機能になる */
  isHome: boolean;
  error?: string;
}

/** NAS のストレージ（SD カード・USB ドライブなど） */
export interface Volume {
  /** API に使う ID（`disk1` など） */
  id: string;
  /** 表示名 */
  name: string;
  /** つながっているか */
  online: boolean;
  writable: boolean;
  totalBytes?: number;
  freeBytes?: number;
}

export interface SelfInfo {
  extension: ExtensionInfo;
  /** NAS が読み取り専用モードなら true（書き込みは 403 になる） */
  readOnly: boolean;
}

export interface UploadOptions {
  /** 同名のファイルがあれば上書きする（既定 false → 409 エラー） */
  overwrite?: boolean;
  /** 途中のフォルダがなければ作る（既定 true） */
  mkdirs?: boolean;
  /** 進捗。total は不明なら 0 */
  onProgress?: (loaded: number, total: number) => void;
  signal?: AbortSignal;
}

/** anas.fetch() / anas.fetchToFile() のリクエスト */
export interface FetchInit {
  /** GET（既定）・HEAD・POST・PUT・PATCH・DELETE */
  method?: string;
  /** User-Agent も指定できる（既定は `ANAS/<バージョン>`）。Host・Cookie の自動送信などはない */
  headers?: Record<string, string> | Headers | [string, string][];
  body?: string | Blob | ArrayBuffer | Uint8Array | URLSearchParams;
  signal?: AbortSignal;
}

export interface FetchToFileOptions extends FetchInit {
  /** 同名のファイルがあれば上書きする（既定 false → 409 エラー） */
  overwrite?: boolean;
  /** 途中のフォルダがなければ作る（既定 true） */
  mkdirs?: boolean;
}

export interface FetchToFileResult {
  /** 保存したファイル */
  entry: Entry;
  /** リダイレクトをたどったあとの URL */
  url: string;
  /** 外部サイトのステータス（200 など） */
  status: number;
  /** 外部サイトの Content-Type（`image/jpeg` など）。なければ null */
  contentType: string | null;
}

export interface SearchHit {
  volume: string;
  /** 拡張機能の API では data/ からのパス */
  path: string;
  name: string;
  isDir: boolean;
  size: number;
  modified: number;
  mime: string;
  /** ANAS に追加（アップロード・保存）された日時。最初の索引作りの前からあったものは undefined */
  added?: number;
}

/** 文章をどう読み取ったか */
export interface SearchQueryInfo {
  keywords: string[];
  kinds: ("text" | "pdf" | "image" | "video" | "audio" | "archive" | "document" | "spreadsheet" | "presentation" | "folder")[];
  extensions: string[];
  since?: number;
  until?: number;
  /** any: 更新日時か追加日時、added: 追加日時、modified: 更新日時 */
  timeField: "any" | "added" | "modified";
  minSize?: number;
  maxSize?: number;
  sort: "relevance" | "newest" | "oldest" | "largest";
  /** 画面に出す説明（「追加: 3日以内」「種類: PDF」など） */
  summary: string[];
}

export interface SearchStatus {
  /** 索引を作っている最中なら true */
  running: boolean;
  volume?: string;
  scanned: number;
  volumes: { volume: string; lastScan?: number; files: number }[];
}

export interface SearchResult {
  q: string;
  query: SearchQueryInfo;
  hits: SearchHit[];
  /** 条件に合う件数（1001 なら「1000 件以上」） */
  total: number;
  /** 完全に一致するものがなく、似た名前を返したとき true */
  fuzzy: boolean;
  status: SearchStatus;
}

/** API のエラー。status は HTTP ステータス、code は `not_found` `exists` `read_only` など */
export class AnasError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly code: string,
  ) {
    super(message);
    this.name = "AnasError";
  }
}

export interface AnasClient {
  /** 拡張機能の ID */
  readonly id: string;
  /** API のベース URL（`/x/<id>/api`） */
  readonly apiBase: string;
  info(): Promise<SelfInfo>;
  /** フォルダの中身。フォルダが先、名前順 */
  list(path?: string): Promise<Entry[]>;
  /** フォルダの中身をサブフォルダまで含めてすべて返す（ファイルだけ）。maxDepth の既定は 8 */
  listRecursive(path?: string, maxDepth?: number): Promise<Entry[]>;
  stat(path: string): Promise<Entry>;
  exists(path: string): Promise<boolean>;
  /** ファイルの URL。download: true ならダウンロードさせる */
  fileUrl(path: string, options?: { download?: boolean }): string;
  /** フォルダを中身ごと zip にしてダウンロードさせる URL（`<a href>` に使う） */
  zipUrl(path: string): string;
  /**
   * 音声・動画などを再生するための URL（`.../stream/<path>`）。中身は fileUrl と同じで Range（シーク）に対応し、
   * URL の最後がファイル名になるので `<audio>` / `<video>` にも外部のプレーヤー（VLC など）にも渡せる
   */
  streamUrl(path: string): string;
  /**
   * フォルダの音声・動画の再生リスト（M3U8）の URL。VLC などで開くとフォルダをまとめて再生できる。
   * recursive: true でサブフォルダも含める。type で音声だけ・動画だけにできる
   */
  playlistUrl(path: string, options?: { recursive?: boolean; type?: "audio" | "video" }): string;
  readText(path: string): Promise<string>;
  readJson<T = unknown>(path: string): Promise<T>;
  readBlob(path: string): Promise<Blob>;
  /** ファイルを書き込む（path は書き込み先のファイルのパス） */
  upload(path: string, data: Blob | string, options?: UploadOptions): Promise<Entry>;
  writeText(path: string, text: string, options?: UploadOptions): Promise<Entry>;
  writeJson(path: string, value: unknown, options?: UploadOptions): Promise<Entry>;
  /** フォルダを作る（途中のフォルダも作る）。すでにあれば何もしない */
  mkdir(path: string): Promise<Entry>;
  rename(path: string, newName: string): Promise<Entry>;
  /**
   * ファイル・フォルダ（中身ごと）を toDir の中へ同じ名前で移す。同じ名前のものがあれば AnasError(409)。
   * toVolume でほかのストレージへ移せるのは nas.volume(id) のクライアントだけ（コピーしてから消すので時間がかかる）
   */
  move(path: string, toDir: string, options?: { toVolume?: string }): Promise<Entry>;
  /** ファイル・フォルダ（中身ごと）を toDir の中へ同じ名前でコピーする。options は move と同じ */
  copy(path: string, toDir: string, options?: { toVolume?: string }): Promise<Entry>;
  /** ファイル、またはフォルダを中身ごと削除する */
  remove(path: string): Promise<void>;
  /**
   * 外部サイトに ANAS（端末）経由でアクセスする。CORS に関係なく中身を読める。
   * 使えるのは anas.json の `network` に書き、アプリで許可したサイトだけ（それ以外は AnasError(403, "network")）。
   * 返り値はふつうの Response で、status・headers・url（リダイレクト後）は外部サイトのもの。
   */
  fetch(url: string, init?: FetchInit): Promise<Response>;
  /**
   * ファイルを探す。q は「3日以内に追加したPDF」「議事録のメモ」のような文章でよい（ファイル名とフォルダ名から探す）。
   * hidden: true なら「.」で始まるファイル・フォルダも含める。
   */
  search(q: string, options?: { limit?: number; hidden?: boolean }): Promise<SearchResult>;
  /** 外部サイトのファイルを、ブラウザを通さずに data/ の path に保存する。外部サイトが 2xx 以外ならエラー（502） */
  fetchToFile(url: string, path: string, options?: FetchToFileOptions): Promise<FetchToFileResult>;
}

/** `/a/`, `b`, `c.txt` → `/a/b/c.txt` */
export function joinPath(...parts: string[]): string {
  const segments = parts.flatMap((p) => p.split("/")).filter((s) => s !== "" && s !== ".");
  return "/" + segments.join("/");
}

/** `/a/b/c.txt` → `/a/b` */
export function parentPath(path: string): string {
  const segments = path.split("/").filter(Boolean);
  segments.pop();
  return "/" + segments.join("/");
}

/** 1234567 → `1.2 MB` */
export function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  const units = ["KB", "MB", "GB", "TB"];
  let value = bytes / 1024;
  let i = 0;
  while (value >= 1024 && i < units.length - 1) {
    value /= 1024;
    i++;
  }
  return `${value >= 100 ? value.toFixed(0) : value.toFixed(1)} ${units[i]}`;
}

async function check(res: Response): Promise<Response> {
  if (res.ok) return res;
  let message = `${res.status} ${res.statusText}`;
  let code = "http";
  try {
    const body = (await res.json()) as { error?: string; code?: string };
    if (body.error) message = body.error;
    if (body.code) code = body.code;
  } catch {
    // JSON でないエラー
  }
  throw new AnasError(message, res.status, code);
}

/** fetch() の init を、/api/fetch に送る JSON の形にする */
async function encodeFetch(url: string, init: FetchInit = {}): Promise<Record<string, unknown>> {
  const headers: Record<string, string> = {};
  new Headers(init.headers).forEach((value, name) => (headers[name] = value));
  const out: Record<string, unknown> = { url, method: init.method ?? "GET", headers };
  const body = init.body;
  if (body == null) return out;
  if (typeof body === "string") {
    out["body"] = body;
  } else if (body instanceof URLSearchParams) {
    out["body"] = body.toString();
    headers["content-type"] ??= "application/x-www-form-urlencoded;charset=UTF-8";
  } else {
    if (body instanceof Blob && body.type) headers["content-type"] ??= body.type;
    const bytes = body instanceof Blob ? new Uint8Array(await body.arrayBuffer()) : body instanceof Uint8Array ? body : new Uint8Array(body);
    let binary = "";
    for (let i = 0; i < bytes.length; i += 0x8000) binary += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
    out["bodyBase64"] = btoa(binary);
  }
  return out;
}

/** 本文を持てないステータス（new Response に本文を渡すとエラーになる） */
const NULL_BODY_STATUS = new Set([101, 103, 204, 205, 304]);

/** XMLHttpRequest で PUT する（fetch はアップロードの進捗が取れないため） */
export function putWithProgress(
  url: string,
  body: Blob | string,
  onProgress?: (loaded: number, total: number) => void,
  signal?: AbortSignal,
): Promise<unknown> {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open("PUT", url);
    xhr.responseType = "json";
    if (onProgress) xhr.upload.onprogress = (e) => onProgress(e.loaded, e.lengthComputable ? e.total : 0);
    xhr.onload = () => {
      if (xhr.status >= 200 && xhr.status < 300) {
        resolve(xhr.response);
      } else {
        const r = (xhr.response ?? {}) as { error?: string; code?: string };
        reject(new AnasError(r.error ?? `${xhr.status} ${xhr.statusText}`, xhr.status, r.code ?? "http"));
      }
    };
    xhr.onerror = () => reject(new AnasError("通信に失敗しました", 0, "network"));
    xhr.onabort = () => reject(new AnasError("中止しました", 0, "aborted"));
    signal?.addEventListener("abort", () => xhr.abort());
    xhr.send(body);
  });
}

/** API のベース URL を指定してクライアントを作る（NAS の Web UI もこれを使う） */
export function createClientForBase(apiBase: string, id = ""): AnasClient {
  const url = (op: string, params: Record<string, string> = {}) =>
    `${apiBase}/${op}?${new URLSearchParams(params).toString()}`;
  const post = async <T>(op: string, body: unknown): Promise<T> => {
    const res = await check(
      await fetch(url(op), { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) }),
    );
    return (await res.json()) as T;
  };
  const getFile = async (path: string) => check(await fetch(url("file", { path })));

  const client: AnasClient = {
    id,
    apiBase,
    async info() {
      return (await (await check(await fetch(`${apiBase}/info`))).json()) as SelfInfo;
    },
    async list(path = "/") {
      const res = await check(await fetch(url("list", { path })));
      return ((await res.json()) as { entries: Entry[] }).entries;
    },
    async listRecursive(path = "/", maxDepth = 8) {
      const out: Entry[] = [];
      const walk = async (dir: string, depth: number) => {
        for (const e of await client.list(dir)) {
          if (e.isDir) {
            if (depth < maxDepth) await walk(e.path, depth + 1);
          } else {
            out.push(e);
          }
        }
      };
      await walk(path, 0);
      return out;
    },
    async stat(path) {
      return (await (await check(await fetch(url("stat", { path })))).json()) as Entry;
    },
    async exists(path) {
      try {
        await client.stat(path);
        return true;
      } catch (e) {
        if (e instanceof AnasError && e.status === 404) return false;
        throw e;
      }
    },
    fileUrl(path, options) {
      return url("file", options?.download ? { path, download: "1" } : { path });
    },
    zipUrl(path) {
      return url("zip", { path });
    },
    streamUrl(path) {
      const segments = path.split("/").filter(Boolean).map(encodeURIComponent);
      return `${apiBase}/stream/${segments.join("/")}`;
    },
    playlistUrl(path, options = {}) {
      const params: Record<string, string> = { path };
      if (options.recursive) params.recursive = "1";
      if (options.type) params.type = options.type;
      return url("playlist.m3u8", params);
    },
    async readText(path) {
      return (await getFile(path)).text();
    },
    async readJson<T>(path: string) {
      return (await (await getFile(path)).json()) as T;
    },
    async readBlob(path) {
      return (await getFile(path)).blob();
    },
    async upload(path, data, options = {}) {
      const params: Record<string, string> = { path };
      if (options.overwrite) params["overwrite"] = "1";
      if (options.mkdirs !== false) params["mkdirs"] = "1";
      return (await putWithProgress(url("file", params), data, options.onProgress, options.signal)) as Entry;
    },
    writeText(path, text, options) {
      return client.upload(path, new Blob([text], { type: "text/plain" }), options);
    },
    writeJson(path, value, options) {
      return client.upload(path, new Blob([JSON.stringify(value, null, 2)], { type: "application/json" }), options);
    },
    mkdir(path) {
      return post<Entry>("mkdir", { path });
    },
    rename(path, newName) {
      return post<Entry>("rename", { path, newName });
    },
    move(path, toDir, options = {}) {
      return post<Entry>("move", { path, toDir, toVolume: options.toVolume });
    },
    copy(path, toDir, options = {}) {
      return post<Entry>("copy", { path, toDir, toVolume: options.toVolume });
    },
    async remove(path) {
      await post("delete", { path });
    },
    async search(q, options = {}) {
      const params: Record<string, string> = { q };
      if (options.limit) params["limit"] = String(options.limit);
      if (options.hidden) params["hidden"] = "1";
      return (await (await check(await fetch(url("search", params)))).json()) as SearchResult;
    },
    async fetch(target, init) {
      const res = await check(
        await fetch(`${apiBase}/fetch`, {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(await encodeFetch(target, init)),
          signal: init?.signal ?? null,
        }),
      );
      // 外部サイトのステータスとヘッダーは X-Anas-* に入っている
      const status = Number(res.headers.get("X-Anas-Status") ?? "200");
      const headers = new Headers(JSON.parse(decodeURIComponent(res.headers.get("X-Anas-Headers") ?? "%7B%7D")) as Record<string, string>);
      const out = new Response(NULL_BODY_STATUS.has(status) ? null : res.body, { status, headers });
      Object.defineProperty(out, "url", { value: res.headers.get("X-Anas-Url") ?? target });
      return out;
    },
    async fetchToFile(target, path, options = {}) {
      const { overwrite = false, mkdirs = true, signal, ...init } = options;
      const res = await check(
        await fetch(`${apiBase}/fetch-to-file`, {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ ...(await encodeFetch(target, init)), path, overwrite, mkdirs }),
          signal: signal ?? null,
        }),
      );
      return (await res.json()) as FetchToFileResult;
    },
  };
  return client;
}

/**
 * NAS 全体へのアクセス（`"scope": "all"` で、アプリで許可された拡張機能だけ。それ以外は AnasError(403, "scope")）。
 * NAS の Web UI と同じ API（/api/v1）を使う
 */
export interface NasAccess {
  /** ストレージの一覧 */
  volumes(): Promise<Volume[]>;
  /**
   * ボリュームのファイルを扱うクライアント。パスはボリュームのルートから（`/` 始まり）。
   * list・stat・fileUrl・zipUrl・streamUrl・playlistUrl・read*・upload・write*・mkdir・rename・move・copy・remove・search が使える（info・fetch・fetchToFile は使えない）
   */
  volume(id: string): AnasClient;
  /** すべてのボリュームから探す（返り値の path はボリュームのルートから） */
  search(q: string, options?: { limit?: number; hidden?: boolean }): Promise<SearchResult>;
  /** 入っている拡張機能の一覧 */
  extensions(): Promise<ExtensionInfo[]>;
}

export const nas: NasAccess = {
  async volumes() {
    return (await (await check(await fetch("/api/v1/volumes"))).json()) as Volume[];
  },
  volume(id) {
    return createClientForBase(`/api/v1/fs/${encodeURIComponent(id)}`);
  },
  async search(q, options = {}) {
    const params = new URLSearchParams({ q });
    if (options.limit) params.set("limit", String(options.limit));
    if (options.hidden) params.set("hidden", "1");
    return (await (await check(await fetch(`/api/v1/search?${params.toString()}`))).json()) as SearchResult;
  },
  async extensions() {
    return (await (await check(await fetch("/api/v1/extensions"))).json()) as ExtensionInfo[];
  },
};

/** 拡張機能 ID を指定してクライアントを作る */
export function createClient(id: string): AnasClient {
  return createClientForBase(`/x/${encodeURIComponent(id)}/api`, id);
}

/** 今のページの URL（/x/<id>/...）から拡張機能 ID を取り出す */
export function currentExtensionId(): string | null {
  const m = /^\/x\/([^/]+)\//.exec(location.pathname);
  return m?.[1] ? decodeURIComponent(m[1]) : null;
}

/**
 * 今のページの拡張機能のクライアント。拡張機能のページ以外で使うとエラーになる。
 */
export const anas: AnasClient = (() => {
  const id = currentExtensionId();
  if (id) return createClient(id);
  return new Proxy({} as AnasClient, {
    get() {
      throw new Error("anas は拡張機能のページ（/x/<id>/）でだけ使えます。createClient(id) を使ってください");
    },
  });
})();
