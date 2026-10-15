package com.moneybook.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import com.moneybook.data.DeviceSettingsRepository
import com.moneybook.data.SettingsRepository
import com.moneybook.data.db.MoneyBookDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * Keeps the summary widgets current: every write to the entries, accounts or categories (the entry form, import,
 * restore, reset, repeat catch-up) and every settings change redraws them, 500 ms after the last one.
 */
class WidgetUpdater(
    private val context: Context,
    private val db: MoneyBookDatabase,
    private val settings: SettingsRepository,
    private val device: DeviceSettingsRepository,
    redraw: (suspend () -> Unit)? = null,
) {
    private val redrawAll: suspend () -> Unit = redraw ?: { SummaryWidget().updateAll(context) }

    private fun sources(): List<Flow<Unit>> = listOf(
        // Created on collection: on a closed database createFlow throws at once, and only then can catch see it.
        flow { emitAll(db.invalidationTracker.createFlow("txn", "account", "category", emitInitialState = false)) }.map { },
        settings.settings.drop(1).map { },
        device.settings.drop(1).map { },
    )

    /** One signal per change, none at the start. */
    fun changes(): Flow<Unit> = merge(*sources().toTypedArray())

    @OptIn(FlowPreview::class)
    fun start(scope: CoroutineScope) {
        // The app's scope has no exception handler, so nothing here may throw: a widget that can't be drawn (no
        // launcher, a removed widget), a failing alarm or a failing source (a closed database) must never take the
        // app down. Each source is collected on its own, so one that fails doesn't silence the others.
        val signals = Channel<Unit>(Channel.CONFLATED)
        sources().forEach { source -> scope.launch { source.catch { }.collect { signals.trySend(Unit) } } }
        scope.launch {
            runCatching { if (WidgetClock.anySummary(context)) WidgetClock(context).scheduleNext() }
            signals.receiveAsFlow().debounce(500).collect { runCatching { redrawAll() } }
        }
    }

    suspend fun updateAll() = redrawAll()
}
