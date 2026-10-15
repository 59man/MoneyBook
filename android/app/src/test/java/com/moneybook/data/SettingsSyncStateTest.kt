package com.moneybook.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsSyncStateTest {
    @get:Rule val folder = TemporaryFolder()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var now = 1_000L

    @After fun tearDown() = scope.cancel()

    private fun repo(): DataStoreSettingsRepository {
        val file = folder.newFile("s${System.nanoTime()}.preferences_pb").also { it.delete() }
        return DataStoreSettingsRepository(PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }), "CZK", clock = { now })
    }

    private suspend fun DataStoreSettingsRepository.sync() = syncState.first()

    @Test fun replaceStampsAndMarksDirty() = runBlocking {
        val r = repo()
        assertEquals(SettingsSync(0, false), r.sync())
        r.replace(r.current().copy(monthStartDay = 5))
        assertEquals(SettingsSync(1_000, true), r.sync())
        assertEquals(5, r.current().monthStartDay)
    }

    @Test fun replaceWithOnlyStyleChangedIsNotSynced() = runBlocking {
        val r = repo()
        r.replace(r.current().copy(style = StyleMode.DARK))
        assertEquals(StyleMode.DARK, r.current().style)
        assertEquals(SettingsSync(0, false), r.sync())
        r.replace(r.current().copy(style = StyleMode.LIGHT, subcategory = true))
        assertEquals(SettingsSync(1_000, true), r.sync())
    }

    @Test fun replaceWithEqualValuesIsNotSynced() = runBlocking {
        val r = repo()
        r.replace(r.current())
        assertEquals(SettingsSync(0, false), r.sync())
    }

    @Test fun stampIsMonotonicWhenTheClockStepsBack() = runBlocking {
        val r = repo()
        r.replace(r.current().copy(monthStartDay = 5))
        now = 10
        r.replace(r.current().copy(monthStartDay = 6))
        assertEquals(1_001L, r.sync().updatedAt)
        r.setMainCurrency("eur")
        assertEquals(1_002L, r.sync().updatedAt)
    }

    @Test fun setMainCurrencyStamps() = runBlocking {
        val r = repo()
        r.setMainCurrency("eur")
        assertEquals("EUR", r.current().mainCurrency)
        assertEquals(SettingsSync(1_000, true), r.sync())
    }

    @Test fun applyRemoteKeepsStyleAndIsClean() = runBlocking {
        val r = repo()
        r.replace(r.current().copy(style = StyleMode.DARK, monthStartDay = 3))
        r.applyRemote(AppSettings("EUR", monthStartDay = 9, style = StyleMode.LIGHT), updatedAt = 5_000)
        val s = r.current()
        assertEquals(StyleMode.DARK, s.style)
        assertEquals("EUR", s.mainCurrency)
        assertEquals(9, s.monthStartDay)
        assertEquals(SettingsSync(5_000, false), r.sync())
    }

    @Test fun clearDirtyChecksTheStamp() = runBlocking {
        val r = repo()
        r.replace(r.current().copy(monthStartDay = 5))
        r.clearDirty(999)
        assertEquals(SettingsSync(1_000, true), r.sync())
        r.clearDirty(1_000)
        assertEquals(SettingsSync(1_000, false), r.sync())
    }

    @Test fun markDirtyOnlyWhenStamped() = runBlocking {
        val r = repo()
        r.markDirty()
        assertEquals(SettingsSync(0, false), r.sync())
        r.applyRemote(AppSettings("CZK"), updatedAt = 7)
        r.markDirty()
        assertEquals(SettingsSync(7, true), r.sync())
    }

    @Test fun resetForReplaceZeroesTheStamp() = runBlocking {
        val r = repo()
        r.replace(r.current().copy(monthStartDay = 5, style = StyleMode.DARK))
        r.resetForReplace("EUR")
        assertEquals(AppSettings("EUR", style = StyleMode.DARK), r.current())
        assertEquals(SettingsSync(0, false), r.sync())
    }
}
