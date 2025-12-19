package space.webkombinat.anas.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import space.webkombinat.anas.data.UserPreferencesRepository

class SettingVM(
    private val userPreferencesRepository: UserPreferencesRepository
): ViewModel(){
    val theme = userPreferencesRepository.isTheme

    fun setTheme(theme: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            userPreferencesRepository.saveTheme(theme)
        }
    }

}