package com.moneybook.server

import com.moneybook.server.SyncFixtures.change
import com.moneybook.server.SyncFixtures.sync
import com.moneybook.server.testutil.PostgresTestBase
import com.moneybook.shared.sync.SyncChange
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class SyncConcurrencyTest : PostgresTestBase() {
    private data class Seen(val updatedAt: Long, val deviceId: String, val payload: String)

    private fun storedRows(): Map<String, Seen> = ds.inTransaction { c ->
        c.createStatement().use { st ->
            st.executeQuery("select id, updated_at, device_id, payload::text from sync_record").use { rs ->
                buildMap {
                    while (rs.next()) put(rs.getString(1), Seen(rs.getLong(2), rs.getString(3), canonical(rs.getString(4))))
                }
            }
        }
    }

    private fun canonical(json: String) = Json.parseToJsonElement(json).jsonObject.toString()

    @Test
    fun `a pulling client misses no row while one user's devices push in parallel`() = withApp { client ->
        val u = insertUser("alice")
        val writers = 6
        val requests = 20
        val perRequest = 25
        val tokens = List(writers) { SyncFixtures.token(u, "dev-$it") }
        val reader = SyncFixtures.token(u, "reader")

        val done = AtomicBoolean(false)
        val received = mutableMapOf<String, Seen>()
        fun apply(changes: List<SyncChange>) = changes.forEach {
            received[it.id] = Seen(it.updatedAt, it.deviceId, it.payload.toString())
        }

        coroutineScope {
            val readerJob = launch(Dispatchers.Default) {
                var cursor = 0L
                while (!done.get()) {
                    val r = client.sync(reader, cursor)
                    apply(r.changes)
                    cursor = r.newCursor
                }
                do {
                    val r = client.sync(reader, cursor)
                    apply(r.changes)
                    cursor = r.newCursor
                } while (r.hasMore)
            }
            val acked = (0 until writers).map { w ->
                async(Dispatchers.Default) {
                    (0 until requests).flatMap { r ->
                        val ids = (0 until perRequest).map { k -> "w$w-r$r-k$k" }
                        client.sync(tokens[w], Long.MAX_VALUE, ids.map { change(it, 1000L + r) })
                        ids
                    }
                }
            }.awaitAll().flatten()
            done.set(true)
            readerJob.join()

            assertEquals(writers * requests * perRequest, acked.size)
            val missed = acked.filterNot { it in received }
            assertTrue(missed.isEmpty(), "reader missed ${missed.size} rows, e.g. ${missed.take(5)}")
            assertEquals(storedRows(), received.mapValues { (_, v) -> v.copy(payload = canonical(v.payload)) })
        }
    }

    @Test
    fun `racing writers on one row leave the larger updatedAt and device id stored`() = withApp { client ->
        val u = insertUser("alice")
        val d1 = SyncFixtures.token(u, "d1")
        val d2 = SyncFixtures.token(u, "D2")
        repeat(20) { round ->
            val id = "row-$round"
            // odd rounds tie on updatedAt, so the device id decides ("d1" > "D2" by code point)
            val t1 = 1000L + round
            val t2 = if (round % 2 == 1) t1 else 1000L + (round * 7) % 40
            coroutineScope {
                listOf(d1 to t1, d2 to t2).map { (token, t) ->
                    async(Dispatchers.Default) { repeat(3) { client.sync(token, Long.MAX_VALUE, listOf(change(id, t, "n$t"))) } }
                }.awaitAll()
            }
            val expected = maxOf(t1 to "d1", t2 to "D2", compareBy({ it.first }, { it.second }))
            val stored = withContext(Dispatchers.IO) { storedRows().getValue(id) }
            assertEquals(expected, stored.updatedAt to stored.deviceId, "round $round")
        }
    }
}
