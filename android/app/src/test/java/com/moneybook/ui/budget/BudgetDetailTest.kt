package com.moneybook.ui.budget

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneybook.data.AppSettings
import com.moneybook.data.BudgetRepository
import com.moneybook.data.TimeSource
import com.moneybook.data.TxnRepository
import com.moneybook.data.db.MoneyBookDatabase
import com.moneybook.shared.model.TxnType
import com.moneybook.testutil.FakeSettingsRepository
import com.moneybook.testutil.Fixtures
import com.moneybook.testutil.MainDispatcherRule
import com.moneybook.testutil.inMemoryDb
import com.moneybook.ui.common.money
import com.moneybook.ui.common.specOf
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
class BudgetDetailTest {
    @get:Rule val rule = createComposeRule()
    @get:Rule val main = MainDispatcherRule()
    private lateinit var db: MoneyBookDatabase
    private val time = TimeSource(Clock.fixed(Instant.parse("2026-10-08T10:00:00Z"), ZoneOffset.UTC))
    private val settings = FakeSettingsRepository(AppSettings(mainCurrency = "USD"))

    @Before fun setUp() = runTest {
        db = inMemoryDb()
        db.accountDao().upsertGroups(listOf(Fixtures.group()))
        db.accountDao().upsertAll(listOf(Fixtures.account("a")))
        db.categoryDao().upsertAll(listOf(Fixtures.category("other", name = "Other")))
        db.txnDao().upsertAll(
            listOf(
                Fixtures.txn("t1", TxnType.EXPENSE, "2026-10-03T12:00:00.000", "a", 7000, categoryId = "other"),
                Fixtures.txn("t2", TxnType.EXPENSE, "2026-10-05T12:00:00.000", "a", 5000, categoryId = "other"),
            ),
        )
        BudgetRepository(db, time).add("other", 10000, 202610)
    }

    @After fun tearDown() = db.close()

    @Test fun showsTheMonthsBudgetUsedAndRemainingAndMovesByMonth() = runTest {
        val vm = BudgetDetailViewModel(TxnRepository(db, time), BudgetRepository(db, time), settings, time, "other", YearMonth.of(2026, 10))
        val s = vm.state.first { it.name.isNotEmpty() && it.days.isNotEmpty() }
        assertEquals(10000L, s.budget)
        assertEquals(12000L, s.used)
        assertEquals(2, s.days.size)
        rule.setContent { MoneyBookTheme { BudgetDetailContent(s, specOf("USD"), {}, {}, {}, {}, {}) } }
        listOf("Budget", "Used Amount", "Remaining").forEach { rule.onNodeWithText(it).assertExists() }
        rule.onNodeWithText(money(-2000, specOf("USD"))).assertExists()

        vm.next()
        val next = vm.state.first { it.month == YearMonth.of(2026, 11) && it.name.isNotEmpty() }
        assertEquals(0L, next.used)
    }
}
