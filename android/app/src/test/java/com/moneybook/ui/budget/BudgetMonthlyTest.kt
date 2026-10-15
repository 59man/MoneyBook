package com.moneybook.ui.budget

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneybook.data.AppSettings
import com.moneybook.data.BudgetRepository
import com.moneybook.data.TimeSource
import com.moneybook.data.db.MoneyBookDatabase
import com.moneybook.domain.Budgets
import com.moneybook.testutil.FakeSettingsRepository
import com.moneybook.testutil.Fixtures
import com.moneybook.testutil.MainDispatcherRule
import com.moneybook.testutil.inMemoryDb
import com.moneybook.ui.common.specOf
import com.moneybook.ui.edit.KeypadKey
import com.moneybook.ui.theme.MoneyBookTheme
import java.time.Clock
import java.time.Instant
import java.time.YearMonth
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
class BudgetMonthlyTest {
    @get:Rule val rule = createComposeRule()
    @get:Rule val main = MainDispatcherRule()
    private lateinit var db: MoneyBookDatabase
    private val time = TimeSource(Clock.fixed(Instant.parse("2026-10-08T10:00:00Z"), ZoneOffset.UTC))
    private val settings = FakeSettingsRepository(AppSettings(mainCurrency = "USD"))

    @Before fun setUp() = runTest {
        db = inMemoryDb()
        db.categoryDao().upsertAll(listOf(Fixtures.category("other", name = "Other")))
        BudgetRepository(db, time).add("other", 10000, 202610)
    }

    @After fun tearDown() = db.close()

    @Test fun showsDefaultAndTwelveMonthsWithTheCurrentOneMarked() = runTest {
        val vm = BudgetMonthlyViewModel(BudgetRepository(db, time), settings, time, "other")
        val s = vm.state.first { it.months.isNotEmpty() }
        assertEquals("Other", s.name)
        assertEquals(10000L, s.defaultAmount)
        assertEquals(YearMonth.of(2026, 12), s.months.first().first)
        assertEquals(12, s.months.size)
        assertEquals(YearMonth.of(2026, 10), s.current)
        rule.setContent { MoneyBookTheme { BudgetMonthlyContent(s, specOf("USD"), {}, {}, {}, {}, {}) } }
        rule.onNodeWithText("Default Budget").assertExists()
        rule.onNodeWithText("Oct").assertExists()
        rule.onNodeWithText("You can set the budget settings for each month.", substring = true).assertExists()
    }

    @Test fun editingAMonthAndTheDefault() = runTest {
        val budgets = BudgetRepository(db, time)
        BudgetEditViewModel(budgets, settings, time, "other", 202610).run {
            state.first { it.label.isNotEmpty() }
            assertEquals("Oct 2026", state.value.label)
            repeat(5) { onKey(KeypadKey.Backspace) }
            listOf('1', '5', '0').forEach { onKey(KeypadKey.Digit(it)) }
            save()
        }
        BudgetEditViewModel(budgets, settings, time, "other", null).run {
            state.first { it.label.isNotEmpty() }
            assertEquals("Default Budget", state.value.label)
            repeat(5) { onKey(KeypadKey.Backspace) }
            listOf('2', '0', '0').forEach { onKey(KeypadKey.Digit(it)) }
            save()
        }
        val rows = budgets.observeOne("other").first()!!.rows
        assertEquals(listOf(10000L, 15000, 20000), listOf(202609, 202610, 202611).map { Budgets.amountFor(rows, it) })
    }

    // Measured on Realbyte: the first digit replaces the stored amount; ⌫ edits it instead.
    @Test fun firstDigitReplacesTheStoredAmount() = runTest {
        val budgets = BudgetRepository(db, time)
        BudgetEditViewModel(budgets, settings, time, "other", 202610).run {
            state.first { it.label.isNotEmpty() }
            onKey(KeypadKey.Digit('1'))
            onKey(KeypadKey.Digit('5'))
            assertEquals("15", state.value.amount.text)
        }
        BudgetEditViewModel(budgets, settings, time, "other", 202610).run {
            state.first { it.label.isNotEmpty() }
            onKey(KeypadKey.Backspace)
            onKey(KeypadKey.Digit('5'))
            assertEquals("105", state.value.amount.text)
        }
    }

    @Test fun deleteRemovesTheBudget() = runTest {
        val vm = BudgetMonthlyViewModel(BudgetRepository(db, time), settings, time, "other")
        vm.state.first { it.months.isNotEmpty() }
        vm.delete()
        assertEquals(true, vm.state.first { it.gone }.gone)
    }
}
