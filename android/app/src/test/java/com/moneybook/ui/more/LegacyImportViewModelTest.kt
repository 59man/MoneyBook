package com.moneybook.ui.more

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneybook.data.AccountRepository
import com.moneybook.data.CategoryChoice
import com.moneybook.data.CategoryRepository
import com.moneybook.data.DefaultData
import com.moneybook.data.LegacyCategoryKey
import com.moneybook.data.LegacyImportRepository
import com.moneybook.data.Seeder
import com.moneybook.data.TimeSource
import com.moneybook.data.db.MoneyBookDatabase
import com.moneybook.shared.model.AccountGroupType
import com.moneybook.shared.model.CategoryType
import com.moneybook.shared.xlsx.OutCell
import com.moneybook.shared.xlsx.XlsxWriter
import com.moneybook.testutil.MainDispatcherRule
import com.moneybook.testutil.inMemoryDb
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LegacyImportViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private lateinit var db: MoneyBookDatabase
    private val time = TimeSource(Clock.fixed(Instant.parse("2026-09-28T10:00:00Z"), ZoneOffset.UTC))

    @Before fun setUp() {
        db = inMemoryDb()
        runBlocking { Seeder(db, time).seedIfEmpty("CZK") }
    }

    @After fun tearDown() = db.close()

    private fun sheet(vararg rows: List<String>): ByteArray =
        ByteArrayOutputStream().also { out -> XlsxWriter.write("Sheet1", rows.map { r -> r.map { OutCell.Text(it) } }, out) }.toByteArray()

    private val file = sheet(
        listOf("Date", "Income/Expenses", "Category", "Memo", "Amount"),
        listOf("2021-06-01", "Expenses", "Food", "oběd", "-150"),
        listOf("2021-06-02", "Expenses", "Sport", "", "-89"),
    )

    private fun vm(bytes: ByteArray = file) = LegacyImportViewModel(
        { ByteArrayInputStream(bytes) }, LegacyImportRepository(db, time), AccountRepository(db, time), CategoryRepository(db, time),
    )

    private suspend fun LegacyImportViewModel.ready() = state.first { it is LegacyImportState.Ready } as LegacyImportState.Ready

    @Test fun loadsThePreviewWithCashAndSuggestions() = runTest {
        val s = vm().ready()
        assertEquals(2, s.preview.importable)
        assertEquals(DefaultData.accountId(AccountGroupType.CASH), s.accountId)
        assertTrue(s.keepBalance)
        val foodKey = LegacyCategoryKey(CategoryType.EXPENSE, "Food")
        val sportKey = LegacyCategoryKey(CategoryType.EXPENSE, "Sport")
        assertEquals(CategoryChoice.Existing(DefaultData.categoryId(CategoryType.EXPENSE, 0)), s.choices[foodKey])
        assertEquals(CategoryChoice.CreateNew, s.choices[sportKey])
        assertEquals("🍜 Food", s.label(foodKey))
        assertEquals("New: Sport", s.label(sportKey))
    }

    @Test fun importUsesTheChosenMapping() = runTest {
        val vm = vm()
        vm.ready()
        val other = DefaultData.categoryId(CategoryType.EXPENSE, 11)
        vm.choose(LegacyCategoryKey(CategoryType.EXPENSE, "Sport"), CategoryChoice.Existing(other))
        vm.import()
        val done = vm.state.first { it is LegacyImportState.Done } as LegacyImportState.Done
        assertEquals(2, done.result.imported)
        assertEquals(0, done.result.categoriesCreated)
        assertEquals(2, db.txnDao().count())
    }

    @Test fun otherSpreadsheetsFailWithAMessage() = runTest {
        val failed = vm(sheet(listOf("Date", "Account"))).state.first { it is LegacyImportState.Failed } as LegacyImportState.Failed
        assertTrue(failed.message.startsWith("Not an old-app export"))
    }
}
