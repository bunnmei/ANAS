package space.webkombinat.server.route

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import space.webkombinat.core.FileAccess
import space.webkombinat.core.data.ANAS_Folder
import space.webkombinat.core.data.ANAS_FolderOrFile

import space.webkombinat.server.data.RequestPath

fun Route.folderRoute(
    fileAccess: FileAccess,
    uris: List<String>
){
    post("/folder") {

        try {
            val body = call.receive<RequestPath>()
            println("Route.folderRoute() called with path = ${body.path}")
            val folder = fileAccess.readDirectory(disk = body.path, path = "/")
            println(folder)
            call.respond(folder)
        } catch (e: Exception) {
            e.printStackTrace()
            call.respondText(e.localizedMessage, status = HttpStatusCode.InternalServerError)
        }

    }
}