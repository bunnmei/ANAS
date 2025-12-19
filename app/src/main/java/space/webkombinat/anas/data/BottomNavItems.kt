package space.webkombinat.anas.data

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.ui.graphics.vector.ImageVector

sealed class BottomNavItems(
    val route: String,
    val icon: ImageVector,
) {
    object Server: BottomNavItems(route = "server", icon = Icons.Default.Storage)
    object Setting: BottomNavItems(route = "settings", icon = Icons.Default.Settings)
}