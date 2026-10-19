// 画像・テキスト・PDF のビューア。画面いっぱいに出し、閉じるボタンか「戻る」で閉じる。
// - 画像: 左右のスワイプ・矢印キーで同じフォルダの前後の画像へ
// - テキスト: 大きいファイルは最初の 2 MB だけ読む。UTF-8 でなければ Shift_JIS・EUC-JP として読む
// - PDF: pdf.js（/vendor/pdfjs）で描く。必要な部分だけ Range で読むので、大きな PDF でも最初のページはすぐ出る
import type * as PdfJs from "pdfjs-dist";
import { h, icon } from "./dom.js";
import { formatBytes, type Entry } from "./sdk.js";
import type { Track } from "./player.js";

export type ViewKind = "image" | "text" | "pdf";

/** ブラウザが表示できる画像（TIFF・HEIC は多くのブラウザで表示できない） */
const IMAGE_TYPES = /^image\/(png|jpeg|gif|webp|avif|bmp|svg\+xml|x-icon)$/;

/** MIME タイプでは分からない、テキストとして読めるファイル */
const TEXT_EXTENSIONS = new Set([
  "txt", "text", "log", "md", "markdown", "csv", "tsv", "json", "jsonc", "xml", "yml", "yaml", "toml", "ini", "cfg", "conf",
  "properties", "env", "gitignore", "editorconfig", "sh", "bash", "zsh", "fish", "bat", "cmd", "ps1",
  "c", "h", "cc", "cpp", "hpp", "cs", "java", "kt", "kts", "gradle", "swift", "m", "mm", "go", "rs", "py", "rb", "php",
  "pl", "lua", "r", "dart", "scala", "js", "mjs", "cjs", "ts", "tsx", "jsx", "vue", "svelte", "css", "scss", "sass", "less",
  "sql", "graphql", "proto", "asm", "s", "v", "vhd", "tex", "bib", "srt", "vtt", "diff", "patch", "makefile", "dockerfile",
]);

const TEXT_LIMIT = 2 * 1024 * 1024;

export function viewKind(entry: Entry): ViewKind | null {
  if (entry.isDir) return null;
  const mime = entry.mime.split(";")[0]!.trim();
  if (IMAGE_TYPES.test(mime)) return "image";
  if (mime === "application/pdf") return "pdf";
  // HTML は今までどおり新しいタブで（表示された形で）開く
  if (mime === "text/html") return null;
  const name = entry.name.toLowerCase();
  const ext = name.includes(".") ? name.split(".").pop()! : name;
  if (mime.startsWith("text/") || mime === "application/json" || mime === "application/xml" || TEXT_EXTENSIONS.has(ext)) {
    return "text";
  }
  return null;
}

let tracks: Track[] = [];
let index = 0;
let root: HTMLElement | null = null;
let pushed = false;
/** 表示を切り替えたときに、前のファイルの読み込みや描画を止める */
let cleanup: (() => void) | null = null;

/** [list] の中の [start] を開く。[list] は同じ種類（画像だけ、など）にしておく */
export function view(list: Track[], start: number): void {
  if (list.length === 0) return;
  tracks = list;
  index = Math.max(0, Math.min(start, list.length - 1));
  show();
}

export function viewOnClick(list: Track[], start: number): (e: Event) => void {
  return (e) => {
    const m = e as MouseEvent;
    if (m.ctrlKey || m.metaKey || m.shiftKey || m.altKey || m.button !== 0) return;
    e.preventDefault();
    view(list, start);
  };
}

