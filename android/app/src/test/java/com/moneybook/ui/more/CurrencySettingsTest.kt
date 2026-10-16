package com.moneybook.ui.more

import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneybook.LockState
import com.moneybook.MainActivity
import com.moneybook.MoneyBookApp
import com.moneybook.data.DefaultData
import com.moneybook.data.db.AccountEntity
import com.moneybook.shared.model.AccountGroupType
import com.moneybook.shared.money.MoneyFormat
import com.moneybook.shared.money.RealbyteCurrencies
import com.moneybook.shared.money.SymbolPosition
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith

/** More → Configuration → Main/Sub Currency Setting, as measured on Realbyte. */
@RunWith(AndroidJUnit4::class)
class CurrencySettingsTest {
    @get:Rule(order = 0) val rule = createAndroidComposeRule<MainActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<MoneyBookApp>()
    private var originalMain = ""
    private val main get() = runBlocking { app.container.currencies.current().main }

    @get:Rule(order = 1) val opened = object : ExternalResource() {
        override fun before() {
            rule.waitUntil(10_000) { app.container.lock.state.value == LockState.OPEN }
            rule.waitUntil(10_000) { runBlocking { app.container.db.categoryDao().all().isNotEmpty() } }
            originalMain = main
            rule.waitForIdle()
        }
    }

    /** The settings DataStore outlives each test's Application. */
    @After fun restoreMain() = runBlocking { app.container.settings.setMainCurrency(originalMain) }

    private fun waitFor(text: String) = rule.waitUntil(10_000) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    private fun openConfiguration() {
        rule.onNodeWithText("More").performClick()
        rule.onNodeWithText("Configuration").performClick()
        waitFor("Sub Currency Setting")
    }

    @Test fun addEditAndDeleteASubCurrency() {
        openConfiguration()
        rule.onNodeWithText("Sub Currency Setting").performClick()
        waitFor("No data available.")
        rule.onNodeWithContentDescription("Add").performClick()
        rule.onNodeWithContentDescription("Search").performTextInput("eur")
        rule.onNodeWithText("EUR - Europe (€)").performClick()
        waitFor("EUR 1.00  =  $main 1.0")
        rule.onNodeWithText("EUR - Europe (€)").assertExists()

        rule.onNodeWithContentDescription("Edit EUR").performClick()
        waitFor("Exchange rate")
        rule.onNodeWithText("Exchange rate").performClick()
        listOf("C", "1", ".", "1", "Done").forEach { rule.onNodeWithText(it).performClick() }
        rule.onNodeWithText("Save").performClick()
        waitFor("EUR 1.00  =  $main 1.1")

        rule.onNodeWithContentDescription("Delete EUR").performClick()
        rule.onNodeWithText("Delete").performClick()
        waitFor("No data available.")
    }

    @Test fun aCurrencyUsedByAnAccountIsNotDeleted() {
        runBlocking {
            app.container.currencies.add("EUR")
            app.container.db.accountDao().upsert(
                AccountEntity("eurosave", DefaultData.groupId(AccountGroupType.ACCOUNTS), "EuroSave", "EUR", 0, sortOrder = 9, updatedAt = 0),
            )
        }
        openConfiguration()
        rule.onNodeWithText("Sub Currency Setting").performClick()
        waitFor("EUR - Europe (€)")
        rule.onNodeWithContentDescription("Delete EUR").performClick()
        rule.onNodeWithText("Delete").performClick()
        waitFor("This currency is used by an account.")
    }

    @Test fun mainCurrencyDisplayAndAChangeWithDataRetention() {
        val target = if (main == "CZK") "USD" else "CZK"
        openConfiguration()
        rule.onNodeWithText("Main Currency Setting").performClick()
        val spec = runBlocking { app.container.currencies.current().mainSpec }
        val thousand = 1_000L * Math.pow(10.0, spec.decimals.toDouble()).toLong()
        waitFor(RealbyteCurrencies.find(main)!!.label)
        rule.onNodeWithText(MoneyFormat.format(thousand, spec)).assertExists()
        rule.onNodeWithText("Unit position").performClick()
        rule.onNodeWithText("End (1.00 ${spec.symbol})").performClick()
        rule.onNodeWithText("Save").performClick()
        waitFor("Start Screen (Daily/Calendar)") // back on Configuration
        rule.onNodeWithText("Main Currency Setting").performClick()
        waitFor(MoneyFormat.format(thousand, spec.copy(position = SymbolPosition.END)))

        val old = main
        val label = RealbyteCurrencies.find(target)!!.label
        rule.onNodeWithText("Change").performClick()
        rule.onNode(hasScrollAction()).performScrollToNode(hasText(label))
        rule.onNodeWithText(label).performClick()
        rule.onNodeWithText("Data retention").performClick()
        rule.onNodeWithText("YES").performClick()
        waitFor("1 $old =")
        listOf("2", "5", "Done").forEach { rule.onNodeWithText(it).performClick() }
        rule.waitUntil(10_000) { main == target }
        waitFor(label)
        assertEquals(1, rule.onAllNodesWithText(label).fetchSemanticsNodes().size)

        rule.onNodeWithContentDescription("Back").performClick()
        // read the state inside the wait: the new main's display row can land a moment after the main code changes
        rule.waitUntil(10_000) {
            val now = runBlocking { app.container.currencies.current() }
            rule.onAllNodesWithText("$target (${now.mainSpec.symbol})").fetchSemanticsNodes().isNotEmpty()
        }
        val cs = runBlocking { app.container.currencies.current() }
        rule.onNodeWithText(cs.spec(old).symbol).assertExists()
    }
}
