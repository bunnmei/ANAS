package space.webkombinat.anas

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import org.koin.android.ext.android.inject
import space.webkombinat.anas.data.UITheme
import space.webkombinat.anas.data.UserPreferencesRepository
import space.webkombinat.anas.ui.theme.ANASTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val userPref: UserPreferencesRepository by inject()

        enableEdgeToEdge()
        setContent {
            val theme by userPref.isTheme.collectAsState(UITheme.SYSTEM.theme)
            ANASTheme(darkTheme = theme == UITheme.DARK.theme) {
                AppRoot()
            }
        }
    }
}