function show(): void {
  const track = tracks[index];
  if (!track) return close();
  const { client, entry } = track;
  const kind = viewKind(entry) ?? "text";

  cleanup?.();
  cleanup = null;
  root ??= document.body.appendChild(h("section", { class: "viewer", "aria-label": "ビューア", tabindex: -1 }));
  root.className = `viewer viewer--${kind}`;
  document.body.classList.add("has-viewer");
  if (!pushed) {
    history.pushState({ anasViewer: true }, "");
    pushed = true;
  }

  const extra = h("span", { class: "viewer__extra" });
  const bar = h(
    "div",
    { class: "viewer__bar" },
    h(
      "div",
      { class: "viewer__title" },
      h("strong", { title: entry.name }, entry.name),
      tracks.length > 1 && h("span", { class: "viewer__count" }, `${index + 1} / ${tracks.length}`),
    ),
    extra,
    h("button", { class: "btn btn--icon", title: "前へ", disabled: index === 0, onclick: () => step(-1) }, icon("previous")),
    h("button", { class: "btn btn--icon", title: "次へ", disabled: index >= tracks.length - 1, onclick: () => step(1) }, icon("next")),
    h("a", { class: "btn btn--icon", title: "新しいタブで開く", href: client.fileUrl(entry.path), target: "_blank", rel: "noopener" }, icon("open")),
    h("a", { class: "btn btn--icon", title: "ダウンロード", href: client.fileUrl(entry.path, { download: true }), download: entry.name }, icon("download")),
    h("button", { class: "btn btn--icon", title: "閉じる", onclick: () => close() }, icon("close")),
  );
  const body = h("div", { class: "viewer__body" });
  root.replaceChildren(bar, body);
  root.focus({ preventScroll: true });

  if (kind === "image") cleanup = showImage(body, track);
  else if (kind === "pdf") cleanup = showPdf(body, extra, track);
  else cleanup = showText(body, extra, track);
}

function step(delta: number): void {
  const next = index + delta;
  if (next < 0 || next >= tracks.length) return;
  index = next;
  show();
}

export function close(fromHistory = false): void {
  cleanup?.();
  cleanup = null;
  root?.remove();
  root = null;
  tracks = [];
  document.body.classList.remove("has-viewer");
  if (pushed) {
    pushed = false;
    if (!fromHistory) history.back();
  }
}

function message(body: HTMLElement, text: string, error = false): void {
  body.replaceChildren(h("p", { class: error ? "viewer__msg error" : "viewer__msg" }, text));
}

// ---- 画像 ----

function showImage(body: HTMLElement, { client, entry }: Track): () => void {
  const img = h("img", { class: "viewer__img", alt: entry.name, src: client.fileUrl(entry.path), draggable: "false" });
  img.addEventListener("error", () => message(body, "この画像は表示できませんでした。ダウンロードして開いてください。", true));
  body.replaceChildren(img);
  // 次の画像を先に読んでおく
  const next = tracks[index + 1];
  if (next) new Image().src = next.client.fileUrl(next.entry.path);

  // 左右のスワイプで前後へ（2 本指の拡大中は何もしない）
  let startX = 0;
  let startY = 0;
  let fingers = 0;
  const down = (e: PointerEvent) => {
    fingers++;
    startX = e.clientX;
    startY = e.clientY;
  };
  const up = (e: PointerEvent) => {
    const multi = fingers > 1;
    fingers = Math.max(0, fingers - 1);
    if (multi || window.visualViewport && window.visualViewport.scale > 1.05) return;
    const dx = e.clientX - startX;
    const dy = e.clientY - startY;
    if (Math.abs(dx) > 60 && Math.abs(dx) > Math.abs(dy) * 1.5) step(dx < 0 ? 1 : -1);
  };
  const cancel = () => (fingers = 0);
  body.addEventListener("pointerdown", down);
  body.addEventListener("pointerup", up);
  body.addEventListener("pointercancel", cancel);
  return () => {
    img.removeAttribute("src");
  };
}

// ---- テキスト ----

