package space.webkombinat.server.data

import kotlinx.serialization.Serializable

@Serializable
data class RequestPath(
    val disk: String,
    val path: String
)

@Serializable
data class Disks(val storages: List<String>)




