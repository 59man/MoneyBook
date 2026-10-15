package com.moneybook.ui.more

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneybook.data.DeviceSettings
import com.moneybook.ui.theme.MoneyBookTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PasscodeSettingsTest {
    @get:Rule val rule = createComposeRule()

    private val device = mutableStateOf(DeviceSettings())
    private var turnedOff = 0
    private var changed = 0
    private var biometric: Boolean? = null
    private var delay: Int? = null

    private fun show(canAuthenticate: Boolean) = rule.setContent {
        MoneyBookTheme(darkTheme = false) {
            PasscodeSettingsContent(
                device.value, canAuthenticate, onBack = {}, onTurnOff = { turnedOff++ }, onChange = { changed++ },
                onBiometric = { biometric = it }, onDelay = { delay = it; device.value = device.value.copy(lockDelaySeconds = it) },
            )
        }
    }

    @Test fun theListHasRealbytesRowsAndTheirActions() {
        show(canAuthenticate = false)
        rule.onNodeWithText("Passcode").assertIsDisplayed()
        rule.onAllNodesWithText("Request Passcode").assertCountEquals(2) // the section header and its row
        rule.onNodeWithText("Turn off Passcode").performClick()
        rule.onNodeWithText("Change Passcode").performClick()
        assertEquals(1, turnedOff)
        assertEquals(1, changed)
        rule.onNodeWithText("Immediately").assertIsDisplayed()
    }

    @Test fun biometricsIsHiddenWhenThePhoneCannotAuthenticate() {
        show(canAuthenticate = false)
        rule.onNodeWithContentDescription("Biometrics").assertDoesNotExist()
        rule.onNodeWithContentDescription("Fingerprint").assertDoesNotExist()
    }

    @Test fun biometricsShowsOnAndWritesItsSwitch() {
        show(canAuthenticate = true)
        rule.onNodeWithContentDescription("Biometrics").assertIsDisplayed().performClick()
        assertEquals(false, biometric) // default on, so a tap turns it off
    }

    @Test fun requestPasscodeWritesTheChosenSeconds() {
        show(canAuthenticate = false)
        rule.onNodeWithText("Immediately").performClick()
        rule.onNodeWithText("After 1 minute").assertIsDisplayed()
        rule.onNodeWithText("After 1 hour").assertIsDisplayed()
        rule.onNodeWithText("After 5 minutes").performClick()
        assertEquals(300, delay)
        rule.onNodeWithText("After 5 minutes").assertIsDisplayed() // now the row's value
    }
}
