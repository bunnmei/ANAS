package space.webkombinat.anas.server

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/** 同じ LAN（プライベート IP）からの接続だけを通すための判定 */
object NetworkGuard {
    private val IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

    fun isPrivate(remote: String): Boolean {
        val host = remote.removePrefix("[").removeSuffix("]").substringBefore('%')
        if (host == "localhost") return true
        // DNS を引かないよう、IP アドレスの文字列のときだけ InetAddress にする
        if (!IPV4.matches(host) && !host.contains(':')) return false
        val addr = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return false
        return when (addr) {
            is Inet4Address -> addr.isLoopbackAddress || addr.isSiteLocalAddress || addr.isLinkLocalAddress
            is Inet6Address -> {
                val b = addr.address
                addr.isLoopbackAddress || addr.isLinkLocalAddress || addr.isSiteLocalAddress ||
                    (b[0].toInt() and 0xfe) == 0xfc || // fc00::/7 (ULA)
                    isMappedPrivateV4(b)
            }
            else -> false
        }
    }

    private fun isMappedPrivateV4(b: ByteArray): Boolean {
        val mapped = (0 until 10).all { b[it] == 0.toByte() } && b[10] == 0xff.toByte() && b[11] == 0xff.toByte()
        if (!mapped) return false
        val v4 = InetAddress.getByAddress(b.copyOfRange(12, 16))
        return v4.isLoopbackAddress || v4.isSiteLocalAddress || v4.isLinkLocalAddress
    }
}
