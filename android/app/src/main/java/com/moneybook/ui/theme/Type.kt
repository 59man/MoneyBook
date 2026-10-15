package com.moneybook.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Sizes calibrated on a physical phone by matching glyph widths against reference captures.
 * Styles not calibrated yet keep their estimate from capture ink heights (docs/ui-spec.md).
 */
object MbType {
    val topTitle = TextStyle(fontSize = 15.sp)
    val screenTitle = TextStyle(fontSize = 16.sp)
    val subTab = TextStyle(fontSize = 14.sp)
    val summaryLabel = TextStyle(fontSize = 12.sp)
    val summaryValue = TextStyle(fontSize = 14.sp)
    val dayNumber = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold)
    val weekdayChip = TextStyle(fontSize = 10.sp)
    val dayDate = TextStyle(fontSize = 10.sp)
    val amount = TextStyle(fontSize = 14.sp)
    val rowCategory = TextStyle(fontSize = 12.sp)
    /** A subcategory below its parent in a day row (Subcategory ON), measured on realbyte_34. */
    val rowSubcategory = TextStyle(fontSize = 10.sp)
    val rowNote = TextStyle(fontSize = 14.sp)
    val rowAccount = TextStyle(fontSize = 12.sp)
    val calendarHeader = TextStyle(fontSize = 11.sp)
    val calendarDay = TextStyle(fontSize = 10.sp)
    val calendarAmount = TextStyle(fontSize = 10.sp)
    val monthName = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold)
    val monthRange = TextStyle(fontSize = 11.sp)
    val weekRange = TextStyle(fontSize = 13.sp)
    val monthlyAmount = TextStyle(fontSize = 13.sp)
    val monthlyTotal = TextStyle(fontSize = 11.sp)
    val formLabel = TextStyle(fontSize = 14.sp)
    /** Trans → Total's budget rows: the name, the amounts and rate, and the Today bubble (Realbyte 12 / 14 / 11 sp). */
    val budgetName = TextStyle(fontSize = 12.sp)
    val budgetAmount = TextStyle(fontSize = 14.sp)
    val budgetBubble = TextStyle(fontSize = 11.sp)
    /** Budget screens (Realbyte's layouts): the help block (configSubKindDescription, 13 sp) and the bold Default Budget row (16 sp). */
    val budgetHelp = TextStyle(fontSize = 13.sp)
    val budgetDefault = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold)
    val formValue = TextStyle(fontSize = 15.sp)
    val segment = TextStyle(fontSize = 14.sp)
    val button = TextStyle(fontSize = 14.sp)
    val keyDone = TextStyle(fontSize = 16.sp)
    val panelTitle = TextStyle(fontSize = 16.sp)
    val key = TextStyle(fontSize = 20.sp)
    /** "Exchange rate: 1.1" under a sub-currency amount. */
    val rateLine = TextStyle(fontSize = 11.sp)
    val pickerCell = TextStyle(fontSize = 14.sp)
    val listItem = TextStyle(fontSize = 14.sp)
    /** Gray second line of a category row with Subcategory ON: "Lunch, Dinner". */
    val categorySubs = TextStyle(fontSize = 12.sp)
    /** Sub Currency Setting's "EUR 1.00  =  USD 1.1", and the edit screen's and Main Currency Setting's samples. */
    val currencyRow = TextStyle(fontSize = 16.sp)
    val currencySample = TextStyle(fontSize = 22.sp)
    val currencyBig = TextStyle(fontSize = 32.sp)
    val sectionHeader = TextStyle(fontSize = 14.sp)
    val gridLabel = TextStyle(fontSize = 13.sp)
    val navLabel = TextStyle(fontSize = 12.sp)
    val emptyText = TextStyle(fontSize = 15.sp)
    val bigAmount = TextStyle(fontSize = 24.sp)
    val pieLabel = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold)
    val piePercent = TextStyle(fontSize = 12.sp)
    val percentChip = TextStyle(fontSize = 11.sp)
    val chartAxis = TextStyle(fontSize = 11.sp, lineHeight = 14.sp)
    val chartValue = TextStyle(fontSize = 11.sp, lineHeight = 14.sp)
    val totalPeriod = TextStyle(fontSize = 15.sp)
    val statsTabAmount = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold)
    val sectionTitle = TextStyle(fontSize = 18.sp)
    val ringPercent = TextStyle(fontSize = 20.sp)
    val ringAmount = TextStyle(fontSize = 15.sp)
    val memoTitle = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold)
    val memoText = TextStyle(fontSize = 17.sp)
    val memoDateChip = TextStyle(fontSize = 15.sp)
    val memoRowTitle = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold)
    val helpTitle = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium)
    val helpText = TextStyle(fontSize = 15.sp, lineHeight = 21.sp)
    val searchText = TextStyle(fontSize = 17.sp)
    val repeatOption = TextStyle(fontSize = 17.sp)
    val repInst = TextStyle(fontSize = 12.sp)
    val calcResult = TextStyle(fontSize = 44.sp)
    val calcExpression = TextStyle(fontSize = 20.sp)
    val calcKey = TextStyle(fontSize = 24.sp)
    val dialogTitle = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold)
    val dialogOption = TextStyle(fontSize = 17.sp)
    val dialogText = TextStyle(fontSize = 15.sp, lineHeight = 21.sp)
    /** Realbyte's option dialog (dialog_common_choice, dialog_common_adapter_check_list_item): title 16 sp bold, description and options 14 sp. */
    val choiceTitle = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold)
    val choiceText = TextStyle(fontSize = 14.sp)
    val passcodeTitle = TextStyle(fontSize = 26.sp)
    /** Account detail: the Statement period ("9.1.26  ~  9.30.26") and the card's "Pay" button. */
    val statementPeriod = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold)
    val fabLabel = TextStyle(fontSize = 15.sp)
    val choiceButton = TextStyle(fontSize = 16.sp)
    val passcodeKey = TextStyle(fontSize = 32.sp)
    // The summary widget: measured against Realbyte's widget on the same emulator (420 dpi, font scale 1.0).
    val widgetTab = TextStyle(fontSize = 13.sp)
    val widgetLabel = TextStyle(fontSize = 12.sp)
    val widgetAmount = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold)
    val widgetMom = TextStyle(fontSize = 14.sp)
    val widgetMomLabel = TextStyle(fontSize = 11.sp)
    val widgetTotalValue = TextStyle(fontSize = 14.sp)
}