function showText(body: HTMLElement, extra: HTMLElement, { client, entry }: Track): () => void {
  const abort = new AbortController();
  message(body, "読み込み中…");
  void (async () => {
    try {
      const res = await fetch(client.fileUrl(entry.path), {
        headers: { Range: `bytes=0-${TEXT_LIMIT - 1}` },
        signal: abort.signal,
      });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const bytes = new Uint8Array(await res.arrayBuffer());
      const total = Number(res.headers.get("Content-Range")?.split("/")[1] ?? bytes.length);
      const { text, encoding } = decodeText(bytes);
      const pre = h("pre", { class: "viewer__text" }, text);
      const note =
        total > bytes.length
          ? h("p", { class: "viewer__note" }, `大きいファイルなので、最初の ${formatBytes(bytes.length)}（全体 ${formatBytes(total)}）だけ表示しています。全体はダウンロードしてください。`)
          : null;
      extra.replaceChildren(h("span", { class: "viewer__count" }, encoding));
      body.replaceChildren(...(note ? [note, pre] : [pre]));
      body.scrollTop = 0;
    } catch (e) {
      if (!abort.signal.aborted) message(body, `読み込めませんでした: ${e instanceof Error ? e.message : String(e)}`, true);
    }
  })();
  return () => abort.abort();
}

/** 文字コードを推測して文字列にする。途中で切れた最後の文字は捨てる（stream: true） */
export function decodeText(bytes: Uint8Array): { text: string; encoding: string } {
  if (bytes[0] === 0xff && bytes[1] === 0xfe) return { text: new TextDecoder("utf-16le").decode(bytes), encoding: "UTF-16" };
  if (bytes[0] === 0xfe && bytes[1] === 0xff) return { text: new TextDecoder("utf-16be").decode(bytes), encoding: "UTF-16" };
  for (const [label, name] of [["utf-8", "UTF-8"], ["shift_jis", "Shift_JIS"], ["euc-jp", "EUC-JP"]] as const) {
    try {
      return { text: new TextDecoder(label, { fatal: true }).decode(bytes, { stream: true }), encoding: name };
    } catch {
      // 次の文字コードで試す
    }
  }
  return { text: new TextDecoder().decode(bytes), encoding: "UTF-8?" };
}

// ---- PDF ----

let pdfjs: Promise<typeof PdfJs> | null = null;
const PDFJS_BASE = "/vendor/pdfjs/";

function loadPdfJs(): Promise<typeof PdfJs> {
  pdfjs ??= (import(/* 実行時に読む */ PDFJS_BASE + "pdf.min.mjs") as Promise<typeof PdfJs>).then((m) => {
    m.GlobalWorkerOptions.workerSrc = PDFJS_BASE + "pdf.worker.min.mjs";
    return m;
  });
  return pdfjs;
}

const ZOOMS = [1, 1.5, 2, 3];

