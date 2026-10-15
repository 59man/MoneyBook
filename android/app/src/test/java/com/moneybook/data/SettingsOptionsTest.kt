package com.moneybook.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneybook.domain.CardExpenseMode
import com.moneybook.domain.WeekendShift
import java.io.File
import java.time.DayOfWeek
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsOptionsTest {
    @get:Rule val folder = TemporaryFolder()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @After fun tearDown() = scope.cancel()

    private fun repo(file: File) =
        DataStoreSettingsRepository(PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }), defaultCurrency = "CZK")

    @Test fun anEmptyStoreGivesTheDefaults() = runBlocking {
        assertEquals(AppSettings("CZK"), repo(folder.newFile("empty.preferences_pb").also { it.delete() }).current())
    }

    @Test fun everyOptionIsStored() = runBlocking {
        val changed = AppSettings(
            mainCurrency = "CZK", monthStartDay = 25, weekStart = DayOfWeek.MONDAY, startScreen = StartScreen.CALENDAR,
            monthStartWeekend = WeekendShift.PREVIOUS_FRIDAY, swipe = SwipeMode.TAB, colorSet = ColorSet.B,
            timeInput = TimeInput.SHOW_ASC, showDescription = true, inputOrder = InputOrder.ACCOUNT, noteButton = false,
            cardExpenseMode = CardExpenseMode.LUMP_SUM, carryOver = true, subcategory = true, copyDateBy = CopyDateBy.ENTRY_DATE, style = StyleMode.DARK,
        )
        val repo = repo(folder.newFile("options.preferences_pb").also { it.delete() })
        repo.replace(changed)
        assertEquals(changed, repo.current()) // read back through the stored preferences
    }

    @Test fun timeInputOptionsCombineWhereAndOrder() {
        assertEquals(listOf(false, false, true, true, true, true), TimeInput.entries.map { it.inForm })
        assertEquals(listOf(false, false, true, true, false, false), TimeInput.entries.map { it.onList })
        assertEquals(listOf(false, true, false, true, false, true), TimeInput.entries.map { it.ascending })
        assertEquals("Input Only, Desc.", TimeInput.INPUT_DESC.label)
    }
}
