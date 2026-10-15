package com.moneybook.testutil

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.moneybook.data.db.AccountEntity
import com.moneybook.data.db.AccountGroupEntity
import com.moneybook.data.db.CategoryEntity
import com.moneybook.data.db.MoneyBookDatabase
import com.moneybook.data.db.TxnEntity
import com.moneybook.shared.model.AccountGroupType
import com.moneybook.shared.model.CategoryType
import com.moneybook.shared.model.TxnType

fun inMemoryDb(): MoneyBookDatabase =
    Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), MoneyBookDatabase::class.java)
        .allowMainThreadQueries()
        .addCallback(MoneyBookDatabase.monotonicCallback)
        .build()

object Fixtures {
    fun group(id: String = "g-cash", type: AccountGroupType = AccountGroupType.CASH, name: String = "Cash", sortOrder: Int = 0) =
        AccountGroupEntity(id = id, name = name, type = type, sortOrder = sortOrder, updatedAt = 0)

    fun account(
        id: String, groupId: String = "g-cash", name: String = id, initialBalance: Long = 0, sortOrder: Int = 0,
        currency: String = "CZK", hidden: Boolean = false, includeInTotals: Boolean = true, transferExpense: Boolean = false,
    ) = AccountEntity(
        id = id, groupId = groupId, name = name, currency = currency, initialBalance = initialBalance,
        includeInTotals = includeInTotals, hidden = hidden, sortOrder = sortOrder, updatedAt = 0, transferExpense = transferExpense,
    )

    fun category(id: String, type: CategoryType = CategoryType.EXPENSE, name: String = id, parentId: String? = null, sortOrder: Int = 0) =
        CategoryEntity(id = id, type = type, parentId = parentId, name = name, sortOrder = sortOrder, updatedAt = 0)

    fun txn(
        id: String, type: TxnType, dateTime: String, accountId: String, amount: Long,
        categoryId: String? = null, toAccountId: String? = null, note: String = "", fee: Long = 0,
    ) = TxnEntity(
        id = id, type = type, dateTime = dateTime, accountId = accountId, toAccountId = toAccountId,
        categoryId = categoryId, amount = amount, currency = "CZK", amountMain = amount, fee = fee,
        note = note, updatedAt = 0,
    )
}

/** A photo folder in a fresh temp directory. */
fun testPhotos() = com.moneybook.data.PhotoStore(java.nio.file.Files.createTempDirectory("photos").toFile().resolve("photo"))
