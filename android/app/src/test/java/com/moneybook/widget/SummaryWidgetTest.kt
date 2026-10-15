package com.moneybook.widget

import android.content.Intent
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.testing.unit.GlanceAppWidgetUnitTest
import androidx.glance.appwidget.testing.unit.assertHasRunCallbackClickAction
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasTextEqualTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneybook.data.StyleMode
import com.moneybook.domain.Mom
import com.moneybook.domain.Totals
import com.moneybook.domain.WidgetSummary
import com.moneybook.shared.money.CurrencySpec
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SummaryWidgetTest {
    // Realbyte's emulator case: 120.00 of expenses this month, 50.00 so far against 100.00.
    private val data = SummaryData(
        WidgetSummary(Totals(income = 0, expense = 12_000), income = Mom(0, null, null), expense = Mom(-5_000, 50, good = true)),
        CurrencySpec.of("CZK"),
    )

    private fun GlanceAppWidgetUnitTest.show(tab: SummaryTab) {
        setAppWidgetSize(DpSize(250.dp, 110.dp))
        provideComposable { SummaryContent(data, tab, WidgetLook(StyleMode.LIGHT), actionStartActivity(Intent())) }
    }

    @Test fun totalShowsTheBalanceBesideIncomeAndExpenses() = runGlanceAppWidgetUnitTest {
        show(SummaryTab.TOTAL)
        onAllNodes(hasTextEqualTo("Total")).assertCountEquals(2)
        onNode(hasTextEqualTo("-120.00")).assertExists()
        onAllNodes(hasTextEqualTo("Expenses")).assertCountEquals(2)
        onNode(hasTextEqualTo("120.00")).assertExists()
        onNode(hasTextEqualTo("vs. MoM")).assertDoesNotExist()
    }

    @Test fun expensesShowsThisMonthAndTheChangeSoFar() = runGlanceAppWidgetUnitTest {
        show(SummaryTab.EXPENSES)
        onNode(hasTextEqualTo("This Month")).assertExists()
        onNode(hasTextEqualTo("120.00")).assertExists()
        onNode(hasTextEqualTo("▼ 50.00 (50%)")).assertExists()
        onNode(hasTextEqualTo("vs. MoM")).assertExists()
    }

    @Test fun incomeWithNoChangeShowsAPlainZero() = runGlanceAppWidgetUnitTest {
        show(SummaryTab.INCOME)
        onAllNodes(hasTextEqualTo("0.00")).assertCountEquals(2)
    }

    @Test fun eachTabTapStoresThatTab() = runGlanceAppWidgetUnitTest {
        show(SummaryTab.EXPENSES)
        onNode(hasTextEqualTo("Income")).assertHasRunCallbackClickAction<SetSummaryTab>(actionParametersOf(SetSummaryTab.TAB to "INCOME"))
        onNode(hasTextEqualTo("Total")).assertHasRunCallbackClickAction<SetSummaryTab>(actionParametersOf(SetSummaryTab.TAB to "TOTAL"))
    }
}
