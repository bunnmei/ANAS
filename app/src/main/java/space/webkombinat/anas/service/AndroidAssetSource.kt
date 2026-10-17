package space.webkombinat.anas.service

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import space.webkombinat.anas.server.AssetSource
import java.io.FileNotFoundException
import java.io.InputStream
import javax.inject.Inject

/** APK の assets/web（web/ を npm でビルドしたもの）を配信する */
class AndroidAssetSource @Inject constructor(
    @ApplicationContext private val context: Context,
) : AssetSource {
    override fun open(path: String): InputStream? = try {
        context.assets.open("web/$path")
    } catch (_: FileNotFoundException) {
        null
    }
}
