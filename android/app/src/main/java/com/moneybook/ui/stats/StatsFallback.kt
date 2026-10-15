package com.moneybook.ui.stats

import com.moneybook.data.CategoryRepository
import com.moneybook.domain.CategoryRef
import com.moneybook.shared.model.CategoryType
import com.moneybook.shared.model.TxnType

/** The type's "Other" category, where Stats counts entries that have no category. */
internal suspend fun CategoryRepository.statsFallback(type: TxnType): CategoryRef? {
    val categoryType = if (type == TxnType.INCOME) CategoryType.INCOME else CategoryType.EXPENSE
    return top(categoryType).firstOrNull { it.name == "Other" }?.let { CategoryRef(it.id, it.name) }
}
