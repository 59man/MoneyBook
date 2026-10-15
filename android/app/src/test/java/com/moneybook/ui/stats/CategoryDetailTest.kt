package com.moneybook.ui.stats

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneybook.data.AppSettings
import com.moneybook.data.CategoryRepository
import com.moneybook.data.DefaultData
import com.moneybook.data.Seeder
import com.moneybook.data.TimeSource
import com.moneybook.data.TxnRepository
import com.moneybook.data.db.MoneyBookDatabase
import com.moneybook.domain.StatsPeriod
import com.moneybook.shared.model.AccountGroupType
import com.moneybook.shared.model.CategoryType
import com.moneybook.shared.model.TxnType.EXPENSE
import com.moneybook.shared.model.TxnType.TRANSFER
import com.moneybook.testutil.FakeSettingsRepository
import com.moneybook.testutil.Fixtures
import com.moneybook.testutil.MainDispatcherRule
import com.moneybook.testutil.inMemoryDb
import com.moneybook.ui.theme.MoneyBookTheme
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CategoryDetailTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val rule = createComposeRule()
    private lateinit var db: MoneyBookDatabase
    private val time = TimeSource(Clock.fixed(Instant.parse("2026-09-26T10:00:00Z"), ZoneOffset.UTC))
    private val cash = DefaultData.accountId(AccountGroupType.CASH)
    private val food = DefaultData.categoryId(CategoryType.EXPENSE, 0)
    private val transport = DefaultData.categoryId(CategoryType.EXPENSE, 3)
    private val september = StatsPeriod.Month(YearMonth.of(2026, 9))

    @Before fun setUp() {
        db = inMemoryDb()
        runBlocking {
            Seeder(db, time).seedIfEmpty("CZK")
            db.categoryDao().upsertAll(listOf(Fixtures.category("lunch", name = "Lunch", parentId = food)))
            db.txnDao().upsertAll(listOf(
                Fixtures.txn("f1", EXPENSE, "2026-09-03T12:00:00.000", cash, 30_000, categoryId = food, note = "Zelenina z trhu"),
                Fixtures.txn("l1", EXPENSE, "2026-09-20T12:00:00.000", cash, 1_000, categoryId = "lunch", note = "Polévka"),
                Fixtures.txn("f8", EXPENSE, "2026-08-15T12:00:00.000", cash, 5_000, categoryId = food),
                Fixtures.txn("f2", EXPENSE, "2026-02-10T12:00:00.000", cash, 7_000, categoryId = food),
                Fixtures.txn("f1old", EXPENSE, "2026-01-10T12:00:00.000", cash, 900, categoryId = food),
                Fixtures.txn("tr", EXPENSE, "2026-09-05T12:00:00.000", cash, 5_000, categoryId = transport),
            ))
        }
    }

    @After fun tearDown() = db.close()

    private fun vm(period: StatsPeriod = september) = CategoryDetailViewModel(
        food, "🍜 Food", EXPENSE, period, TxnRepository(db, time), CategoryRepository(db, time), FakeSettingsRepository(),
    )

    @Test fun showsThePeriodTotalTheTrendAndTheEntries() = runTest {
        val vm = vm()
        val s = vm.state.first { it.dayGroups.isNotEmpty() }
        assertEquals(31_000L, s.total)
        assertEquals(listOf("Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep"), s.trendLabels)
        assertEquals(listOf<Long>(7_000, 0, 0, 0, 0, 0, 5_000, 31_000), s.trend)
        assertEquals(listOf(LocalDate.of(2026, 9, 20), LocalDate.of(2026, 9, 3)), s.dayGroups.map { it.date })
        vm.previous()
        assertEquals(5_000L, vm.state.first { it.period == StatsPeriod.Month(YearMonth.of(2026, 8)) }.total)
    }

    private fun subVm(on: Boolean) = CategoryDetailViewModel(
        food, "🍜 Food", EXPENSE, september, TxnRepository(db, time), CategoryRepository(db, time),
        FakeSettingsRepository(AppSettings(mainCurrency = "CZK", subcategory = on)),
    )

    @Test fun subcategoryOnBreaksTheCategoryDownFromTwoSubcategories() = runTest {
        // Only Lunch has entries: no breakdown. A second subcategory makes one.
        assertEquals(emptyList<Long>(), subVm(true).state.first { it.dayGroups.isNotEmpty() }.subSlices.map { it.amount })
        db.categoryDao().upsert(Fixtures.category("dinner", name = "Dinner", parentId = food))
        db.txnDao().upsert(Fixtures.txn("d1", EXPENSE, "2026-09-21T12:00:00.000", cash, 4_000, categoryId = "dinner"))
        val on = subVm(true).state.first { it.subSlices.isNotEmpty() }
        assertEquals(listOf("🍜 Food" to 30_000L, "Dinner" to 4_000L, "Lunch" to 1_000L), on.subSlices.map { it.name to it.amount })
        assertEquals(emptyList<Long>(), subVm(false).state.first { it.dayGroups.isNotEmpty() }.subSlices.map { it.amount })
    }

    @Test fun contentShowsTheSubcategoryPieAndListAboveTheEntries() {
        val state = runBlocking {
            db.categoryDao().upsert(Fixtures.category("dinner", name = "Dinner", parentId = food))
            db.txnDao().upsert(Fixtures.txn("d1", EXPENSE, "2026-09-21T12:00:00.000", cash, 4_000, categoryId = "dinner"))
            subVm(true).state.first { it.subSlices.isNotEmpty() }
        }
        rule.setContent { MoneyBookTheme(darkTheme = false) { CategoryDetailContent(state, {}, {}, {}, {}) } }
        rule.onNodeWithContentDescription("Pie chart").assertIsDisplayed()
        rule.onNodeWithText("Dinner").assertIsDisplayed()
        rule.onAllNodesWithText("40.00Kč").onFirst().assertIsDisplayed()
    }

    @Test fun aGroupSliceListsItsTransfers() = runTest {
        db.accountDao().upsertGroups(listOf(Fixtures.group("g-save", AccountGroupType.OTHERS, "EuroSave", sortOrder = 9)))
        db.accountDao().upsertAll(listOf(Fixtures.account("save", groupId = "g-save", name = "Save", transferExpense = true)))
        db.txnDao().upsertAll(listOf(
            Fixtures.txn("tx", TRANSFER, "2026-09-07T12:00:00.000", cash, 4_000, toAccountId = "save"),
            Fixtures.txn("back", TRANSFER, "2026-09-08T12:00:00.000", "save", 900, toAccountId = cash),
        ))
        val vm = CategoryDetailViewModel(
            "group:g-save", "EuroSave", EXPENSE, september, TxnRepository(db, time), CategoryRepository(db, time), FakeSettingsRepository(),
        )
        val s = vm.state.first { it.dayGroups.isNotEmpty() }
        assertEquals(4_000L, s.total)
        assertEquals(listOf("tx"), s.dayGroups.flatMap { g -> g.rows.map { it.id } })
        assertEquals(4_000L, s.trend.last())
    }

    @Test fun aCustomPeriodHasNoTrend() = runTest {
        val s = vm(StatsPeriod.custom(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30))).state.first { it.dayGroups.isNotEmpty() }
        assertEquals(31_000L, s.total)
        assertEquals(emptyList<Long>(), s.trend)
    }

    @Test fun contentShowsTheHeaderAndOpensEntries() {
        val state = runBlocking { vm().state.first { it.dayGroups.isNotEmpty() } }
        var opened: String? = null
        var back = false
        rule.setContent {
            MoneyBookTheme(darkTheme = false) { CategoryDetailContent(state, onBack = { back = true }, onPrevious = {}, onNext = {}, onOpenTxn = { opened = it }) }
        }
        listOf("Sep 2026", "Total Balance", "310.00Kč", "Feb", "Sep", "Polévka").forEach { rule.onNodeWithText(it).assertIsDisplayed() }
        rule.onAllNodesWithText("🍜 Food").onFirst().assertIsDisplayed() // the title (an entry row repeats it)
        rule.onNodeWithText("Polévka").performClick()
        assertEquals("l1", opened)
        rule.onNodeWithContentDescription("Back").performClick()
        assertEquals(true, back)
    }
}
