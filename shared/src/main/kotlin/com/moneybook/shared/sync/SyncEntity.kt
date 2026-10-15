package com.moneybook.shared.sync

import com.moneybook.shared.dto.AccountDto
import com.moneybook.shared.dto.AccountGroupDto
import com.moneybook.shared.dto.AttachmentDto
import com.moneybook.shared.dto.BookmarkDto
import com.moneybook.shared.dto.BudgetDto
import com.moneybook.shared.dto.CategoryDto
import com.moneybook.shared.dto.CurrencyDto
import com.moneybook.shared.dto.MemoDto
import com.moneybook.shared.dto.RepeatRuleDto
import com.moneybook.shared.dto.SettingsDto
import com.moneybook.shared.dto.TxnDto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

enum class SyncEntity(val wire: String) {
    ACCOUNT_GROUP("account_group"), ACCOUNT("account"), CATEGORY("category"), TXN("txn"),
    MEMO("memo"), BOOKMARK("bookmark"), REPEAT_RULE("repeat_rule"), CURRENCY("currency"),
    BUDGET("budget"), ATTACHMENT("attachment"), SETTINGS("settings");

    companion object {
        // A newer app may send fields this version doesn't know.
        private val lenient = Json { ignoreUnknownKeys = true }

        fun fromWire(wire: String): SyncEntity? = entries.firstOrNull { it.wire == wire }

        /**
         * Checks [change] without looking at its size and returns its entity. Throws [IllegalArgumentException]
         * with a message that names the problem but never contains payload values.
         */
        fun validate(change: SyncChange): SyncEntity {
            val entity = requireNotNull(fromWire(change.entity)) { "unknown entity" }
            require(change.id.length in 1..SYNC_MAX_ID) { "id must be 1..$SYNC_MAX_ID characters" }
            // PostgreSQL's text and jsonb cannot hold U+0000: it would fail the whole batch with a 500 on every retry.
            require('\u0000' !in change.id && !change.payload.hasNul()) { "contains a NUL character" }
            val dto = try {
                entity.decode(change.payload)
            } catch (e: IllegalArgumentException) {
                // kotlinx messages can quote the input, so say only which entity failed.
                throw IllegalArgumentException("payload is not a valid ${entity.wire}")
            }
            if (dto is SettingsDto) require(change.id == SETTINGS_ID) { "settings id must be $SETTINGS_ID" }
            else require(change.id == dto.idOf()) { "id does not match the payload" }
            if (dto is AttachmentDto) require(ATTACHMENT_NAME_REGEX.matches(dto.fileName)) { "bad attachment file name" }
            return entity
        }

        private fun JsonElement.hasNul(): Boolean = when (this) {
            is JsonPrimitive -> isString && '\u0000' in content
            is JsonArray -> any { it.hasNul() }
            is JsonObject -> any { (k, v) -> '\u0000' in k || v.hasNul() }
        }

        private fun Any.idOf(): String = when (this) {
            is AccountGroupDto -> id
            is AccountDto -> id
            is CategoryDto -> id
            is TxnDto -> id
            is MemoDto -> id
            is BookmarkDto -> id
            is RepeatRuleDto -> id
            is CurrencyDto -> code
            is BudgetDto -> id
            is AttachmentDto -> id
            else -> error("no id")
        }
    }

    private fun decode(payload: JsonElement): Any = when (this) {
        ACCOUNT_GROUP -> lenient.decodeFromJsonElement(AccountGroupDto.serializer(), payload)
        ACCOUNT -> lenient.decodeFromJsonElement(AccountDto.serializer(), payload)
        CATEGORY -> lenient.decodeFromJsonElement(CategoryDto.serializer(), payload)
        TXN -> lenient.decodeFromJsonElement(TxnDto.serializer(), payload)
        MEMO -> lenient.decodeFromJsonElement(MemoDto.serializer(), payload)
        BOOKMARK -> lenient.decodeFromJsonElement(BookmarkDto.serializer(), payload)
        REPEAT_RULE -> lenient.decodeFromJsonElement(RepeatRuleDto.serializer(), payload)
        CURRENCY -> lenient.decodeFromJsonElement(CurrencyDto.serializer(), payload)
        BUDGET -> lenient.decodeFromJsonElement(BudgetDto.serializer(), payload)
        ATTACHMENT -> lenient.decodeFromJsonElement(AttachmentDto.serializer(), payload)
        SETTINGS -> lenient.decodeFromJsonElement(SettingsDto.serializer(), payload)
    }
}
