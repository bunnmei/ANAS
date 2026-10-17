package space.webkombinat.anas.server

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * パスワード認証のセッション。メモリにだけ持つので、サーバーを再起動するとログインし直しになる。
 * パスワードを変えると、それまでのセッションは無効になる。
 */
class Sessions {
    private val random = SecureRandom()
    /** token → 発行したときのパスワードのハッシュ */
    private val tokens = ConcurrentHashMap<String, String>()

    fun issue(password: String): String {
        if (tokens.size > MAX_SESSIONS) tokens.clear()
        val bytes = ByteArray(32).also(random::nextBytes)
        val token = bytes.joinToString("") { "%02x".format(it) }
        tokens[token] = hash(password)
        return token
    }

    fun isValid(token: String?, password: String): Boolean =
        token != null && tokens[token]?.let { MessageDigest.isEqual(it.toByteArray(), hash(password).toByteArray()) } == true

    fun revoke(token: String?) {
        token?.let(tokens::remove)
    }

    fun checkPassword(input: String, password: String): Boolean =
        MessageDigest.isEqual(hash(input).toByteArray(), hash(password).toByteArray())

    private fun hash(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        const val COOKIE = "anas_session"
        private const val MAX_SESSIONS = 256
    }
}
