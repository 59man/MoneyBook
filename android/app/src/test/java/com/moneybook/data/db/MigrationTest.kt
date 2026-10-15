package com.moneybook.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneybook.shared.model.ArchiveState
import com.moneybook.shared.model.RepeatFrequency
import com.moneybook.shared.model.TxnType
import com.moneybook.shared.money.SymbolPosition
import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "migration-test.db"

    @After fun tearDown() {
        context.deleteDatabase(name)
    }

    private fun open() = Room.databaseBuilder(context, MoneyBookDatabase::class.java, name)
        .addMigrations(MoneyBookDatabase.MIGRATION_1_2, MoneyBookDatabase.MIGRATION_2_3, MoneyBookDatabase.MIGRATION_3_4, MoneyBookDatabase.MIGRATION_4_5, MoneyBookDatabase.MIGRATION_5_6, MoneyBookDatabase.MIGRATION_6_7, MoneyBookDatabase.MIGRATION_7_8).allowMainThreadQueries().build()

    @Test fun version1DataSurvivesEveryMigration() = runTest {
        createVersion(1)
        val db = open()
        try {
            assertEquals(1, db.txnDao().count())
            assertEquals("Cash", db.accountDao().get("a")!!.name)
            assertEquals("🍜 Food", db.categoryDao().get("c")!!.name)
            db.memoDao().upsert(MemoEntity(id = "m", date = "2026-09-26", title = "Plan", updatedAt = 1))
            db.bookmarkDao().upsert(
                BookmarkEntity(id = "b", name = "Lunch", type = TxnType.EXPENSE, accountId = "a", amount = 100, currency = "CZK", createdAt = 1, updatedAt = 1),
            )
            assertEquals("Plan", db.memoDao().get("m")!!.title)
            assertEquals("Lunch", db.bookmarkDao().get("b")!!.name)
        } finally {
            db.close()
        }
    }

    @Test fun version2DataSurvivesTheRepeatMigration() = runTest {
        createVersion(2)
        val db = open()
        try {
            assertEquals(1, db.txnDao().count())
            assertEquals("Plan", db.memoDao().get("m")!!.title)
            assertEquals("Lunch", db.bookmarkDao().get("b")!!.name)
            db.repeatRuleDao().upsert(
                RepeatRuleEntity(
                    id = "r", frequency = RepeatFrequency.MONTHLY, startDate = "2026-09-26", lastDate = "2026-09-26",
                    type = TxnType.EXPENSE, accountId = "a", amount = 500, currency = "CZK", time = "12:00", updatedAt = 1,
                ),
            )
            assertEquals(RepeatFrequency.MONTHLY, db.repeatRuleDao().get("r")!!.frequency)
        } finally {
            db.close()
        }
    }

    @Test fun version3DataSurvivesTheCurrencyMigration() = runTest {
        createVersion(3)
        val db = open()
        try {
            assertEquals(null, db.txnDao().get("t")!!.accountAmount)
            assertEquals(500L, db.txnDao().get("t")!!.amount)
            db.currencyDao().upsert(CurrencyEntity("EUR", "€", SymbolPosition.START, 2, "1.1", 0, updatedAt = 1))
            assertEquals("1.1", db.currencyDao().get("EUR")!!.rate)
        } finally {
            db.close()
        }
    }

    @Test fun version4DataSurvivesTheBudgetMigration() = runTest {
        createVersion(4)
        val db = open()
        try {
            assertEquals(500L, db.txnDao().get("t")!!.amount)
            db.budgetDao().upsertAll(listOf(BudgetEntity(BudgetEntity.idFor("c", 0), "c", 0, 5_000, updatedAt = 1)))
            assertEquals(5_000L, db.budgetDao().allRows().single().amount)
        } finally {
            db.close()
        }
    }

    @Test fun version5DataSurvivesTheAccountStateMigration() = runTest {
        createVersion(5)
        val db = open()
        try {
            val account = db.accountDao().get("a")!!
            assertEquals(ArchiveState.ACTIVE, account.state)
            assertEquals(false, account.transferExpense)
            assertEquals(ArchiveState.ACTIVE, db.accountDao().getGroup("g")!!.state)
            assertEquals(listOf("a"), db.accountDao().accounts().map { it.id })
            assertEquals(500L, db.txnDao().get("t")!!.amount)
        } finally {
            db.close()
        }
    }

    @Test fun version6DataSurvivesTheAttachmentMigration() = runTest {
        createVersion(6)
        val db = open()
        try {
            assertEquals(500L, db.txnDao().get("t")!!.amount)
            assertEquals(0, db.attachmentDao().allRows().size)
            db.attachmentDao().replaceForTxn("t", listOf("a.jpg"), now = 1)
            assertEquals(listOf("a.jpg"), db.attachmentDao().forTxn("t").map { it.fileName })
        } finally {
            db.close()
        }
    }

    @Test fun version7DataSurvivesTheTriggerMigration() = runTest {
        createVersion(7)
        val db = open()
        try {
            val triggers = db.openHelper.readableDatabase.query("SELECT name FROM sqlite_master WHERE type = 'trigger'").use {
                generateSequence { if (it.moveToNext()) it.getString(0) else null }.toList()
            }
            assertEquals(10, triggers.size)
            val t = db.txnDao().get("t")!!
            assertEquals(500L, t.amount)
            assertEquals(1L, t.updatedAt)
            db.txnDao().upsert(t.copy(updatedAt = 0))
            assertEquals(2L, db.txnDao().get("t")!!.updatedAt)
        } finally {
            db.close()
        }
    }

    /** Builds a database at [version] from Room's exported schema, as MigrationTestHelper would. */
    private fun createVersion(version: Int) {
        val schema = Json.parseToJsonElement(File("schemas/com.moneybook.data.db.MoneyBookDatabase/$version.json").readText())
            .jsonObject.getValue("database").jsonObject
        val file = context.getDatabasePath(name).apply { parentFile?.mkdirs() }
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            for (entity in schema.getValue("entities").jsonArray) {
                val e = entity.jsonObject
                val table = e.getValue("tableName").jsonPrimitive.content
                db.execSQL(e.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                e["indices"]?.jsonArray?.forEach { db.execSQL(it.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table)) }
            }
            schema.getValue("setupQueries").jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
            db.execSQL("INSERT INTO account_group (id, name, type, sortOrder, updatedAt, deleted, dirty, serverVersion) VALUES ('g', 'Cash', 'CASH', 0, 1, 0, 1, 0)")
            db.execSQL(
                "INSERT INTO account (id, groupId, name, currency, initialBalance, description, includeInTotals, hidden, sortOrder, autoPay, updatedAt, deleted, dirty, serverVersion) " +
                    "VALUES ('a', 'g', 'Cash', 'CZK', 0, '', 1, 0, 0, 0, 1, 0, 1, 0)",
            )
            db.execSQL("INSERT INTO category (id, type, name, sortOrder, hidden, updatedAt, deleted, dirty, serverVersion) VALUES ('c', 'EXPENSE', '🍜 Food', 0, 0, 1, 0, 1, 0)")
            db.execSQL(
                "INSERT INTO txn (id, type, dateTime, accountId, categoryId, amount, currency, amountMain, fee, note, description, updatedAt, deleted, dirty, serverVersion) " +
                    "VALUES ('t', 'EXPENSE', '2026-09-26T12:00:00.000', 'a', 'c', 500, 'CZK', 500, 0, 'Oběd', '', 1, 0, 1, 0)",
            )
            if (version >= 2) {
                db.execSQL("INSERT INTO memo (id, date, title, text, color, pinned, updatedAt, deleted, dirty, serverVersion) VALUES ('m', '2026-09-26', 'Plan', '', NULL, 0, 1, 0, 1, 0)")
                db.execSQL(
                    "INSERT INTO bookmark (id, name, type, accountId, toAccountId, categoryId, amount, currency, note, description, createdAt, updatedAt, deleted, dirty, serverVersion) " +
                        "VALUES ('b', 'Lunch', 'EXPENSE', 'a', NULL, 'c', 500, 'CZK', '', '', 1, 1, 0, 1, 0)",
                )
            }
            db.version = version
        } finally {
            db.close()
        }
    }
}
