package com.moneybook.data.sync

import com.moneybook.data.DeviceSettings
import com.moneybook.data.RepeatRepository
import com.moneybook.data.Seeder
import com.moneybook.data.TimeSource
import com.moneybook.shared.sync.LoginRequest
import com.moneybook.testutil.FakeDeviceSettingsRepository
import com.moneybook.testutil.FakeSettingsRepository
import com.moneybook.testutil.inMemoryDb
import com.moneybook.testutil.testPhotos
import java.io.Closeable
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** A fixed past day, so rows stamped by the app's clock are never clamped by the fake server's real clock. */
val SYNC_TEST_CLOCK: Clock = Clock.fixed(Instant.parse("2026-01-15T10:00:00Z"), ZoneOffset.UTC)

/** One phone: its own in-memory database, settings, photo folder and engine, talking to [server] as [id]. */
class SyncDevice(val server: FakeSyncServer, val id: String, clock: Clock = SYNC_TEST_CLOCK) : Closeable {
    val db = inMemoryDb()
    val settings = FakeSettingsRepository()
    val device = FakeDeviceSettingsRepository(DeviceSettings(deviceId = id))
    val photos = testPhotos()
    val time = TimeSource(clock)
    val repeats = RepeatRepository(db, time)
    val api = server.api(id)
    val engine = SyncEngine(db, settings, device, api, photos, repeats, time)

    /** The first start's defaults, stamped 0. */
    suspend fun seed() = Seeder(db, time).seedIfEmpty("CZK", stamp = 0)

    /** What Task 4's login does: tokens stored, cursor and epoch cleared, everything marked dirty. */
    suspend fun login(key: String) {
        api.login("https://fake", LoginRequest(key, id, "test"))
        device.update { it.copy(serverUrl = "https://fake", refreshToken = "refresh-$id", syncCursor = 0, syncEpoch = "") }
        engine.markAllDirty()
    }

    suspend fun sync(): SyncOutcome = engine.sync()

    override fun close() = db.close()
}
