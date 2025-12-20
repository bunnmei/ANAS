package space.webkombinat.anas.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import space.webkombinat.anas.data.DirectoryManager
import space.webkombinat.anas.data.UserPreferencesRepository
import space.webkombinat.anas.presentation.ServerVM
import space.webkombinat.anas.presentation.SettingVM

val appModule = module {

    single<DataStore<Preferences>> {
        PreferenceDataStoreFactory.create(
            produceFile = {
                androidContext().preferencesDataStoreFile("settings")
            }
        )
    }
    single {
        UserPreferencesRepository(get())
    }
    single {
        DirectoryManager()
    }

    viewModel { ServerVM(get()) }
    viewModel { SettingVM(get()) }
}