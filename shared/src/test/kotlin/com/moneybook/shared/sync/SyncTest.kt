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
import com.moneybook.shared.model.AccountGroupType
import com.moneybook.shared.model.CategoryType
import com.moneybook.shared.model.RepeatFrequency
import com.moneybook.shared.model.TxnType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncTest {
    private val json = Json

    private val hash = "0123456789abcdef0123456789abcdef"

    private fun obj(entity: SyncEntity): JsonObject = when (entity) {
        SyncEntity.ACCOUNT_GROUP -> json.encodeToJsonElement(AccountGroupDto.serializer(), AccountGroupDto("g1", "Cash", AccountGroupType.CASH, 0, 5))
        SyncEntity.ACCOUNT -> json.encodeToJsonElement(AccountDto.serializer(), AccountDto("a1", "g1", "Wallet", "CZK", 0, sortOrder = 0, updatedAt = 5))
        SyncEntity.CATEGORY -> json.encodeToJsonElement(CategoryDto.serializer(), CategoryDto("c1", CategoryType.EXPENSE, null, "Food", 0, updatedAt = 5))
        SyncEntity.TXN -> json.encodeToJsonElement(TxnDto.serializer(), TxnDto("t1", TxnType.EXPENSE, "2026-10-12T10:00:00.000", "a1", categoryId = "c1", amount = 100, currency = "CZK", amountMain = 100, updatedAt = 5))
        SyncEntity.MEMO -> json.encodeToJsonElement(MemoDto.serializer(), MemoDto("m1", "2026-10-12", updatedAt = 5))
        SyncEntity.BOOKMARK -> json.encodeToJsonElement(BookmarkDto.serializer(), BookmarkDto("b1", "Lunch", TxnType.EXPENSE, "a1", amount = 100, currency = "CZK", createdAt = 1, updatedAt = 5))
        SyncEntity.REPEAT_RULE -> json.encodeToJsonElement(RepeatRuleDto.serializer(), RepeatRuleDto("r1", RepeatFrequency.MONTHLY, "2026-10-01", "2026-10-01", null, TxnType.EXPENSE, "a1", amount = 100, currency = "CZK", time = "09:00", updatedAt = 5))
        SyncEntity.CURRENCY -> json.encodeToJsonElement(CurrencyDto.serializer(), CurrencyDto("EUR", "€", "END", 2, "25.00", 0, 5))
        SyncEntity.BUDGET -> json.encodeToJsonElement(BudgetDto.serializer(), BudgetDto("budget-total-0", null, 0, 1000, 5))
        SyncEntity.ATTACHMENT -> json.encodeToJsonElement(AttachmentDto.serializer(), AttachmentDto("t1/$hash.jpg", "t1", "$hash.jpg", 0, 5))
        SyncEntity.SETTINGS -> json.encodeToJsonElement(SettingsDto.serializer(), SettingsDto("CZK")) as JsonObject
    }.jsonObject

    private fun idOf(entity: SyncEntity): String = when (entity) {
        SyncEntity.CURRENCY -> "EUR"
        SyncEntity.SETTINGS -> SETTINGS_ID
        else -> obj(entity)["id"]!!.jsonPrimitive.content
    }

    private fun change(entity: SyncEntity, id: String = idOf(entity), payload: JsonObject = obj(entity), wire: String = entity.wire) =
        SyncChange(wire, id, 5, false, payload = payload)

    private fun without(o: JsonObject, key: String) = JsonObject(o - key)

    @Test fun `validate accepts every entity`() {
        for (e in SyncEntity.entries) assertEquals(e, SyncEntity.validate(change(e)), e.wire)
    }

    @Test fun `fromWire maps the 11 wire names and nothing else`() {
        assertEquals(11, SyncEntity.entries.size)
        for (e in SyncEntity.entries) assertEquals(e, SyncEntity.fromWire(e.wire))
        assertNull(SyncEntity.fromWire("TXN"))
        assertNull(SyncEntity.fromWire("nope"))
    }

    @Test fun `validate rejects unknown entity empty id and long id`() {
        assertFailsWith<IllegalArgumentException> { SyncEntity.validate(change(SyncEntity.TXN, wire = "nope")) }
        assertFailsWith<IllegalArgumentException> { SyncEntity.validate(change(SyncEntity.TXN, id = "")) }
        val long = "x".repeat(201)
        assertFailsWith<IllegalArgumentException> {
            SyncEntity.validate(change(SyncEntity.TXN, id = long, payload = JsonObject(obj(SyncEntity.TXN) + ("id" to JsonPrimitive(long)))))
        }
        val ok = "x".repeat(200)
        SyncEntity.validate(change(SyncEntity.TXN, id = ok, payload = JsonObject(obj(SyncEntity.TXN) + ("id" to JsonPrimitive(ok)))))
    }

    @Test fun `validate rejects payloads that do not decode`() {
        for (e in SyncEntity.entries) {
            val required = if (e == SyncEntity.SETTINGS) "mainCurrency" else "updatedAt"
            assertFailsWith<IllegalArgumentException>("$e missing") {
                SyncEntity.validate(change(e, payload = without(obj(e), required)))
            }
            assertFailsWith<IllegalArgumentException>("$e wrong type") {
                SyncEntity.validate(change(e, payload = JsonObject(obj(e) + (required to buildJsonObject { put("x", 1) }))))
            }
        }
    }

    @Test fun `decode errors do not leak payload values`() {
        val p = JsonObject(obj(SyncEntity.TXN) + ("amount" to JsonPrimitive("SECRET-VALUE-123")))
        val e = assertFailsWith<IllegalArgumentException> { SyncEntity.validate(change(SyncEntity.TXN, payload = p)) }
        assertFalse(e.message!!.contains("SECRET"))
        val e2 = assertFailsWith<IllegalArgumentException> {
            SyncEntity.validate(change(SyncEntity.MEMO, id = "SECRET-ID", payload = obj(SyncEntity.MEMO)))
        }
        assertFalse(e2.message!!.contains("SECRET"))
    }

    @Test fun `validate rejects NUL characters in the id, string values, keys and nested arrays`() {
        val nul = "a\u0000b"
        val note = JsonObject(obj(SyncEntity.TXN) + ("note" to JsonPrimitive(nul)))
        val key = JsonObject(obj(SyncEntity.TXN) + (nul to JsonPrimitive("x")))
        val nested = JsonObject(obj(SyncEntity.TXN) + ("extra" to buildJsonObject { put("list", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive(nul)))) }))
        for (p in listOf(note, key, nested)) {
            val e = assertFailsWith<IllegalArgumentException> { SyncEntity.validate(change(SyncEntity.TXN, payload = p)) }
            assertFalse(e.message!!.contains("\u0000"))
        }
        val idP = JsonObject(obj(SyncEntity.MEMO) + ("id" to JsonPrimitive(nul)))
        assertFailsWith<IllegalArgumentException> { SyncEntity.validate(change(SyncEntity.MEMO, id = nul, payload = idP)) }
    }

    @Test fun `validate rejects an id that differs from the payload id`() {
        for (e in SyncEntity.entries - SyncEntity.SETTINGS) {
            assertFailsWith<IllegalArgumentException>(e.wire) { SyncEntity.validate(change(e, id = "other")) }
        }
    }

    @Test fun `currency id is the code`() {
        SyncEntity.validate(change(SyncEntity.CURRENCY, id = "EUR"))
        assertFailsWith<IllegalArgumentException> {
            SyncEntity.validate(change(SyncEntity.CURRENCY, payload = JsonObject(obj(SyncEntity.CURRENCY) + ("code" to JsonPrimitive("USD")))))
        }
    }

    @Test fun `settings id must be app`() {
        SyncEntity.validate(change(SyncEntity.SETTINGS, id = "app"))
        assertFailsWith<IllegalArgumentException> { SyncEntity.validate(change(SyncEntity.SETTINGS, id = "App")) }
        assertFailsWith<IllegalArgumentException> { SyncEntity.validate(change(SyncEntity.SETTINGS, id = "other")) }
    }

    @Test fun `attachment file name rule`() {
        fun att(name: String) = change(
            SyncEntity.ATTACHMENT,
            payload = JsonObject(obj(SyncEntity.ATTACHMENT) + ("fileName" to JsonPrimitive(name))),
        )
        for (bad in listOf("${hash.uppercase()}.jpg", "${hash.dropLast(1)}.jpg", "$hash.png", "../x.jpg", "$hash.jpg\n", "$hash.jpg.jpg", "x$hash.jpg")) {
            assertFailsWith<IllegalArgumentException>(bad) { SyncEntity.validate(att(bad)) }
        }
        SyncEntity.validate(att("$hash.jpg"))
    }

    @Test fun `extra unknown keys are accepted and kept by overwriteEnvelope`() {
        val p = JsonObject(obj(SyncEntity.TXN) + ("futureField" to JsonPrimitive("v")))
        SyncEntity.validate(change(SyncEntity.TXN, payload = p))
        assertEquals(JsonPrimitive("v"), overwriteEnvelope(p, 9, true)["futureField"])
    }

    @Test fun `overwriteEnvelope replaces present keys only`() {
        // encodeDefaults is off, so `deleted = false` is absent from a DTO's JSON until a payload sets it
        val p = JsonObject(obj(SyncEntity.TXN) + ("deleted" to JsonPrimitive(false)))
        val out = overwriteEnvelope(p, 99, true)
        assertEquals(99L, out["updatedAt"]!!.jsonPrimitive.long)
        assertTrue(out["deleted"]!!.jsonPrimitive.boolean)
        assertEquals(5L, p["updatedAt"]!!.jsonPrimitive.long)
        assertEquals(p.keys, out.keys)
        assertEquals(p - "updatedAt" - "deleted", out.toMap() - "updatedAt" - "deleted")
        assertFalse("deleted" in overwriteEnvelope(obj(SyncEntity.TXN), 99, true))
        val settings = obj(SyncEntity.SETTINGS)
        assertEquals(settings, overwriteEnvelope(settings, 99, true))
        assertFalse("updatedAt" in overwriteEnvelope(settings, 99, true))
    }

    @Test fun `request round trip stays valid`() {
        val req = SyncRequest(7, SyncEntity.entries.map { change(it) })
        val back = json.decodeFromString(SyncRequest.serializer(), json.encodeToString(SyncRequest.serializer(), req))
        assertEquals(req, back)
        for (c in back.changes) SyncEntity.validate(c)
    }

    @Test fun `other wire types round trip`() {
        val login = LoginRequest("key", "dev", "Phone")
        assertEquals(login, json.decodeFromString(LoginRequest.serializer(), json.encodeToString(LoginRequest.serializer(), login)))
        val tok = TokenResponse("a", "r", 900)
        assertEquals(tok, json.decodeFromString(TokenResponse.serializer(), json.encodeToString(TokenResponse.serializer(), tok)))
        val refresh = RefreshRequest("r")
        assertEquals(refresh, json.decodeFromString(RefreshRequest.serializer(), json.encodeToString(RefreshRequest.serializer(), refresh)))
        val resp = SyncResponse(listOf(change(SyncEntity.TXN).copy(deviceId = "d1")), 12, true, "e1")
        assertEquals(resp, json.decodeFromString(SyncResponse.serializer(), json.encodeToString(SyncResponse.serializer(), resp)))
    }

    @Test fun `change decodes without deviceId and request without changes`() {
        val c = json.decodeFromString(
            SyncChange.serializer(),
            """{"entity":"memo","id":"m1","updatedAt":5,"deleted":false,"payload":{}}""",
        )
        assertEquals("", c.deviceId)
        assertTrue(json.decodeFromString(SyncRequest.serializer(), """{"cursor":3}""").changes.isEmpty())
        assertEquals("", json.decodeFromString(SyncResponse.serializer(), """{"changes":[],"newCursor":0,"hasMore":false}""").epoch)
    }

    @Test fun `limits`() {
        assertEquals(1000, SYNC_MAX_CHANGES)
        assertEquals(65536, SYNC_MAX_PAYLOAD_BYTES)
        assertEquals(16777216, SYNC_MAX_BODY_BYTES)
        assertEquals(200, SYNC_MAX_ID)
    }
}
