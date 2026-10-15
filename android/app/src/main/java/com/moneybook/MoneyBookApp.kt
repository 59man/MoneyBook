package com.moneybook

import android.app.Application
import com.moneybook.data.sweepUnused
import com.moneybook.notify.Notifications
import com.moneybook.ui.common.Currencies
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MoneyBookApp : Application() {
    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Notifications.ensureChannels(this)
        applyDeviceSettings()
        // Redraws the summary widgets on every change, and (re)arms their midnight alarm: at start and after a reboot.
        container.widgets.start(appScope)
        container.triggers.start(appScope)
        // Every screen formats amounts through Currencies, so a currency's new look shows everywhere.
        // A plain global-snapshot write: a nested mutable snapshot could create the holder's state inside itself or
        // conflict with another writer.
        appScope.launch { container.currencies.state.collect { s -> Currencies.state = s } }
        appScope.launch { container.device.settings.collect { container.lock.noteSettings(it) } }
        appScope.launch { container.photos.sweepUnused(container.db) }
        appScope.launch {
            container.seeder.seedIfEmpty(container.settings.current().mainCurrency, stamp = 0)
            container.repeats.catchUp(container.time.today())
        }
    }

    /** Adds the repeat occurrences that came due while the app was away (MainActivity.onStart). */
    fun catchUpRepeats() {
        appScope.launch { container.repeats.catchUp(container.time.today()) }
    }

    /** Re-arms the reminder and re-posts quick add from the device settings: at start and after a reboot. */
    fun applyDeviceSettings(onDone: () -> Unit = {}) {
        appScope.launch {
            try {
                val s = container.device.current()
                container.reminders.apply(s)
                container.quickAdd.apply(s)
            } finally {
                onDone()
            }
        }
    }
}
