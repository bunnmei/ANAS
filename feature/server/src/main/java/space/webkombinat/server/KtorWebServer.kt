package space.webkombinat.server

import io.ktor.http.HttpHeaders
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.routing.routing
import space.webkombinat.server.route.rootRoute


class KtorWebServer(
    private val port: Int = 8080,
) {
    private var server : NettyApplicationEngine? = null

    fun startServer() {
        if (server != null) return

        server = embeddedServer(Netty, port = port, host = "0.0.0.0") {
            install(CORS){
                anyHost()
                allowHeader(HttpHeaders.ContentType)
            }

            routing {
                rootRoute()
            }
        }.start(wait = false)

    }

    fun stopServer() {
        server?.stop(1000, 2000)
        server = null
    }

}