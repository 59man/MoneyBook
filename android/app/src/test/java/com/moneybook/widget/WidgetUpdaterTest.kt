package com.moneybook.widget

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneybook.data.AppSettings
import com.moneybook.data.DefaultData
import com.moneybook.data.Seeder
import com.moneybook.data.TimeSource
import com.moneybook.data.db.MoneyBookDatabase
import com.moneybook.shared.model.AccountGroupType
import com.moneybook.shared.model.TxnType
import com.moneybook.testutil.FakeDeviceSettingsRepository
import com.moneybook.testutil.FakeSettingsRepository
import com.moneybook.testutil.Fixtures
import com.moneybook.testutil.inMemoryDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WidgetUpdaterTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var db: MoneyBookDatabase
    private val settings = FakeSettingsRepository()
    private val device = FakeDeviceSettingsRepository()

    @Before fun setUp() {
        db = inMemoryDb()
        runBlocking { Seeder(db, TimeSource()).seedIfEmpty("CZK") }
    }

    @After fun tearDown() = db.close()

    @Test fun savingAnEntrySignalsAChange() = runBlocking {
        val updater = WidgetUpdater(app, db, settings, device)
        val first = async(Dispatchers.Default) { withTimeout(5_000) { updater.changes().first() } }
        delay(300) // the invalidation observer is registered when collection starts
        db.txnDao().upsert(Fixtures.txn("n", TxnType.EXPENSE, "2026-10-07T09:00:00.000", DefaultData.accountId(AccountGroupType.CASH), 1_000))
        first.await()
    }

    @Test fun nothingSignalsAtTheStart() = runBlocking {
        assertNull(withTimeoutOrNull(500) { WidgetUpdater(app, db, settings, device).changes().first() })
    }

    // Many changes in a row redraw once, and a failing redraw doesn't stop the next one (or crash the app).
    @Test fun changesAreDebouncedAndAFailureIsSurvived() = runTest {
        var calls = 0
        val updater = WidgetUpdater(app, db, settings, device, redraw = {
            calls++
            if (calls == 1) error("the launcher is gone")
        })
        updater.start(backgroundScope)
        runCurrent()
        settings.state.value = AppSettings("CZK", monthStartDay = 5)
        runCurrent()
        settings.state.value = AppSettings("CZK", monthStartDay = 6)
        runCurrent()
        device.state.value = device.state.value.copy(widgetOpacity = 40)
        advanceTimeBy(600)
        assertEquals(1, calls)
        settings.state.value = AppSettings("CZK", monthStartDay = 7)
        advanceTimeBy(600)
        assertEquals(2, calls)
    }

    // The app's scope has no exception handler: nothing the widgets do may escape it (a closed database, no launcher).
    @Test fun aBrokenChangeFeedNeverEscapes() = runTest {
        val closed = inMemoryDb().also { it.close() }
        var calls = 0
        WidgetUpdater(app, closed, settings, device, redraw = { calls++ }).start(backgroundScope)
        runCurrent()
        settings.state.value = AppSettings("CZK", monthStartDay = 9)
        advanceTimeBy(600)
        assertEquals(1, calls) // the settings still redraw the widgets
    }
}
