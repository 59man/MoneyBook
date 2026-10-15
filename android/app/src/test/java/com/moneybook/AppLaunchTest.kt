package com.moneybook

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.core.view.WindowCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneybook.data.StyleMode
import com.moneybook.domain.Formats
import java.time.YearMonth
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppLaunchTest {
    @get:Rule(order = 0) val rule = createAndroidComposeRule<MainActivity>()

    /** The app shows only once the lock has read the device settings (no passcode here, so it opens). */
    @get:Rule(order = 1) val opened = object : ExternalResource() {
        override fun before() {
            val lock = ApplicationProvider.getApplicationContext<MoneyBookApp>().container.lock
            rule.waitUntil(10_000) { lock.state.value == LockState.OPEN }
            rule.waitForIdle() // the frame that puts the screens back in the semantics tree
        }
    }

    @Test fun opensOnTransDailyForCurrentMonth() {
        rule.onNodeWithText("Trans.").assertIsDisplayed()
        rule.onNodeWithText("Daily").assertIsDisplayed()
        rule.onNodeWithText(Formats.monthTitle(YearMonth.now())).assertIsDisplayed()
        rule.onNodeWithText("Income").assertIsDisplayed()
    }

    // With the clock stopped, both taps of a double tap land before the screen transition runs.
    @Test fun doubleTappedBackReturnsToTheMainScreen() {
        rule.onNodeWithContentDescription("Add").performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithContentDescription("Back").fetchSemanticsNodes().isNotEmpty() }
        rule.mainClock.autoAdvance = false
        val back = rule.onNodeWithContentDescription("Back")
        back.performClick()
        back.performClick()
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        rule.onNodeWithText("Trans.").assertIsDisplayed()
    }

    @Test fun doubleTappedAddOpensOneEditor() {
        rule.mainClock.autoAdvance = false
        val add = rule.onNodeWithContentDescription("Add")
        add.performClick()
        add.performClick()
        rule.mainClock.autoAdvance = true
        rule.waitUntil(10_000) { rule.onAllNodesWithContentDescription("Back").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithContentDescription("Back").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Trans.").assertIsDisplayed()
    }

    /** Seeding runs asynchronously in MoneyBookApp.onCreate; wait for it before using the pickers. */
    private fun waitForSeed() {
        val app = ApplicationProvider.getApplicationContext<MoneyBookApp>()
        rule.waitUntil(10_000) { runBlocking { app.container.db.categoryDao().all().isNotEmpty() } }
    }

    @Test fun addExpenseEndToEnd() {
        waitForSeed()
        rule.onNodeWithContentDescription("Add").performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithText("Done").fetchSemanticsNodes().isNotEmpty() }
        "525".forEach { rule.onNodeWithText(it.toString()).performClick() }
        rule.onNodeWithText("Done").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("🍜 Food").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("🍜 Food").performClick()
        rule.onNodeWithText("Cash").performClick()
        rule.onNodeWithContentDescription("Note").performTextInput("Zelenina z trhu")
        rule.onNodeWithText("Save").performClick()
        rule.waitUntil(10_000) {
            rule.onAllNodesWithText("Save").fetchSemanticsNodes().isEmpty() &&
                rule.onAllNodesWithText("Zelenina z trhu").fetchSemanticsNodes().isNotEmpty()
        }
        // The currency symbol depends on the Robolectric default locale, so match the number only.
        rule.onAllNodesWithText("525.00", substring = true).onFirst().assertIsDisplayed()
    }

    @Test fun statsTabOpensOnThisMonthsExpenses() {
        rule.onNodeWithText("Stats").performClick()
        rule.onNodeWithText("Monthly").assertIsDisplayed()
        rule.onNodeWithText("Expenses").assertIsDisplayed()
    }

    @Test fun filterIconOpensAndClosesTheFilterPanel() {
        rule.onNodeWithContentDescription("Filter").performClick()
        rule.onNodeWithText("Select items that you want to filter.").assertIsDisplayed()
        rule.onNodeWithContentDescription("Close filter").performClick()
        rule.onNodeWithText("Daily").assertIsDisplayed()
    }

    @Test fun starOpensBookmarks() {
        rule.onNodeWithContentDescription("Bookmarks").performClick()
        rule.onNodeWithText("How to register Bookmark?").assertIsDisplayed()
        rule.onNodeWithContentDescription("Back").performClick()
        rule.onNodeWithText("Daily").assertIsDisplayed()
    }

    @Test fun magnifierOpensSearch() {
        rule.onNodeWithContentDescription("Search").performClick()
        rule.onNodeWithContentDescription("Search text").assertIsDisplayed()
        rule.onNodeWithContentDescription("Back").performClick()
        rule.onNodeWithText("Daily").assertIsDisplayed()
    }

    @Test fun repInstOpensTheRepeatListOnANewEntry() {
        rule.onNodeWithContentDescription("Add").performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithText("Rep/Inst.").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Rep/Inst.").performClick()
        rule.onNodeWithText("Installment").assertIsDisplayed()
        rule.onNodeWithText("Repeat").performClick()
        rule.onNodeWithText("Every Month").performClick()
        rule.onNodeWithText("Every Month").assertIsDisplayed() // now the Rep/Inst. label
        rule.onNodeWithText("Rep/Inst.").assertDoesNotExist()
    }

    @Test fun configurationOpensRepeatSetting() {
        rule.onNodeWithText("More").performClick()
        rule.onNodeWithText("Configuration").performClick()
        rule.onNodeWithText("Repeat Setting").performClick()
        rule.onNodeWithText("Timing of reflection").assertIsDisplayed()
    }

    @Test fun configurationOpensMonthlyStartDateAndStyle() {
        rule.onNodeWithText("More").performClick()
        rule.onNodeWithText("Configuration").performClick()
        rule.onNodeWithText("Monthly Start Date").performClick()
        rule.onNodeWithText("If monthly start date is weekend,").assertIsDisplayed()
        rule.onNodeWithContentDescription("Back").performClick()
        rule.onNodeWithText("Style").performScrollTo().performClick()
        rule.onNodeWithText("System Mode").assertIsDisplayed()
    }

    @Test fun dailyShowsTheNoteButtonByDefault() {
        rule.onNodeWithContentDescription("Add note").assertIsDisplayed()
    }

    @Test fun theStyleSetsTheSystemBarIcons() {
        val app = ApplicationProvider.getApplicationContext<MoneyBookApp>()
        val bars = { WindowCompat.getInsetsController(rule.activity.window, rule.activity.window.decorView) }
        runBlocking { app.container.settings.replace(app.container.settings.current().copy(style = StyleMode.DARK)) }
        rule.waitUntil(5_000) { !bars().isAppearanceLightStatusBars } // light icons on the dark app
        runBlocking { app.container.settings.replace(app.container.settings.current().copy(style = StyleMode.LIGHT)) }
        rule.waitUntil(5_000) { bars().isAppearanceLightStatusBars }
        runBlocking { app.container.settings.replace(app.container.settings.current().copy(style = StyleMode.SYSTEM)) }
    }
}
