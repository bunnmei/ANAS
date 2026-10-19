// 移動・コピー先のフォルダを選ぶダイアログ
import { h, icon, render } from "./dom.js";
import { joinPath, type AnasClient } from "./sdk.js";

export interface PickTarget {
  volume: string;
  path: string;
}

export interface PickOptions {
  title: string;
  /** 決定ボタンの文字（「ここに移動」など） */
  action: string;
  /** 選べるストレージ（書き込めるものだけ渡す） */
  volumes: { id: string; name: string }[];
  start: PickTarget;
  client: (volume: string) => AnasClient;
  /** 選べないフォルダ（移動するフォルダ自身とその中） */
  disabled?: (target: PickTarget) => string | null;
}

/** 選んだフォルダ。やめたら null */
export function pickFolder(options: PickOptions): Promise<PickTarget | null> {
  return new Promise((resolve) => {
    let current: PickTarget = options.volumes.some((v) => v.id === options.start.volume)
      ? options.start
      : { volume: options.volumes[0]?.id ?? "", path: "/" };
    let loading = 0;

    const listEl = h("ul", { class: "picker__list" });
    const crumbs = h("div", { class: "picker__crumbs" });
    const reason = h("p", { class: "picker__reason error small", hidden: true });
    const ok = h("button", { class: "btn", type: "button" }, options.action);
    const dialog = h(
      "dialog",
      { class: "picker" },
      h("h2", { class: "picker__title" }, options.title),
      options.volumes.length > 1 &&
        h(
          "div",
          { class: "picker__volumes" },
          ...options.volumes.map((v) =>
            h("button", { class: "nav__item", type: "button", "data-volume": v.id, onclick: () => go({ volume: v.id, path: "/" }) }, icon("storage"), v.name),
          ),
        ),
      crumbs,
      listEl,
      reason,
      h(
        "div",
        { class: "picker__actions" },
        h("button", { class: "btn btn--text", type: "button", onclick: () => newFolder() }, icon("newFolder"), "新しいフォルダ"),
        h("span", { class: "toolbar__spacer" }),
        h("button", { class: "btn btn--tonal", type: "button", onclick: () => finish(null) }, "キャンセル"),
        ok,
      ),
    );
    ok.addEventListener("click", () => finish(current));
    dialog.addEventListener("cancel", (e) => {
      e.preventDefault();
      finish(null);
    });

    function finish(result: PickTarget | null): void {
      dialog.close();
      dialog.remove();
      resolve(result);
    }

    async function go(target: PickTarget): Promise<void> {
      current = target;
      const ticket = ++loading;
      for (const b of dialog.querySelectorAll<HTMLElement>(".picker__volumes .nav__item")) {
        b.setAttribute("aria-current", b.dataset.volume === target.volume ? "page" : "false");
      }
      const segs = target.path.split("/").filter(Boolean);
      const volName = options.volumes.find((v) => v.id === target.volume)?.name ?? target.volume;
      render(
        crumbs,
        h("button", { class: "picker__crumb", type: "button", onclick: () => go({ volume: target.volume, path: "/" }) }, volName),
        ...segs.flatMap((s, i) => [
          h("span", { class: "crumbs__sep" }, "/"),
          h("button", { class: "picker__crumb", type: "button", onclick: () => go({ volume: target.volume, path: joinPath(...segs.slice(0, i + 1)) }) }, s),
        ]),
      );
      const why = options.disabled?.(target) ?? null;
      reason.textContent = why ?? "";
      reason.hidden = !why;
      ok.disabled = why !== null;
      render(listEl, h("li", { class: "muted small" }, "読み込み中…"));
      try {
        const entries = await options.client(target.volume).list(target.path);
        if (ticket !== loading) return;
        const dirs = entries.filter((e) => e.isDir && !e.name.startsWith("."));
        render(
          listEl,
          target.path !== "/" &&
            h(
              "li",
              {},
              h("button", { type: "button", onclick: () => go({ volume: target.volume, path: joinPath(...segs.slice(0, -1)) || "/" }) }, icon("folder"), ".."),
            ),
          ...dirs.map((d) => h("li", {}, h("button", { type: "button", onclick: () => go({ volume: target.volume, path: d.path }) }, icon("folder"), d.name))),
          dirs.length === 0 && h("li", { class: "muted small" }, "フォルダはありません"),
        );
      } catch (e) {
        if (ticket === loading) render(listEl, h("li", { class: "error small" }, e instanceof Error ? e.message : String(e)));
      }
    }

    async function newFolder(): Promise<void> {
      const name = prompt("新しいフォルダの名前");
      if (!name) return;
      try {
        const made = await options.client(current.volume).mkdir(joinPath(current.path, name));
        await go({ volume: current.volume, path: made.path });
      } catch (e) {
        reason.textContent = e instanceof Error ? e.message : String(e);
        reason.hidden = false;
      }
    }

    document.body.append(dialog);
    dialog.showModal();
    void go(current);
  });
}
