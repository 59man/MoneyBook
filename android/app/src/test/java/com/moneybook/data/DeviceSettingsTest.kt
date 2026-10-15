package com.moneybook.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneybook.domain.Passcode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceSettingsTest {
    @get:Rule val folder = TemporaryFolder()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @After fun tearDown() = scope.cancel()

    @Test fun everyDeviceSettingIsStoredAndTheDefaultsAreOff() = runBlocking {
        val file = folder.newFile("device.preferences_pb").also { it.delete() }
        val repo = DataStoreDeviceSettingsRepository(PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }))
        assertEquals(DeviceSettings(), repo.current())
        assertFalse(repo.current().passcodeSet)
        val salt = Passcode.newSalt()
        repo.update { it.copy(passcodeHash = Passcode.hash("2468", salt), passcodeSalt = salt, alarmOn = true, alarmMinutes = 8 * 60 + 15, quickAdd = true) }
        val s = repo.current()
        assertTrue(s.passcodeSet)
        assertTrue(s.checks("2468"))
        assertFalse(s.checks("1111"))
        assertEquals(true, s.alarmOn)
        assertEquals(8 * 60 + 15, s.alarmMinutes)
        assertEquals(true, s.quickAdd)
    }

    @Test fun biometricsDefaultsOnAndRequestPasscodeToImmediately() = runBlocking {
        val file = folder.newFile("device4.preferences_pb").also { it.delete() }
        val repo = DataStoreDeviceSettingsRepository(PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }))
        assertTrue(repo.current().biometric)
        assertEquals(0, repo.current().lockDelaySeconds)
        repo.update { it.copy(biometric = false, lockDelaySeconds = 900) }
        assertFalse(repo.current().biometric)
        assertEquals(900, repo.current().lockDelaySeconds)
    }

    @Test fun theWrongTriesAndTheWaitAreStored() = runBlocking {
        val file = folder.newFile("device2.preferences_pb").also { it.delete() }
        val repo = DataStoreDeviceSettingsRepository(PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }))
        assertEquals(0, repo.current().wrongTries)
        assertEquals(null, repo.current().retryAt)
        repo.update { it.copy(wrongTries = 3, retryAt = 45_000L) }
        assertEquals(3, repo.current().wrongTries)
        assertEquals(45_000L, repo.current().retryAt)
        repo.update { it.copy(retryAt = null) }
        assertEquals(null, repo.current().retryAt)
    }

    @Test fun theWidgetLookIsStoredAndDefaultsToSystemAndOpaque() = runBlocking {
        val file = folder.newFile("device3.preferences_pb").also { it.delete() }
        val repo = DataStoreDeviceSettingsRepository(PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }))
        assertEquals(StyleMode.SYSTEM, repo.current().widgetBackground)
        assertEquals(100, repo.current().widgetOpacity)
        repo.update { it.copy(widgetBackground = StyleMode.DARK, widgetOpacity = 35) }
        assertEquals(StyleMode.DARK, repo.current().widgetBackground)
        assertEquals(35, repo.current().widgetOpacity)
    }

    @Test fun syncFieldsRoundTrip() = runBlocking {
        val file = folder.newFile("device5.preferences_pb").also { it.delete() }
        val repo = DataStoreDeviceSettingsRepository(PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }))
        assertFalse(repo.current().signedIn)
        assertEquals(0L, repo.current().syncCursor)
        repo.update {
            it.copy(
                serverUrl = "https://box.example.ts.net", refreshToken = "r1", syncCursor = 42,
                syncEpoch = "e1", lastSyncAt = 1_700_000_000_000, lastSyncError = "offline",
            )
        }
        val s = repo.current()
        assertEquals("https://box.example.ts.net", s.serverUrl)
        assertEquals("r1", s.refreshToken)
        assertEquals(42L, s.syncCursor)
        assertEquals("e1", s.syncEpoch)
        assertEquals(1_700_000_000_000, s.lastSyncAt)
        assertEquals("offline", s.lastSyncError)
        assertTrue(s.signedIn)
        repo.update { it.copy(refreshToken = "") }
        assertFalse(repo.current().signedIn)
    }

    @Test fun deviceIdIsCreatedOnceAndStable() = runBlocking {
        val file = folder.newFile("device6.preferences_pb").also { it.delete() }
        val repo = DataStoreDeviceSettingsRepository(PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }))
        assertEquals("", repo.current().deviceId)
        val id = repo.deviceId()
        assertEquals(36, id.length)
        assertEquals(id, repo.deviceId())
        repo.update { it.copy(serverUrl = "x") } // other edits keep it
        assertEquals(id, repo.current().deviceId)
        // concurrent first calls agree
        val file2 = folder.newFile("device7.preferences_pb").also { it.delete() }
        val repo2 = DataStoreDeviceSettingsRepository(PreferenceDataStoreFactory.create(scope = scope, produceFile = { file2 }))
        val ids = kotlinx.coroutines.coroutineScope { (1..8).map { async(Dispatchers.IO) { repo2.deviceId() } }.map { it.await() } }
        assertEquals(1, ids.toSet().size)
    }

    @Test fun syncFieldsNeverReachBackups() {
        val names = com.moneybook.shared.dto.SettingsDto.serializer().descriptor.let { d -> (0 until d.elementsCount).map { d.getElementName(it).lowercase() } }
        val syncOnly = listOf("deviceid", "serverurl", "refreshtoken", "synccursor", "syncepoch", "lastsyncat", "lastsyncerror")
        assertEquals(emptyList<String>(), names.filter { it in syncOnly })
    }
}
