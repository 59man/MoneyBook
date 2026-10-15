package com.moneybook

import android.content.Context
import android.os.Build
import androidx.datastore.preferences.preferencesDataStore
import com.moneybook.data.AccountRepository
import com.moneybook.data.BackupRepository
import com.moneybook.data.BookmarkRepository
import com.moneybook.data.BudgetRepository
import com.moneybook.data.CategoryRepository
import com.moneybook.data.CurrencyRepository
import com.moneybook.data.DataStoreDeviceSettingsRepository
import com.moneybook.data.DataStoreSettingsRepository
import com.moneybook.data.DeviceSettingsRepository
import com.moneybook.data.ExportRepository
import com.moneybook.data.ImportRepository
import com.moneybook.data.LegacyImportRepository
import com.moneybook.data.MemoRepository
import com.moneybook.data.PhotoStore
import com.moneybook.data.RepeatRepository
import com.moneybook.data.ResetRepository
import com.moneybook.data.Seeder
import com.moneybook.data.SettingsRepository
import com.moneybook.data.TimeSource
import com.moneybook.data.TxnRepository
import com.moneybook.data.db.MoneyBookDatabase
import com.moneybook.data.sync.HttpSyncApi
import com.moneybook.data.sync.SyncAccount
import com.moneybook.data.sync.SyncApi
import com.moneybook.data.sync.SyncEngine
import com.moneybook.data.sync.SyncScheduler
import com.moneybook.data.sync.SyncTriggers
import com.moneybook.notify.QuickAddNotifier
import com.moneybook.notify.ReminderScheduler
import com.moneybook.widget.WidgetUpdater
import kotlinx.coroutines.flow.map
import java.io.File

private val Context.settingsStore by preferencesDataStore(name = "settings")
private val Context.deviceStore by preferencesDataStore(name = "device_settings")

/** Hand-written dependency container; one per process, owned by [MoneyBookApp]. */
class AppContainer(context: Context) {
    val time = TimeSource()
    val db: MoneyBookDatabase = MoneyBookDatabase.build(context)
    val settings: SettingsRepository =
        DataStoreSettingsRepository(context.settingsStore, DataStoreSettingsRepository.defaultCurrency())

    /** This phone's own settings (passcode, alarm, quick add); never backed up. */
    val device: DeviceSettingsRepository = DataStoreDeviceSettingsRepository(context.deviceStore)
    val lock = AppLock(device)
    val syncApi: SyncApi = HttpSyncApi(device)

    val reminders = ReminderScheduler(context)
    val quickAdd = QuickAddNotifier(context)
    val widgets = WidgetUpdater(context, db, settings, device)
    val txns = TxnRepository(db, time)
    val accounts = AccountRepository(db, time)
    val categories = CategoryRepository(db, time)
    val memos = MemoRepository(db, time)
    val bookmarks = BookmarkRepository(db, time)
    val repeats = RepeatRepository(db, time) { currencies.current().fx }
    val seeder = Seeder(db, time)
    val importer = ImportRepository(db, settings, seeder, time)
    val exporter = ExportRepository(db, settings)
    /** Receipt photos (`filesDir/photo`), named by the MD5 of their bytes. */
    val photos = PhotoStore(File(context.filesDir, "photo"))
    val backup = BackupRepository(db, settings, time, BuildConfig.VERSION_NAME, photos)
    val reset = ResetRepository(db, settings, seeder, time, DataStoreSettingsRepository.defaultCurrency(), photos)
    val budgets = BudgetRepository(db, time)
    val currencies = CurrencyRepository(db, settings, time) { code -> reset.resetAll(code) }
    val legacy = LegacyImportRepository(db, time)
    val sync = SyncEngine(db, settings, device, syncApi, photos, repeats, time)
    val account = SyncAccount(device, settings, db, sync, syncApi, seeder, Build.MODEL.take(100).ifBlank { "Android" })
    val scheduler = SyncScheduler(context)
    val triggers = SyncTriggers(device.settings.map { it.signedIn }, account.pending, sync.hasDirty, scheduler)
}
