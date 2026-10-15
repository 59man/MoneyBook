package com.moneybook.data

import androidx.room.withTransaction
import com.moneybook.data.db.AccountEntity
import com.moneybook.data.db.AccountGroupEntity
import com.moneybook.data.db.CategoryEntity
import com.moneybook.data.db.MoneyBookDatabase
import com.moneybook.shared.model.AccountGroupType
import com.moneybook.shared.model.CategoryType

/** Realbyte's fresh-install data (captured in docs/ui-spec.md). Ids are deterministic so devices merge in M4. */
object DefaultData {
    val groups: List<Pair<AccountGroupType, String>> = listOf(
        AccountGroupType.CASH to "Cash",
        AccountGroupType.ACCOUNTS to "Accounts",
        AccountGroupType.CARD to "Card",
        AccountGroupType.DEBIT_CARD to "Debit Card",
        AccountGroupType.SAVINGS to "Savings",
        AccountGroupType.TOPUP_PREPAID to "Top-Up/Prepaid",
        AccountGroupType.INVESTMENTS to "Investments",
        AccountGroupType.OVERDRAFTS to "Overdrafts",
        AccountGroupType.LOAN to "Loan",
        AccountGroupType.INSURANCE to "Insurance",
        AccountGroupType.OTHERS to "Others",
    )
    val accounts: List<Pair<AccountGroupType, String>> = listOf(
        AccountGroupType.CASH to "Cash",
        AccountGroupType.ACCOUNTS to "Accounts",
        AccountGroupType.CARD to "Card",
    )
    val expenseCategories = listOf(
        "🍜 Food", "👬🏻 Social Life", "🐶 Pets", "🚖 Transport", "🖼 Culture", "🪑 Household",
        "🧥 Apparel", "💄 Beauty", "🧘🏼 Health/Fitness", "📙 Education", "🎁 Gift", "Other",
    )
    val incomeCategories = listOf("🤑 Allowance", "💰 Salary", "💵 Petty cash", "🏅 Bonus", "Other")

    fun groupId(type: AccountGroupType) = "default-group-${type.name.lowercase()}"
    fun accountId(type: AccountGroupType) = "default-account-${type.name.lowercase()}"
    fun categoryId(type: CategoryType, index: Int) = "default-category-${type.name.lowercase()}-$index"
}

class Seeder(private val db: MoneyBookDatabase, private val time: TimeSource) {
    /** [stamp] is the rows' updatedAt: null = now (Reset's re-seed, imports); 0 = the first start, so a seed loses to every synced row. */
    suspend fun seedIfEmpty(mainCurrency: String, stamp: Long? = null) {
        db.withTransaction {
            if (db.accountDao().groups().isNotEmpty()) return@withTransaction
            val now = stamp ?: time.millis()
            db.accountDao().upsertGroups(DefaultData.groups.mapIndexed { i, (type, name) ->
                AccountGroupEntity(id = DefaultData.groupId(type), name = name, type = type, sortOrder = i, updatedAt = now)
            })
            db.accountDao().upsertAll(DefaultData.accounts.mapIndexed { i, (type, name) ->
                AccountEntity(
                    id = DefaultData.accountId(type), groupId = DefaultData.groupId(type), name = name,
                    currency = mainCurrency, initialBalance = 0, sortOrder = i, updatedAt = now,
                )
            })
            fun categories(type: CategoryType, names: List<String>) = names.mapIndexed { i, name ->
                CategoryEntity(id = DefaultData.categoryId(type, i), type = type, name = name, sortOrder = i, updatedAt = now)
            }
            db.categoryDao().upsertAll(
                categories(CategoryType.EXPENSE, DefaultData.expenseCategories) +
                    categories(CategoryType.INCOME, DefaultData.incomeCategories),
            )
        }
    }
}
