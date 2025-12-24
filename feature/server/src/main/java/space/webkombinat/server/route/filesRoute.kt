package space.webkombinat.server.route

import io.ktor.server.application.call
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import space.webkombinat.core.FileAccess

fun Route.filesRoute(
    fileAccess: FileAccess,
){
    get("/files/{path...}") {
        val pathSegment = call.parameters.getAll("path")?.joinToString("/") ?: ""
        println("request path = $pathSegment")
        val file = fileAccess.fileRead(path = pathSegment)
    }
}