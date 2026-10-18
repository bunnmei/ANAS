package space.webkombinat.anas.client

import android.content.Context
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

/**
 * Google Play 開発者サービスのコードスキャナーで QR を読む。
 * 読み取り画面は Play 開発者サービスが出すので、このアプリにカメラの権限は要らない。
 */
object QrScanner {

    sealed interface Result {
        data class Scanned(val text: String) : Result
        data object Canceled : Result
        /** Play 開発者サービスがない・スキャナーを準備中など。[message] はそのまま表示する */
        data class Unavailable(val message: String) : Result
    }

    fun scan(context: Context, onResult: (Result) -> Unit) {
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
        GmsBarcodeScanning.getClient(context, options).startScan()
            .addOnSuccessListener { onResult(Result.Scanned(it.rawValue.orEmpty())) }
            .addOnCanceledListener { onResult(Result.Canceled) }
            .addOnFailureListener { e ->
                val code = (e as? MlKitException)?.errorCode
                onResult(
                    when (code) {
                        MlKitException.CODE_SCANNER_CANCELLED -> Result.Canceled
                        // 初めて使うときは、Play 開発者サービスが読み取り機能をダウンロードする
                        MlKitException.UNAVAILABLE -> Result.Unavailable("QR の読み取り機能を準備しています。少し待ってからもう一度押してください")
                        else -> Result.Unavailable("QR を読めません（${e.message ?: e.javaClass.simpleName}）。アドレスを入力してください")
                    },
                )
            }
    }
}
