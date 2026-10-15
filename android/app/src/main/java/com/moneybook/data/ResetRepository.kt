package com.moneybook.data

import androidx.room.withTransaction
import com.moneybook.data.db.MoneyBookDatabase

/**
 * Realbyte's two resets (Backup → Reset). Deletes are tombstones, so once M4 syncs, the reset reaches
 * the user's other devices instead of being undone by them.
 */
class ResetRepository(
    private val db: MoneyBookDatabase,
    private val settings: SettingsRepository,
    private val seeder: Seeder,
    private val time: TimeSource,
    private val defaultCurrency: String,
    private val photos: PhotoStore,
) {
    /** "Reset contents only (Others remain)": entries, memos, bookmarks and repeats go; accounts, categories and settings stay. */
    suspend fun resetContents() {
        val now = time.millis()
        db.withTransaction {
            db.txnDao().softDeleteAll(now)
            db.memoDao().softDeleteAll(now)
            db.bookmarkDao().softDeleteAll(now)
            db.repeatRuleDao().softDeleteAll(now)
            db.attachmentDao().softDeleteAll(now)
        }
        photos.sweepUnused(db)
    }

    /** "A complete reset": everything goes, then the fresh-install defaults come back, in [currency]. */
    suspend fun resetAll(currency: String = defaultCurrency) {
        val now = time.millis()
        db.withTransaction {
            db.txnDao().softDeleteAll(now)
            db.memoDao().softDeleteAll(now)
            db.bookmarkDao().softDeleteAll(now)
            db.repeatRuleDao().softDeleteAll(now)
            db.categoryDao().softDeleteAll(now)
            db.accountDao().softDeleteAllAccounts(now)
            db.accountDao().softDeleteAllGroups(now)
            db.currencyDao().softDeleteAll(now)
            db.budgetDao().softDeleteAll(now)
            db.attachmentDao().softDeleteAll(now)
        }
        photos.sweepUnused(db)
        settings.replace(AppSettings(mainCurrency = currency))
        // The default rows have fixed ids, so seeding writes them over their tombstones.
        seeder.seedIfEmpty(currency)
    }
}
