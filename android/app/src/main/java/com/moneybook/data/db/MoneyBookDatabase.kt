package com.moneybook.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        AccountGroupEntity::class, AccountEntity::class, CategoryEntity::class, TxnEntity::class,
        MemoEntity::class, BookmarkEntity::class, RepeatRuleEntity::class, CurrencyEntity::class,
        BudgetEntity::class, AttachmentEntity::class,
    ],
    version = 8,
    exportSchema = true,
)
abstract class MoneyBookDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao
    abstract fun categoryDao(): CategoryDao
    abstract fun txnDao(): TxnDao
    abstract fun memoDao(): MemoDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun repeatRuleDao(): RepeatRuleDao
    abstract fun currencyDao(): CurrencyDao
    abstract fun budgetDao(): BudgetDao
    abstract fun attachmentDao(): AttachmentDao

    companion object {
        /** M2c: memos and bookmarks. The SQL matches `schemas/…/2.json`. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `memo` (`id` TEXT NOT NULL, `date` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                        "`text` TEXT NOT NULL, `color` INTEGER, `pinned` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                        "`deleted` INTEGER NOT NULL, `dirty` INTEGER NOT NULL, `serverVersion` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_memo_date` ON `memo` (`date`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `bookmark` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `type` TEXT NOT NULL, " +
                        "`accountId` TEXT NOT NULL, `toAccountId` TEXT, `categoryId` TEXT, `amount` INTEGER NOT NULL, " +
                        "`currency` TEXT NOT NULL, `note` TEXT NOT NULL, `description` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, `deleted` INTEGER NOT NULL, `dirty` INTEGER NOT NULL, " +
                        "`serverVersion` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                )
            }
        }

        /** M2d: repeat rules. The SQL matches `schemas/…/3.json`. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `repeat_rule` (`id` TEXT NOT NULL, `frequency` TEXT NOT NULL, `startDate` TEXT NOT NULL, " +
                        "`lastDate` TEXT NOT NULL, `installments` INTEGER, `type` TEXT NOT NULL, `accountId` TEXT NOT NULL, " +
                        "`toAccountId` TEXT, `categoryId` TEXT, `amount` INTEGER NOT NULL, `currency` TEXT NOT NULL, `note` TEXT NOT NULL, " +
                        "`description` TEXT NOT NULL, `time` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, `deleted` INTEGER NOT NULL, " +
                        "`dirty` INTEGER NOT NULL, `serverVersion` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                )
            }
        }

        /** M2i: sub-currencies and the From account's side of an entry. The SQL matches `schemas/…/4.json`. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `txn` ADD COLUMN `accountAmount` INTEGER")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `currency` (`code` TEXT NOT NULL, `symbol` TEXT NOT NULL, `position` TEXT NOT NULL, " +
                        "`decimals` INTEGER NOT NULL, `rate` TEXT NOT NULL, `sortOrder` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                        "`deleted` INTEGER NOT NULL, `dirty` INTEGER NOT NULL, `serverVersion` INTEGER NOT NULL, PRIMARY KEY(`code`))",
                )
            }
        }

        /** M2j: budgets. The SQL matches `schemas/…/5.json`. */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `budget` (`id` TEXT NOT NULL, `categoryId` TEXT, `fromMonth` INTEGER NOT NULL, " +
                        "`amount` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `deleted` INTEGER NOT NULL, `dirty` INTEGER NOT NULL, " +
                        "`serverVersion` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_budget_categoryId` ON `budget` (`categoryId`)")
            }
        }

        /** M2k: account and group states and the Transfer-Expense flag. The SQL matches `schemas/…/6.json`. */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `account` ADD COLUMN `state` TEXT NOT NULL DEFAULT 'ACTIVE'")
                db.execSQL("ALTER TABLE `account` ADD COLUMN `transferExpense` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `account_group` ADD COLUMN `state` TEXT NOT NULL DEFAULT 'ACTIVE'")
            }
        }

        /** M2l: receipt photos. The SQL matches `schemas/…/7.json`. */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `attachment` (`id` TEXT NOT NULL, `txnId` TEXT NOT NULL, `fileName` TEXT NOT NULL, `sortOrder` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `deleted` INTEGER NOT NULL, `dirty` INTEGER NOT NULL, `serverVersion` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_attachment_txnId` ON `attachment` (`txnId`)")
            }
        }

        /** M4: no schema change, only the monotonic-updatedAt triggers. `schemas/…/8.json` equals `7.json` apart from the version. */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) = createMonotonicTriggers(db)
        }

        /** Room does not call migrations for a database created at version 8, so onCreate adds the triggers. */
        val monotonicCallback = object : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) = createMonotonicTriggers(db)
        }

        fun build(context: Context): MoneyBookDatabase =
            Room.databaseBuilder(context, MoneyBookDatabase::class.java, "moneybook.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
                .addCallback(monotonicCallback)
                .build()
    }
}

/** Every synced table with its key column. */
internal val SYNCED_TABLES = listOf(
    "account_group" to "id", "account" to "id", "category" to "id", "txn" to "id", "memo" to "id",
    "bookmark" to "id", "repeat_rule" to "id", "currency" to "code", "budget" to "id", "attachment" to "id",
)

/**
 * A local edit (dirty = 1) never leaves updatedAt at or below the old value, so it beats this device's previous version
 * after a clock step back. Sync writes (dirty = 0) and mark-all-dirty (dirty = 2) never fire it. SQLite's recursive
 * triggers stay off, and the inner UPDATE would not match the WHEN anyway.
 */
internal fun createMonotonicTriggers(db: SupportSQLiteDatabase) = SYNCED_TABLES.forEach { (t, key) ->
    db.execSQL(
        "CREATE TRIGGER IF NOT EXISTS `${t}_mono` AFTER UPDATE ON `$t` WHEN NEW.dirty = 1 AND NEW.updatedAt <= OLD.updatedAt " +
            "BEGIN UPDATE `$t` SET updatedAt = OLD.updatedAt + 1 WHERE $key = NEW.$key; END",
    )
}
