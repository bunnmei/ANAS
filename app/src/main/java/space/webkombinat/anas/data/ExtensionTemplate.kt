package space.webkombinat.anas.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import space.webkombinat.anas.core.model.ExtensionManifest

/** アプリの「新しく作る」で置く、最小の拡張機能のひな形 */
object ExtensionTemplate {
    private val ID_PATTERN = Regex("^[a-z0-9][a-z0-9_-]{0,63}$")
    private val json = Json { prettyPrint = true }

    fun isValidId(id: String): Boolean = ID_PATTERN.matches(id)

    /** 拡張機能のフォルダからの相対パス → 中身 */
    fun files(id: String, name: String): Map<String, String> {
        require(isValidId(id)) { "ID が不正です: $id" }
        val manifest = buildJsonObject {
            put("anas", ExtensionManifest.SPEC_VERSION)
            put("id", id)
            put("name", name.ifBlank { id })
            put("version", "0.1.0")
            put("description", "")
        }
        val title = name.ifBlank { id }.replace("&", "&amp;").replace("<", "&lt;")
        return mapOf(
            ExtensionManifest.FILE_NAME to json.encodeToString(manifest) + "\n",
            "app/index.html" to """
                <!doctype html>
                <html lang="ja">
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1">
                  <title>$title</title>
                  <link rel="stylesheet" href="/_anas/base.css">
                </head>
                <body>
                  <main class="anas-container">
                    <h1>$title</h1>
                    <p>このページは <code>/.$id/app/index.html</code> です。仕様書（<a href="/_anas/spec.md">/_anas/spec.md</a>）を見ながら自由に書き換えてください。</p>
                    <ul id="files"></ul>
                  </main>
                  <script type="module">
                    import { anas } from "/_anas/sdk.js";
                    const ul = document.getElementById("files");
                    for (const e of await anas.list("/")) {
                      const li = document.createElement("li");
                      li.textContent = e.name;
                      ul.append(li);
                    }
                  </script>
                </body>
                </html>
            """.trimIndent() + "\n",
            "data/.keep" to "",
        )
    }
}
