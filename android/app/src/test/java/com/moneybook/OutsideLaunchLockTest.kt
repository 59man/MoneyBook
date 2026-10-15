package com.moneybook

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneybook.domain.Passcode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Request Passcode = Immediately (the default): only the launches that announce themselves to the lock may come back unlocked. */
@RunWith(AndroidJUnit4::class)
class OutsideLaunchLockTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<MoneyBookApp>()
    private val lock get() = app.container.lock

    @Before fun setPasscodeOnceOpen() {
        rule.waitUntil(10_000) { lock.state.value == LockState.OPEN }
        val salt = Passcode.newSalt()
        runBlocking { app.container.device.update { it.copy(passcodeHash = Passcode.hash("4826", salt), passcodeSalt = salt, lockDelaySeconds = 0) } }
        rule.waitForIdle()
    }

    /** The DataStore outlives this test's Application (one per process), so later tests would start locked. */
    @After fun removePasscode() = runBlocking { app.container.device.update { it.copy(passcodeHash = null, passcodeSalt = null) } }

    private fun awayAndBack() {
        lock.onStop(now = 0)
        runBlocking { lock.onStart(now = 60_000) } // a minute on the picker, inside LockPolicy.OUTSIDE_MAX_MS
    }

    private fun openBackup() {
        rule.onNodeWithText("More").performClick()
        rule.onNodeWithText("Backup").performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithText("Import Excel File").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun aPlainTripAwayLocks() {
        awayAndBack()
        assertEquals(LockState.LOCKED, lock.state.value)
    }

    @Test fun anOpenDocumentPickerComesBackUnlocked() {
        openBackup()
        rule.onNodeWithText("Import Excel File").performClick() // OpenDocument; the same wrapper serves restore and the old app's import
        awayAndBack()
        assertEquals(LockState.OPEN, lock.state.value)
    }

    @Test fun aCreateDocumentPickerComesBackUnlocked() {
        openBackup()
        rule.onNodeWithText("Export data to Excel").performClick() // CreateDocument; the same wrapper serves the backup file and Trans → Total's button
        awayAndBack()
        assertEquals(LockState.OPEN, lock.state.value)
    }

    @Test fun theNotificationPermissionDialogComesBackUnlocked() {
        rule.onNodeWithText("More").performClick()
        rule.onNodeWithText("Configuration").performClick()
        rule.onNodeWithText("Alarm Setting").performScrollTo().performClick()
        rule.onNodeWithContentDescription("Alarm").performClick() // asks for POST_NOTIFICATIONS (SDK 35 here)
        awayAndBack()
        assertEquals(LockState.OPEN, lock.state.value)
    }
}
