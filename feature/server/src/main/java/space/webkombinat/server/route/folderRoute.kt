package space.webkombinat.server.route

import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import space.webkombinat.core.FileAccess

import space.webkombinat.server.data.RequestPath

fun Route.folderRoute(fileAccess: FileAccess){
    post("folder") {
        val path = call.receive<RequestPath>()
        println("Route.folderRoute() called with path = ${path.path}")
        val folder = fileAccess.readDirectory(path.path)
        call.respond(folder)
    }
}