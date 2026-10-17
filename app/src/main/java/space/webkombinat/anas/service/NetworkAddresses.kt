package space.webkombinat.anas.service

import java.net.Inet4Address
import java.net.NetworkInterface

/** PC から接続できる端末のアドレス */
data class LocalAddress(val label: String, val ip: String)

object NetworkAddresses {
    /**
     * Wi-Fi・USB テザリング・有線 LAN のプライベート IPv4 アドレス。
     * モバイルデータ (rmnet など) は PC から届かないので除外する。
     */
    fun list(): List<LocalAddress> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { nif ->
                val label = classify(nif.name) ?: return@flatMap emptyList()
                nif.inetAddresses.toList()
                    .filterIsInstance<Inet4Address>()
                    .filter { it.isSiteLocalAddress }
                    .mapNotNull { addr -> addr.hostAddress?.let { LocalAddress(label, it) } }
            }
            .distinct()
    }.getOrDefault(emptyList())

    private fun classify(name: String): String? = when {
        name.startsWith("wlan") || name.startsWith("swlan") || name.startsWith("ap") -> "Wi-Fi"
        name.startsWith("rndis") || name.startsWith("ncm") || name.startsWith("usb") -> "USB"
        name.startsWith("eth") -> "LAN"
        else -> null
    }
}
