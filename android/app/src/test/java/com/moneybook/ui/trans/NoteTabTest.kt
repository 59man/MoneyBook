package com.moneybook.ui.trans

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneybook.data.AccountRepository
import com.moneybook.data.BudgetRepository
import com.moneybook.data.CategoryRepository
import com.moneybook.data.MemoRepository
import com.moneybook.data.Seeder
import com.moneybook.data.TimeSource
import com.moneybook.data.TxnRepository
import com.moneybook.data.db.MemoEntity
import com.moneybook.testutil.FakeSettingsRepository
import com.moneybook.testutil.MainDispatcherRule
import com.moneybook.testutil.inMemoryDb
import com.moneybook.ui.memo.MemoActions
import com.moneybook.ui.memo.MemoEditContent
import com.moneybook.ui.memo.MemoEditState
import com.moneybook.ui.theme.MoneyBookTheme
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NoteTabTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val rule = createComposeRule()
    private val time = TimeSource(Clock.fixed(Instant.parse("2026-09-26T10:00:00Z"), ZoneOffset.UTC))
    private val plan = MemoEntity(id = "m", date = "2026-09-26", title = "Plan", text = "Koupit chleba\nA mléko", pinned = true, updatedAt = 0)

    @Test fun theNoteTabListsTheMonthsMemosAndDatesNewOnesInTheShownMonth() {
        val db = inMemoryDb()
        try {
            runBlocking {
                Seeder(db, time).seedIfEmpty("CZK")
                MemoRepository(db, time).save(plan)
                MemoRepository(db, time).save(MemoEntity(id = "aug", date = "2026-08-10", title = "August", updatedAt = 0))
                val vm = TransViewModel(
                    TxnRepository(db, time), FakeSettingsRepository(), time, CategoryRepository(db, time), AccountRepository(db, time),
                    MemoRepository(db, time), BudgetRepository(db, time),
                )
                vm.selectTab(TransTab.NOTE)
                val sep = vm.state.first { it.tab == TransTab.NOTE && it.memos.isNotEmpty() }
                assertEquals(listOf("m"), sep.memos.map { it.id })
                assertEquals(LocalDate.of(2026, 9, 26), sep.memoDate)
                vm.previous()
                val aug = vm.state.first { it.tab == TransTab.NOTE && it.memos.any { m -> m.id == "aug" } }
                assertEquals(LocalDate.of(2026, 8, 1), aug.memoDate)
            }
        } finally {
            db.close()
        }
    }

    @Test fun rowsShowDateTitleAndFirstLine() {
        var opened: String? = null
        var added = false
        rule.setContent {
            MoneyBookTheme(darkTheme = false) {
                NoteTab(listOf(plan)) { opened = it }
                MemoFab(onClick = { added = true })
            }
        }
        listOf("9.26 (Sat)", "Plan", "Koupit chleba").forEach { rule.onNodeWithText(it).assertIsDisplayed() }
        rule.onNodeWithContentDescription("Pinned").assertIsDisplayed()
        rule.onNodeWithText("Plan").performClick()
        assertEquals("m", opened)
        rule.onNodeWithContentDescription("Add note").performClick()
        assertEquals(true, added)
    }

    @Test fun noMemosShowTheEmptyState() {
        rule.setContent { MoneyBookTheme(darkTheme = false) { NoteTab(emptyList()) {} } }
        rule.onNodeWithText("No data available.").assertIsDisplayed()
    }

    @Test fun theEditorShowsItsFieldsAndAsksBeforeDeleting() {
        var deleted = false
        var palette = false
        rule.setContent {
            MoneyBookTheme(darkTheme = false) {
                MemoEditContent(
                    MemoEditState(loaded = true, isNew = false, date = LocalDate.of(2026, 9, 26), paletteOpen = true),
                    MemoActions(onDelete = { deleted = true }, onPalette = { palette = true }),
                )
            }
        }
        rule.onAllNodesWithText("Note").assertCountEquals(2) // the title and the empty field's placeholder
        listOf("9.26 (Sat)", "Title").forEach { rule.onNodeWithText(it).assertIsDisplayed() }
        rule.onNodeWithContentDescription("No color").assertIsDisplayed()
        rule.onNodeWithContentDescription("Color").performClick()
        assertEquals(true, palette)
        rule.onNodeWithContentDescription("Delete").performClick()
        rule.onNodeWithText("Delete this note?").assertIsDisplayed()
        rule.onNodeWithText("Delete").performClick()
        assertEquals(true, deleted)
    }
}
