package space.webkombinat.server.route

import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get


fun Route.rootRoute() {
    get("/") {
        call.respondText("Hello, world!", ContentType.Text.Plain)
    }
}


