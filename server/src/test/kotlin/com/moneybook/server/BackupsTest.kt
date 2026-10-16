package com.moneybook.server

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking

class BackupsTest {
    class TestClock(var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = now
    }

    private val root: Path = Files.createTempDirectory("mb-backups")
    private val dir = root.resolve("backups")
    private val data = root.resolve("data")
    private val utc = ZoneId.of("UTC")

    @AfterTest fun cleanup() { root.toFile().deleteRecursively() }

    private fun day(s: String) = Instant.parse("${s}T10:00:00Z")
    private fun attachments() { Files.createDirectories(data.resolve("attachments/u1")); Files.writeString(data.resolve("attachments/u1/a.jpg"), "jpg") }
    private fun service(clock: Clock, dataDir: Path = data, dump: (Path) -> Unit = { Files.writeString(it, "dump") }) =
        BackupService(dir, dataDir, dump, clock, utc)

    private fun names(sub: String): List<String> =
        Files.list(dir.resolve(sub)).use { s -> s.map { it.fileName.toString() }.sorted().toList() }

    private fun perms(p: Path) = PosixFilePermissions.toString(Files.getPosixFilePermissions(p))

    @Test
    fun `one run writes both files with owner-only permissions`() {
        attachments()
        val s = service(TestClock(day("2026-10-14")))
        assertTrue(s.runOnce())
        assertEquals(listOf("attachments-2026-10-14.tgz", "moneybook-2026-10-14.dump"), names("daily"))
        for (f in names("daily")) assertEquals("rw-------", perms(dir.resolve("daily/$f")))
        for (d in listOf("", "daily", "weekly", "monthly")) assertEquals("rwx------", perms(dir.resolve(d)))
        val tar = ProcessBuilder("tar", "tzf", dir.resolve("daily/attachments-2026-10-14.tgz").toString()).start()
        assertTrue(tar.inputStream.readBytes().decodeToString().contains("attachments/u1/a.jpg"))
        val st = s.status()
        assertEquals(listOf("moneybook-2026-10-14.dump", "attachments-2026-10-14.tgz"), st.files)
        assertNotNull(st.lastAt); assertNull(st.errorCode); assertFalse(st.running)
    }

    @Test
    fun `a missing attachments directory still gives a valid tgz`() {
        val s = service(TestClock(day("2026-10-14")))
        assertTrue(s.runOnce())
        assertTrue(Files.isDirectory(data.resolve("attachments")))
        assertEquals(0, ProcessBuilder("tar", "tzf", dir.resolve("daily/attachments-2026-10-14.tgz").toString()).start().waitFor())
    }

    private fun runDays(start: String, count: Int, only: (java.time.LocalDate) -> Boolean = { true }) {
        attachments()
        val clock = TestClock(day(start))
        val s = service(clock)
        repeat(count) {
            if (only(java.time.LocalDate.ofInstant(clock.now, utc))) assertTrue(s.runOnce())
            clock.now = clock.now.plusSeconds(86_400)
        }
    }

    @Test
    fun `retention keeps 7 daily, 4 weekly and the first of the month`() {
        runDays("2026-09-14", 40)       // Mon 2026-09-14 .. Fri 2026-10-23
        val daily = names("daily")
        assertEquals(14, daily.size)
        assertEquals((17..23).map { "2026-10-$it" }.toSet(), daily.filter { it.endsWith(".dump") }.map { it.substring(10, 20) }.toSet())
        assertEquals(daily.filter { it.endsWith(".dump") }.map { it.replace("moneybook-", "").removeSuffix(".dump") }.sorted(),
            daily.filter { it.endsWith(".tgz") }.map { it.replace("attachments-", "").removeSuffix(".tgz") }.sorted())
        val weekly = names("weekly").filter { it.endsWith(".dump") }
        assertEquals(listOf("2026-09-27", "2026-10-04", "2026-10-11", "2026-10-18").map { "moneybook-$it.dump" }, weekly)
        assertEquals(8, names("weekly").size)
        assertEquals(listOf("attachments-2026-10-01.tgz", "moneybook-2026-10-01.dump"), names("monthly"))
        for (f in names("weekly")) assertEquals("rw-------", perms(dir.resolve("weekly/$f")))
    }

