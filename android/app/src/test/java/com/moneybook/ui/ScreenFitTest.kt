package com.moneybook.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneybook.data.AppSettings
import com.moneybook.data.StyleMode
import com.moneybook.data.TimeInput
import com.moneybook.data.db.BookmarkRow
import com.moneybook.data.db.MemoEntity
import com.moneybook.data.db.RepeatRuleRow
import com.moneybook.data.db.TxnRow
import com.moneybook.domain.BudgetLine
import com.moneybook.domain.BudgetYearPoint
import com.moneybook.ui.budget.BudgetAddContent
import com.moneybook.ui.budget.BudgetAddState
import com.moneybook.ui.budget.BudgetDetailContent
import com.moneybook.ui.budget.BudgetDetailState
import com.moneybook.ui.budget.BudgetEditContent
import com.moneybook.ui.budget.BudgetEditState
import com.moneybook.ui.budget.BudgetMonthlyContent
import com.moneybook.ui.budget.BudgetMonthlyState
import com.moneybook.ui.budget.BudgetPanel
import com.moneybook.ui.budget.BudgetSettingContent
import com.moneybook.ui.budget.BudgetSettingItem
import com.moneybook.domain.TOTAL_BUDGET
import com.moneybook.domain.AccountGroupItem
import com.moneybook.domain.AccountItem
import com.moneybook.domain.AccountPeriodSummary
import com.moneybook.domain.AccountsOverview
import com.moneybook.domain.Calculator
import com.moneybook.domain.CalendarCell
import com.moneybook.domain.CardStatus
import com.moneybook.domain.CategorySlice
import com.moneybook.domain.DateRange
import com.moneybook.domain.DayGroup
import com.moneybook.domain.FilterAccount
import com.moneybook.domain.FilterCategory
import com.moneybook.domain.FilterGroup
import com.moneybook.domain.FilterOptions
import com.moneybook.domain.Mom
import com.moneybook.domain.MonthRow
import com.moneybook.domain.PasscodeEntry
import com.moneybook.domain.PasscodeMode
import com.moneybook.domain.PeriodLine
import com.moneybook.domain.SearchFilter
import com.moneybook.domain.SearchTotals
import com.moneybook.domain.StatsPeriod
import com.moneybook.domain.TotalBreakdown
import com.moneybook.domain.Totals
import com.moneybook.domain.TxnFilter
import com.moneybook.domain.WeekRow
import com.moneybook.domain.WeekendShift
import com.moneybook.domain.WidgetSummary
import com.moneybook.shared.model.AccountGroupType
import com.moneybook.shared.model.CategoryType
import com.moneybook.shared.model.RepeatFrequency
import com.moneybook.shared.model.TxnType
import com.moneybook.shared.money.CurrencySpec
import com.moneybook.ui.accounts.AccountDetailContent
import com.moneybook.ui.accounts.AccountDetailState
import com.moneybook.ui.accounts.AccountsContent
import com.moneybook.ui.accounts.AccountsUiState
import com.moneybook.ui.accounts.DetailLine
import com.moneybook.ui.accounts.DetailTab
import com.moneybook.ui.accounts.PayPreset
import com.moneybook.ui.accounts.TotalStatsContent
import com.moneybook.ui.accounts.TotalStatsState
import com.moneybook.ui.bookmarks.BookmarksContent
import com.moneybook.ui.bookmarks.BookmarksState
import com.moneybook.ui.calc.CalculatorScreen
import com.moneybook.ui.common.ChoiceDialog
import com.moneybook.ui.common.DayHeader
import com.moneybook.ui.common.FilledButton
import com.moneybook.ui.common.FormRow
import com.moneybook.ui.common.MbTopBar
import com.moneybook.ui.common.MonthNavigator
import com.moneybook.ui.common.OutlinedButtonBox
import com.moneybook.ui.common.SubTabs
import com.moneybook.ui.common.SummaryBar
import com.moneybook.ui.common.TxnRowItem
import com.moneybook.ui.common.ValueText
import com.moneybook.ui.common.incomeExpenseTotal
import com.moneybook.domain.AmountInput
import com.moneybook.shared.money.Fx
import com.moneybook.ui.common.specOf
import com.moneybook.data.CurrencyState
import com.moneybook.data.db.CurrencyEntity
import com.moneybook.shared.money.SymbolPosition
import com.moneybook.ui.more.AccountsSettingContent
import com.moneybook.ui.more.CurrencyEditContent
import com.moneybook.ui.more.CurrencyListContent
import com.moneybook.ui.more.CurrencyListMode
import com.moneybook.ui.more.MainCurrencyContent
import com.moneybook.ui.more.SubCurrencyContent
import com.moneybook.ui.edit.AmountKeypad
import com.moneybook.ui.edit.AmountRows
import com.moneybook.ui.edit.EditTxnState
import com.moneybook.ui.edit.RateSheet
import com.moneybook.ui.edit.RateSheetOverlay
import java.math.BigDecimal
import com.moneybook.ui.edit.DateRow
import com.moneybook.ui.edit.DayKeypad
import com.moneybook.ui.edit.GridPickerPanel
import com.moneybook.ui.edit.InstallmentScreen
import com.moneybook.ui.edit.RepeatPickScreen
import com.moneybook.ui.edit.TypeSegments
import com.moneybook.ui.lock.PasscodeContent
import com.moneybook.ui.memo.MemoEditContent
import com.moneybook.ui.memo.MemoEditState
import com.moneybook.ui.more.AlarmContent
import com.moneybook.ui.more.CardExpenseConfigContent
import com.moneybook.ui.more.CARRY_OVER_HELP
import com.moneybook.ui.more.CategorySettingContent
import com.moneybook.ui.more.DeletedAccountsContent
import com.moneybook.ui.more.DeletedGroupsContent
import com.moneybook.ui.more.SUBCATEGORY_HELP
import com.moneybook.ui.more.TransferExpenseContent
import com.moneybook.ui.more.TransferExpenseState
import com.moneybook.data.db.DeletedGroupRow
import com.moneybook.ui.edit.TwoPaneCategoryPanel
import com.moneybook.ui.more.ConfigurationContent
import com.moneybook.ui.more.MonthlyStartDateContent
import com.moneybook.ui.more.MoreScreen
import com.moneybook.ui.more.QuickAddSample
import com.moneybook.ui.more.RepeatSettingContent
import com.moneybook.ui.more.RepeatSettingState
import com.moneybook.ui.more.StyleContent
import com.moneybook.ui.more.SyncContent
import com.moneybook.ui.more.SyncUiState
import com.moneybook.ui.more.WidgetSettingsContent
import com.moneybook.ui.more.repeatItems
import com.moneybook.ui.nav.BottomNav
import com.moneybook.ui.nav.MainTab
import com.moneybook.ui.search.SearchContent
import com.moneybook.ui.search.SearchUiState
import com.moneybook.ui.stats.CategoryDetailContent
import com.moneybook.ui.stats.CategoryDetailState
import com.moneybook.ui.stats.StatsContent
import com.moneybook.ui.stats.StatsUiState
import com.moneybook.testutil.testPhotos
import com.moneybook.ui.edit.PhotoViewer
import com.moneybook.ui.edit.PhotoStrip
import com.moneybook.ui.edit.CopyDialog
import com.moneybook.data.CopyDateBy
import com.moneybook.data.DeviceSettings
import com.moneybook.domain.LockDelay
import com.moneybook.ui.more.PasscodeSettingsContent
import com.moneybook.ui.theme.MoneyBookTheme
import com.moneybook.ui.trans.CalendarTab
import com.moneybook.ui.trans.MonthlyTab
import com.moneybook.ui.trans.NoteTab
import com.moneybook.ui.trans.TotalTab
import com.moneybook.ui.trans.TransFilterPanel
import com.moneybook.widget.SummaryData
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Every M1 screen must fit on a narrow phone and at the largest system font size: rows grow
 * and long numbers shrink, but no text may be cut off. An ellipsis on one line (a long note
 * or category) is Realbyte's intended truncation and is allowed.
 * Native graphics measure text for real; the legacy mode counts one pixel per character.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenFitTest {
    @get:Rule val rule = createComposeRule()
    private val czk = CurrencySpec.of("CZK")
    private val big = 1_234_567_890L // 12,345,678.90

    @Test @Config(qualifiers = "w320dp-h640dp-xhdpi")
    fun narrowPhoneFitsLargeAmounts() = assertScreensFit(fontScale = 1f, calendarAmount = 123_456_700L)

    @Test @Config(qualifiers = "w360dp-h780dp-xxhdpi")
    fun largestFontFitsEverywhere() = assertScreensFit(fontScale = 2f, calendarAmount = 25_000_000L)

    @Test @Config(qualifiers = "w360dp-h780dp-xxhdpi")
    fun formLabelsStayOnOneLineAtLargestFont() {
        show(2f) { FormScreen() }
        val labels = listOf("Date", "Amount", "Category", "Account", "Note")
        val wrapped = textLayouts().filter { it.layoutInput.text.text in labels && it.lineCount > 1 }.map { it.layoutInput.text.text }
        assertEquals(emptyList<String>(), wrapped)
    }

    @Test @Config(qualifiers = "w360dp-h780dp-xxhdpi")
    fun categoryNamesShrinkInsteadOfTruncatingAtLargestFont() {
        val names = listOf("👬 Social Life", "🚕 Transport", "🧘 Health/Fitness", "předplatné + členské poplatky")
        show(2f) { GridPickerPanel("Category", names.map { it to it }, {}, {}) }
        val truncated = textLayouts().filter { it.layoutInput.text.text in names && it.isEllipsized() }.map { it.layoutInput.text.text }
        assertEquals(emptyList<String>(), truncated)
    }

    @Test @Config(qualifiers = "w360dp-h780dp-xxhdpi")
    fun balancesKeepTheirSizeWhenTheRowHasRoomAtLargestFont() {
        val overview = AccountsOverview(41_280_000, 0, listOf(
            AccountGroupItem("g", "Cash", AccountGroupType.CASH, 41_280_000, listOf(AccountItem("c", "Cash", 41_280_000, "CZK"))),
        ))
        show(2f) { AccountsContent(AccountsUiState(czk, overview), {}, {}) }
        val shrunk = textLayouts().filter { it.layoutInput.text.text == "412,800.00Kč" && it.isShrunk() }.map { it.layoutInput.text.text }
        assertEquals(emptyList<String>(), shrunk)
    }

    @Test @Config(qualifiers = "w360dp-h780dp-xxhdpi")
    fun longTitlesLeaveTheTopBarArrowsOnScreen() {
        val state = CategoryDetailState("🧾 předplatné + členské poplatky", StatsPeriod.Week(LocalDate.of(2026, 9, 20)), czk)
        show(1f) { CategoryDetailContent(state, {}, {}, {}, {}) }
        val width = rule.onRoot().fetchSemanticsNode().boundsInRoot.right
        for (arrow in listOf("Previous", "Next")) {
            val b = rule.onNodeWithContentDescription(arrow).fetchSemanticsNode().boundsInRoot
            assertTrue("$arrow at ${b.left}..${b.right} of $width", b.width > 0f && b.right <= width + 0.5f)
        }
    }

    private fun assertScreensFit(fontScale: Float, calendarAmount: Long) {
        var screen by mutableIntStateOf(0)
        val screens = screens(calendarAmount)
        show(fontScale) { screens[screen].second() }
        val failures = screens.indices.mapNotNull { i ->
            screen = i
            rule.waitForIdle()
            val bad = textLayouts().filter { it.isClipped() || it.breaksWord() }.map { it.layoutInput.text.text }
            if (bad.isEmpty()) null else "${screens[i].first}: $bad"
        }
        assertEquals(emptyList<String>(), failures)
    }

    private fun screens(calendarAmount: Long): List<Pair<String, @Composable () -> Unit>> = listOf(
        "trans header" to {
            Column {
                MbTopBar(title = { MonthNavigator("Sep 2026", {}, {}) })
                SubTabs(listOf("Daily", "Calendar", "Monthly", "Total"), 0) {}
                SummaryBar(incomeExpenseTotal(Totals(big, big / 2), czk))
            }
        },
        "daily" to {
            Column {
                DayHeader(DayGroup(LocalDate.of(2026, 9, 26), Totals(big, big), emptyList()), czk)
                TxnRowItem(row(TxnType.EXPENSE, "Zelenina z trhu a pití na celý víkend", "🍜 Food"), czk) {}
                TxnRowItem(row(TxnType.INCOME, "Výplata", "💰 Salary"), czk) {}
                TxnRowItem(row(TxnType.EXPENSE, "S fotkou", "🍜 Food").copy(hasPhoto = true), czk) {}
                TxnRowItem(row(TxnType.TRANSFER, "", null, to = "Bank"), czk) {}
            }
        },
        "calendar" to {
            val cells = (0 until 42).map { i ->
                val date = LocalDate.of(2026, 8, 30).plusDays(i.toLong())
                CalendarCell(date, inPeriod = date.monthValue == 9, isToday = date.dayOfMonth == 27, totals = Totals(calendarAmount, calendarAmount / 3))
            }
            CalendarTab(cells, DayOfWeek.SUNDAY, czk) {}
        },
        "monthly" to {
            val sep = DateRange(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 1))
            val week = WeekRow(DateRange(LocalDate.of(2026, 9, 20), LocalDate.of(2026, 9, 27)), Totals(big, big / 3), isCurrent = true)
            MonthlyTab(
                listOf(
                    MonthRow(YearMonth.of(2026, 9), sep, Totals(big, big / 3), listOf(week, week.copy(range = DateRange(LocalDate.of(2026, 9, 13), LocalDate.of(2026, 9, 20)), isCurrent = false))),
                    MonthRow(YearMonth.of(2026, 8), sep, Totals(big, big / 3), emptyList()),
                ),
                czk,
            ) {}
        },
        "accounts" to {
            val items = listOf(AccountItem("c", "Cash", big, "CZK"), AccountItem("s", "Spořicí účet u banky", -big, "CZK"))
            val overview = AccountsOverview(big, big, listOf(AccountGroupItem("g", "Accounts", AccountGroupType.ACCOUNTS, 0, items)))
            AccountsContent(AccountsUiState(czk, overview), {}, {})
        },
        "accounts setting" to {
            val items = listOf(AccountItem("c", "Cash", big, "CZK"), AccountItem("s", "Spořicí účet u banky s dlouhým názvem", -big, "CZK", hidden = true))
            val overview = AccountsOverview(0, 0, listOf(AccountGroupItem("g", "Accounts and other long group name", AccountGroupType.ACCOUNTS, 0, items)))
            AccountsSettingContent(AccountsUiState(czk, overview, overview), {}, {}, {})
        },
        "card accounts" to {
            val card = AccountItem("v", "Kreditní karta Visa", -big, "CZK", card = CardStatus(payable = big, outstanding = big / 2, overdue = big / 3))
            val overview = AccountsOverview(0, big, listOf(AccountGroupItem("g", "Card", AccountGroupType.CARD, -big, listOf(card))))
            AccountsContent(AccountsUiState(czk, overview), {}, {})
        },
        "account detail" to {
            val sep = DateRange(LocalDate.of(2026, 9, 20), LocalDate.of(2026, 10, 20))
            val rows = listOf(row(TxnType.EXPENSE, "Zelenina z trhu a pití na celý víkend", "🍜 Food"))
            AccountDetailContent(
                AccountDetailState(
                    name = "Kreditní karta Visa", month = YearMonth.of(2026, 10), currency = czk, period = sep,
                    summary = AccountPeriodSummary(big, big), balance = -big, isCard = true, pay = PayPreset(null, big),
                    dayGroups = listOf(DayGroup(LocalDate.of(2026, 9, 26), Totals(big, big), rows)), balances = mapOf(rows[0].id to -big),
                ),
                {}, {}, {}, onAdd = {}, onPay = {},
            )
        },
        "account detail monthly" to {
            val line = PeriodLine(DateRange(LocalDate.of(2026, 11, 20), LocalDate.of(2026, 12, 20)), big, big, -big)
            AccountDetailContent(
                AccountDetailState(
                    name = "Card", tab = DetailTab.MONTHLY, month = YearMonth.of(2026, 10), currency = czk, period = line.range,
                    lines = listOf(DetailLine("Dec", YearMonth.of(2026, 12), 2026, line), DetailLine("Nov", YearMonth.of(2026, 11), 2026, line)),
                ),
                {}, {}, {}, onAdd = {}, onPay = null,
            )
        },
        "day keypad" to { DayKeypad("Settlement Date", {}, {}, {}) },
        "card expenses display config" to { CardExpenseConfigContent(onBack = {}) {} },
        "form" to { FormScreen() },
        "form with 3 photos" to {
            Column { FormScreen(); PhotoStrip(listOf("a.jpg", "b.jpg", "c.jpg"), testPhotos(), {}, decode = { null }) }
        },
        "copy dialog" to { CopyDialog(CopyDateBy.TODAY, {}, {}, {}) },
        "keypad" to { AmountKeypad({}, {}, {}) },
        "keypad with currency strip" to {
            AmountKeypad({}, {}, {}, onCalc = {}, strip = listOf("CZK", "EUR", "USD", "JPY").map(::specOf), selected = "EUR", onGlobe = {})
        },
        "rate sheet" to {
            RateSheetOverlay(RateSheet(AmountInput.of(1_234_567_890, specOf("CZK")), "25.1234567891"), specOf("CZK"), {}, {}, {}, {})
        },
        "sub currency list" to {
            val rows = listOf(
                CurrencyEntity("EUR", "€", SymbolPosition.START, 2, "25.1234567891", 0, 0),
                CurrencyEntity("XOF", "CFA", SymbolPosition.START, 8, "0.0381234567", 1, 0),
            )
            SubCurrencyContent(CurrencyState("CZK", rows), {}, {}, {}, {}, {})
        },
        "sub currency empty" to { SubCurrencyContent(CurrencyState("CZK", emptyList()), {}, {}, {}, {}, {}) },
        "currency list" to { CurrencyListContent(CurrencyListMode.SUB, "CZK", onBack = {}) },
        "main currency list" to { CurrencyListContent(CurrencyListMode.MAIN, "CZK", onBack = {}) },
        "currency edit" to {
            CurrencyEditContent(specOf("XOF").copy(display = 8), specOf("CZK"), BigDecimal("25.1234567891"), {}) { _, _, _ -> }
        },
        "main currency setting" to { MainCurrencyContent(specOf("CZK").copy(display = 8), {}, {}) { _, _ -> } },
        "transfer with fees" to {
            val fx = Fx("CZK", mapOf("EUR" to BigDecimal("25.1")))
            val s = EditTxnState(
                date = LocalDate.of(2026, 10, 8), time = LocalTime.of(12, 15), type = TxnType.TRANSFER,
                amount = AmountInput.of(12_345_678_90, specOf("EUR")), currency = specOf("EUR"), mainCurrency = specOf("CZK"), fx = fx,
                fee = AmountInput.of(1_234_56, specOf("CZK")), feeCurrency = specOf("CZK"),
            )
            Column { AmountRows(s, Color.Black, {}, {}, {}, {}) }
        },
        "category panel" to {
            val names = listOf("🍜 Food", "👬 Social Life", "🧘 Health/Fitness", "předplatné + členské poplatky", "entertainment", "charita")
            GridPickerPanel("Category", names.map { it to it }, {}, {})
        },
        "category settings" to {
            val names = listOf("🍜 Food", "👬🏻 Social Life", "předplatné + členské poplatky")
            CategorySettingContent(
                CategoryType.EXPENSE, names.mapIndexed { i, n -> com.moneybook.testutil.Fixtures.category("c$i", name = n, sortOrder = i) },
                {}, {}, {}, {}, {},
            )
        },
        "category settings with subcategories" to {
            val top = listOf("🍜 Food", "předplatné + členské poplatky").mapIndexed { i, n -> com.moneybook.testutil.Fixtures.category("c$i", name = n, sortOrder = i) }
            val subs = listOf("Restaurace a kavárny", "Zelenina z trhu", "Pití na celý víkend").mapIndexed { i, n ->
                com.moneybook.testutil.Fixtures.category("s$i", name = n, parentId = "c0", sortOrder = i)
            }
            CategorySettingContent(CategoryType.EXPENSE, top, {}, {}, {}, {}, {}, subcategory = true, onSubcategory = {}, subs = subs)
        },
        "subcategory list" to {
            val subs = listOf("Restaurace a kavárny s dlouhým názvem", "🧘 Health/Fitness").mapIndexed { i, n ->
                com.moneybook.testutil.Fixtures.category("s$i", name = n, parentId = "c0", sortOrder = i)
            }
            CategorySettingContent(CategoryType.EXPENSE, subs, {}, {}, {}, {}, {}, title = "Subcategory")
        },
        "two-pane category panel" to {
            val top = listOf("🍜 Food", "předplatné + členské poplatky", "entertainment").mapIndexed { i, n ->
                com.moneybook.testutil.Fixtures.category("c$i", name = n, sortOrder = i)
            }
            val subs = listOf("Restaurace a kavárny", "Zelenina z trhu a pití").mapIndexed { i, n ->
                com.moneybook.testutil.Fixtures.category("s$i", name = n, parentId = "c0", sortOrder = i)
            }
            TwoPaneCategoryPanel(top, subs, "s1", {}, {})
        },
        "deleted accounts" to {
            val groups = listOf(AccountGroupItem("g", "Accounts and other long group name", AccountGroupType.ACCOUNTS, 0, listOf(AccountItem("d", "Spořicí účet u banky s dlouhým názvem", 0, "CZK", hidden = true))))
            DeletedAccountsContent(groups, {}, {}, {})
        },
        "deleted account group" to {
            DeletedGroupsContent(listOf(DeletedGroupRow("g", "Spořicí skupina s velmi dlouhým názvem", AccountGroupType.ACCOUNTS, 0, 3)), {}, {}, {})
        },
        "transfer-expense setting" to {
            val items = listOf(AccountItem("a", "Spořicí účet u banky s dlouhým názvem", 0, "CZK"), AccountItem("b", "Investice", 0, "CZK"))
            TransferExpenseContent(
                TransferExpenseState(listOf(AccountGroupItem("g", "Savings and other long group name", AccountGroupType.SAVINGS, 0, items)), setOf("a")), {}, { _, _ -> },
            )
        },
        "carry-over dialog" to { ChoiceDialog("Carry-over Setting", listOf("OFF", "ON"), 1, onDismiss = {}, description = CARRY_OVER_HELP) {} },
        "subcategory dialog" to { ChoiceDialog("Subcategory Setting", listOf("OFF", "ON"), 0, onDismiss = {}, description = SUBCATEGORY_HELP) {} },
        "stats" to {
            val slices = listOf(CategorySlice("a", "předplatné + členské poplatky", big, big + big / 1000), CategorySlice("b", "🍜 Food", big / 1000, big + big / 1000))
            StatsContent(StatsUiState(StatsPeriod.Month(YearMonth.of(2026, 9)), TxnType.EXPENSE, czk, Totals(big, big), slices))
        },
        "stats period" to {
            StatsContent(StatsUiState(StatsPeriod.custom(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)), TxnType.INCOME, czk, Totals(big, big)))
        },
        "total" to {
            val budgets = listOf(
                BudgetLine(null, TOTAL_BUDGET, big, big / 2, synthetic = true),
                BudgetLine("a", "předplatné + členské poplatky", big / 2, big),
                BudgetLine("b", "🍜 Food", 5_000_00, 1_200_00),
            )
            TotalTab(TotalBreakdown(999, big, big, big), DateRange(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 1)), czk, budgets, 0.97f, {}, {}) {
                OutlinedButtonBox("Export data to Excel", Modifier.fillMaxWidth()) {}
            }
        },
        "budget setting" to {
            BudgetSettingContent(
                listOf(
                    BudgetSettingItem(null, "Total Budget", big), BudgetSettingItem("a", "předplatné + členské poplatky", big / 2),
                    BudgetSettingItem("b", "🍜 Food", 5_000_00),
                ),
                czk, {}, {}, {},
            )
        },
        "add budget" to {
            BudgetAddContent(
                BudgetAddState(categoryId = "a", categoryName = "předplatné + členské poplatky", amount = AmountInput.of(big, czk), panel = BudgetPanel.AMOUNT),
                czk, {}, {}, {}, {}, {}, {}, {},
            )
        },
        "add budget category" to {
            BudgetAddContent(
                BudgetAddState(categories = listOf("all" to "· All", "a" to "předplatné + členské poplatky", "b" to "🍜 Food"), categoryName = "· All"),
                czk, {}, {}, {}, {}, {}, {}, {},
            )
        },
        "budget monthly" to {
            val months = (12 downTo 1).map { YearMonth.of(2026, it) to big / it }
            BudgetMonthlyContent(
                BudgetMonthlyState("předplatné + členské poplatky", 2026, big, months, YearMonth.of(2026, 9)), czk, {}, {}, {}, {}, {},
            )
        },
        "budget edit" to {
            BudgetEditContent(BudgetEditState("předplatné + členské poplatky", "September 2026", AmountInput.of(big, czk)), 202609, czk, {}, {}, {})
        },
        "budget detail" to {
            val points = (1..12).map { BudgetYearPoint("M$it", 202600 + it, big / it, big / 2) }
            val day = DayGroup(LocalDate.of(2026, 9, 26), Totals(0, big), listOf(row(TxnType.EXPENSE, "Zelenina z trhu a pití na celý víkend", "🍜 Food")))
            BudgetDetailContent(
                BudgetDetailState("předplatné + členské poplatky", YearMonth.of(2026, 9), big / 2, big, points, listOf(day)), czk, {}, {}, {}, {}, {},
            )
        },
        "total stats" to {
            val months = (4..9).map { YearMonth.of(2026, it) }
            TotalStatsContent(TotalStatsState(YearMonth.of(2026, 9), czk, months, months.map { big }, months.map { Totals(big, big) }), {}, {}, {})
        },
        "filter panel" to {
            TransFilterPanel(
                "Sep 2026",
                FilterOptions(
                    income = listOf(FilterCategory("a", "předplatné + členské poplatky", big)),
                    groups = listOf(FilterGroup("Accounts", listOf(FilterAccount("b", "Spořicí účet s dlouhým názvem", big, big)))),
                    month = Totals(big, big), selected = Totals(big / 2, big),
                ),
                TxnFilter(), czk,
            )
        },
        "note tab" to {
            NoteTab(listOf(MemoEntity(id = "m", date = "2026-09-26", title = "předplatné + členské poplatky za celý rok", text = "Zaplatit do konce měsíce, jinak penále", pinned = true, updatedAt = 0))) {}
        },
        "memo editor" to {
            MemoEditContent(MemoEditState(loaded = true, isNew = false, date = LocalDate.of(2026, 9, 26), title = "předplatné + členské poplatky", text = "Zaplatit do konce měsíce", paletteOpen = true))
        },
        "bookmarks" to {
            BookmarksContent(
                BookmarksState(listOf(BookmarkRow("b", TxnType.EXPENSE, big, "CZK", "předplatné + členské poplatky", "Spořicí účet", null, "🍜 Food", null)), czk),
                onBack = {}, onUse = {}, onDelete = {},
            )
        },
        "search" to {
            val found = TxnRow(
                id = "a", type = TxnType.EXPENSE, dateTime = "2026-09-26T12:00:00.000", amount = big, toAmount = null, amountMain = big,
                currency = "CZK", fee = 0, note = "předplatné + členské poplatky", description = "", accountId = "x",
                accountName = "Spořicí účet s dlouhým názvem", toAccountId = null, toAccountName = null, categoryId = "c",
                categoryName = "🧘 Health/Fitness", parentCategoryName = null,
            )
            SearchContent(
                SearchUiState(
                    today = LocalDate.of(2026, 9, 26), filter = SearchFilter(text = "jídlo"), panelOpen = true, minText = "1000", maxText = "5000",
                    results = listOf(found), totals = SearchTotals(big, big, big), currency = czk,
                ),
                autoFocus = false,
            )
        },
        "repeat list" to { RepeatPickScreen(RepeatFrequency.EVERY_6_MONTHS, includeNothing = true, onPick = {}, onBack = {}) },
        "photo viewer" to { PhotoViewer(listOf("a.jpg", "b.jpg", "c.jpg"), setOf("a.jpg"), 0, testPhotos(), {}, {}, decode = { null }) },
        "installment" to { InstallmentScreen(12, onSave = {}, onBack = {}) },
        "repeat setting" to {
            val plan = RepeatRuleRow(
                id = "p", frequency = RepeatFrequency.MONTHLY, startDate = "2026-08-10", lastDate = "2026-09-10", installments = 12,
                type = TxnType.EXPENSE, amount = big, currency = "CZK", note = "předplatné + členské poplatky", accountName = "Cash",
                toAccountName = null, categoryName = "🧘 Health/Fitness", parentCategoryName = null,
            )
            RepeatSettingContent(RepeatSettingState(repeatItems(listOf(plan, plan.copy(id = "e", frequency = RepeatFrequency.END_OF_MONTH, installments = null)))), {}, {}, {})
        },
        "calculator" to { CalculatorScreen(Calculator("12345678901234÷3"), 2, onDone = {}, onClose = {}) },
        "configuration" to { ConfigurationContent(AppSettings("CZK", monthStartDay = 31, timeInput = TimeInput.SHOW_DESC)) },
        "option dialog" to {
            ChoiceDialog(
                "Color Setting", listOf("Set. A", "Set. B"), 0, onDismiss = {},
                description = "By default, income is shown in 'Blue' color and expenses in 'Red' color.",
            ) {}
        },
        "monthly start date" to { MonthlyStartDateContent(31, WeekendShift.FOLLOWING_MONDAY, onSave = { _, _ -> }, onBack = {}) },
        "style" to { StyleContent(StyleMode.DARK, onPick = {}, onBack = {}) },
        "passcode" to {
            PasscodeContent(
                PasscodeEntry(PasscodeMode.SET, digits = "12", error = "Passcodes do not match"), "Set Passcode",
                "If you forget it, only clearing the app's data removes it.", onBack = {}, onDigit = {}, onBackspace = {},
            )
        },
        "passcode settings" to {
            PasscodeSettingsContent(DeviceSettings(passcodeHash = "h", lockDelaySeconds = 900), canAuthenticate = true, {}, {}, {}, {}, {})
        },
        "request passcode dialog" to { ChoiceDialog("Request Passcode", LockDelay.options.map { it.second }, 3, onDismiss = {}) {} },
        "alarm" to { AlarmContent(true, 21 * 60, onToggle = {}, onTime = {}, onBack = {}) },
        "quick add dialog" to {
            ChoiceDialog("Quick add", listOf("OFF", "ON"), 0, onDismiss = {}, description = "Displays quick add feature in the notification bar.", illustration = { QuickAddSample() }) {}
        },
        "widget settings" to {
            WidgetSettingsContent(
                SummaryData(WidgetSummary(Totals(income = 0, expense = 2_890_300), Mom(0, null, null), Mom(990_600, 52, good = false)), CurrencySpec.of("CZK")),
                StyleMode.SYSTEM, 100, onBackground = {}, onOpacity = {}, onBack = {},
            )
        },
        "bottom nav" to { BottomNav(MainTab.TRANS) {} },
        "more" to { MoreScreen(onOpenConfiguration = {}, onOpenAccountSettings = {}, onOpenSync = {}, onOpenBackup = {}) },
        "sync signed out" to { SyncScreenSample(SyncUiState(error = "Too many attempts. Try again in a minute.")) },
        "sync signed in with an error" to {
            SyncScreenSample(
                SyncUiState(
                    signedIn = true, serverUrl = "https://casa-server.tail1234.ts.net",
                    lastSyncAt = 1_792_000_000_000L, error = "Session expired. Log in again.",
                ),
            )
        },
        "sync choice dialog" to { SyncScreenSample(SyncUiState(choice = true)) },
    )

    @Composable
    private fun FormScreen() {
        Column {
            TypeSegments(TxnType.EXPENSE) {}
            DateRow(LocalDate.of(2026, 9, 27), LocalTime.of(19, 44), "The end of the month", onDate = {}, onTime = {}, onRepeat = {})
            FormRow("Amount") { ValueText("12,345,678.90Kč") }
            FormRow("Category") { ValueText("🍜 Food") }
            FormRow("Account") { ValueText("Cash") }
            FormRow("Note") { ValueText("Zelenina z trhu") }
            Row(Modifier.fillMaxWidth()) {
                FilledButton("Save", Color.Red, Modifier.weight(235f)) {}
                OutlinedButtonBox("Continue", Modifier.weight(88f)) {}
            }
        }
    }

    @Composable
    private fun SyncScreenSample(state: SyncUiState) =
        SyncContent(state, onBack = {}, onLogin = { _, _ -> }, onSyncNow = {}, onLogout = {}, onMerge = {}, onReplace = {}, onCancelLogin = {})

    private fun show(fontScale: Float, content: @Composable () -> Unit) = rule.setContent {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
            MoneyBookTheme(darkTheme = false) { content() }
        }
    }

    private fun textLayouts(): List<TextLayoutResult> =
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
            .fetchSemanticsNodes().mapNotNull { node ->
                val results = mutableListOf<TextLayoutResult>()
                node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
                results.firstOrNull()
            }

    /**
     * Cut off: taller than its box, more lines than allowed without an ellipsis, or a line wider
     * than the box. (didOverflowWidth is no use here: it is true for most short texts.)
     */
    private fun TextLayoutResult.isClipped(): Boolean =
        size.height < multiParagraph.height ||
            (multiParagraph.didExceedMaxLines && layoutInput.overflow != TextOverflow.Ellipsis) ||
            (0 until lineCount).any { visibleLineWidth(it) > size.width + 0.5f }

    /** Line width without the trailing space a soft wrap leaves on the line. */
    private fun TextLayoutResult.visibleLineWidth(line: Int): Float {
        val end = getLineEnd(line, visibleEnd = true)
        if (end <= getLineStart(line)) return 0f
        return getBoundingBox(end - 1).right - getBoundingBox(getLineStart(line)).left
    }

    /** Auto-size leaves style.fontSize alone, so compare the laid-out height with the full font size. */
    private fun TextLayoutResult.isShrunk(): Boolean = with(layoutInput.density) { size.height < layoutInput.style.fontSize.toPx() }

    private fun TextLayoutResult.isEllipsized(): Boolean = (0 until lineCount).any { isLineEllipsized(it) }

    /** A word split across two lines, like "Catego|ry". */
    private fun TextLayoutResult.breaksWord(): Boolean {
        val text = layoutInput.text.text
        return (0 until lineCount - 1).any { line ->
            val end = getLineEnd(line)
            end in 1 until text.length && text[end - 1].isLetterOrDigit() && text[end].isLetterOrDigit()
        }
    }

    private fun row(type: TxnType, note: String, category: String?, to: String? = null) = TxnRow(
        id = "r-$type", type = type, dateTime = "2026-09-26T18:40:12.345", amount = big, toAmount = null, amountMain = big,
        currency = "CZK", fee = 0, note = note, description = "", accountId = "cash", accountName = "Cash",
        toAccountId = to, toAccountName = to, categoryId = null, categoryName = category, parentCategoryName = null,
    )
}
