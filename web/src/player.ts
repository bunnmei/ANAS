// 音声・動画のプレーヤー。
// - 音声は画面の下に出したままにして、ほかのフォルダを見ている間も続けて再生する
// - 動画は画面いっぱいに出す（戻る操作で閉じる）
// - 終わったら同じ一覧の次のファイルへ進む
import { h, icon, render } from "./dom.js";
import type { AnasClient, Entry } from "./sdk.js";

export type MediaKind = "audio" | "video";

/** 再生するファイルと、その URL を作るクライアント（検索結果ではボリュームがばらばらなので 1 件ずつ持つ） */
export interface Track {
  client: AnasClient;
  entry: Entry;
}

export function mediaKind(entry: Entry): MediaKind | null {
  if (entry.isDir) return null;
  if (entry.mime.startsWith("audio/")) return "audio";
  if (entry.mime.startsWith("video/")) return "video";
  return null;
}

let tracks: Track[] = [];
let index = 0;
let root: HTMLElement | null = null;
let media: HTMLMediaElement | null = null;
/** 動画を開いたときに履歴を 1 つ積んだか（戻るで閉じるため） */
let pushed = false;

/** [list] の中の [start] から再生する。[list] は同じ種類（音声だけ・動画だけ）にしておく */
export function play(list: Track[], start: number): void {
  if (list.length === 0) return;
  tracks = list;
  index = Math.max(0, Math.min(start, list.length - 1));
  show();
}

/** 一覧のリンクのクリックをプレーヤーで開く。Ctrl などを押しながらなら普段どおり新しいタブで開く */
export function playOnClick(list: Track[], start: number): (e: Event) => void {
  return (e) => {
    const m = e as MouseEvent;
    if (m.ctrlKey || m.metaKey || m.shiftKey || m.altKey || m.button !== 0) return;
    e.preventDefault();
    play(list, start);
  };
}

function show(): void {
  const track = tracks[index];
  if (!track) return close();
  const { client, entry } = track;
  const kind = mediaKind(entry) ?? "audio";

  root ??= document.body.appendChild(h("section", { class: "player", "aria-label": "プレーヤー" }));
  root.className = `player player--${kind}`;
  document.body.classList.toggle("has-player", kind === "audio");
  if (kind === "video" && !pushed) {
    history.pushState({ anasPlayer: true }, "");
    pushed = true;
  }

  // 同じ種類なら同じ要素で src だけ替える（自動再生の制限は要素ごとなので、
  // 一度タップで再生した要素なら次の曲もそのまま再生できる）
  if (!media || media.tagName.toLowerCase() !== kind) {
    stopMedia();
    media = createMedia(kind);
  }
  const el = media;
  el.src = client.streamUrl(entry.path);
  // タップの処理の中で play() を呼ぶ（autoplay 属性だけだと、スマホではタップによる再生と見なされない）
  el.play().catch(() => {
    // 自動再生が止められたときは、コントロールの再生ボタンで始めてもらう
  });

  const title = h(
    "div",
    { class: "player__title" },
    h("strong", { title: entry.name }, entry.name),
    tracks.length > 1 && h("span", { class: "muted small" }, `${index + 1} / ${tracks.length}`),
  );
  const bar = h(
    "div",
    { class: "player__bar" },
    title,
    h("button", { class: "btn btn--icon", title: "前へ", disabled: index === 0, onclick: () => step(-1) }, icon("previous")),
    h("button", { class: "btn btn--icon", title: "次へ", disabled: index >= tracks.length - 1, onclick: () => step(1) }, icon("next")),
    h("a", { class: "btn btn--icon", title: "ダウンロード", href: client.fileUrl(entry.path, { download: true }), download: entry.name }, icon("download")),
    h("button", { class: "btn btn--icon", title: "閉じる", onclick: () => close() }, icon("close")),
  );
  // 再生中の要素は外さずに、まわりだけ入れ替える
  const stage = el.parentElement ?? h("div", { class: "player__stage" }, el);
  for (const child of [...root.children]) if (child !== stage) child.remove();
  root.prepend(bar);
  root.append(stage, h("p", { class: "player__error", hidden: true }));
  updateMediaSession(entry);
}

function createMedia(kind: MediaKind): HTMLMediaElement {
  const el = h(kind, { class: "player__media", controls: true, playsinline: true, preload: "metadata" });
  el.addEventListener("ended", () => {
    if (index < tracks.length - 1) step(1);
  });
  el.addEventListener("error", () => {
    const entry = tracks[index]?.entry;
    const message = root?.querySelector<HTMLElement>(".player__error");
    if (el !== media || !el.getAttribute("src") || !entry || !message) return;
    const ext = entry.name.includes(".") ? entry.name.split(".").pop()!.toUpperCase() : "";
    message.textContent =
      `再生できませんでした。${ext ? `この形式（${ext}）や` : ""}中の圧縮方式によっては、ブラウザでは再生できません。` +
      "ダウンロードするか、「再生リスト」から VLC などのプレーヤーで開いてください。";
    message.hidden = false;
  });
  return el;
}

function step(delta: number): void {
  const next = index + delta;
  if (next < 0 || next >= tracks.length) return;
  index = next;
  show();
}

function stopMedia(): void {
  if (!media) return;
  media.pause();
  media.removeAttribute("src");
  media.load(); // 読み込み途中の通信も止める
  media = null;
}

export function close(fromHistory = false): void {
  stopMedia();
  root?.remove();
  root = null;
  tracks = [];
  document.body.classList.remove("has-player");
  if ("mediaSession" in navigator) navigator.mediaSession.metadata = null;
  if (pushed) {
    pushed = false;
    if (!fromHistory) history.back();
  }
}

/** ロック画面や通知の再生コントロールに曲名と前後の操作を出す */
function updateMediaSession(entry: Entry): void {
  if (!("mediaSession" in navigator)) return;
  const folder = entry.path.split("/").slice(-2, -1)[0] ?? "";
  try {
    navigator.mediaSession.metadata = new MediaMetadata({ title: entry.name.replace(/\.[^.]+$/, ""), album: folder });
    navigator.mediaSession.setActionHandler("previoustrack", index > 0 ? () => step(-1) : null);
    navigator.mediaSession.setActionHandler("nexttrack", index < tracks.length - 1 ? () => step(1) : null);
  } catch {
    // 対応していない操作があっても再生には関係ない
  }
}

window.addEventListener("popstate", () => {
  if (pushed) close(true);
});
window.addEventListener("keydown", (e) => {
  if (e.key === "Escape" && root?.classList.contains("player--video")) close();
});
