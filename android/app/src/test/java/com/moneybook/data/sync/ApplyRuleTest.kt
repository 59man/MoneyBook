package com.moneybook.data.sync

import com.moneybook.shared.sync.SyncChange
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** The client half of LWW must agree with the server's `(updated_at, device_id collate "C") <`. */
class ApplyRuleTest {
    private fun incoming(updatedAt: Long, deviceId: String) = SyncChange("txn", "t", updatedAt, false, deviceId, JsonObject(emptyMap()))

    @Test fun table() {
        val me = "dev-m"
        // localDirty, localUpdatedAt, incoming updatedAt, incoming device -> written
        val cases = listOf(
            Triple(false, 9L, incoming(1, "dev-a")) to true, // clean: always written, even when older
            Triple(false, 1L, incoming(9, "dev-z")) to true,
            Triple(true, 9L, incoming(1, "dev-z")) to false, // dirty and newer: kept
            Triple(true, 1L, incoming(9, "dev-a")) to true, // dirty but older: overwritten
            Triple(true, 5L, incoming(5, "dev-a")) to false, // tie, my id is greater
            Triple(true, 5L, incoming(5, "dev-z")) to true, // tie, theirs is greater
            Triple(true, 5L, incoming(5, me)) to true, // the echo of my own push: written, which cleans the row
            Triple(true, 5L, incoming(5, "dev-M")) to false, // plain String (UTF-16 = "C" for ASCII) compare: 'M' < 'm'
        )
        for ((input, expected) in cases) {
            val (dirty, at, c) = input
            assertEquals("dirty=$dirty local=$at in=${c.updatedAt}/${c.deviceId}", expected, incomingWins(at, dirty, me, c))
        }
    }
}
