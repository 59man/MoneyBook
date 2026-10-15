package com.moneybook.widget

import android.app.Application
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.testing.unit.assertHasStartActivityClickAction
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasContentDescriptionEqualTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneybook.MainActivity
import com.moneybook.notify.Notifications
import com.moneybook.widget.QuickButton.APP
import com.moneybook.widget.QuickButton.BOOKMARKS
import com.moneybook.widget.QuickButton.EXPENSE
import com.moneybook.widget.QuickButton.INCOME
import com.moneybook.widget.QuickButton.SEARCH
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QuickAddWidgetTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    private fun intentOf(b: QuickButton) = Notifications.openIntent(app, b.open)

    @Test fun theSizesHaveRealbytesButtons() {
        assertEquals(listOf(listOf(INCOME, EXPENSE)), quickAddRows(QuickAddSize.TWO_BY_ONE))
        assertEquals(listOf(listOf(INCOME, EXPENSE), listOf(BOOKMARKS)), quickAddRows(QuickAddSize.TWO_BY_TWO))
        assertEquals(listOf(listOf(APP, SEARCH, INCOME, EXPENSE, BOOKMARKS)), quickAddRows(QuickAddSize.FOUR_BY_ONE))
    }

    @Test fun plusAddsIncomeAndMinusAnExpense() {
        assertEquals(MainActivity.OPEN_ADD_INCOME, intentOf(INCOME).getStringExtra(MainActivity.EXTRA_OPEN))
        assertEquals(MainActivity.OPEN_ADD_EXPENSE, intentOf(EXPENSE).getStringExtra(MainActivity.EXTRA_OPEN))
        assertNull(intentOf(APP).getStringExtra(MainActivity.EXTRA_OPEN))
        assertEquals(5, QuickButton.entries.map { intentOf(it).data }.toSet().size)
    }

    @Test fun eachButtonOfTheBarOpensItsScreen() = runGlanceAppWidgetUnitTest {
        setAppWidgetSize(DpSize(280.dp, 60.dp))
        provideComposable { QuickAddContent(QuickAddSize.FOUR_BY_ONE, QuickAddStyle.RED) { actionStartActivity(intentOf(it)) } }
        QuickButton.entries.forEach { b -> onNode(hasContentDescriptionEqualTo(b.label)).assertHasStartActivityClickAction(intentOf(b)) }
    }

    @Test fun theWhite2x2HasPlusMinusAndBookmarks() = runGlanceAppWidgetUnitTest {
        setAppWidgetSize(DpSize(110.dp, 110.dp))
        provideComposable { QuickAddContent(QuickAddSize.TWO_BY_TWO, QuickAddStyle.WHITE) { actionStartActivity(intentOf(it)) } }
        onNode(hasContentDescriptionEqualTo("Bookmarks")).assertHasStartActivityClickAction(intentOf(BOOKMARKS))
        onNode(hasContentDescriptionEqualTo("Income")).assertExists()
        onNode(hasContentDescriptionEqualTo("Search")).assertDoesNotExist()
    }
}
