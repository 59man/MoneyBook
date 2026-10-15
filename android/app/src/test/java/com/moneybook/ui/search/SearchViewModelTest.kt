package com.moneybook.ui.search

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneybook.data.AccountRepository
import com.moneybook.data.CategoryRepository
import com.moneybook.data.DefaultData
import com.moneybook.data.Seeder
import com.moneybook.data.TimeSource
import com.moneybook.data.TxnRepository
import com.moneybook.data.db.MoneyBookDatabase
import com.moneybook.domain.SearchTotals
import com.moneybook.shared.model.AccountGroupType
import com.moneybook.shared.model.CategoryType
import com.moneybook.shared.model.TxnType.EXPENSE
import com.moneybook.shared.model.TxnType.INCOME
import com.moneybook.testutil.FakeSettingsRepository
import com.moneybook.testutil.Fixtures
import com.moneybook.testutil.MainDispatcherRule
import com.moneybook.testutil.inMemoryDb
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SearchViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private lateinit var db: MoneyBookDatabase
    private val time = TimeSource(Clock.fixed(Instant.parse("2026-09-26T10:00:00Z"), ZoneOffset.UTC))
    private val cash = DefaultData.accountId(AccountGroupType.CASH)
    private val card = DefaultData.accountId(AccountGroupType.CARD)
    private val food = DefaultData.categoryId(CategoryType.EXPENSE, 0)
    private lateinit var vm: SearchViewModel

    @Before fun setUp() {
        db = inMemoryDb()
        runBlocking {
            Seeder(db, time).seedIfEmpty("CZK")
            db.txnDao().upsertAll(listOf(
                Fixtures.txn("a", EXPENSE, "2026-09-26T12:00:00.000", cash, 49_100, categoryId = food, note = "Nákup jídlo"),
                Fixtures.txn("b", EXPENSE, "2026-08-25T12:00:00.000", card, 17_400, categoryId = food, note = "Oběd JÍDLO"),
                Fixtures.txn("c", INCOME, "2026-09-10T12:00:00.000", cash, 250_000, note = "Výplata"),
            ))
        }
        vm = SearchViewModel(TxnRepository(db, time), AccountRepository(db, time), CategoryRepository(db, time), FakeSettingsRepository(), time)
    }

    @After fun tearDown() = db.close()

    @Test fun typingFindsNotesIgnoringCaseNewestFirst() = runTest {
        assertEquals(emptyList<String>(), vm.state.first { it.accounts.isNotEmpty() }.results.map { it.id })
        vm.setText("jídlo")
        val s = vm.state.first { it.results.isNotEmpty() }
        assertEquals(listOf("a", "b"), s.results.map { it.id })
        assertEquals(SearchTotals(expense = 66_500), s.totals)
        assertEquals("CZK", s.currency.code)
    }

    @Test fun theFilterPanelNarrowsTheResults() = runTest {
        vm.setText("jídlo")
        vm.togglePanel()
        vm.setAccount(card)
        assertEquals(listOf("b"), vm.state.first { it.panelOpen && it.filter.accountId == card }.results.map { it.id })
        assertEquals("Card", vm.state.value.accountName())
        vm.setAccount(null)
        vm.setRange(LocalDate.of(2026, 9, 30), LocalDate.of(2026, 9, 1)) // picked backwards
        assertEquals(listOf("a"), vm.state.first { it.filter.range != null }.results.map { it.id })
        vm.clearRange()
        vm.setMin("200")
        assertEquals(listOf("a"), vm.state.first { it.minText == "200" }.results.map { it.id })
        vm.setMin("")
        vm.setText("")
        vm.setCategory(food)
        assertEquals(listOf("a", "b"), vm.state.first { it.filter.categoryId == food }.results.map { it.id })
        assertEquals("🍜 Food", vm.state.value.categoryName())
    }

    @Test fun amountsParseInMajorUnits() {
        assertEquals(20_000L, parseAmount("200", 2))
        assertEquals(12_350L, parseAmount(" 123,5 ", 2))
        assertNull(parseAmount("", 2))
        assertNull(parseAmount("abc", 2))
    }
}
