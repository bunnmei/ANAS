package space.webkombinat.anas

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import space.webkombinat.anas.presentation.components.BottomNavBar

@Composable
fun AppRoot(modifier: Modifier = Modifier) {
    val navController = rememberNavController()
    Scaffold(
        bottomBar = {
            BottomNavBar(onItemClick = {
                navController.navigate(it.route)
            })
        },
    ) { paddingValues ->
        Navigation(
            navController = navController,
            modifier = modifier.padding(paddingValues)
        )
    }
}