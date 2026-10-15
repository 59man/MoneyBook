package com.moneybook.shared.backup

import com.moneybook.shared.dto.BackupDto
import com.moneybook.shared.money.Fx
import com.moneybook.shared.money.SymbolPosition
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.time.LocalDate
import java.time.LocalTime
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class BackupException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** A MoneyBook backup file: a zip whose "backup.json" entry holds a [BackupDto]. */
object BackupCodec {
    const val FORMAT = "moneybook-backup"
    const val VERSION = 7
    const val ENTRY = "backup.json"
    const val PHOTOS = "photos/"
    /** What PhotoStore names a photo: the MD5 of its bytes. Anything else in a backup is refused, so a name can't leave the photo folder. */
    val PHOTO_NAME = Regex("[0-9a-f]{32}\\.jpg")
    private const val MAX_BYTES = 128L * 1024 * 1024
    /** PhotoStore keeps photos near 2 MiB; anything far larger in a backup is not ours. */
    private const val MAX_PHOTO_BYTES = 16L * 1024 * 1024
    /** A restore stops before the photos leave less than this free, so a crafted zip can't fill the disk. */
    private const val MIN_FREE_BYTES = 64L * 1024 * 1024
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** [photo] opens a photo file by name, or null when it is gone; each distinct photo is stored once under `photos/`. */
    fun write(backup: BackupDto, out: OutputStream, photo: (String) -> InputStream? = { null }) {
        val zip = ZipOutputStream(out)
        zip.putNextEntry(ZipEntry(ENTRY))
        zip.write(json.encodeToString(BackupDto.serializer(), backup).toByteArray(Charsets.UTF_8))
        zip.closeEntry()
        for (name in backup.attachments.map { it.fileName }.distinct()) {
            val input = photo(name) ?: continue
            input.use {
                zip.putNextEntry(ZipEntry(PHOTOS + name))
                it.copyTo(zip)
                zip.closeEntry()
            }
        }
        zip.finish()
    }

    /**
     * Reads and checks the whole backup before anything uses it. Throws [BackupException] with a message for the user.
     * The photo files are copied into [photoDir] as they stream by (null skips them); on a failure the caller removes the folder.
     */
    fun read(input: InputStream, photoDir: File? = null): BackupDto {
        val photos = HashSet<String>()
        val text = readEntries(input, photoDir, photos) ?: throw BackupException("This is not a MoneyBook backup.")
        // The header first: a newer backup may hold fields or values this version cannot decode.
        val header = decode(Header.serializer(), text)
        if (header.format != FORMAT) throw BackupException("This is not a MoneyBook backup.")
        if (header.version > VERSION) throw BackupException("This backup was made by a newer version of MoneyBook.")
        val backup = decode(BackupDto.serializer(), text)
        validate(backup, photos)
        return backup
    }

    @Serializable
    private class Header(val format: String = "", val version: Int = 0)

    private fun <T> decode(serializer: KSerializer<T>, text: String): T = try {
        json.decodeFromString(serializer, text)
    } catch (e: IllegalArgumentException) { // includes kotlinx SerializationException
        throw BackupException("The backup file is damaged.", e)
    }

