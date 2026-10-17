package space.webkombinat.anas.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import space.webkombinat.anas.core.model.StorageBackend
import space.webkombinat.anas.core.storage.SafStorageBackend
import space.webkombinat.anas.core.storage.VolumeStore
import space.webkombinat.anas.data.DataStoreSettingsRepository
import space.webkombinat.anas.data.SettingsRepository
import space.webkombinat.anas.server.ExtensionRegistry
import javax.inject.Qualifier
import javax.inject.Singleton

/** アプリが生きているあいだ動くコルーチンのスコープ */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AppScope

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SettingsStore

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class VolumesStore

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    @AppScope
    fun provideAppScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @Singleton
    @SettingsStore
    fun provideSettingsDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("settings") }

    @Provides
    @Singleton
    @VolumesStore
    fun provideVolumesDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("volumes") }

    @Provides
    @Singleton
    fun provideVolumeStore(
        @ApplicationContext context: Context,
        @VolumesStore dataStore: DataStore<Preferences>,
        @AppScope scope: CoroutineScope,
    ): VolumeStore = VolumeStore(context, dataStore, scope)

    @Provides
    @Singleton
    fun provideStorageBackend(@ApplicationContext context: Context, store: VolumeStore): StorageBackend =
        SafStorageBackend(context, store)

    @Provides
    @Singleton
    fun provideExtensionRegistry(backend: StorageBackend): ExtensionRegistry = ExtensionRegistry(backend)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class BindModule {
    @Binds
    abstract fun bindSettings(impl: DataStoreSettingsRepository): SettingsRepository
}
