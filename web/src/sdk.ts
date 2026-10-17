/**
 * ANAS 拡張機能 SDK
 *
 * 拡張機能のページ（/x/<id>/ 以下）から、自分の data/ フォルダを読み書きするための小さなライブラリ。
 *
 * ```ts
 * import { anas } from "/_anas/sdk.js";
 * const entries = await anas.list("/");              // data/ 直下の一覧
 * img.src = anas.fileUrl("/covers/a.jpg");           // <img> / <iframe> / <video> に使える URL
 * await anas.upload("/books/a.pdf", file, { onProgress: (done, total) => ... });
 * ```
 *
 * パスはすべて data/ フォルダからの相対パス（`/` 始まり）。data/ の外には出られない。
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
  error?: string;
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
  /** ファイル、またはフォルダを中身ごと削除する */
  remove(path: string): Promise<void>;
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
    async remove(path) {
      await post("delete", { path });
    },
  };
  return client;
}

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