    /** Returns backup.json's text; records the names of `photos/` entries in [photos] and copies them to [photoDir]. */
    private fun readEntries(input: InputStream, photoDir: File?, photos: MutableSet<String>): String? {
        var text: String? = null
        try {
            val zip = ZipInputStream(input)
            while (true) {
                val entry = zip.nextEntry ?: return text
                val photoName = entry.name.removePrefix(PHOTOS).takeIf { entry.name.startsWith(PHOTOS) && !entry.isDirectory }
                if (entry.name != ENTRY && (photoName == null || !PHOTO_NAME.matches(photoName))) continue
                val sink = if (photoName != null && photoDir != null) File(photoDir.also { it.mkdirs() }, photoName).outputStream() else null
                val bytes = if (photoName == null) ByteArrayOutputStream() else null
                try {
                    val chunk = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = zip.read(chunk)
                        if (n < 0) break
                        total += n
                        if (total > (if (photoName != null) MAX_PHOTO_BYTES else MAX_BYTES)) throw BackupException("The backup file is too large.")
                        if (sink != null && photoDir!!.usableSpace < MIN_FREE_BYTES) throw BackupException("There is not enough free space.")
                        bytes?.write(chunk, 0, n)
                        sink?.write(chunk, 0, n)
                    }
                } finally {
                    sink?.close()
                }
                if (photoName != null) photos += photoName else text = bytes!!.toString(Charsets.UTF_8)
            }
        } catch (e: ZipException) {
            throw BackupException("This is not a MoneyBook backup.", e)
        }
    }

    /** Every reference must point at a row in the same backup, or a restore would leave orphans. */
    private fun validate(b: BackupDto, photos: Set<String>) {
        fun damaged(what: String): Nothing = throw BackupException("The backup file is damaged ($what).")
        val groups = b.accountGroups.mapTo(HashSet()) { it.id }
        val accounts = b.accounts.mapTo(HashSet()) { it.id }
        val categories = b.categories.mapTo(HashSet()) { it.id }
        if (b.accountGroups.none { !it.deleted }) damaged("no account groups")
        b.accounts.forEach { if (it.groupId !in groups) damaged("account ${it.id} has no group") }
        b.categories.forEach { if (it.parentId != null && it.parentId !in categories) damaged("category ${it.id} has no parent") }
        b.txns.forEach { t ->
            if (t.accountId !in accounts) damaged("entry ${t.id} has no account")
            if (t.toAccountId != null && t.toAccountId !in accounts) damaged("entry ${t.id} has no target account")
            if (t.categoryId != null && t.categoryId !in categories) damaged("entry ${t.id} has no category")
        }
        b.bookmarks.forEach { m ->
            if (m.accountId !in accounts) damaged("bookmark ${m.id} has no account")
            if (m.toAccountId != null && m.toAccountId !in accounts) damaged("bookmark ${m.id} has no target account")
            if (m.categoryId != null && m.categoryId !in categories) damaged("bookmark ${m.id} has no category")
        }
        b.repeatRules.forEach { r ->
            if (r.accountId !in accounts) damaged("repeat rule ${r.id} has no account")
            if (r.toAccountId != null && r.toAccountId !in accounts) damaged("repeat rule ${r.id} has no target account")
            if (r.categoryId != null && r.categoryId !in categories) damaged("repeat rule ${r.id} has no category")
            if (runCatching { LocalDate.parse(r.startDate); LocalDate.parse(r.lastDate); LocalTime.parse(r.time) }.isFailure) {
                damaged("repeat rule ${r.id} has a bad date or time")
            }
            if (r.installments != null && r.installments !in 2..60) damaged("repeat rule ${r.id} has a bad installment count")
        }
        b.currencies.forEach { c ->
            if (SymbolPosition.entries.none { it.name == c.position }) damaged("currency ${c.code} has a bad unit position")
            if (Fx.parseRate(c.rate) == null) damaged("currency ${c.code} has a bad rate")
            if (c.decimals !in 0..8) damaged("currency ${c.code} has a bad decimal point")
        }
        val txns = b.txns.mapTo(HashSet()) { it.id }
        b.attachments.forEach { a ->
            if (a.txnId !in txns) damaged("photo ${a.id} has no entry")
            if (!PHOTO_NAME.matches(a.fileName)) damaged("photo ${a.id} has a bad file name")
            if (!a.deleted && a.fileName !in photos) damaged("photo ${a.id} is missing")
        }
        b.budgets.forEach { r ->
            if (r.categoryId != null && r.categoryId !in categories) damaged("budget ${r.id} has no category")
            if (r.fromMonth != 0 && (r.fromMonth % 100 !in 1..12 || r.fromMonth / 100 !in 1900..9999)) damaged("budget ${r.id} has a bad month")
        }
    }
}
