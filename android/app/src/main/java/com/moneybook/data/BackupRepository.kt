package com.moneybook.data

import androidx.room.withTransaction
import com.moneybook.data.backup.toAppSettings
import com.moneybook.data.backup.toDto
import com.moneybook.data.backup.toEntity
import com.moneybook.data.db.MoneyBookDatabase
import com.moneybook.shared.backup.BackupCodec
import com.moneybook.shared.dto.BackupDto
import java.io.InputStream
import java.io.OutputStream
import java.time.temporal.ChronoUnit

data class RestoreResult(val entries: Int, val createdAt: String)

/** Full-file backup and restore (Backup → "Backup/restore on device"). */
class BackupRepository(
    private val db: MoneyBookDatabase,
    private val settings: SettingsRepository,
    private val time: TimeSource,
    private val appVersion: String,
    private val photos: PhotoStore,
) {
    /** Writes every row, tombstones included, and the synced settings. Returns the number of live entries. */
    suspend fun backup(out: OutputStream): Int {
        val s = settings.current()
        val txns = db.txnDao().allRows()
        // A live photo whose file is gone can't be restored (the file check would fail), so the row is left out.
        val attachments = db.attachmentDao().allRows().filter { it.deleted || photos.file(it.fileName).exists() }
        BackupCodec.write(
            BackupDto(
                format = BackupCodec.FORMAT,
                version = BackupCodec.VERSION,
                createdAt = time.now().truncatedTo(ChronoUnit.MINUTES).toString(),
                appVersion = appVersion,
                settings = s.toDto(),
                accountGroups = db.accountDao().allGroupRows().map { it.toDto() },
                accounts = db.accountDao().allAccountRows().map { it.toDto() },
                categories = db.categoryDao().allRows().map { it.toDto() },
                txns = txns.map { it.toDto() },
                memos = db.memoDao().allRows().map { it.toDto() },
                bookmarks = db.bookmarkDao().allRows().map { it.toDto() },
                repeatRules = db.repeatRuleDao().allRows().map { it.toDto() },
                currencies = db.currencyDao().allRows().map { it.toDto() },
                budgets = db.budgetDao().allRows().map { it.toDto() },
                attachments = attachments.map { it.toDto() },
            ),
            out,
        ) { name -> photos.file(name).takeIf { it.exists() }?.inputStream() }
        return txns.count { !it.deleted }
    }

    /** Replaces all data with the backup. The file is read and checked in full before anything is deleted. */
    suspend fun restore(input: InputStream): RestoreResult {
        val staging = photos.newStaging()
        val b = try {
            BackupCodec.read(input, staging)
        } catch (e: Exception) {
            staging.deleteRecursively()
            throw e
        }
        // The files first, then the rows; the sweep below drops the old files. A failure in between leaves the old photos in place.
        photos.adopt(staging)
        // Tombstones, not deletes, so the restore reaches the other synced devices as a whole (rows the backup lacks included).
        val now = time.millis()
        db.withTransaction {
            db.attachmentDao().softDeleteAll(now)
            db.repeatRuleDao().softDeleteAll(now)
            db.currencyDao().softDeleteAll(now)
            db.budgetDao().softDeleteAll(now)
            db.memoDao().softDeleteAll(now)
            db.bookmarkDao().softDeleteAll(now)
            db.txnDao().softDeleteAll(now)
            db.categoryDao().softDeleteAll(now)
            db.accountDao().softDeleteAllAccounts(now)
            db.accountDao().softDeleteAllGroups(now)
            db.accountDao().upsertGroups(b.accountGroups.map { it.toEntity().copy(updatedAt = now) })
            b.accounts.map { it.toEntity().copy(updatedAt = now) }.chunked(500).forEach { db.accountDao().upsertAll(it) }
            b.categories.map { it.toEntity().copy(updatedAt = now) }.chunked(500).forEach { db.categoryDao().upsertAll(it) }
            b.txns.map { it.toEntity().copy(updatedAt = now) }.chunked(500).forEach { db.txnDao().upsertAll(it) }
            db.memoDao().upsertAll(b.memos.map { it.toEntity().copy(updatedAt = now) })
            db.bookmarkDao().upsertAll(b.bookmarks.map { it.toEntity().copy(updatedAt = now) })
            db.repeatRuleDao().upsertAll(b.repeatRules.map { it.toEntity().copy(updatedAt = now) })
            db.currencyDao().upsertAll(b.currencies.map { it.toEntity().copy(updatedAt = now) })
            db.budgetDao().upsertAll(b.budgets.map { it.toEntity().copy(updatedAt = now) })
            db.attachmentDao().upsertAll(b.attachments.map { it.toEntity().copy(updatedAt = now) })
        }
        photos.sweepUnused(db)
        settings.replace(b.settings.toAppSettings(style = settings.current().style))
        return RestoreResult(b.txns.count { !it.deleted }, b.createdAt)
    }
}
