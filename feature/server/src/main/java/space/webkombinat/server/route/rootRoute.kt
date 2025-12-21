package space.webkombinat.server.route

import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post


fun Route.rootRoute(uris: List<String>) {
    val path = uris.map { parse(it) }

    get("/") {
//        uris.forEach {
//            println(it)
//        }
        call.respondText("Hello, world!", ContentType.Text.Plain)
    }
    post("/") {
        println("${path}")
        call.respond(path)
    }
}

private fun parse(uri: String): String {
    val paths = uri.split("?")
    return paths[1]
}


