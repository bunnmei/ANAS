package space.webkombinat.anas.ui

import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import space.webkombinat.anas.R
import space.webkombinat.anas.data.AppMode
import space.webkombinat.anas.data.AppSettings
import space.webkombinat.anas.ui.browse.BrowseScreen
import space.webkombinat.anas.ui.extensions.ExtensionsScreen
import space.webkombinat.anas.ui.home.HomeScreen
import space.webkombinat.anas.ui.settings.SettingsScreen
import space.webkombinat.anas.ui.setup.ModeScreen

private enum class Tab(val route: String, val icon: ImageVector, val label: Int) {
    HOME("home", Icons.Default.Home, R.string.tab_home),
    EXTENSIONS("extensions", Icons.Default.Extension, R.string.tab_extensions),
    BROWSE("browse", Icons.Default.Language, R.string.tab_browse),
    SETTINGS("settings", Icons.Default.Settings, R.string.tab_settings),
}

/** 画面のどこからでもスナックバーを出せるようにする */
val LocalSnackbar = staticCompositionLocalOf<SnackbarHostState> { error("no snackbar") }

@Composable
fun AppRoot(settings: AppSettings, onChooseMode: (AppMode) -> Unit) {
    if (!settings.loaded) return
    val mode = settings.mode
    if (mode == null) {
        ModeScreen(onChoose = onChooseMode)
        return
    }
    // 使い方を変えたら、タブと最初の画面を作り直す
    key(mode) { MainScaffold(mode) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScaffold(mode: AppMode) {
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route
    val client = mode == AppMode.CLIENT
    // クライアントのみのときはタブを出さず、ブラウズを画面いっぱいに。設定はメニューから開く
    val tabs = if (client) emptyList() else Tab.entries
    CompositionLocalProvider(LocalSnackbar provides snackbar) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                if (client && current == Tab.SETTINGS.route) {
                    TopAppBar(
                        title = { Text(stringResource(R.string.tab_settings)) },
                        navigationIcon = {
                            IconButton(onClick = { nav.popBackStack() }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                            }
                        },
                    )
                }
            },
            bottomBar = {
                if (tabs.isNotEmpty()) {
                    NavigationBar {
                        tabs.forEach { tab ->
                            NavigationBarItem(
                                selected = current == tab.route,
                                onClick = {
                                    nav.navigate(tab.route) {
                                        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = { Icon(tab.icon, contentDescription = null) },
                                label = { Text(stringResource(tab.label)) },
                            )
                        }
                    }
                }
            },
        ) { padding ->
            NavHost(
                nav,
                startDestination = if (client) Tab.BROWSE.route else Tab.HOME.route,
                // 中の画面で imePadding() を使うとき、タブなどの分を二重に引かないように
                modifier = Modifier.padding(padding).consumeWindowInsets(padding),
            ) {
                composable(Tab.HOME.route) { HomeScreen() }
                composable(Tab.EXTENSIONS.route) { ExtensionsScreen() }
                composable(Tab.BROWSE.route) {
                    BrowseScreen(onOpenSettings = if (client) ({ nav.navigate(Tab.SETTINGS.route) }) else null)
                }
                composable(Tab.SETTINGS.route) { SettingsScreen() }
            }
        }
    }
}
