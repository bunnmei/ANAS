package space.webkombinat.server

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.routing.routing
import space.webkombinat.core.FileAccess
import space.webkombinat.server.route.folderRoute
import space.webkombinat.server.route.rootRoute
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json


class KtorWebServer(
    private val port: Int = 8080,
    private val uris: List<String>,
    private val fileAccess: FileAccess
) {
    private var server : NettyApplicationEngine? = null

    @OptIn(ExperimentalSerializationApi::class)
    fun startServer() {
        if (server != null) return

        server = embeddedServer(Netty, port = port, host = "0.0.0.0") {
            install(CORS){
                anyHost()
                allowHeader(HttpHeaders.ContentType)
                allowMethod(HttpMethod.Post)
            }
            install(ContentNegotiation) {
                json(Json {
                    prettyPrint = true
                    isLenient = true
                    ignoreUnknownKeys = true
                    classDiscriminator = "kind"
//                    explicitNulls = false
                })
            }

            routing {
                rootRoute(uris = uris)
                folderRoute(fileAccess = fileAccess, uris = uris)
            }

        }.start(wait = false)

    }

    fun stopServer() {
        server?.stop(1000, 2000)
        server = null
    }

}