    @Test
    fun `eight first-of-month runs keep six`() {
        attachments()
        val clock = TestClock(day("2026-01-01"))
        val s = service(clock)
        for (m in 1..8) { clock.now = day("2026-%02d-01".format(m)); assertTrue(s.runOnce()) }
        assertEquals(6, names("monthly").count { it.endsWith(".dump") })
        assertEquals(6, names("monthly").count { it.endsWith(".tgz") })
        assertTrue(names("monthly").contains("moneybook-2026-08-01.dump"))
        assertFalse(names("monthly").contains("moneybook-2026-02-01.dump"))
    }

    private fun noTmp() = listOf("daily", "weekly", "monthly").forEach { assertTrue(names(it).none { n -> n.endsWith(".tmp") }, it) }

    @Test
    fun `a failing dump keeps everything and a later good run clears the error`() {
        attachments()
        val clock = TestClock(day("2026-10-01"))
        var fail = false
        val s = service(clock) { if (fail) { Files.writeString(it, "partial"); error("boom") } else Files.writeString(it, "dump") }
        assertTrue(s.runOnce()); clock.now = day("2026-10-02"); assertTrue(s.runOnce())
        val before = names("daily")
        fail = true; clock.now = day("2026-10-03")
        assertFalse(s.runOnce())
        val st = s.status()
        assertEquals("dump_failed", st.errorCode); assertNotNull(st.errorAt)
        assertEquals(before, names("daily")); noTmp()
        fail = false; clock.now = day("2026-10-04")
        assertTrue(s.runOnce())
        assertNull(s.status().errorCode)
    }

    @Test
    fun `an unusable data directory fails and deletes nothing`() {
        attachments()
        val clock = TestClock(day("2026-10-01"))
        assertTrue(service(clock).runOnce())
        val before = names("daily")
        Files.writeString(root.resolve("file"), "x")
        clock.now = day("2026-10-02")
        val s = service(clock, dataDir = root.resolve("file/sub"))
        assertFalse(s.runOnce())
        assertTrue(s.status().errorCode in setOf("tar_failed", "io_error"))
        assertEquals(before, names("daily")); noTmp()
    }

    @Test
    fun `next backup is the next 0300`() {
        fun at(s: String, zone: String = "UTC") = ZonedDateTime.parse(s).withZoneSameLocal(ZoneId.of(zone))
        assertEquals(at("2026-10-14T03:00:00Z"), nextBackupAfter(at("2026-10-14T02:59:00Z")))
        assertEquals(at("2026-10-15T03:00:00Z"), nextBackupAfter(at("2026-10-14T03:00:00Z")))
        assertEquals(at("2026-10-15T03:00:00Z"), nextBackupAfter(at("2026-10-14T23:00:00Z")))
        for (d in listOf("2026-10-25", "2026-03-29")) for (h in listOf("00:30", "01:30", "04:00")) {
            val now = ZonedDateTime.of(java.time.LocalDate.parse(d), java.time.LocalTime.parse(h), ZoneId.of("Europe/Prague"))
            val next = nextBackupAfter(now)
            assertTrue(next.isAfter(now)); assertEquals(java.time.LocalTime.of(3, 0), next.toLocalTime())
        }
    }

    @Test
    fun `only one run at a time`() = runBlocking {
        attachments()
        val started = CountDownLatch(1); val release = CountDownLatch(1)
        val s = service(TestClock(day("2026-10-14"))) { started.countDown(); release.await(); Files.writeString(it, "dump") }
        val scope = CoroutineScope(Dispatchers.IO + Job())
        assertTrue(s.launch(scope))
        assertTrue(started.await(5, TimeUnit.SECONDS))
        assertTrue(s.status().running)
        assertFalse(s.launch(scope))
        release.countDown()
        val deadline = System.currentTimeMillis() + 10_000
        while (s.status().running && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertFalse(s.status().running)
        assertTrue(s.launch(scope))
        while (s.status().running && System.currentTimeMillis() < deadline) Thread.sleep(20)
    }
}
