package com.moneybook.shared.model

enum class TxnType { INCOME, EXPENSE, TRANSFER }

enum class CategoryType { INCOME, EXPENSE }

/** M2k: an account or group is normal, deleted (restorable) or permanently deleted (never shown again, but still a live row for its entries). */
enum class ArchiveState { ACTIVE, DELETED, PURGED }

enum class AccountGroupType {
    CASH, ACCOUNTS, CARD, DEBIT_CARD, SAVINGS, TOPUP_PREPAID,
    INVESTMENTS, OVERDRAFTS, LOAN, INSURANCE, OTHERS,
}

/** Rep/Inst. → Repeat, in Realbyte's order; [label] is Realbyte's wording. */
enum class RepeatFrequency(val label: String) {
    DAILY("Every Day"),
    WEEKDAYS("Weekdays"),
    WEEKEND("Weekend"),
    WEEKLY("Every Week"),
    EVERY_2_WEEKS("Every 2 weeks"),
    EVERY_4_WEEKS("Every 4 weeks"),
    MONTHLY("Every Month"),
    END_OF_MONTH("The end of the month"),
    EVERY_2_MONTHS("Every 2 Month"),
    EVERY_3_MONTHS("Every 3 Month"),
    EVERY_4_MONTHS("Every 4 Month"),
    EVERY_6_MONTHS("Every 6 Month"),
    YEARLY("Annually"),
}
