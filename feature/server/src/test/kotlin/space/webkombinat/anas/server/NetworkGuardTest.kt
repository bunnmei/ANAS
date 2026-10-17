package space.webkombinat.anas.server

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkGuardTest {
    @Test
    fun allowsLanAddresses() {
        for (ip in listOf("192.168.1.10", "10.0.0.1", "172.16.5.4", "127.0.0.1", "localhost", "169.254.1.1",
            "::1", "fe80::1%wlan0", "fd12:3456::1", "::ffff:192.168.1.2")) {
            assertTrue(ip, NetworkGuard.isPrivate(ip))
        }
    }

    @Test
    fun rejectsPublicAddresses() {
        for (ip in listOf("8.8.8.8", "172.32.0.1", "2001:4860::8888", "::ffff:8.8.8.8", "example.com", "")) {
            assertFalse(ip, NetworkGuard.isPrivate(ip))
        }
    }
}
