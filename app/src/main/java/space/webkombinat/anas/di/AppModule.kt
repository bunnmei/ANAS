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
import space.webkombinat.anas.server.IndexingStorageBackend
import space.webkombinat.anas.server.SearchIndex
import space.webkombinat.anas.server.SearchService
import java.io.File
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

/** 検索の索引を更新しない、元のストレージ（索引の走査に使う） */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class RawStorage

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
    @RawStorage
    fun provideRawStorageBackend(@ApplicationContext context: Context, store: VolumeStore): StorageBackend =
        SafStorageBackend(context, store)

    @Provides
    @Singleton
    fun provideSearchIndex(@ApplicationContext context: Context): SearchIndex =
        SearchIndex(File(context.noBackupFilesDir, "search.db").path)

    @Provides
    @Singleton
    fun provideSearchService(@RawStorage raw: StorageBackend, index: SearchIndex): SearchService = SearchService(raw, index)

    /** アプリ全体で使うストレージ。書き込み・名前の変更・削除をその場で検索の索引にも反映する */
    @Provides
    @Singleton
    fun provideStorageBackend(@RawStorage raw: StorageBackend, index: SearchIndex): StorageBackend =
        IndexingStorageBackend(raw, index)

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