function showPdf(body: HTMLElement, extra: HTMLElement, { client, entry }: Track): () => void {
  let closed = false;
  let task: PdfJs.PDFDocumentLoadingTask | null = null;
  let observer: IntersectionObserver | null = null;
  message(body, "読み込み中…");

  void (async () => {
    try {
      const lib = await loadPdfJs();
      if (closed) return;
      task = lib.getDocument({
        url: client.fileUrl(entry.path),
        disableAutoFetch: true,
        disableStream: true,
        cMapUrl: PDFJS_BASE + "cmaps/",
        cMapPacked: true,
        standardFontDataUrl: PDFJS_BASE + "standard_fonts/",
        wasmUrl: PDFJS_BASE + "wasm/",
        iccUrl: PDFJS_BASE + "iccs/",
      });
      const doc = await task.promise;
      if (closed) return;

      // ページの大きさは 1 ページ目に合わせて仮置きし、描いたときに本当の大きさにする
      const first = (await doc.getPage(1)).getViewport({ scale: 1 });
      let zoom = 0;
      const pages = h("div", { class: "viewer__pages" });
      const slots: HTMLDivElement[] = [];
      const rendered = new Map<number, number>(); // ページ番号 → 描いたときの幅
      const width = () => Math.min(body.clientWidth - 16, 1000) * ZOOMS[zoom]!;

      for (let n = 1; n <= doc.numPages; n++) {
        const slot = h("div", { class: "viewer__page", "data-page": n });
        slot.style.aspectRatio = `${first.width} / ${first.height}`;
        slots.push(slot);
        pages.append(slot);
      }
      const label = h("span", { class: "viewer__count" }, `${doc.numPages} ページ`);
      const zoomBtn = (delta: number, name: "zoomIn" | "zoomOut", title: string) =>
        h("button", { class: "btn btn--icon", title, onclick: () => setZoom(zoom + delta) }, icon(name));
      extra.replaceChildren(label, zoomBtn(-1, "zoomOut", "縮小"), zoomBtn(1, "zoomIn", "拡大"));
      body.replaceChildren(pages);

      const render = async (slot: HTMLDivElement) => {
        const n = Number(slot.dataset.page);
        const w = width();
        if (rendered.get(n) === w) return;
        rendered.set(n, w);
        const page = await doc.getPage(n);
        if (closed) return;
        const unit = page.getViewport({ scale: 1 });
        const dpr = Math.min(window.devicePixelRatio || 1, 2);
        const viewport = page.getViewport({ scale: (w / unit.width) * dpr });
        const canvas = h("canvas", { class: "viewer__canvas" });
        canvas.width = Math.floor(viewport.width);
        canvas.height = Math.floor(viewport.height);
        await page.render({ canvas, viewport }).promise;
        page.cleanup();
        if (closed || rendered.get(n) !== w) return;
        slot.style.aspectRatio = `${unit.width} / ${unit.height}`;
        slot.replaceChildren(canvas);
      };

      const setZoom = (next: number) => {
        zoom = Math.max(0, Math.min(ZOOMS.length - 1, next));
        // 見ていたページの位置を保つ
        const ratio = body.scrollTop / Math.max(1, body.scrollHeight);
        pages.style.width = `${width()}px`;
        body.scrollTop = ratio * body.scrollHeight;
        for (const slot of slots) if (isVisible(slot)) void render(slot).catch(() => undefined);
      };
      const isVisible = (el: HTMLElement) => {
        const r = el.getBoundingClientRect();
        return r.bottom > -window.innerHeight && r.top < window.innerHeight * 2;
      };

      pages.style.width = `${width()}px`;
      observer = new IntersectionObserver(
        (items) => {
          for (const item of items) {
            if (item.isIntersecting) void render(item.target as HTMLDivElement).catch(() => undefined);
          }
        },
        { root: body, rootMargin: "100% 0px" },
      );
      for (const slot of slots) observer.observe(slot);
      // 今見ているページ番号
      let ticking = false;
      body.addEventListener("scroll", () => {
        if (ticking) return;
        ticking = true;
        requestAnimationFrame(() => {
          ticking = false;
          const top = body.getBoundingClientRect().top + body.clientHeight / 3;
          const current = slots.find((slot) => slot.getBoundingClientRect().bottom > top);
          if (current) label.textContent = `${current.dataset.page} / ${doc.numPages} ページ`;
        });
      }, { passive: true });
    } catch (e) {
      if (closed) return;
      const name = (e as { name?: string }).name;
      message(
        body,
        name === "PasswordException"
          ? "パスワード付きの PDF は表示できません。ダウンロードして開いてください。"
          : `PDF を表示できませんでした: ${e instanceof Error ? e.message : String(e)}`,
        true,
      );
    }
  })();

  return () => {
    closed = true;
    observer?.disconnect();
    void task?.destroy();
  };
}

window.addEventListener("popstate", () => {
  if (pushed) close(true);
});
window.addEventListener("keydown", (e) => {
  if (!root) return;
  if (e.key === "Escape") close();
  else if (root.classList.contains("viewer--image") && e.key === "ArrowRight") step(1);
  else if (root.classList.contains("viewer--image") && e.key === "ArrowLeft") step(-1);
});
