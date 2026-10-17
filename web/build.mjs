// web/ をビルドして、引数のフォルダ（APK の assets/web）に出力する
//   node build.mjs <出力先>
// - src/*.ts       → tsc で ES Modules の .js に変換（バンドルしない）
// - static/*       → そのままコピー
// - ../spec/*      → 仕様書を /_anas/spec.md などとして配信するためにコピー
import { execFileSync } from "node:child_process";
import { cpSync, mkdirSync, readdirSync, rmSync, existsSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const out = resolve(process.argv[2] ?? join(here, "dist"));

rmSync(out, { recursive: true, force: true });
mkdirSync(out, { recursive: true });

const tsc = join(here, "node_modules", ".bin", process.platform === "win32" ? "tsc.cmd" : "tsc");
execFileSync(tsc, ["-p", join(here, "tsconfig.json"), "--outDir", out], { stdio: "inherit" });

// 型定義は SDK のものだけ配る
for (const f of readdirSync(out)) {
  if (f.endsWith(".d.ts") && f !== "sdk.d.ts") rmSync(join(out, f));
}

cpSync(join(here, "static"), out, { recursive: true });

const spec = join(here, "..", "spec");
if (existsSync(spec)) {
  cpSync(join(spec, "EXTENSION_SPEC.md"), join(out, "spec.md"));
  cpSync(join(spec, "anas.schema.json"), join(out, "anas.schema.json"));
}
console.log(`web: built into ${out}`);
