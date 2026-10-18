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

    /**
     * インターネット上のアドレスか。拡張機能の外部アクセス（[OutboundHttp]）で、
     * LAN の機器（ルーターの管理画面など）や ANAS 自身に向かわせないために使う
     */
    fun isPublicAddress(addr: InetAddress): Boolean {
        if (addr.isAnyLocalAddress || addr.isLoopbackAddress || addr.isLinkLocalAddress ||
            addr.isSiteLocalAddress || addr.isMulticastAddress
        ) return false
        val b = addr.address
        return when (addr) {
            is Inet4Address -> !isReservedV4(b)
            is Inet6Address -> {
                if ((b[0].toInt() and 0xfe) == 0xfc) return false // fc00::/7 (ULA)
                val mapped = (0 until 10).all { b[it] == 0.toByte() } && b[10] == 0xff.toByte() && b[11] == 0xff.toByte()
                if (mapped) return isPublicAddress(InetAddress.getByAddress(b.copyOfRange(12, 16)))
                // 6to4 (2002::/16) と NAT64 (64:ff9b::/96) は中の IPv4 で判定する
                if (b[0] == 0x20.toByte() && b[1] == 0x02.toByte()) return isPublicAddress(InetAddress.getByAddress(b.copyOfRange(2, 6)))
                val nat64 = b[0] == 0.toByte() && b[1] == 0x64.toByte() && b[2] == 0xff.toByte() && b[3] == 0x9b.toByte() &&
                    (4 until 12).all { b[it] == 0.toByte() }
                if (nat64) return isPublicAddress(InetAddress.getByAddress(b.copyOfRange(12, 16)))
                true
            }
            else -> false
        }
    }

    /** 0/8, 100.64/10 (CGNAT), 192.0.0/24, 198.18/15, 240/4 など、上の判定で拾えない特殊用途の IPv4 */
    private fun isReservedV4(b: ByteArray): Boolean {
        val a0 = b[0].toInt() and 0xff
        val a1 = b[1].toInt() and 0xff
        val a2 = b[2].toInt() and 0xff
        return a0 == 0 || a0 >= 240 ||
            (a0 == 100 && a1 in 64..127) ||
            (a0 == 192 && a1 == 0 && a2 == 0) ||
            (a0 == 198 && (a1 == 18 || a1 == 19))
    }

    private fun isMappedPrivateV4(b: ByteArray): Boolean {
        val mapped = (0 until 10).all { b[it] == 0.toByte() } && b[10] == 0xff.toByte() && b[11] == 0xff.toByte()
        if (!mapped) return false
        val v4 = InetAddress.getByAddress(b.copyOfRange(12, 16))
        return v4.isLoopbackAddress || v4.isSiteLocalAddress || v4.isLinkLocalAddress
    }
}
