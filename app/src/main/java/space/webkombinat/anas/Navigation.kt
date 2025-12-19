package space.webkombinat.anas

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import org.koin.androidx.compose.koinViewModel
import space.webkombinat.anas.data.BottomNavItems
import space.webkombinat.anas.presentation.ServerScreen
import space.webkombinat.anas.presentation.ServerVM
import space.webkombinat.anas.presentation.SettingScreen
import space.webkombinat.anas.presentation.SettingVM

@Composable
fun Navigation(
    modifier: Modifier = Modifier,
    navController: NavHostController
) {

    NavHost(
        navController = navController,
        startDestination = BottomNavItems.Server.route,
        modifier = modifier
    ) {
        composable(
            route = BottomNavItems.Server.route
        ) {
            val vm: ServerVM = koinViewModel()
            ServerScreen(vm = vm)
        }
        composable(
            route = BottomNavItems.Setting.route
        ) {
            val vm: SettingVM = koinViewModel()
            SettingScreen(vm = vm)
        }

    }
}