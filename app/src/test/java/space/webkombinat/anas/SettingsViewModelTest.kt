package space.webkombinat.anas

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import space.webkombinat.anas.data.AppSettings
import space.webkombinat.anas.data.SettingsRepository
import space.webkombinat.anas.data.ThemeMode
import space.webkombinat.anas.ui.settings.SettingsViewModel

private class FakeSettings : SettingsRepository {
    val state = MutableStateFlow(AppSettings())
    override val settings: StateFlow<AppSettings> = state
    override suspend fun current() = state.value
    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        state.value = transform(state.value)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val repo = FakeSettings()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun updatesThemePasswordAndReadOnly() = runTest {
        val vm = SettingsViewModel(repo)
        vm.setTheme(ThemeMode.DARK)
        vm.setPassword("secret")
        vm.setReadOnly(true)
        assertEquals(ThemeMode.DARK, vm.settings.value.theme)
        assertEquals("secret", vm.settings.value.password)
        assertTrue(vm.settings.value.readOnly)
    }

    @Test
    fun rejectsInvalidPorts() = runTest {
        val vm = SettingsViewModel(repo)
        assertFalse(vm.setPort("80"))
        assertFalse(vm.setPort("70000"))
        assertFalse(vm.setPort("abc"))
        assertEquals(8080, repo.state.value.port)
        assertTrue(vm.setPort(" 8888 "))
        assertEquals(8888, repo.state.value.port)
        assertNull(SettingsViewModel.parsePort(""))
    }

    @Test
    fun settingsMapToServerConfig() {
        val config = AppSettings(password = "p", readOnly = true, disabledExtensions = setOf("a"))
            .toServerConfig("1.0")
        assertEquals("p", config.password)
        assertTrue(config.readOnly)
        assertEquals(setOf("a"), config.disabledExtensions)
        assertEquals("1.0", config.appVersion)
    }
}
