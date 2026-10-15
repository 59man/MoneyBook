package com.moneybook.ui.lock

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import android.content.Context
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneybook.data.DeviceSettings
import com.moneybook.domain.Passcode
import com.moneybook.testutil.FakeDeviceSettingsRepository
import com.moneybook.ui.theme.MoneyBookTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PasscodeScreenTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun type(code: String) = code.forEach { rule.onNodeWithText(it.toString()).performClick() }

    @Test fun settingAPasscodeAsksTwiceAndStoresItsHash() {
        val device = FakeDeviceSettingsRepository()
        var done = false
        rule.setContent { MoneyBookTheme(darkTheme = false) { PasscodeFlow(PasscodePurpose.SET, device, onDone = { done = true }, onBack = {}) } }
        rule.onNodeWithText("Set Passcode").assertIsDisplayed()
        rule.onNodeWithText("Enter your passcode").assertIsDisplayed()
        type("2580")
        rule.onNodeWithText("Confirm your passcode").assertIsDisplayed()
        type("2580")
        rule.waitUntil(5_000) { done }
        assertTrue(device.state.value.checks("2580"))
    }

    @Test fun theSettingsListOpensOnlyForTheCurrentPasscode() {
        val salt = Passcode.newSalt()
        val device = FakeDeviceSettingsRepository(DeviceSettings(passcodeHash = Passcode.hash("1357", salt), passcodeSalt = salt))
        var done = false
        rule.setContent { MoneyBookTheme(darkTheme = false) { PasscodeFlow(PasscodePurpose.UNLOCK_SETTINGS, device, onDone = { done = true }, onBack = {}) } }
        type("0000")
        rule.onNodeWithText("Wrong passcode").assertIsDisplayed()
        assertFalse(done)
        type("1357")
        rule.waitUntil(5_000) { done }
        assertTrue(device.state.value.checks("1357"))
    }

    @Test fun changingItChecksTheOldCodeThenReplacesTheHash() {
        val salt = Passcode.newSalt()
        val device = FakeDeviceSettingsRepository(DeviceSettings(passcodeHash = Passcode.hash("1357", salt), passcodeSalt = salt, wrongTries = 2))
        var done = false
        rule.setContent { MoneyBookTheme(darkTheme = false) { PasscodeFlow(PasscodePurpose.CHANGE, device, onDone = { done = true }, onBack = {}) } }
        type("1111")
        rule.onNodeWithText("Wrong passcode").assertIsDisplayed()
        assertTrue(device.state.value.checks("1357"))
        type("1357")
        rule.onNodeWithText("Set Passcode").assertIsDisplayed()
        assertFalse(done)
        type("9753")
        rule.onNodeWithText("Confirm your passcode").assertIsDisplayed()
        type("9753")
        rule.waitUntil(5_000) { done }
        assertTrue(device.state.value.checks("9753"))
        assertFalse(device.state.value.checks("1357"))
        assertEquals(0, device.state.value.wrongTries)
    }

    @Test fun theLockScreenCoversTheAppUntilTheRightCode() {
        val salt = Passcode.newSalt()
        val device = FakeDeviceSettingsRepository(DeviceSettings(passcodeHash = Passcode.hash("1357", salt), passcodeSalt = salt))
        var behind = false
        var unlocked = false
        rule.setContent {
            MoneyBookTheme(darkTheme = false) {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().clickable { behind = true })
                    LockScreen(device, onUnlock = { unlocked = true }, onLeave = {})
                }
            }
        }
        rule.onNodeWithText("Enter your passcode").performClick()
        assertFalse(behind)
        rule.onNodeWithContentDescription("Back").assertDoesNotExist()
        type("1358")
        assertFalse(unlocked)
        type("1357")
        rule.waitUntil(5_000) { unlocked }
        assertEquals(true, unlocked)
    }

    @Test fun fiveWrongCodesMakeTheLockScreenWaitThirtySeconds() {
        val salt = Passcode.newSalt()
        val device = FakeDeviceSettingsRepository(DeviceSettings(passcodeHash = Passcode.hash("1357", salt), passcodeSalt = salt))
        var now = 100_000L
        var unlocked = false
        rule.setContent { MoneyBookTheme(darkTheme = false) { LockScreen(device, onUnlock = { unlocked = true }, onLeave = {}, clock = { now }) } }
        repeat(5) { type("0000"); rule.waitForIdle() }
        rule.onNodeWithText("Too many wrong tries. Try again in 30 s.").assertIsDisplayed()
        type("1357")
        rule.waitForIdle()
        assertFalse(unlocked)
        now += 12_000
        rule.mainClock.advanceTimeBy(1_000)
        rule.onNodeWithText("Too many wrong tries. Try again in 18 s.").assertIsDisplayed()
        now += 18_000
        rule.mainClock.advanceTimeBy(1_000)
        rule.onNodeWithText("Too many wrong tries", substring = true).assertDoesNotExist()
        type("1357")
        rule.waitUntil(5_000) { unlocked }
        assertEquals(0, device.state.value.wrongTries)
    }

    @Test fun theWaitAlsoGuardsTheSettingsListAndOutlivesTheScreen() {
        val salt = Passcode.newSalt()
        val device = FakeDeviceSettingsRepository(
            DeviceSettings(passcodeHash = Passcode.hash("1357", salt), passcodeSalt = salt, wrongTries = 0, retryAt = 120_000L),
        )
        rule.setContent { MoneyBookTheme(darkTheme = false) { PasscodeFlow(PasscodePurpose.UNLOCK_SETTINGS, device, onDone = {}, onBack = {}, clock = { 100_000L }) } }
        rule.onNodeWithText("Too many wrong tries. Try again in 20 s.").assertIsDisplayed()
        type("1357")
        rule.waitForIdle()
        assertTrue(device.state.value.passcodeSet)
    }

    private class FakeGate(var can: Boolean = true) : BiometricGate() {
        var prompts = 0
        var succeed: (() -> Unit)? = null
        override fun canAuthenticate(context: Context) = can
        override fun prompt(context: Context, onSuccess: () -> Unit) {
            prompts++
            succeed = onSuccess
        }
    }

    private fun lockedDevice(biometric: Boolean = true, wrongTries: Int = 0, retryAt: Long? = null): FakeDeviceSettingsRepository {
        val salt = Passcode.newSalt()
        return FakeDeviceSettingsRepository(
            DeviceSettings(passcodeHash = Passcode.hash("1357", salt), passcodeSalt = salt, biometric = biometric, wrongTries = wrongTries, retryAt = retryAt),
        )
    }

    private fun showLock(gate: BiometricGate, device: FakeDeviceSettingsRepository, onUnlock: () -> Unit = {}) = rule.setContent {
        CompositionLocalProvider(LocalBiometricGate provides gate) {
            MoneyBookTheme(darkTheme = false) { LockScreen(device, onUnlock = onUnlock, onLeave = {}, clock = { 100_000L }) }
        }
    }

    @Test fun theLockScreenPromptsOnceAndAFingerprintUnlocksLikeTheRightCode() {
        val gate = FakeGate()
        val device = lockedDevice(wrongTries = 3)
        var unlocked = 0
        showLock(gate, device) { unlocked++ }
        rule.waitUntil(5_000) { gate.prompts > 0 }
        rule.waitForIdle()
        assertEquals(1, gate.prompts)
        rule.runOnIdle { gate.succeed!!() }
        rule.waitUntil(5_000) { unlocked > 0 }
        assertEquals(1, unlocked)
        assertEquals(0, device.state.value.wrongTries)
    }

    @Test fun cancellingThePromptLeavesThePadAndTouchesNoCounter() {
        val gate = FakeGate()
        val device = lockedDevice(wrongTries = 2)
        var unlocked = false
        showLock(gate, device) { unlocked = true }
        rule.waitUntil(5_000) { gate.prompts > 0 }
        rule.onNodeWithText("Enter your passcode").assertIsDisplayed()
        assertEquals(2, device.state.value.wrongTries)
        type("1357")
        rule.waitUntil(5_000) { unlocked }
    }

    private fun assertNoPrompt(device: FakeDeviceSettingsRepository, can: Boolean = true) {
        val gate = FakeGate(can)
        showLock(gate, device)
        rule.waitForIdle()
        rule.onNodeWithText("Enter your passcode").assertIsDisplayed()
        assertEquals(0, gate.prompts)
    }

    @Test fun noPromptWhileTheRetryWaitRuns() = assertNoPrompt(lockedDevice(retryAt = 120_000L))

    @Test fun noPromptWithBiometricOff() = assertNoPrompt(lockedDevice(biometric = false))

    @Test fun noPromptWhenThePhoneCannotAuthenticate() = assertNoPrompt(lockedDevice(), can = false)

    @Test fun thePasscodeListOpensOnAFingerprintButChangingAndSettingNeverPrompt() {
        val gate = FakeGate()
        var done = false
        rule.setContent {
            CompositionLocalProvider(LocalBiometricGate provides gate) {
                MoneyBookTheme(darkTheme = false) { PasscodeFlow(PasscodePurpose.UNLOCK_SETTINGS, lockedDevice(), onDone = { done = true }, onBack = {}) }
            }
        }
        rule.waitUntil(5_000) { gate.prompts > 0 }
        rule.runOnIdle { gate.succeed!!() }
        rule.waitUntil(5_000) { done }
    }

    @Test fun theSettingsPadHiddenBehindTheLockNeverPrompts() {
        val gate = FakeGate()
        rule.setContent {
            CompositionLocalProvider(LocalBiometricGate provides gate, com.moneybook.ui.common.LocalAppLocked provides true) {
                MoneyBookTheme(darkTheme = false) { PasscodeFlow(PasscodePurpose.UNLOCK_SETTINGS, lockedDevice(), onDone = {}, onBack = {}) }
            }
        }
        rule.waitForIdle()
        val scenario = rule.activityRule.scenario
        scenario.moveToState(androidx.lifecycle.Lifecycle.State.STARTED)
        scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        rule.waitForIdle()
        assertEquals(0, gate.prompts)
    }

    @Test fun changingAndSettingAPasscodeNeverPrompt() {
        val gate = FakeGate()
        val purpose = androidx.compose.runtime.mutableStateOf(PasscodePurpose.CHANGE)
        rule.setContent {
            CompositionLocalProvider(LocalBiometricGate provides gate) {
                MoneyBookTheme(darkTheme = false) { PasscodeFlow(purpose.value, lockedDevice(), onDone = {}, onBack = {}) }
            }
        }
        rule.waitForIdle()
        purpose.value = PasscodePurpose.SET
        rule.waitForIdle()
        assertEquals(0, gate.prompts)
    }

    @Test fun itPromptsAgainEachTimeTheActivityResumesWithoutStacking() {
        val gate = FakeGate()
        showLock(gate, lockedDevice())
        rule.waitUntil(5_000) { gate.prompts == 1 }
        val scenario = rule.activityRule.scenario
        scenario.moveToState(androidx.lifecycle.Lifecycle.State.STARTED)
        scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        rule.waitUntil(5_000) { gate.prompts == 2 }
        rule.waitForIdle()
        assertEquals(2, gate.prompts)
    }
}
