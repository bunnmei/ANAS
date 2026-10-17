package space.webkombinat.anas.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import space.webkombinat.anas.data.AppSettings
import space.webkombinat.anas.data.SettingsRepository
import space.webkombinat.anas.data.ThemeMode
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = repository.settings

    fun setTheme(theme: ThemeMode) = viewModelScope.launch { repository.update { it.copy(theme = theme) } }

    /** 不正な値なら保存せず false を返す */
    fun setPort(input: String): Boolean {
        val port = parsePort(input) ?: return false
        viewModelScope.launch { repository.update { it.copy(port = port) } }
        return true
    }

    fun setPassword(password: String) = viewModelScope.launch { repository.update { it.copy(password = password) } }

    fun setReadOnly(readOnly: Boolean) = viewModelScope.launch { repository.update { it.copy(readOnly = readOnly) } }

    companion object {
        /** 1024 未満は Android では root でないと開けない */
        fun parsePort(input: String): Int? = input.trim().toIntOrNull()?.takeIf { it in 1024..65535 }
    }
}
