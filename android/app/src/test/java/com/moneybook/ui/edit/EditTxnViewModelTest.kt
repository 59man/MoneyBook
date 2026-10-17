package com.moneybook.ui.edit

import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneybook.data.AccountRepository
import com.moneybook.data.AppSettings
import com.moneybook.data.BookmarkRepository
import com.moneybook.data.CategoryRepository
import com.moneybook.data.CurrencyRepository
import com.moneybook.data.DefaultData
import com.moneybook.data.InputOrder
import com.moneybook.data.RepeatRepository
import com.moneybook.data.Seeder
import com.moneybook.data.TimeInput
import com.moneybook.data.TimeSource
import com.moneybook.data.TxnRepository
import com.moneybook.data.db.MoneyBookDatabase
import com.moneybook.domain.RepeatSchedule
import com.moneybook.shared.model.AccountGroupType
import com.moneybook.shared.model.CategoryType
import com.moneybook.shared.model.RepeatFrequency
import com.moneybook.shared.model.TxnType
import com.moneybook.testutil.FakeSettingsRepository
import com.moneybook.testutil.Fixtures
import com.moneybook.testutil.MainDispatcherRule
import com.moneybook.testutil.inMemoryDb
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EditTxnViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private lateinit var db: MoneyBookDatabase
    private val time = TimeSource(Clock.fixed(Instant.parse("2026-09-26T10:00:00Z"), ZoneOffset.UTC))
    private val cash = DefaultData.accountId(AccountGroupType.CASH)
    private val bank = DefaultData.accountId(AccountGroupType.ACCOUNTS)
    private val food = DefaultData.categoryId(CategoryType.EXPENSE, 0)

    @Before fun setUp() {
        db = inMemoryDb()
        runBlocking { Seeder(db, time).seedIfEmpty("CZK") }
    }

    /** The form collects the currencies for as long as it lives; a test's VMs must stop before its database closes. */
    private val vms = mutableListOf<EditTxnViewModel>()

    @After fun tearDown() {
        // Joined, so nothing resumes on Dispatchers.Main while the rule resets it.
        runBlocking { vms.forEach { it.viewModelScope.coroutineContext.job.cancelAndJoin() } }
        db.close()
    }

    private fun vm(
        txnId: String? = null, date: LocalDate? = null, bookmarkId: String? = null, ruleId: String? = null,
        settings: AppSettings = AppSettings(mainCurrency = "CZK"), type: TxnType = TxnType.EXPENSE,
    ): EditTxnViewModel {
        val fake = FakeSettingsRepository(settings)
        return EditTxnViewModel(
            txnId, date, TxnRepository(db, time), AccountRepository(db, time), CategoryRepository(db, time),
            fake, time, BookmarkRepository(db, time), RepeatRepository(db, time), CurrencyRepository(db, fake, time) {}, bookmarkId, ruleId, type,
        ).also { vms += it }
    }

    private suspend fun EditTxnViewModel.loaded() = state.first { it.loaded }

    private fun EditTxnViewModel.type(digits: String) = digits.forEach { onKey(KeypadKey.Digit(it)) }

    private suspend fun onlyRow() =
        db.txnDao().observeRange("2026-01-01T00:00:00.000", "2027-01-01T00:00:00.000").first().single()

    @Test fun newEntryStartsOnAmountKeypadToday() = runTest {
        val s = vm().loaded()
        assertEquals(Panel.AMOUNT, s.panel)
        assertEquals(LocalDate.of(2026, 9, 26), s.date)
        assertEquals(LocalTime.of(10, 0), s.time)
        assertEquals(TxnType.EXPENSE, s.type)
        assertTrue(s.isNew)
        assertEquals(12, s.categories.size)
        assertEquals("CZK", s.currency.code)
    }

    @Test fun calendarDateIsUsedForNewEntries() = runTest {
        assertEquals(LocalDate.of(2026, 9, 1), vm(date = LocalDate.of(2026, 9, 1)).loaded().date)
    }

    @Test fun keypadFlowAdvancesPanelsAndSaves() = runTest {
        val vm = vm().also { it.loaded() }
        vm.type("525")
        vm.onAmountDone()
        assertEquals(Panel.CATEGORY, vm.state.value.panel)
        vm.pickCategory(food)
        assertEquals(Panel.ACCOUNT, vm.state.value.panel)
        vm.pickAccount(cash)
        assertEquals(Panel.NONE, vm.state.value.panel)
        vm.setNote(" Zelenina z trhu ")
        vm.save()
        assertEquals(EditEvent.Closed, vm.events.first())
        val row = onlyRow()
        assertEquals(52_500L, row.amount)
        assertEquals("Zelenina z trhu", row.note)
        assertEquals("🍜 Food", row.categoryName)
        assertEquals("2026-09-26T10:00:00.000", row.dateTime)
    }

    @Test fun validationErrors() = runTest {
        val vm = vm().also { it.loaded() }
        vm.save()
        assertEquals(EditEvent.Error("Please enter the amount."), vm.events.first())
        vm.type("5")
        vm.save()
        assertEquals(EditEvent.Error("Please select a category."), vm.events.first())
        vm.pickCategory(food)
        vm.closePanel()
        vm.save()
        assertEquals(EditEvent.Error("Please select an account."), vm.events.first())
        assertEquals(0, db.txnDao().count())
    }

    @Test fun transferNeedsTwoDifferentAccounts() = runTest {
        val vm = vm().also { it.loaded() }
        vm.setType(TxnType.TRANSFER)
        vm.type("1000")
        vm.onAmountDone()
        assertEquals(Panel.ACCOUNT, vm.state.value.panel)
        vm.pickAccount(cash)
        assertEquals(Panel.TO_ACCOUNT, vm.state.value.panel)
        vm.pickAccount(cash)
        vm.save()
        assertEquals(EditEvent.Error("Please select different accounts."), vm.events.first())
        vm.openPanel(Panel.TO_ACCOUNT)
        vm.pickAccount(bank)
        vm.save()
        assertEquals(EditEvent.Closed, vm.events.first())
        val row = onlyRow()
        assertEquals(TxnType.TRANSFER, row.type)
        assertEquals(bank, row.toAccountId)
        assertNull(row.categoryId)
    }

    @Test fun doubleTappedSaveStoresOneEntry() = runTest {
        val vm = vm().also { it.loaded() }
        vm.type("525")
        vm.onAmountDone()
        vm.pickCategory(food)
        vm.pickAccount(cash)
        vm.save()
        vm.save()
        repeat(2) { assertEquals(EditEvent.Closed, vm.events.first()) }
        assertEquals(1, db.txnDao().count())
    }

    @Test fun continueSavesAndResetsForTheNextEntry() = runTest {
        val vm = vm().also { it.loaded() }
        vm.type("15")
        vm.pickCategory(food)
        vm.pickAccount(cash)
        vm.setNote("Káva")
        vm.save(continueAfter = true)
        val s = vm.state.first { it.amount.isEmpty && !it.dirty }
        assertTrue(s.isNew)
        assertEquals(cash, s.accountId)
        assertNull(s.categoryId)
        assertEquals("", s.note)
        assertEquals(Panel.AMOUNT, s.panel)
        assertEquals(1, db.txnDao().count())
    }

    @Test fun editExistingLoadsAndUpdatesSameRow() = runTest {
        db.txnDao().upsert(Fixtures.txn("t1", TxnType.EXPENSE, "2026-09-20T08:30:15.123", cash, 18_600, categoryId = food, note = "Večeře", fee = 7))
        val vm = vm("t1")
        val s = vm.loaded()
        assertFalse(s.isNew)
        assertEquals("186", s.amount.text)
        assertEquals(LocalDate.of(2026, 9, 20), s.date)
        assertEquals(Panel.NONE, s.panel)
        assertEquals("Večeře", s.note)
        assertEquals("🍜 Food", s.categoryName)
        assertFalse(s.dirty)
        vm.onKey(KeypadKey.Backspace)
        vm.onKey(KeypadKey.Digit('9'))
        assertTrue(vm.state.value.dirty)
        vm.save()
        assertEquals(EditEvent.Closed, vm.events.first())
        val t = db.txnDao().get("t1")!!
        assertEquals(18_900L, t.amount)
        assertEquals("2026-09-20T08:30:15.123", t.dateTime)
        assertEquals(7L, t.fee)
        assertEquals(1, db.txnDao().count())
    }

    @Test fun anEntryKeepsShowingItsDeletedCategory() = runTest {
        val categories = CategoryRepository(db, time)
        val lunch = categories.add(CategoryType.EXPENSE, "Lunch")
        db.txnDao().upsert(Fixtures.txn("t1", TxnType.EXPENSE, "2026-09-20T08:30:00.000", cash, 18_600, categoryId = lunch.id))
        categories.delete(lunch.id)
        val s = vm("t1").loaded()
        assertEquals("Lunch", s.categoryName)
        assertTrue(s.categories.none { it.id == lunch.id })
    }

    private suspend fun lunchOfFood(): String {
        val lunch = CategoryRepository(db, time).addSub(food, "Lunch")
        db.txnDao().upsert(Fixtures.txn("t1", TxnType.EXPENSE, "2026-09-20T08:30:00.000", cash, 18_600, categoryId = lunch.id))
        return lunch.id
    }

    @Test fun subcategoryOnShowsParentSlashSubAndOffShowsTheParent() = runTest {
        val lunch = lunchOfFood()
        val on = vm("t1", settings = AppSettings(mainCurrency = "CZK", subcategory = true)).loaded()
        assertEquals("🍜 Food/Lunch", on.categoryName)
        assertEquals(listOf(lunch), on.subcategories.map { it.id })
        val off = vm("t1").loaded()
        assertEquals("🍜 Food", off.categoryName)
    }

    @Test fun aReSaveKeepsTheStoredSubcategoryUnlessAnotherIsPicked() = runTest {
        val lunch = lunchOfFood()
        val keep = vm("t1").also { it.loaded() }
        keep.save()
        assertEquals(EditEvent.Closed, keep.events.first())
        assertEquals(lunch, db.txnDao().get("t1")!!.categoryId)
        val pick = vm("t1", settings = AppSettings(mainCurrency = "CZK", subcategory = true)).also { it.loaded() }
        pick.pickCategory(food)
        pick.save()
        assertEquals(EditEvent.Closed, pick.events.first())
        assertEquals(food, db.txnDao().get("t1")!!.categoryId)
    }

    @Test fun anEntryOfADeletedAccountStaysEditableButOtherEntriesDoNotOfferIt() = runTest {
        db.txnDao().upsert(Fixtures.txn("t1", TxnType.EXPENSE, "2026-09-20T08:30:00.000", bank, 18_600, categoryId = food))
        AccountRepository(db, time).delete(bank)
        assertTrue(vm("t1").loaded().accounts.any { it.id == bank })
        assertTrue(vm().loaded().accounts.none { it.id == bank })
    }

    @Test fun deleteTombstones() = runTest {
        db.txnDao().upsert(Fixtures.txn("t1", TxnType.EXPENSE, "2026-09-20T08:30:00.000", cash, 18_600, categoryId = food))
        val deleter = vm("t1").also { it.loaded() }
        deleter.delete()
        assertEquals(EditEvent.Closed, deleter.events.first())
        assertTrue(db.txnDao().get("t1")!!.deleted)
    }

    @Test fun switchingTypeClearsCategory() = runTest {
        val vm = vm().also { it.loaded() }
        vm.pickCategory(food)
        vm.setType(TxnType.INCOME)
        assertNull(vm.state.value.categoryId)
        assertEquals(5, vm.state.value.categories.size)
    }

    @Test fun bookmarkSavesTheShownEntry() = runTest {
        db.txnDao().upsert(Fixtures.txn("t", TxnType.EXPENSE, "2026-09-20T12:00:00.000", cash, 17_400, categoryId = food, note = "Oběd"))
        val vm = vm(txnId = "t").also { it.loaded() }
        vm.bookmark()
        assertEquals(EditEvent.Info("Added to Bookmarks"), vm.events.first())
        val saved = db.bookmarkDao().allRows().single()
        assertEquals("Oběd", saved.name)
        assertEquals(17_400L, saved.amount)
        assertEquals(food, saved.categoryId)
    }

    @Test fun aBookmarkPrefillsANewEntryDatedToday() = runTest {
        val b = BookmarkRepository(db, time).add(
            Fixtures.txn("old", TxnType.EXPENSE, "2026-01-02T08:00:00.000", cash, 17_400, categoryId = food, note = "Oběd"),
        )
        val vm = vm(bookmarkId = b.id)
        val s = vm.loaded()
        assertTrue(s.isNew)
        assertEquals(LocalDate.of(2026, 9, 26), s.date)
        assertEquals(17_400L, s.amount.minorUnits())
        assertEquals(food, s.categoryId)
        assertEquals(cash, s.accountId)
        assertEquals("Oběd", s.note)
        assertEquals(Panel.NONE, s.panel)
        vm.save()
        assertEquals(EditEvent.Closed, vm.events.first())
        val row = onlyRow()
        assertTrue(row.dateTime.startsWith("2026-09-26"))
        assertEquals(17_400L, row.amount)
    }

    @Test fun bookmarkWithDeletedCategoryLeavesItEmpty() = runTest {
        val b = BookmarkRepository(db, time).add(Fixtures.txn("old", TxnType.EXPENSE, "2026-01-02T08:00:00.000", cash, 17_400, categoryId = food))
        CategoryRepository(db, time).delete(food)
        AccountRepository(db, time).setHidden(cash, true)
        val vm = vm(bookmarkId = b.id)
        val s = vm.loaded()
        assertNull(s.categoryId)
        assertNull(s.accountId)
        assertEquals(17_400L, s.amount.minorUnits())
        vm.save()
        assertEquals(EditEvent.Error("Please select a category."), vm.events.first())
    }

    @Test fun noteSuggestionsFillOnlyTheNote() = runTest {
        db.txnDao().upsertAll(listOf(
            Fixtures.txn("a", TxnType.EXPENSE, "2026-09-01T12:00:00.000", cash, 100, note = "Oběd jídlo"),
            Fixtures.txn("b", TxnType.EXPENSE, "2026-09-02T12:00:00.000", cash, 100, note = "Oběd jídlo"),
            Fixtures.txn("c", TxnType.EXPENSE, "2026-09-03T12:00:00.000", cash, 100, note = "Nákup jídlo"),
        ))
        val vm = vm().also { it.loaded() }
        vm.setNote("jíd")
        assertEquals(listOf("Oběd jídlo", "Nákup jídlo"), vm.state.first { it.suggestions.isNotEmpty() }.suggestions)
        vm.pickSuggestion("Nákup jídlo")
        val s = vm.state.value
        assertEquals("Nákup jídlo", s.note)
        assertEquals(emptyList<String>(), s.suggestions)
        assertNull(s.categoryId)
        assertNull(s.accountId)
    }

    @Test fun autocompleteOffSuggestsNothing() = runBlocking {
        db.txnDao().upsert(Fixtures.txn("a", TxnType.EXPENSE, "2026-09-01T12:00:00.000", cash, 100, note = "Oběd jídlo"))
        val vm = EditTxnViewModel(
            null, null, TxnRepository(db, time), AccountRepository(db, time), CategoryRepository(db, time),
            FakeSettingsRepository(AppSettings("CZK", autocomplete = false)), time, BookmarkRepository(db, time), RepeatRepository(db, time),
            CurrencyRepository(db, FakeSettingsRepository(), time) {},
        ).also { vms += it }
        vm.state.first { it.loaded }
        vm.setNote("jíd")
        assertNull(withTimeoutOrNull(500) { vm.state.first { it.suggestions.isNotEmpty() } })
    }

    @Test fun aMonthlyRepeatFromThePastAddsTheMissedMonths() = runTest {
        val vm = vm(date = LocalDate.of(2026, 7, 26)).also { it.loaded() }
        vm.type("100")
        vm.pickCategory(food)
        vm.pickAccount(cash)
        vm.setRepeat(RepeatChoice.Repeat(RepeatFrequency.MONTHLY))
        vm.save()
        assertEquals(EditEvent.Closed, vm.events.first())
        val rows = db.txnDao().allRows().sortedBy { it.dateTime }
        assertEquals(listOf("2026-07-26", "2026-08-26", "2026-09-26"), rows.map { it.dateTime.take(10) })
        assertEquals(listOf(10_000L, 10_000L, 10_000L), rows.map { it.amount })
        val ruleId = rows.first().repeatRuleId!!
        assertTrue(rows.all { it.repeatRuleId == ruleId })
        assertEquals("2026-09-26", db.repeatRuleDao().get(ruleId)!!.lastDate)
    }

    @Test fun aThreeMonthPlanStoresTheFirstPartAndNumbersIt() = runTest {
        val vm = vm().also { it.loaded() }
        vm.type("1000")
        vm.pickCategory(food)
        vm.pickAccount(cash)
        vm.setRepeat(RepeatChoice.Installments(3))
        vm.save()
        assertEquals(EditEvent.Closed, vm.events.first())
        val entry = db.txnDao().get(onlyRow().id)!!
        assertEquals(33_334L, entry.amount)
        assertEquals(3, entry.installmentTotal)
        assertEquals(1, entry.installmentIndex)
        val rule = db.repeatRuleDao().get(entry.repeatRuleId!!)!!
        assertEquals(33_333L, rule.amount)
        assertEquals(3, rule.installments)
        assertEquals(RepeatFrequency.MONTHLY, rule.frequency)
    }

    @Test fun leavingExpenseDropsAPlanButKeepsARepeat() = runTest {
        val vm = vm().also { it.loaded() }
        vm.setRepeat(RepeatChoice.Installments(3))
        vm.setType(TxnType.INCOME)
        assertNull(vm.state.value.repeat)
        vm.setRepeat(RepeatChoice.Repeat(RepeatFrequency.WEEKLY))
        vm.setType(TxnType.EXPENSE)
        assertEquals(RepeatChoice.Repeat(RepeatFrequency.WEEKLY), vm.state.value.repeat)
        assertEquals("Every Week", vm.state.value.repeat!!.label)
        assertEquals("6 months", RepeatChoice.Installments(6).label)
    }

    @Test fun continueStartsTheNextEntryWithoutARepeat() = runTest {
        val vm = vm().also { it.loaded() }
        vm.type("50")
        vm.pickCategory(food)
        vm.pickAccount(cash)
        vm.setRepeat(RepeatChoice.Repeat(RepeatFrequency.WEEKLY))
        vm.save(continueAfter = true)
        vm.state.first { it.amount.isEmpty && it.repeat == null }
        assertEquals(1, db.repeatRuleDao().live().size)
    }

    /** A monthly rule "r" from Aug 26, caught up to the test's today (Sep 26): two entries. */
    private suspend fun monthlyRent(): RepeatRepository {
        val entry = Fixtures.txn("t", TxnType.EXPENSE, "2026-08-26T09:30:00.000", cash, 10_000, categoryId = food, note = "Nájem")
            .copy(repeatRuleId = "r")
        db.txnDao().upsert(entry)
        return RepeatRepository(db, time).also {
            it.create("r", entry, RepeatFrequency.MONTHLY)
            it.catchUp(LocalDate.of(2026, 9, 26))
        }
    }

    @Test fun ruleModeOpensTheTemplateOnItsNextDate() = runTest {
        monthlyRent()
        val s = vm(ruleId = "r").loaded()
        assertTrue(s.isRule)
        assertEquals(LocalDate.of(2026, 10, 26), s.date)
        assertEquals(LocalTime.of(9, 30), s.time)
        assertEquals(10_000L, s.amount.minorUnits())
        assertEquals(food, s.categoryId)
        assertEquals(cash, s.accountId)
        assertEquals("Nájem", s.note)
        assertEquals(RepeatChoice.Repeat(RepeatFrequency.MONTHLY), s.repeat)
    }

    @Test fun ruleModeSaveChangesOnlyLaterOccurrences() = runTest {
        val repeats = monthlyRent()
        val vm = vm(ruleId = "r").also { it.loaded() }
        repeat(3) { vm.onKey(KeypadKey.Backspace) }
        vm.type("200")
        vm.setRepeat(RepeatChoice.Repeat(RepeatFrequency.WEEKLY))
        vm.save()
        assertEquals(EditEvent.Closed, vm.events.first())
        val rule = repeats.get("r")!!
        assertEquals(20_000L, rule.amount)
        assertEquals(RepeatFrequency.WEEKLY, rule.frequency)
        assertEquals(listOf(10_000L, 10_000L), db.txnDao().allRows().map { it.amount }) // what was added stays
    }

    @Test fun ruleModeMovesTheNextDate() = runTest {
        val repeats = monthlyRent()
        val vm = vm(ruleId = "r").also { it.loaded() }
        vm.setDate(LocalDate.of(2026, 10, 3))
        vm.save()
        assertEquals(EditEvent.Closed, vm.events.first())
        val rule = repeats.get("r")!!
        assertEquals("2026-10-03", rule.startDate)
        val next = RepeatSchedule.next(rule.frequency, LocalDate.parse(rule.startDate), LocalDate.parse(rule.lastDate), rule.installments)
        assertEquals(LocalDate.of(2026, 10, 3), next!!.date)
    }

    @Test fun ruleModeDeleteStopsTheRuleAndKeepsItsEntries() = runTest {
        val repeats = monthlyRent()
        val vm = vm(ruleId = "r").also { it.loaded() }
        vm.delete()
        assertEquals(EditEvent.Closed, vm.events.first())
        assertNull(repeats.get("r"))
        assertEquals(2, db.txnDao().allRows().count { !it.deleted })
    }

    @Test fun theCalculatorResultFillsTheAmount() = runTest {
        val vm = vm().also { it.loaded() }
        vm.setAmount(20_550)
        assertEquals(20_550L, vm.state.value.amount.minorUnits())
    }

    /** A monthly rule "o" from Jul 26 whose last entry is Aug 26: the form shows Sep 26 (the test's today). */
    private suspend fun oldMonthlyRule(): RepeatRepository {
        val entry = Fixtures.txn("t", TxnType.EXPENSE, "2026-07-26T09:30:00.000", cash, 10_000, categoryId = food).copy(repeatRuleId = "o")
        db.txnDao().upsert(entry)
        return RepeatRepository(db, time).also {
            it.create("o", entry, RepeatFrequency.MONTHLY)
            it.catchUp(LocalDate.of(2026, 8, 26))
        }
    }

    @Test fun ruleModeFrequencyChangeRestartsOnTheShownDate() = runTest {
        val repeats = oldMonthlyRule()
        val vm = vm(ruleId = "o").also { it.loaded() }
        assertEquals(LocalDate.of(2026, 9, 26), vm.state.value.date)
        vm.setRepeat(RepeatChoice.Repeat(RepeatFrequency.DAILY))
        vm.save()
        assertEquals(EditEvent.Closed, vm.events.first())
        // Only the shown date is added, not every day since Aug 26.
        assertEquals(listOf("2026-07-26", "2026-08-26", "2026-09-26"), db.txnDao().allRows().map { it.dateTime.take(10) }.sorted())
        assertEquals("2026-09-26", repeats.get("o")!!.startDate)
    }

    @Test fun ruleModeRefusesADateItAlreadyCovered() = runTest {
        val repeats = oldMonthlyRule()
        val vm = vm(ruleId = "o").also { it.loaded() }
        vm.setDate(LocalDate.of(2026, 7, 26))
        vm.save()
        assertEquals(EditEvent.Error("Please pick a date after 8.26."), vm.events.first())
        assertEquals(2, db.txnDao().allRows().size)
        assertEquals("2026-07-26", repeats.get("o")!!.startDate)
    }

    @Test fun fromAccountAsksForTheAccountThenCategoryThenAmount() = runTest {
        val vm = vm(settings = AppSettings("CZK", inputOrder = InputOrder.ACCOUNT))
        assertEquals(Panel.ACCOUNT, vm.loaded().panel)
        vm.pickAccount(cash)
        assertEquals(Panel.CATEGORY, vm.state.value.panel)
        vm.pickCategory(food)
        assertEquals(Panel.AMOUNT, vm.state.value.panel)
        vm.type("50")
        vm.onAmountDone()
        assertEquals(Panel.NONE, vm.state.value.panel)
    }

    @Test fun fromAccountTransfersGoFromToThenAmount() = runTest {
        val vm = vm(settings = AppSettings("CZK", inputOrder = InputOrder.ACCOUNT)).also { it.loaded() }
        vm.setType(TxnType.TRANSFER)
        vm.openPanel(Panel.ACCOUNT)
        vm.pickAccount(cash)
        assertEquals(Panel.TO_ACCOUNT, vm.state.value.panel)
        vm.pickAccount(bank)
        assertEquals(Panel.AMOUNT, vm.state.value.panel)
    }

    @Test fun aPickGoesToTheNextUnfilledFieldWrappingToTheTop() = runTest {
        val vm = vm().also { it.loaded() }
        vm.openPanel(Panel.ACCOUNT)
        vm.pickAccount(cash)
        assertEquals(Panel.AMOUNT, vm.state.value.panel)
        vm.type("50")
        vm.onAmountDone()
        assertEquals(Panel.CATEGORY, vm.state.value.panel)
        vm.pickCategory(food)
        assertEquals(Panel.NONE, vm.state.value.panel)
    }

    @Test fun transferToAccountFirstAsksForFromThenAmount() = runTest {
        val vm = vm().also { it.loaded() }
        vm.setType(TxnType.TRANSFER)
        vm.openPanel(Panel.TO_ACCOUNT)
        vm.pickAccount(bank)
        assertEquals(Panel.AMOUNT, vm.state.value.panel)
        vm.onAmountDone()
        assertEquals(Panel.ACCOUNT, vm.state.value.panel)
    }

    @Test fun fromAmountKeepsTheUsualOrder() = runTest {
        val vm = vm()
        assertEquals(Panel.AMOUNT, vm.loaded().panel)
        vm.type("50")
        vm.onAmountDone()
        assertEquals(Panel.CATEGORY, vm.state.value.panel)
        vm.pickCategory(food)
        assertEquals(Panel.ACCOUNT, vm.state.value.panel)
        vm.pickAccount(cash)
        assertEquals(Panel.NONE, vm.state.value.panel)
    }

    @Test fun timeInputNoneHidesTheTimeButStoresTheCurrentOne() = runTest {
        val vm = vm(settings = AppSettings("CZK", timeInput = TimeInput.NONE_DESC))
        assertEquals(false, vm.loaded().timeShown)
        vm.type("50")
        vm.pickCategory(food)
        vm.pickAccount(cash)
        vm.save()
        assertEquals(EditEvent.Closed, vm.events.first())
        assertEquals("2026-09-26T10:00:00.000", onlyRow().dateTime)
    }

    @Test fun aShortcutCanOpenANewEntryOnIncome() = runTest {
        val v = vm(type = TxnType.INCOME)
        assertEquals(TxnType.INCOME, v.loaded().type)
    }
}
