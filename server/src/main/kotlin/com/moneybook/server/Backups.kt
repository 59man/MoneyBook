package com.moneybook.server

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.PosixFilePermissions
import java.time.Clock
import java.time.DayOfWeek
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable
data class BackupStatus(val running: Boolean, val lastAt: Long?, val files: List<String>, val errorAt: Long?, val errorCode: String?)

private class BackupFailure(val code: String) : Exception(code)
private class BackupError(val at: Long, val code: String)

private val DIR_PERMS = PosixFilePermissions.fromString("rwx------")
private val FILE_PERMS = PosixFilePermissions.fromString("rw-------")

/**
 * Backs up the database ([dump]) and the attachments into [dir]/daily|weekly|monthly (7 / 4 / 6 kept).
 * One run at a time; a failed run deletes nothing and leaves no temp file.
 */
class BackupService(
    private val dir: Path,
    private val dataDir: Path,
    private val dump: (target: Path) -> Unit,
    private val clock: Clock,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private val running = AtomicBoolean(false)

    // ponytail: the last error lives in memory only; it is gone after a restart. Persist it if that ever matters.
    @Volatile private var lastError: BackupError? = null

    /** Starts a run on [scope]; false when one is already going. */
    fun launch(scope: CoroutineScope): Boolean {
        if (!running.compareAndSet(false, true)) return false
        scope.launch(Dispatchers.IO) { try { runOnce() } finally { running.set(false) } }
        return true
    }

    internal fun runOnce(): Boolean {
        val day = java.time.LocalDate.now(clock.withZone(zone))
        val dumpName = "moneybook-$day.dump"
        val tgzName = "attachments-$day.tgz"
        val daily = dir.resolve("daily")
        val dumpTmp = daily.resolve("$dumpName.tmp")
        val tgzTmp = daily.resolve("$tgzName.tmp")
        try {
            for (d in listOf(dir, daily, dir.resolve("weekly"), dir.resolve("monthly"))) {
                if (!Files.isDirectory(d)) Files.createDirectories(d, PosixFilePermissions.asFileAttribute(DIR_PERMS))
            }
            try { dump(dumpTmp) } catch (e: Exception) { throw BackupFailure("dump_failed") }
            Files.createDirectories(dataDir.resolve("attachments"))
            tar(tgzTmp)
            val dumpFile = place(dumpTmp, daily.resolve(dumpName))
            val tgzFile = place(tgzTmp, daily.resolve(tgzName))
            if (day.dayOfWeek == DayOfWeek.SUNDAY) copyPair(dumpFile, tgzFile, "weekly")
            if (day.dayOfMonth == 1) copyPair(dumpFile, tgzFile, "monthly")
            rotate("daily", 7); rotate("weekly", 4); rotate("monthly", 6)
            lastError = null
            return true
        } catch (e: Exception) {
            runCatching { Files.deleteIfExists(dumpTmp); Files.deleteIfExists(tgzTmp) }
            lastError = BackupError(clock.millis(), (e as? BackupFailure)?.code ?: "io_error")
            return false
        }
    }

    private fun tar(target: Path) {
        val p = ProcessBuilder("tar", "czf", target.toString(), "-C", dataDir.toString(), "attachments")
            .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        if (!p.waitFor(10, TimeUnit.MINUTES)) { p.destroyForcibly(); throw BackupFailure("tar_failed") }
        if (p.exitValue() != 0) throw BackupFailure("tar_failed")
    }

    private fun place(tmp: Path, target: Path): Path {
        Files.move(tmp, target, ATOMIC_MOVE, REPLACE_EXISTING)
        Files.setPosixFilePermissions(target, FILE_PERMS)
        return target
    }

    private fun copyPair(dumpFile: Path, tgzFile: Path, sub: String) {
        for (f in listOf(dumpFile, tgzFile)) {
            val target = dir.resolve(sub).resolve(f.fileName)
            val tmp = dir.resolve(sub).resolve("${f.fileName}.tmp")
            Files.copy(f, tmp, REPLACE_EXISTING)
            place(tmp, target)
        }
    }

    /** Keeps the newest [keep] dumps of [sub] (the date in the name sorts); a dump and its tgz go together. */
    private fun rotate(sub: String, keep: Int) {
        val d = dir.resolve(sub)
        val dumps = Files.list(d).use { s -> s.map { it.fileName.toString() }.filter { dumpNameRe.matches(it) }.sorted().toList() }
        for (name in dumps.dropLast(keep)) {
            Files.deleteIfExists(d.resolve(name))
            Files.deleteIfExists(d.resolve("attachments-" + name.removePrefix("moneybook-").removeSuffix(".dump") + ".tgz"))
        }
    }

    fun status(): BackupStatus {
        val daily = dir.resolve("daily")
        val newest = runCatching {
            Files.list(daily).use { s -> s.filter { dumpNameRe.matches(it.fileName.toString()) }.max(compareBy { it.fileName.toString() }).orElse(null) }
        }.getOrNull()
        val files = buildList {
            if (newest != null) {
                add(newest.fileName.toString())
                val tgz = "attachments-" + newest.fileName.toString().removePrefix("moneybook-").removeSuffix(".dump") + ".tgz"
                if (Files.exists(daily.resolve(tgz))) add(tgz)
            }
        }
        val err = lastError
        return BackupStatus(
            running = running.get(),
            lastAt = newest?.let { runCatching { Files.getLastModifiedTime(it).toMillis() }.getOrNull() },
            files = files,
            errorAt = err?.at,
            errorCode = err?.code,
        )
    }

    companion object {
        private val dumpNameRe = Regex("moneybook-\\d{4}-\\d{2}-\\d{2}\\.dump")

        fun fromConfig(config: Config, clock: Clock) =
            BackupService(Path.of(config.backupDir), Path.of(config.dataDir), pgDump(config), clock)
    }
}

private fun pgDump(config: Config): (Path) -> Unit {
    val uri = java.net.URI(config.dbUrl.removePrefix("jdbc:"))   // postgresql://127.0.0.1:5432/moneybook
    return { target ->
        val p = ProcessBuilder(
            "pg_dump", "-Fc", "-h", uri.host, "-p", uri.port.toString(), "-U", config.dbUser,
            "-f", target.toString(), uri.path.removePrefix("/"),
        ).apply {
            environment()["PGPASSWORD"] = config.dbPassword
            redirectErrorStream(true); redirectOutput(ProcessBuilder.Redirect.DISCARD)
        }.start()
        if (!p.waitFor(10, TimeUnit.MINUTES)) { p.destroyForcibly(); throw IOException("pg_dump timed out") }
        if (p.exitValue() != 0) throw IOException("pg_dump failed")
    }
}

/** The next 03:00 strictly after [now]. */
fun nextBackupAfter(now: ZonedDateTime): ZonedDateTime {
    val today = now.toLocalDate().atTime(3, 0).atZone(now.zone)
    return if (today.isAfter(now)) today else now.toLocalDate().plusDays(1).atTime(3, 0).atZone(now.zone)
}

fun CoroutineScope.scheduleBackups(b: BackupService, clock: Clock, zone: ZoneId): Job = launch {
    while (isActive) {
        val now = ZonedDateTime.now(clock.withZone(zone))
        delay(Duration.between(now, nextBackupAfter(now)).toMillis().coerceAtLeast(1_000))
        b.launch(this@scheduleBackups)
    }
}
