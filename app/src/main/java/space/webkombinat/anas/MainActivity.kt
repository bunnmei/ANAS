package space.webkombinat.anas

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import space.webkombinat.anas.data.SettingsRepository
import space.webkombinat.anas.ui.AppRoot
import space.webkombinat.anas.ui.theme.AnasTheme
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var settings: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val current by settings.settings.collectAsStateWithLifecycle()
            AnasTheme(mode = current.theme) {
                AppRoot()
            }
        }
    }
}
