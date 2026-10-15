package com.moneybook.ui.budget

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneybook.data.AppSettings
import com.moneybook.data.BudgetRepository
import com.moneybook.data.CategoryRepository
import com.moneybook.data.TimeSource
import com.moneybook.data.db.MoneyBookDatabase
import com.moneybook.ui.edit.KeypadKey
import com.moneybook.domain.TOTAL_BUDGET
import com.moneybook.testutil.FakeSettingsRepository
import com.moneybook.testutil.Fixtures
import com.moneybook.testutil.MainDispatcherRule
import com.moneybook.testutil.inMemoryDb
import com.moneybook.ui.common.money
import com.moneybook.ui.common.specOf
import com.moneybook.ui.theme.MoneyBookTheme
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
class BudgetSettingTest {
    @get:Rule val rule = createComposeRule()
    @get:Rule val main = MainDispatcherRule()
    private lateinit var db: MoneyBookDatabase
    private val time = TimeSource(Clock.fixed(Instant.parse("2026-10-08T10:00:00Z"), ZoneOffset.UTC))

    @Before fun setUp() = runTest {
        db = inMemoryDb()
        db.categoryDao().upsertAll(listOf(Fixtures.category("food", name = "🍜 Food"), Fixtures.category("other", name = "Other", sortOrder = 1)))
    }

    @After fun tearDown() = db.close()

    @Test fun emptyListShowsHelpAndNoData() {
        rule.setContent { MoneyBookTheme { BudgetSettingContent(emptyList(), specOf("USD"), {}, {}, {}) } }
        rule.onNodeWithText("No data available.").assertIsDisplayed()
        rule.onNodeWithText("You can check out the budget status in", substring = true).assertIsDisplayed()
    }

    @Test fun rowsOpenTheirMonthlyScreen() {
        var opened: String? = "none"
        val items = listOf(BudgetSettingItem(null, TOTAL_BUDGET, 30000), BudgetSettingItem("food", "🍜 Food", 5000))
        rule.setContent { MoneyBookTheme { BudgetSettingContent(items, specOf("USD"), {}, {}, { opened = it }) } }
        rule.onNodeWithText(money(30000, specOf("USD")), useUnmergedTree = true).assertExists()
        rule.onNodeWithText("🍜 Food", useUnmergedTree = true).performClick()
        assertEquals("food", opened)
    }

    @Test fun addPicksAllThenAmountAndSaves() = runTest {
        val budgets = BudgetRepository(db, time)
        val vm = BudgetAddViewModel(budgets, CategoryRepository(db, time), FakeSettingsRepository(AppSettings(mainCurrency = "USD")), time, total = false)
        assertEquals(listOf("· All", "🍜 Food", "Other"), vm.state.value.categories.map { it.second }.ifEmpty { vm.awaitCategories() })
        vm.pickCategory(ALL_ID)
        assertEquals(BudgetPanel.AMOUNT, vm.state.value.panel)
        listOf('3', '0', '0').forEach { vm.onKey(KeypadKey.Digit(it)) }
        assertEquals(true, vm.save())
        assertEquals(30000L, budgets.observe().first().single { it.categoryId == null }.rows.single().amount)
    }

    @Test fun addRefusesNoCategoryOrZero() = runTest {
        val vm = BudgetAddViewModel(BudgetRepository(db, time), CategoryRepository(db, time), FakeSettingsRepository(AppSettings(mainCurrency = "USD")), time, total = false)
        assertEquals(false, vm.save())
        vm.pickCategory("food")
        assertEquals(false, vm.save())
    }

    @Test fun totalPresetStartsOnTheAmount() = runTest {
        val vm = BudgetAddViewModel(BudgetRepository(db, time), CategoryRepository(db, time), FakeSettingsRepository(AppSettings(mainCurrency = "USD")), time, total = true)
        assertEquals(true, vm.state.value.total)
        assertEquals(BudgetPanel.AMOUNT, vm.state.value.panel)
        vm.awaitCategories() // let the init load finish before tearDown closes the db
    }

    private suspend fun BudgetAddViewModel.awaitCategories() = state.first { it.categories.isNotEmpty() }.categories.map { it.second }
}
