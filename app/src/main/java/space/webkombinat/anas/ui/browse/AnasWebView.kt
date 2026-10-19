package space.webkombinat.anas.ui.browse

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Base64
import android.content.ContextWrapper
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import space.webkombinat.anas.BuildConfig
import space.webkombinat.anas.client.LanUrls
import java.io.ByteArrayInputStream

/** WebView の表示状態（上のバーや、エラー表示に使う） */
class WebViewState {
    var title by mutableStateOf("")
    var progress by mutableIntStateOf(100)
    var canGoBack by mutableStateOf(false)
    /** ページ自体を開けなかったときの理由 */
    var error by mutableStateOf<String?>(null)
    internal var view: WebView? = null

    fun reload() {
        error = null
        view?.reload()
    }
}

/** 保存先を選んでもらうまで預かっておく、ページが作ったファイル（blob: のダウンロード） */
private class PendingFile(val bytes: ByteArray, val name: String, val mime: String)

private const val MAX_BLOB_BYTES = 50 * 1024 * 1024

/**
 * `<a download href="blob:...">` は WebView の DownloadListener に名前が届かず、
 * ダウンロードマネージャーでも取れないので、ページの中で読んでアプリに渡す
 */
/** アプリのテーマをページに伝える。color-scheme も合わせて、フォームの部品やスクロールバーの色も揃える */
private fun themeJs(dark: Boolean): String {
    val theme = if (dark) "dark" else "light"
    return "document.documentElement.dataset.anasTheme='$theme';document.documentElement.style.colorScheme='$theme';"
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private const val BLOB_DOWNLOAD_JS = """
(() => {
  if (window.__anasDownload) return;
  window.__anasDownload = true;
  // 拡張機能のページは CSP（connect-src）で blob: を fetch できないので、作られた Blob を覚えておく
  const blobs = new Map();
  const create = URL.createObjectURL;
  URL.createObjectURL = function (obj) {
    const url = create.call(URL, obj);
    if (obj instanceof Blob) blobs.set(url, obj);
    return url;
  };
  const revoke = URL.revokeObjectURL;
  URL.revokeObjectURL = function (url) {
    setTimeout(() => blobs.delete(url), 60000);
    return revoke.call(URL, url);
  };
  const send = (base64, name, mime) => AnasAndroid.save(base64, name, mime);
  const hit = (a) => a && a.hasAttribute("download") && /^(blob|data):/.test(a.href);
  const save = (a) => {
    const name = a.getAttribute("download") || "download";
    const href = a.href;
    if (href.startsWith("data:")) {
      const comma = href.indexOf(",");
      const meta = href.slice(5, comma);
      const body = href.slice(comma + 1);
      const base64 = meta.endsWith(";base64") ? body : btoa(unescape(encodeURIComponent(decodeURIComponent(body))));
      send(base64, name, meta.split(";")[0]);
      return;
    }
    const blob = blobs.get(href);
    if (!blob) { alert("このファイルは保存できません"); return; }
    if (blob.size > $MAX_BLOB_BYTES) { alert("大きすぎて保存できません"); return; }
    const reader = new FileReader();
    reader.onload = () => send(String(reader.result).split(",")[1] || "", name, blob.type || "");
    reader.readAsDataURL(blob);
  };
  document.addEventListener("click", (e) => {
    const a = e.target instanceof Element ? e.target.closest("a") : null;
    if (hit(a)) { e.preventDefault(); save(a); }
  }, true);
  const click = HTMLAnchorElement.prototype.click;
  HTMLAnchorElement.prototype.click = function () {
    if (!this.isConnected && hit(this)) { save(this); return; }
    return click.call(this);
  };
})();
"""

/**
 * LAN（プライベート IP）の中だけを開く WebView。
 * - 外部サイトへのリンクは普段のブラウザに渡す。ページが外部から読み込もうとする画像・スクリプトは止める
 * - アップロード（ファイル選択）・ダウンロード（ログインのクッキー付き）に対応
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun AnasWebView(
    url: String,
    dark: Boolean,
    state: WebViewState,
    onMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val message by rememberUpdatedState(onMessage)
    // 回転やタブの切り替えで戻ってきたとき、見ていたページと履歴を戻す
    val saved = rememberSaveable(url) { Bundle() }

    // ---- ファイル選択 ----
    var pendingChooser by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val chooser = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        val uris = if (result.resultCode != Activity.RESULT_OK || data == null) {
            null
        } else {
            data.clipData?.let { clip -> Array(clip.itemCount) { clip.getItemAt(it).uri } }
                ?: data.data?.let { arrayOf(it) }
        }
        pendingChooser?.onReceiveValue(uris)
        pendingChooser = null
    }

    // ---- blob: の保存 ----
    var pendingFile by remember { mutableStateOf<PendingFile?>(null) }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val file = pendingFile
        pendingFile = null
        val uri = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && file != null && uri != null) {
            runCatching { context.contentResolver.openOutputStream(uri)!!.use { it.write(file.bytes) } }
                .onSuccess { message("${file.name} を保存しました") }
                .onFailure { message("保存できませんでした: ${it.message}") }
        }
    }

    // ---- Android 9 のダウンロード（共有の Download フォルダへの書き込みに権限が要る） ----
    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        message(if (granted) "もう一度ダウンロードしてください" else "保存の権限がないためダウンロードできません")
    }

    val launchChooser by rememberUpdatedState { callback: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams ->
        pendingChooser?.onReceiveValue(null)
        pendingChooser = callback
        try {
            chooser.launch(chooserIntent(params))
            true
        } catch (_: ActivityNotFoundException) {
            pendingChooser = null
            false
        }
    }
    val saveBlob by rememberUpdatedState { file: PendingFile ->
        pendingFile = file
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(file.mime.ifBlank { "application/octet-stream" })
            .putExtra(Intent.EXTRA_TITLE, file.name)
        runCatching { saver.launch(intent) }.onFailure { pendingFile = null; message("保存先を選べません") }
    }
    val download by rememberUpdatedState { downloadUrl: String, userAgent: String, disposition: String?, mime: String? ->
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            enqueueDownload(context, downloadUrl, userAgent, disposition, mime)
                .onSuccess { message("$it をダウンロードしています") }
                .onFailure { message("ダウンロードできませんでした: ${it.message}") }
        }
    }

    BackHandler(enabled = state.canGoBack) { state.view?.goBack() }

    // ---- 動画の全画面（WebView は自分では全画面にならないので、渡された View を画面の一番上に重ねる） ----
    var exitFullscreen by remember { mutableStateOf<(() -> Unit)?>(null) }
    val showFullscreen by rememberUpdatedState { view: View, callback: WebChromeClient.CustomViewCallback ->
        val activity = context.findActivity()
        if (activity == null) {
            callback.onCustomViewHidden()
        } else {
            exitFullscreen?.invoke()
            val decor = activity.window.decorView as ViewGroup
            decor.addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            val bars = WindowCompat.getInsetsController(activity.window, decor)
            bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            bars.hide(WindowInsetsCompat.Type.systemBars())
            exitFullscreen = {
                exitFullscreen = null
                decor.removeView(view)
                bars.show(WindowInsetsCompat.Type.systemBars())
                callback.onCustomViewHidden()
            }
        }
    }
    // 全画面のときの「戻る」は全画面をやめるだけにする（後に登録したほうが優先される）
    BackHandler(enabled = exitFullscreen != null) { exitFullscreen?.invoke() }

    DisposableEffect(state) {
        onDispose {
            CookieManager.getInstance().flush()
            state.view?.let { view ->
                saved.clear()
                view.saveState(saved)
                view.destroy()
            }
            state.view = null
            exitFullscreen?.invoke()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            // ページの prefers-color-scheme をアプリのテーマ（ライト / ダーク）に合わせる。
            // Android 9 はこの仕組み（isLightTheme）がないので、下の themeJs でも伝える
            val themed = ContextThemeWrapper(
                ctx,
                if (dark) android.R.style.Theme_Material_NoActionBar else android.R.style.Theme_Material_Light_NoActionBar,
            )
            // デバッグビルドだけ、PC の Chrome（chrome://inspect）から中身を調べられるようにする
            if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
            WebView(themed).apply {
                // AndroidView の既定（WRAP_CONTENT）のままだと、ページの 100vh が 0 になる
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.setSupportZoom(true)
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                settings.setSupportMultipleWindows(false)
                CookieManager.getInstance().setAcceptCookie(true)

                // ページの JS より先に <html data-anas-theme="dark|light"> を付ける（base.css が見る）
                val themeJs = themeJs(dark)
                val atDocumentStart = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
                if (atDocumentStart) WebViewCompat.addDocumentStartJavaScript(this, themeJs, setOf("*"))

                addJavascriptInterface(
                    object {
                        @JavascriptInterface
                        fun save(base64: String, name: String, mime: String) {
                            val bytes = runCatching { Base64.decode(base64, Base64.DEFAULT) }.getOrNull() ?: return
                            val clean = LanUrls.fileName("attachment; filename=\"${name.replace("\"", "")}\"", "")
                            post { saveBlob(PendingFile(bytes, clean, mime)) }
                        }
                    },
                    "AnasAndroid",
                )

                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val target = request.url.toString()
                        // フォルダの再生リスト（M3U8）は WebView では再生できないので、VLC などのプレーヤーに渡す
                        if (LanUrls.isLan(target) && request.url.path.orEmpty().endsWith("/playlist.m3u8")) {
                            try {
                                view.context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(request.url, "audio/x-mpegurl"))
                            } catch (_: ActivityNotFoundException) {
                                message("再生リストを開けるアプリがありません（VLC などのプレーヤーを入れてください）")
                            }
                            return true
                        }
                        if (LanUrls.isLan(target)) return false
                        // LAN の外（https://… や mailto: など）は WebView では開かない
                        if (request.isForMainFrame || request.hasGesture()) {
                            try {
                                view.context.startActivity(Intent(Intent.ACTION_VIEW, request.url).addCategory(Intent.CATEGORY_BROWSABLE))
                            } catch (_: ActivityNotFoundException) {
                                message("開けるアプリがありません: $target")
                            }
                        }
                        return true
                    }

                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                        val target = request.url.toString()
                        val asHttp = when (request.url.scheme?.lowercase()) {
                            "http", "https" -> target
                            "ws", "wss" -> "http" + target.drop(2)
                            else -> return null // data: blob: など
                        }
                        if (!LanUrls.isLan(asHttp)) {
                            return WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                        }
                        return null
                    }

                    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                        state.error = null
                        if (!atDocumentStart) view.evaluateJavascript(themeJs, null)
                    }

                    override fun onPageFinished(view: WebView, url: String?) {
                        view.evaluateJavascript(BLOB_DOWNLOAD_JS, null)
                    }

                    override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                        state.canGoBack = view.canGoBack()
                    }

                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                        if (request.isForMainFrame) state.error = error.description?.toString() ?: "error ${error.errorCode}"
                    }
                }

                webChromeClient = object : WebChromeClient() {
                    override fun onShowCustomView(view: View, callback: CustomViewCallback) = showFullscreen(view, callback)

                    override fun onHideCustomView() {
                        exitFullscreen?.invoke()
                    }

                    override fun onProgressChanged(view: WebView, newProgress: Int) {
                        state.progress = newProgress
                    }

                    override fun onReceivedTitle(view: WebView, title: String?) {
                        state.title = title.orEmpty()
                    }

                    override fun onShowFileChooser(
                        webView: WebView,
                        filePathCallback: ValueCallback<Array<Uri>>,
                        fileChooserParams: FileChooserParams,
                    ): Boolean = launchChooser(filePathCallback, fileChooserParams)
                }

                setDownloadListener { downloadUrl, userAgent, disposition, mime, _ ->
                    if (LanUrls.isLan(downloadUrl)) download(downloadUrl, userAgent, disposition, mime)
                }

                state.view = this
                if (saved.isEmpty || restoreState(saved) == null) loadUrl(url)
            }
        },
    )
}

/** input の accept（`.pdf` や `image/png`）を Android の MIME タイプにする。分からない拡張子があれば何でも選べるようにする */
private fun chooserIntent(params: WebChromeClient.FileChooserParams): Intent {
    val accepts = params.acceptTypes.flatMap { it.split(',') }.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
    val mimes = accepts.map { type ->
        when {
            type.contains('/') -> type
            type.startsWith('.') -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(type.drop(1))
            else -> null
        }
    }
    val intent = Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
    if (mimes.isNotEmpty() && mimes.all { it != null }) intent.putExtra(Intent.EXTRA_MIME_TYPES, mimes.filterNotNull().distinct().toTypedArray())
    if (params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
    return intent
}

/** ダウンロードマネージャーに、ログインのクッキーを付けて渡す。保存先は Download フォルダ */
private fun enqueueDownload(
    context: Context,
    url: String,
    userAgent: String,
    disposition: String?,
    mime: String?,
): Result<String> = runCatching {
    val name = LanUrls.fileName(disposition, url)
    val request = DownloadManager.Request(url.toUri())
        .setTitle(name)
        .setMimeType(mime?.takeIf { it.isNotBlank() } ?: "application/octet-stream")
        .addRequestHeader("User-Agent", userAgent)
        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
    CookieManager.getInstance().getCookie(url)?.let { request.addRequestHeader("Cookie", it) }
    context.getSystemService(DownloadManager::class.java).enqueue(request)
    name
}
