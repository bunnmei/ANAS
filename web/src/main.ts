// NAS の Web UI。画面は「ファイル」（#/files/<volume>/<path>）と「拡張機能」（#/extensions）の2つ
import { $, h, icon, render, type IconName } from "./dom.js";
import {
  AnasError,
  createClientForBase,
  formatBytes,
  joinPath,
  type AnasClient,
  type Entry,
  type ExtensionInfo,
} from "./sdk.js";

interface Info {
  version: string;
  authRequired: boolean;
  authenticated: boolean;
  readOnly: boolean;
}

interface Volume {
  id: string;
  name: string;
  online: boolean;
  writable: boolean;
  totalBytes?: number;
  freeBytes?: number;
}

interface ListResponse {
  volume: string;
  path: string;
  entries: Entry[];
  extension?: ExtensionInfo;
}

type Route = { kind: "files"; volume: string; path: string } | { kind: "extensions" };

const state = {
  info: null as Info | null,
  volumes: [] as Volume[],
  extensions: [] as ExtensionInfo[],
  showHidden: loadBool("anas.showHidden", false),
};

// ---- 共通 ----

function loadBool(key: string, fallback: boolean): boolean {
  try {
    const v = localStorage.getItem(key);
    return v === null ? fallback : v === "1";
  } catch {
    return fallback;
  }
}

function saveBool(key: string, value: boolean): void {
  try {
    localStorage.setItem(key, value ? "1" : "0");
  } catch {
    // プライベートモードなど
  }
}

async function api<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(`/api/v1/${path}`, init);
  if (!res.ok) {
    const body = (await res.json().catch(() => ({}))) as { error?: string; code?: string };
    throw new AnasError(body.error ?? res.statusText, res.status, body.code ?? "http");
  }
  return (await res.json()) as T;
}

function postJson<T>(path: string, body: unknown): Promise<T> {
  return api<T>(path, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });
}

function client(volume: string): AnasClient {
  return createClientForBase(`/api/v1/fs/${encodeURIComponent(volume)}`);
}

let toastTimer = 0;
function toast(message: string, error = false): void {
  const el = $("#toast");
  el.textContent = message;
  el.className = error ? "toast toast--error show" : "toast show";
  clearTimeout(toastTimer);
  toastTimer = window.setTimeout(() => el.classList.remove("show"), error ? 6000 : 3000);
}

function errorMessage(e: unknown): string {
  return e instanceof Error ? e.message : String(e);
}

