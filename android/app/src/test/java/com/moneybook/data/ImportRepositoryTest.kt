package com.moneybook.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneybook.data.db.AccountEntity
import com.moneybook.data.db.MoneyBookDatabase
import com.moneybook.shared.importer.ImportBatch
import com.moneybook.shared.importer.ImportedTxn
import com.moneybook.shared.importer.SkippedRow
import com.moneybook.shared.model.AccountGroupType
import com.moneybook.shared.model.CategoryType
import com.moneybook.shared.model.TxnType
import com.moneybook.testutil.FakeSettingsRepository
import com.moneybook.testutil.inMemoryDb
import com.moneybook.testutil.testPhotos
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImportRepositoryTest {
    private lateinit var db: MoneyBookDatabase
    private val time = TimeSource(Clock.fixed(Instant.parse("2026-09-26T10:00:00Z"), ZoneOffset.UTC))
    private val settings = FakeSettingsRepository(AppSettings(mainCurrency = "USD"))
    private lateinit var repo: ImportRepository

    @Before fun setUp() {
        db = inMemoryDb()
        repo = ImportRepository(db, settings, Seeder(db, time), time)
    }

    @After fun tearDown() = db.close()

    private fun txn(
        key: String, type: TxnType, account: String, category: String?, amount: Long,
        to: String? = null, sub: String? = null,
    ) = ImportedTxn(
        sourceKey = key, type = type, dateTime = LocalDateTime.of(2026, 9, 1, 12, 0), accountName = account,
        toAccountName = to, categoryName = category, subcategoryName = sub, note = "n-$key", description = "",
        amountMinor = amount, currency = "CZK",
    )

    private val batch = ImportBatch(
        mainCurrency = "CZK",
        txns = listOf(
            txn("1", TxnType.EXPENSE, "Cash", "🍜 Food", 52_500),
            txn("2", TxnType.EXPENSE, "cash", "charita", 9_900),
            txn("3", TxnType.INCOME, "Cash", "💰 Salary", 4_710_000),
            txn("4", TxnType.TRANSFER, "Cash", null, 100_000, to = "Bank"),
            txn("5", TxnType.EXPENSE, "Moje karta", "🍜 Food", 1_000, sub = "Lunch"),
        ),
        skipped = listOf(SkippedRow(7, "Unreadable date")),
    )

    @Test fun importsReusingMatchingNamesAndCreatingTheRest() = runTest {
        val result = repo.apply(batch)
        assertEquals(5, result.imported)
        assertEquals(0, result.duplicates)
        assertEquals(listOf(SkippedRow(7, "Unreadable date")), result.skipped)
        assertEquals(2, result.accountsCreated)
        assertEquals(2, result.categoriesCreated)

        val food = db.txnDao().get(ImportRepository.txnId("1"))!!
        assertEquals(DefaultData.categoryId(CategoryType.EXPENSE, 0), food.categoryId)
        assertEquals(DefaultData.accountId(AccountGroupType.CASH), food.accountId)
        assertEquals("2026-09-01T12:00:00.000", food.dateTime)
        assertEquals("n-1", food.note)

        val transfer = db.txnDao().get(ImportRepository.txnId("4"))!!
        assertNull(transfer.categoryId)
        val bank = db.accountDao().get(transfer.toAccountId!!)!!
        assertEquals("Bank", bank.name)
        assertEquals(DefaultData.groupId(AccountGroupType.ACCOUNTS), bank.groupId)

        val card = db.txnDao().get(ImportRepository.txnId("5"))!!
        assertEquals(DefaultData.groupId(AccountGroupType.CARD), db.accountDao().get(card.accountId)!!.groupId)
        val lunch = db.categoryDao().all().single { it.id == card.categoryId }
        assertEquals("Lunch", lunch.name)
        assertEquals(DefaultData.categoryId(CategoryType.EXPENSE, 0), lunch.parentId)
    }

    @Test fun aRowsMainAndAccountAmountsAreStored() = runTest {
        val euro = txn("e", TxnType.EXPENSE, "Cash", "🍜 Food", 2_000).copy(currency = "EUR", amountMainMinor = 50_200, accountAmountMinor = 50_200)
        repo.apply(batch.copy(txns = listOf(euro, txn("c", TxnType.EXPENSE, "Cash", "🍜 Food", 9_900)), skipped = emptyList()))
        val rows = db.txnDao().allRows().associateBy { it.note }
        assertEquals(50_200L, rows.getValue("n-e").amountMain)
        assertEquals(50_200L, rows.getValue("n-e").accountAmount)
        assertEquals(9_900L, rows.getValue("n-c").amountMain) // no main column: 1:1
        assertEquals(null, rows.getValue("n-c").accountAmount)
    }

    @Test fun reImportIsIdempotent() = runTest {
        repo.apply(batch)
        val again = repo.apply(batch)
        assertEquals(0, again.imported)
        assertEquals(5, again.duplicates)
        assertEquals(0, again.accountsCreated)
        assertEquals(0, again.categoriesCreated)
        assertEquals(5, db.txnDao().count())
    }

    @Test fun reImportAfterEitherResetBringsTheEntriesBack() = runTest {
        val reset = ResetRepository(db, settings, Seeder(db, time), time, defaultCurrency = "CZK", photos = testPhotos())
        for (wipe in listOf<suspend () -> Unit>({ reset.resetContents() }, { reset.resetAll() })) {
            repo.apply(batch)
            wipe()
            val again = repo.apply(batch)
            assertEquals(5, again.imported)
            assertEquals(0, again.duplicates)
            assertEquals(5, db.txnDao().count())
            val revived = db.txnDao().get(ImportRepository.txnId("1"))!!
            assertEquals(false, revived.deleted)
            assertEquals(true, revived.dirty)
        }
    }

    @Test fun mainCurrencyComesFromFileOnlyWhenDatabaseIsEmpty() = runTest {
        repo.apply(batch)
        assertEquals("CZK", settings.current().mainCurrency)
        settings.setMainCurrency("EUR")
        repo.apply(batch.copy(mainCurrency = "GBP"))
        assertEquals("EUR", settings.current().mainCurrency)
    }

    @Test fun firstImportMovesUnusedSeededAccountsToTheFileCurrency() = runTest {
        // Seeded at first launch with the device-locale currency (USD here), before any import.
        Seeder(db, time).seedIfEmpty("USD")
        repo.apply(batch)
        val cash = db.accountDao().get(DefaultData.accountId(AccountGroupType.CASH))!!
        assertEquals("CZK", cash.currency)
        assertEquals("CZK", db.accountDao().get(DefaultData.accountId(AccountGroupType.CARD))!!.currency)
    }

    @Test fun guessesGroups() {
        assertEquals(AccountGroupType.CASH, ImportRepository.guessGroupType("Hotovost"))
        assertEquals(AccountGroupType.DEBIT_CARD, ImportRepository.guessGroupType("Debit card"))
        assertEquals(AccountGroupType.CARD, ImportRepository.guessGroupType("Credit"))
        assertEquals(AccountGroupType.SAVINGS, ImportRepository.guessGroupType("Savings"))
        assertEquals(AccountGroupType.ACCOUNTS, ImportRepository.guessGroupType("Fio"))
    }

    @Test fun mainCurrencyIsSavedOnlyAfterTheRowsAreWritten() = runTest {
        var countWhenSaved = -1
        val recording = object : SettingsRepository by settings {
            override suspend fun setMainCurrency(code: String) {
                countWhenSaved = db.txnDao().count()
                this@ImportRepositoryTest.settings.setMainCurrency(code)
            }
        }
        ImportRepository(db, recording, Seeder(db, time), time).apply(batch)
        assertEquals(batch.txns.size, countWhenSaved)
        assertEquals("CZK", settings.current().mainCurrency)
    }

    @Test fun `import reuses a deleted account by name`() = runTest {
        Seeder(db, time).seedIfEmpty("CZK")
        val accounts = AccountRepository(db, time)
        val bank = accounts.addGroup("Bank").let { g ->
            AccountEntity(id = "old", groupId = g.id, name = "Bank", currency = "CZK", initialBalance = 0, sortOrder = 0, updatedAt = 0)
                .also { accounts.save(it) }
        }
        accounts.delete(bank.id)
        accounts.purge("default-account-card") // a permanently deleted account is reused too
        val before = db.accountDao().allAccountRows().size
        val result = repo.apply(batch.copy(txns = listOf(txn("4", TxnType.TRANSFER, "Cash", null, 100_000, to = "Bank"), txn("5", TxnType.EXPENSE, "Card", "🍜 Food", 1_000))))
        assertEquals(0, result.accountsCreated)
        assertEquals(before, db.accountDao().allAccountRows().size)
        assertEquals("old", db.txnDao().get(ImportRepository.txnId("4"))!!.toAccountId)
        assertEquals("default-account-card", db.txnDao().get(ImportRepository.txnId("5"))!!.accountId)
    }

    @Test fun `import prefers a live account over a deleted one of the same name`() = runTest {
        Seeder(db, time).seedIfEmpty("CZK")
        val accounts = AccountRepository(db, time)
        val old = accounts.addGroup("Old").let { g ->
            AccountEntity(id = "old", groupId = g.id, name = "Bank", currency = "CZK", initialBalance = 0, sortOrder = 0, updatedAt = 0)
                .also { accounts.save(it) }
        }
        accounts.delete(old.id)
        accounts.save(AccountEntity(id = "new", groupId = "default-group-accounts", name = "Bank", currency = "CZK", initialBalance = 0, sortOrder = 0, updatedAt = 0))
        repo.apply(batch.copy(txns = listOf(txn("6", TxnType.TRANSFER, "Cash", null, 100_000, to = "Bank"))))
        assertEquals("new", db.txnDao().get(ImportRepository.txnId("6"))!!.toAccountId)
    }
}
