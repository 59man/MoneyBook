package com.moneybook.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneybook.data.db.CurrencyEntity
import com.moneybook.data.db.MemoEntity
import com.moneybook.data.db.MoneyBookDatabase
import com.moneybook.domain.CardExpenseMode
import com.moneybook.domain.WeekendShift
import com.moneybook.shared.model.AccountGroupType
import com.moneybook.shared.model.CategoryType
import com.moneybook.shared.model.RepeatFrequency
import com.moneybook.shared.model.TxnType
import com.moneybook.shared.money.SymbolPosition
import com.moneybook.testutil.FakeSettingsRepository
import com.moneybook.testutil.Fixtures
import com.moneybook.testutil.inMemoryDb
import com.moneybook.testutil.testPhotos
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupExtrasTest {
    private lateinit var db: MoneyBookDatabase
    private val time = TimeSource(Clock.fixed(Instant.parse("2026-09-28T10:00:00Z"), ZoneOffset.UTC))
    private val settings = FakeSettingsRepository(AppSettings("CZK", autocomplete = false, autocompleteSince = 99))
    private val cash = DefaultData.accountId(AccountGroupType.CASH)
    private val food = DefaultData.categoryId(CategoryType.EXPENSE, 0)

    @Before fun setUp() {
        db = inMemoryDb()
        runBlocking {
            Seeder(db, time).seedIfEmpty("CZK")
            MemoRepository(db, time).save(MemoEntity(id = "m", date = "2026-09-26", title = "Plan", pinned = true, updatedAt = 0))
            BookmarkRepository(db, time).add(Fixtures.txn("t", TxnType.EXPENSE, "2026-09-26T12:00:00.000", cash, 17_400, categoryId = food, note = "Oběd"))
        }
    }

    @After fun tearDown() = db.close()

    @Test fun backupAndRestoreKeepMemosBookmarksAndAutocomplete() = runTest {
        val repo = BackupRepository(db, settings, time, "test", testPhotos())
        val bytes = ByteArrayOutputStream().also { repo.backup(it) }.toByteArray()
        db.memoDao().deleteAll()
        db.bookmarkDao().deleteAll()
        settings.replace(AppSettings("CZK"))
        repo.restore(ByteArrayInputStream(bytes))
        assertEquals("Plan", db.memoDao().get("m")!!.title)
        assertEquals(listOf("Oběd"), db.bookmarkDao().allRows().map { it.name })
        assertEquals(false, settings.current().autocomplete)
        assertEquals(99L, settings.current().autocompleteSince)
    }

    @Test fun bothResetsRemoveMemosAndBookmarks() = runTest {
        val reset = ResetRepository(db, settings, Seeder(db, time), time, defaultCurrency = "CZK", photos = testPhotos())
        reset.resetContents()
        assertNull(MemoRepository(db, time).get("m"))
        assertTrue(db.bookmarkDao().allRows().all { it.deleted && it.dirty })
        MemoRepository(db, time).save(MemoEntity(id = "m2", date = "2026-09-27", updatedAt = 0))
        reset.resetAll()
        assertNull(MemoRepository(db, time).get("m2"))
        assertEquals(true, settings.current().autocomplete) // a complete reset restores the defaults
    }

    @Test fun backupRestoreAndResetsCoverRepeatRules() = runTest {
        val entry = Fixtures.txn("t", TxnType.EXPENSE, "2026-09-26T12:00:00.000", cash, 17_400, categoryId = food, note = "Oběd")
        db.txnDao().upsert(entry)
        RepeatRepository(db, time).create("r", entry, RepeatFrequency.WEEKLY)
        val repo = BackupRepository(db, settings, time, "test", testPhotos())
        val bytes = ByteArrayOutputStream().also { repo.backup(it) }.toByteArray()
        db.repeatRuleDao().deleteAll()
        repo.restore(ByteArrayInputStream(bytes))
        assertEquals(RepeatFrequency.WEEKLY, db.repeatRuleDao().get("r")!!.frequency)
        assertEquals("12:00", db.repeatRuleDao().get("r")!!.time)
        val reset = ResetRepository(db, settings, Seeder(db, time), time, defaultCurrency = "CZK", photos = testPhotos())
        reset.resetContents()
        assertNull(RepeatRepository(db, time).get("r"))
        RepeatRepository(db, time).create("r2", entry, RepeatFrequency.DAILY)
        reset.resetAll()
        assertNull(RepeatRepository(db, time).get("r2"))
    }

    @Test fun backupRestoreAndTheCompleteResetCoverCurrencies() = runTest {
        db.currencyDao().upsert(CurrencyEntity("EUR", "€", SymbolPosition.START, 2, "25.3", 0, updatedAt = 1))
        db.txnDao().upsert(Fixtures.txn("t", TxnType.EXPENSE, "2026-09-26T12:00:00.000", cash, 2_530, categoryId = food).copy(currency = "EUR", amountMain = 2_530, accountAmount = 2_530))
        val repo = BackupRepository(db, settings, time, "test", testPhotos())
        val bytes = ByteArrayOutputStream().also { repo.backup(it) }.toByteArray()
        db.currencyDao().deleteAll()
        repo.restore(ByteArrayInputStream(bytes))
        assertEquals("25.3", db.currencyDao().get("EUR")!!.rate)
        assertEquals(2_530L, db.txnDao().get("t")!!.accountAmount)
        ResetRepository(db, settings, Seeder(db, time), time, defaultCurrency = "CZK", photos = testPhotos()).resetAll()
        assertTrue(db.currencyDao().allRows().all { it.deleted })
    }

    @Test fun optionsSurviveABackupButTheStyleStaysOnTheDevice() = runTest {
        settings.replace(
            AppSettings(
                "CZK", monthStartDay = 25, weekStart = DayOfWeek.MONDAY, startScreen = StartScreen.CALENDAR,
                monthStartWeekend = WeekendShift.FOLLOWING_MONDAY, swipe = SwipeMode.NONE, colorSet = ColorSet.B,
                timeInput = TimeInput.NONE_ASC, showDescription = true, inputOrder = InputOrder.ACCOUNT, noteButton = false,
                cardExpenseMode = CardExpenseMode.LUMP_SUM, carryOver = true, subcategory = true, style = StyleMode.DARK,
            ),
        )
        val repo = BackupRepository(db, settings, time, "test", testPhotos())
        val bytes = ByteArrayOutputStream().also { repo.backup(it) }.toByteArray()
        settings.replace(AppSettings("CZK", style = StyleMode.LIGHT))
        repo.restore(ByteArrayInputStream(bytes))
        val s = settings.current()
        assertEquals(StartScreen.CALENDAR, s.startScreen)
        assertEquals(WeekendShift.FOLLOWING_MONDAY, s.monthStartWeekend)
        assertEquals(SwipeMode.NONE, s.swipe)
        assertEquals(ColorSet.B, s.colorSet)
        assertEquals(TimeInput.NONE_ASC, s.timeInput)
        assertEquals(true, s.showDescription)
        assertEquals(InputOrder.ACCOUNT, s.inputOrder)
        assertEquals(false, s.noteButton)
        assertEquals(CardExpenseMode.LUMP_SUM, s.cardExpenseMode)
        assertEquals(true, s.carryOver)
        assertEquals(true, s.subcategory)
        assertEquals(StyleMode.LIGHT, s.style) // the device's own style is kept
    }
}