function formatDate(ms: number): string {
  if (!ms) return "";
  const d = new Date(ms);
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}/${pad(d.getMonth() + 1)}/${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

function iconFor(entry: Entry): IconName {
  if (entry.isDir) return "folder";
  if (entry.mime.startsWith("image/")) return "image";
  if (entry.mime.startsWith("video/")) return "video";
  if (entry.mime === "application/pdf") return "pdf";
  return "file";
}

function writable(volume: string): boolean {
  return !state.info?.readOnly && state.volumes.find((v) => v.id === volume)?.writable === true;
}

// ---- ルーティング ----

function parseRoute(): Route {
  const hash = location.hash.replace(/^#/, "");
  const parts = hash.split("/").filter(Boolean).map(decodeURIComponent);
  if (parts[0] === "extensions") return { kind: "extensions" };
  if (parts[0] === "files" && parts[1]) return { kind: "files", volume: parts[1], path: joinPath(...parts.slice(2)) };
  const first = state.volumes.find((v) => v.online) ?? state.volumes[0];
  return first ? { kind: "files", volume: first.id, path: "/" } : { kind: "extensions" };
}

function filesHash(volume: string, path: string): string {
  const segs = path.split("/").filter(Boolean).map(encodeURIComponent);
  return `#/files/${encodeURIComponent(volume)}/${segs.join("/")}`;
}

async function route(): Promise<void> {
  const r = parseRoute();
  renderNav(r);
  if (r.kind === "extensions") await showExtensions();
  else await showFiles(r.volume, r.path);
}

function renderNav(r: Route): void {
  const nav = $("#nav");
  render(
    nav,
    ...state.volumes.map((v) =>
      h(
        "a",
        {
          href: filesHash(v.id, "/"),
          class: "nav__item" + (!v.online ? " nav__item--offline" : ""),
          "aria-current": r.kind === "files" && r.volume === v.id ? "page" : null,
          title: v.online ? v.name : `${v.name}（接続されていません）`,
        },
        icon("storage"),
        h("span", {}, v.name),
      ),
    ),
    h(
      "a",
      { href: "#/extensions", class: "nav__item", "aria-current": r.kind === "extensions" ? "page" : null },
      icon("extension"),
      h("span", {}, "拡張機能"),
    ),
  );
}

// ---- ファイル画面 ----

async function showFiles(volume: string, path: string): Promise<void> {
  const main = $("#main");
  const vol = state.volumes.find((v) => v.id === volume);
  let res: ListResponse;
  try {
    res = await api<ListResponse>(`fs/${encodeURIComponent(volume)}/list?${new URLSearchParams({ path })}`);
  } catch (e) {
    render(
      main,
      h("div", { class: "empty" }, h("p", {}, errorMessage(e)), h("p", {}, h("a", { href: filesHash(volume, "/") }, "ストレージの一番上へ"))),
    );
    return;
  }
  const canWrite = writable(volume);
  const reload = () => void showFiles(volume, path);

  // パンくず
  const crumbs = h("nav", { class: "crumbs", "aria-label": "場所" });
  const segs = path.split("/").filter(Boolean);
  crumbs.append(h("a", { href: filesHash(volume, "/") }, vol?.name ?? volume));
  segs.forEach((s, i) => {
    crumbs.append(h("span", { class: "crumbs__sep" }, "/"));
    crumbs.append(h("a", { href: filesHash(volume, joinPath(...segs.slice(0, i + 1))) }, s));
  });

  // 操作
  const fileInput = h("input", { type: "file", multiple: true, hidden: true });
  const folderInput = h("input", { type: "file", hidden: true, webkitdirectory: true });
  const enqueue = (files: { file: File; rel: string }[]) => {
    uploads.add(files.map(({ file, rel }) => ({ volume, path: joinPath(path, rel), file })), reload);
  };
  fileInput.addEventListener("change", () => {
    enqueue([...(fileInput.files ?? [])].map((file) => ({ file, rel: file.name })));
    fileInput.value = "";
  });
  folderInput.addEventListener("change", () => {
    enqueue([...(folderInput.files ?? [])].map((file) => ({ file, rel: file.webkitRelativePath || file.name })));
    folderInput.value = "";
  });

  const hiddenToggle = h("input", { type: "checkbox", checked: state.showHidden });
  hiddenToggle.addEventListener("change", () => {
    state.showHidden = hiddenToggle.checked;
    saveBool("anas.showHidden", state.showHidden);
    reload();
  });

  const toolbar = h(
    "div",
    { class: "toolbar" },
    canWrite && h("button", { class: "btn", onclick: () => fileInput.click() }, icon("upload"), "アップロード"),
    canWrite && h("button", { class: "btn btn--tonal", onclick: () => folderInput.click() }, icon("upload"), "フォルダごと"),
    canWrite &&
      h(
        "button",
        {
          class: "btn btn--tonal",
          onclick: async () => {
            const name = prompt("新しいフォルダの名前");
            if (!name) return;
            try {
              await client(volume).mkdir(joinPath(path, name));
              reload();
            } catch (e) {
              toast(errorMessage(e), true);
            }
          },
        },
        icon("newFolder"),
        "新しいフォルダ",
      ),
    h("button", { class: "btn btn--text", onclick: reload, title: "再読み込み" }, icon("refresh")),
    h("span", { class: "toolbar__spacer" }),
    h("label", { class: "check" }, hiddenToggle, "隠しファイルを表示"),
    fileInput,
    folderInput,
  );

  // 拡張機能のフォルダなら案内を出す
  const ext = res.extension;
  const banner = ext
    ? h(
        "div",
        { class: "banner" },
        icon("extension"),
        h(
          "div",
          { class: "banner__text" },
          h("strong", {}, ext.name),
          ext.error
            ? h("span", { class: "error" }, ext.error)
            : !ext.enabled
              ? h("span", {}, "この拡張機能は無効になっています（アプリの「拡張」タブで有効にできます）")
              : h("span", {}, "このフォルダには専用の画面があります"),
        ),
        !ext.error && ext.enabled && h("a", { class: "btn", href: ext.url }, icon("open"), "専用画面で開く"),
      )
    : null;

  // 一覧
  const extFolders = new Set(state.extensions.filter((e) => e.volume === volume).map((e) => e.folder));
  const entries = res.entries.filter(
    (e) => state.showHidden || !e.name.startsWith(".") || extFolders.has(e.path),
  );
  const rows = entries.map((e) => fileRow(volume, e, canWrite, reload));
  const list =
    rows.length > 0
      ? h(
          "table",
          { class: "files" },
          h("thead", {}, h("tr", {}, h("th", {}, "名前"), h("th", { class: "num" }, "サイズ"), h("th", { class: "date" }, "更新日時"), h("th", {}))),
          h("tbody", {}, ...rows),
        )
      : h("div", { class: "empty" }, canWrite ? "空のフォルダです。ファイルをここにドラッグ＆ドロップできます。" : "空のフォルダです。");

  const space =
    vol?.freeBytes !== undefined && vol.totalBytes !== undefined
      ? h("p", { class: "muted small" }, `空き ${formatBytes(vol.freeBytes)} / ${formatBytes(vol.totalBytes)}`)
      : null;

  render(main, crumbs, toolbar, banner, list, space);
  dropTarget = canWrite ? { volume, path, reload } : null;
}

function fileRow(volume: string, e: Entry, canWrite: boolean, reload: () => void): HTMLTableRowElement {
  const c = client(volume);
  const isExtFolder = e.isDir && state.extensions.some((x) => x.volume === volume && x.folder === e.path);
  const link = e.isDir
    ? h("a", { href: filesHash(volume, e.path), class: "files__name" }, icon(isExtFolder ? "extension" : "folder"), h("span", {}, e.name))
    : h("a", { href: c.fileUrl(e.path), target: "_blank", rel: "noopener", class: "files__name" }, icon(iconFor(e)), h("span", {}, e.name));

  const actions = h(
    "td",
    { class: "files__actions" },
    !e.isDir && h("a", { class: "btn btn--icon", href: c.fileUrl(e.path, { download: true }), title: "ダウンロード", download: e.name }, icon("download")),
    canWrite &&
      h(
        "button",
        {
          class: "btn btn--icon",
          title: "名前を変更",
          onclick: async () => {
            const name = prompt("新しい名前", e.name);
            if (!name || name === e.name) return;
            try {
              await c.rename(e.path, name);
              reload();
            } catch (err) {
              toast(errorMessage(err), true);
            }
          },
        },
        icon("edit"),
      ),
    canWrite &&
      h(
        "button",
        {
          class: "btn btn--icon btn--danger",
          title: "削除",
          onclick: async () => {
            if (!confirm(`「${e.name}」を削除しますか？${e.isDir ? "\nフォルダの中身もすべて削除されます。" : ""}`)) return;
            try {
              await c.remove(e.path);
              toast(`「${e.name}」を削除しました`);
              reload();
            } catch (err) {
              toast(errorMessage(err), true);
            }
          },
        },
        icon("delete"),
      ),
  );
  return h(
    "tr",
    {},
    h("td", {}, link),
    h("td", { class: "num" }, e.isDir ? "" : formatBytes(e.size)),
    h("td", { class: "date" }, formatDate(e.modified)),
    actions,
  );
}

// ---- アップロード ----

interface UploadJob {
  volume: string;
  /** 書き込み先のファイルのパス */
  path: string;
  file: File;
}

const uploads = (() => {
  const queue: { job: UploadJob; row: HTMLElement; bar: HTMLProgressElement; done: () => void }[] = [];
  let running = false;
  let overwriteAll = false;

  const panel = () => $("#uploads");

  function add(jobs: UploadJob[], onDone: () => void): void {
    if (jobs.length === 0) return;
    overwriteAll = false;
    panel().hidden = false;
    for (const job of jobs) {
      const bar = h("progress", { max: 100, value: 0 });
      const row = h("div", { class: "upload" }, h("span", { class: "upload__name", title: job.path }, job.path), bar);
      $("#uploads-list").append(row);
      queue.push({ job, row, bar, done: onDone });
    }
    void run();
  }

  async function run(): Promise<void> {
    if (running) return;
    running = true;
    let last: (() => void) | null = null;
    while (queue.length > 0) {
      const item = queue.shift()!;
      last = item.done;
      await uploadOne(item.job, item.bar, item.row);
    }
    running = false;
    last?.();
    setTimeout(() => {
      if (!running) {
        $("#uploads-list").replaceChildren();
        panel().hidden = true;
      }
    }, 2500);
  }

  async function uploadOne(job: UploadJob, bar: HTMLProgressElement, row: HTMLElement, overwrite = overwriteAll): Promise<void> {
    try {
      await client(job.volume).upload(job.path, job.file, {
        overwrite,
        onProgress: (loaded, total) => {
          bar.value = total ? (loaded / total) * 100 : 0;
        },
      });
      bar.value = 100;
      row.classList.add("upload--done");
    } catch (e) {
      if (e instanceof AnasError && e.status === 409 && !overwrite) {
        const answer = confirm(`「${job.path}」はすでにあります。上書きしますか？\n（OK: 上書き / キャンセル: スキップ）`);
        if (answer) {
          overwriteAll = confirm("残りのファイルも、同じ名前があればすべて上書きしますか？");
          return uploadOne(job, bar, row, true);
        }
        row.classList.add("upload--skipped");
        return;
      }
      row.classList.add("upload--error");
      row.title = errorMessage(e);
      toast(`${job.path}: ${errorMessage(e)}`, true);
    }
  }

  return { add };
})();

/** ドラッグ＆ドロップの受け先（書き込めるフォルダを開いているときだけ） */
let dropTarget: { volume: string; path: string; reload: () => void } | null = null;

async function readDropped(items: DataTransferItemList): Promise<{ file: File; rel: string }[]> {
  const out: { file: File; rel: string }[] = [];
  const walk = async (entry: FileSystemEntry, prefix: string): Promise<void> => {
    if (entry.isFile) {
      const file = await new Promise<File>((resolve, reject) => (entry as FileSystemFileEntry).file(resolve, reject));
      out.push({ file, rel: prefix + entry.name });
    } else if (entry.isDirectory) {
      const reader = (entry as FileSystemDirectoryEntry).createReader();
      // readEntries は一度に全部返すとは限らないので、空になるまで繰り返す
      for (;;) {
        const batch = await new Promise<FileSystemEntry[]>((resolve, reject) => reader.readEntries(resolve, reject));
        if (batch.length === 0) break;
        for (const child of batch) await walk(child, `${prefix}${entry.name}/`);
      }
    }
  };
  const entries = [...items].map((i) => i.webkitGetAsEntry()).filter((e): e is FileSystemEntry => e !== null);
  for (const e of entries) await walk(e, "");
  return out;
}

function setupDragAndDrop(): void {
  const overlay = $("#drop");
  let depth = 0;
  window.addEventListener("dragenter", (e) => {
    if (!dropTarget || !e.dataTransfer?.types.includes("Files")) return;
    depth++;
    overlay.hidden = false;
  });
  window.addEventListener("dragleave", () => {
    depth = Math.max(0, depth - 1);
    if (depth === 0) overlay.hidden = true;
  });
  window.addEventListener("dragover", (e) => {
    if (dropTarget) e.preventDefault();
  });
  window.addEventListener("drop", async (e) => {
    e.preventDefault();
    depth = 0;
    overlay.hidden = true;
    const target = dropTarget;
    if (!target || !e.dataTransfer) return;
    const files = await readDropped(e.dataTransfer.items);
    uploads.add(
      files.map(({ file, rel }) => ({ volume: target.volume, path: joinPath(target.path, rel), file })),
      target.reload,
    );
  });
}

// ---- 拡張機能画面 ----

async function showExtensions(): Promise<void> {
  const main = $("#main");
  dropTarget = null;
  try {
    state.extensions = await api<ExtensionInfo[]>("extensions");
  } catch (e) {
    toast(errorMessage(e), true);
  }
  const writableVolumes = state.volumes.filter((v) => v.online && v.writable && !state.info?.readOnly);

  const zipInput = h("input", { type: "file", accept: ".zip,application/zip", hidden: true });
  const volumeSelect = h("select", {}, ...writableVolumes.map((v) => h("option", { value: v.id }, v.name)));
  zipInput.addEventListener("change", async () => {
    const file = zipInput.files?.[0];
    zipInput.value = "";
    if (!file) return;
    toast("インストールしています…");
    try {
      const info = await api<ExtensionInfo>(`extensions/install?${new URLSearchParams({ volume: volumeSelect.value })}`, {
        method: "POST",
        body: file,
      });
      toast(`「${info.name}」をインストールしました`);
      await showExtensions();
    } catch (e) {
      toast(errorMessage(e), true);
    }
  });

  const cards = state.extensions.map((x) =>
    h(
      "article",
      { class: "card" },
      h(
        "div",
        { class: "card__head" },
        x.iconUrl && !x.error ? h("img", { src: x.iconUrl, alt: "", class: "card__icon" }) : icon("extension"),
        h("div", {}, h("h3", {}, x.name), h("p", { class: "muted small mono" }, `${x.volume}:${x.folder}${x.version ? `  v${x.version}` : ""}`)),
      ),
      x.description && h("p", {}, x.description),
      x.error && h("p", { class: "error" }, x.error),
      !x.error && !x.enabled && h("p", { class: "muted" }, "無効（アプリの「拡張」タブで有効にできます）"),
      !x.error && x.scope === "all" && h("p", { class: "muted small" }, x.fullAccess ? "NAS 全体へのアクセス: 許可" : "NAS 全体へのアクセス: 未許可（アプリで許可できます）"),
      h(
        "div",
        { class: "card__actions" },
        !x.error && x.enabled && h("a", { class: "btn", href: x.url }, icon("open"), "開く"),
        h("a", { class: "btn btn--tonal", href: filesHash(x.volume, x.folder) }, icon("folder"), "フォルダ"),
      ),
    ),
  );

  render(
    main,
    h("h1", {}, "拡張機能"),
    h(
      "p",
      {},
      "ストレージ直下の「.名前」フォルダに anas.json を置くと、そのフォルダ専用の画面が使えます。作り方は ",
      h("a", { href: "/_anas/spec.md", target: "_blank" }, "仕様書"),
      "（AI に渡せます）と ",
      h("a", { href: "/_anas/sdk.d.ts", target: "_blank" }, "SDK の型定義"),
      " を見てください。",
    ),
    h(
      "div",
      { class: "toolbar" },
      writableVolumes.length > 0 && h("button", { class: "btn", onclick: () => zipInput.click() }, icon("upload"), "zip からインストール"),
      writableVolumes.length > 1 && h("label", { class: "check" }, "インストール先 ", volumeSelect),
      h(
        "button",
        {
          class: "btn btn--tonal",
          onclick: async () => {
            try {
              state.extensions = await postJson<ExtensionInfo[]>("extensions/rescan", {});
              await showExtensions();
              toast("再スキャンしました");
            } catch (e) {
              toast(errorMessage(e), true);
            }
          },
        },
        icon("refresh"),
        "再スキャン",
      ),
      zipInput,
    ),
    cards.length > 0 ? h("div", { class: "cards" }, ...cards) : h("div", { class: "empty" }, "拡張機能はまだありません。"),
  );
}

// ---- ログイン ----

function showLogin(): void {
  const input = h("input", { type: "password", autocomplete: "current-password", required: true, autofocus: true });
  const error = h("p", { class: "error" });
  const form = h(
    "form",
    { class: "login card" },
    h("h1", {}, "ANAS"),
    h("label", {}, "パスワード", input),
    error,
    h("button", { class: "btn", type: "submit" }, "ログイン"),
  );
  form.addEventListener("submit", async (e) => {
    e.preventDefault();
    try {
      await postJson("login", { password: input.value });
      const next = new URLSearchParams(location.search).get("next");
      if (next && next.startsWith("/x/")) location.href = next;
      else location.reload();
    } catch (err) {
      error.textContent = errorMessage(err);
    }
  });
  render($("#nav"));
  render($("#main"), form);
  input.focus();
}

// ---- 起動 ----

async function main(): Promise<void> {
  try {
    state.info = await api<Info>("info");
  } catch (e) {
    render($("#main"), h("div", { class: "empty" }, `サーバーに接続できません: ${errorMessage(e)}`));
    return;
  }
  if (state.info.authRequired && !state.info.authenticated) {
    showLogin();
    return;
  }
  const next = new URLSearchParams(location.search).get("next");
  if (next && next.startsWith("/x/")) {
    location.href = next;
    return;
  }
  if (state.info.authRequired) {
    const logout = $("#logout");
    logout.hidden = false;
    logout.addEventListener("click", async () => {
      await postJson("logout", {});
      location.reload();
    });
  }
  if (state.info.readOnly) $("#readonly").hidden = false;
  [state.volumes, state.extensions] = await Promise.all([api<Volume[]>("volumes"), api<ExtensionInfo[]>("extensions")]);
  setupDragAndDrop();
  window.addEventListener("hashchange", () => void route());
  await route();
}

void main();
