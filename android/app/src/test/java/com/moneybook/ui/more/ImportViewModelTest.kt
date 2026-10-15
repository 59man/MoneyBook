package com.moneybook.ui.more

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneybook.data.ImportRepository
import com.moneybook.data.ImportResult
import com.moneybook.data.Seeder
import com.moneybook.data.TimeSource
import com.moneybook.data.db.MoneyBookDatabase
import com.moneybook.shared.importer.SkippedRow
import com.moneybook.testutil.FakeSettingsRepository
import com.moneybook.testutil.MainDispatcherRule
import com.moneybook.testutil.inMemoryDb
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImportViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private lateinit var db: MoneyBookDatabase
    private lateinit var vm: ImportViewModel

    @Before fun setUp() {
        db = inMemoryDb()
        val time = TimeSource()
        vm = ImportViewModel(ImportRepository(db, FakeSettingsRepository(), Seeder(db, time), time))
    }

    @After fun tearDown() = db.close()

    /** Minimal Realbyte-shaped workbook: inline strings, date as an Excel serial number. */
    private fun realbyteXlsx(): ByteArray {
        val header = listOf("Date", "Account", "Category", "Subcategory", "Note", "CZK", "Income/Expense", "Description", "Amount", "Currency", "Account")
        fun s(ref: String, v: String) = """<c r="$ref" t="inlineStr"><is><t>$v</t></is></c>"""
        fun n(ref: String, v: String) = """<c r="$ref"><v>$v</v></c>"""
        val cols = "ABCDEFGHIJK"
        val row1 = header.mapIndexed { i, h -> s("${cols[i]}1", h) }.joinToString("")
        val row2 = listOf(n("A2", "46291.5"), s("B2", "Cash"), s("C2", "🍜 Food"), s("D2", ""), s("E2", "Zelenina"), n("F2", "525"),
            s("G2", "Expense"), s("H2", ""), s("I2", "525.0"), s("J2", "CZK"), n("K2", "525")).joinToString("")
        val sheet = """<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData><row r="1">$row1</row><row r="2">$row2</row></sheetData></worksheet>"""
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
            z.write(sheet.toByteArray(Charsets.UTF_8))
            z.closeEntry()
        }
        return out.toByteArray()
    }

    @Test fun importsAValidFile() = runTest {
        vm.import { ByteArrayInputStream(realbyteXlsx()) }
        val done = vm.state.first { it is ImportState.Done } as ImportState.Done
        assertEquals(1, done.result.imported)
        assertEquals(1, db.txnDao().count())
        vm.dismiss()
        assertEquals(ImportState.Idle, vm.state.value)
    }

    @Test fun reportsANonSpreadsheet() = runTest {
        vm.import { ByteArrayInputStream("not a spreadsheet".toByteArray()) }
        assertEquals(ImportState.Failed("Not an .xlsx file"), vm.state.first { it is ImportState.Failed })
    }

    @Test fun reportsAWrongSpreadsheet() = runTest {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
            z.write("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData><row r="1"><c r="A1" t="inlineStr"><is><t>Date</t></is></c></row></sheetData></worksheet>""".toByteArray())
            z.closeEntry()
        }
        vm.import { ByteArrayInputStream(out.toByteArray()) }
        val failed = vm.state.first { it is ImportState.Failed } as ImportState.Failed
        assertEquals(true, failed.message.startsWith("Not a Money Manager export"))
    }

    @Test fun summaryText() {
        val text = importSummary(ImportResult(2499, 3, listOf(SkippedRow(7, "Unreadable date")), 1, 4))
        assertEquals(
            "Imported 2499 transactions.\nSkipped 3 already imported.\nCreated 1 accounts.\nCreated 4 categories.\n" +
                "Could not read 1 rows: row 7 (Unreadable date)",
            text,
        )
    }
}
