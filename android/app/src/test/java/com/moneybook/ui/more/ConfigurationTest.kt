package com.moneybook.ui.more

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneybook.data.AppSettings
import com.moneybook.data.ColorSet
import com.moneybook.data.DeviceSettings
import com.moneybook.data.InputOrder
import com.moneybook.data.StartScreen
import com.moneybook.data.SwipeMode
import com.moneybook.data.TimeInput
import com.moneybook.data.TimeSource
import com.moneybook.domain.Passcode
import com.moneybook.testutil.FakeSettingsRepository
import com.moneybook.testutil.MainDispatcherRule
import com.moneybook.ui.theme.MoneyBookTheme
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConfigurationTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val rule = createComposeRule()
    private val time = TimeSource(Clock.fixed(Instant.parse("2026-09-26T10:00:00Z"), ZoneOffset.UTC))

    @Test fun theViewModelChangesAndResets() = runTest {
        val settings = FakeSettingsRepository()
        val vm = ConfigurationViewModel(settings, time)
        vm.change(AppSettings("CZK", autocomplete = false, swipe = SwipeMode.NONE))
        assertEquals(SwipeMode.NONE, settings.state.first { !it.autocomplete }.swipe)
        vm.resetAutocomplete()
        assertEquals(time.millis(), settings.state.first { it.autocompleteSince > 0 }.autocompleteSince)
    }

    private fun show(s: AppSettings = AppSettings("CZK"), actions: ConfigActions = ConfigActions()) = rule.setContent {
        MoneyBookTheme(darkTheme = false) { ConfigurationContent(s, actions) }
    }

    @Test fun rowsShowTheCurrentValues() {
        show(AppSettings("CZK", monthStartDay = 25, timeInput = TimeInput.SHOW_ASC))
        listOf("Start Screen (Daily/Calendar)", "Daily", "Monthly Start Date", "Every 25", "Weekly Start Day", "Sunday", "Swipe",
            "To Change Date", "Income-Expenses Color Setting", "Set. A", "Time Input", "Show on list, Asc.", "Show description",
            "Input order", "From Amount", "Note button setting", "Style")
            .forEach { rule.onNodeWithText(it).performScrollTo().assertIsDisplayed() }
    }

    @Test fun budgetSettingRowOpensBudgetSetting() {
        var opened = false
        show(actions = ConfigActions(onOpenBudgetSetting = { opened = true }))
        rule.onNodeWithText("Budget Setting").performScrollTo().performClick()
        assertEquals(true, opened)
    }

    @Test fun eachOptionDialogChangesItsSetting() {
        var changed: AppSettings? = null
        show(actions = ConfigActions(onChange = { changed = it }))
        fun pick(row: String, option: String) {
            rule.onNodeWithText(row).performScrollTo().performClick()
            rule.onNodeWithText(option).performClick()
        }
        pick("Start Screen (Daily/Calendar)", "Calendar")
        assertEquals(StartScreen.CALENDAR, changed!!.startScreen)
        pick("Weekly Start Day", "Monday")
        assertEquals(DayOfWeek.MONDAY, changed!!.weekStart)
        pick("Swipe", "To Change Tab")
        assertEquals(SwipeMode.TAB, changed!!.swipe)
        pick("Income-Expenses Color Setting", "Set. B")
        assertEquals(ColorSet.B, changed!!.colorSet)
        pick("Time Input", "None, Asc.")
        assertEquals(TimeInput.NONE_ASC, changed!!.timeInput)
        pick("Input order", "From Account")
        assertEquals(InputOrder.ACCOUNT, changed!!.inputOrder)
    }

    @Test fun onOffOptionsAndScreens() {
        var changed: AppSettings? = null
        var monthly = false
        var style = false
        show(actions = ConfigActions(onChange = { changed = it }, onOpenMonthlyStartDate = { monthly = true }, onOpenStyle = { style = true }))
        rule.onNodeWithText("Show description").performScrollTo().performClick()
        rule.onAllNodesWithText("ON").onLast().performClick() // the dialog's; the rows behind it show ON too
        assertEquals(true, changed!!.showDescription)
        rule.onNodeWithText("Note button setting").performScrollTo().performClick()
        rule.onAllNodesWithText("OFF").onLast().performClick()
        assertEquals(false, changed!!.noteButton)
        rule.onNodeWithText("Monthly Start Date").performScrollTo().performClick()
        assertEquals(true, monthly)
        rule.onNodeWithText("Style").performScrollTo().performClick()
        assertEquals(true, style)
    }

    @Test fun carryOverRowSitsBetweenWeeklyStartDayAndSwipeAndSavesOnOff() {
        var changed: AppSettings? = null
        show(actions = ConfigActions(onChange = { changed = it }))
        val top = { text: String -> rule.onNodeWithText(text).performScrollTo().fetchSemanticsNode().positionInRoot.y }
        assertTrue(top("Weekly Start Day") < top("Carry-over Setting") && top("Carry-over Setting") < top("Swipe"))
        rule.onNodeWithText("Carry-over Setting").performScrollTo().performClick()
        rule.onAllNodesWithText("Carry-over Setting").assertCountEquals(2) // the row and the dialog's title
        rule.onNodeWithText("Carry-over allows your previous month", substring = true).assertIsDisplayed()
        rule.onAllNodesWithText("ON").onLast().performClick()
        assertEquals(true, changed!!.carryOver)
        rule.onAllNodesWithText("Carry-over Setting").assertCountEquals(1) // the dialog closed
    }

    @Test fun subcategoryRowFollowsExpensesCategorySettingAndSavesOnOff() {
        var changed: AppSettings? = null
        show(actions = ConfigActions(onChange = { changed = it }))
        val top = { text: String -> rule.onNodeWithText(text).performScrollTo().fetchSemanticsNode().positionInRoot.y }
        assertTrue(top("Expenses Category Setting") < top("Subcategory") && top("Subcategory") < top("Budget Setting"))
        rule.onNodeWithText("Subcategory").performClick()
        rule.onNodeWithText("Subcategory Setting").assertIsDisplayed()
        rule.onNodeWithText("Subcategory function is disabled in Money Manager by default.", substring = true).assertIsDisplayed()
        rule.onAllNodesWithText("ON").onLast().performClick()
        assertEquals(true, changed!!.subcategory)
        rule.onAllNodesWithText("Subcategory Setting").assertCountEquals(0)
    }

    @Test fun theColorDialogExplainsTheSetsAndCloses() {
        show()
        rule.onNodeWithText("Income-Expenses Color Setting").performScrollTo().performClick()
        rule.onNodeWithText("Color Setting").assertIsDisplayed()
        rule.onNodeWithText("A. Income: Blue, Expenses: Red", substring = true).assertIsDisplayed()
        rule.onNodeWithContentDescription("Close").performClick()
        rule.onNodeWithText("Color Setting").assertDoesNotExist()
    }

    @Test fun autocompleteStillOpensItsDialog() {
        var changed: AppSettings? = null
        var reset = false
        show(actions = ConfigActions(onChange = { changed = it }, onResetAutocomplete = { reset = true }))
        rule.onNodeWithText("Autocomplete").performScrollTo().performClick()
        rule.onNodeWithText("Reset").performClick()
        assertEquals(true, reset)
        rule.onAllNodesWithText("OFF").onLast().performClick()
        assertEquals(false, changed!!.autocomplete)
    }

    @Test fun saveFinishesTheWriteBeforeReturning() = runTest {
        val settings = FakeSettingsRepository()
        val vm = ConfigurationViewModel(settings, time)
        vm.save(AppSettings("CZK", monthStartDay = 25))
        assertEquals(25, settings.current().monthStartDay)
    }

    @Test fun thePasscodeRowShowsItsStateAndOpensTheRightFlow() {
        var opened: Boolean? = null
        val salt = Passcode.newSalt()
        rule.setContent {
            MoneyBookTheme(darkTheme = false) {
                ConfigurationContent(
                    AppSettings("CZK"), ConfigActions(onOpenPasscode = { opened = it }),
                    device = DeviceSettings(passcodeHash = Passcode.hash("1234", salt), passcodeSalt = salt),
                )
            }
        }
        rule.onNodeWithText("Passcode").performScrollTo().performClick()
        assertEquals(true, opened)
    }

    @Test fun thePasscodeRowStartsTheSetFlowWhenNoneIsSet() {
        var opened: Boolean? = null
        rule.setContent { MoneyBookTheme(darkTheme = false) { ConfigurationContent(AppSettings("CZK"), ConfigActions(onOpenPasscode = { opened = it })) } }
        rule.onNodeWithText("Passcode").performScrollTo().performClick()
        assertEquals(false, opened)
    }

    @Test fun quickAddOpensItsDialogWithTheSample() {
        var set: Boolean? = null
        rule.setContent { MoneyBookTheme(darkTheme = false) { ConfigurationContent(AppSettings("CZK"), ConfigActions(onSetQuickAdd = { set = it })) } }
        rule.onNodeWithText("Quick add").performScrollTo().performClick()
        rule.onNodeWithText("Displays quick add feature in the notification bar.").assertIsDisplayed()
        rule.onAllNodesWithText("ON").onLast().performClick()
        assertEquals(true, set)
    }

    @Test fun widgetSettingsFollowsStyle() {
        var opened = false
        rule.setContent { MoneyBookTheme(darkTheme = false) { ConfigurationContent(AppSettings("CZK"), ConfigActions(onOpenWidgetSettings = { opened = true })) } }
        rule.onNodeWithText("Widget Settings").performScrollTo().performClick()
        assertEquals(true, opened)
    }
}
