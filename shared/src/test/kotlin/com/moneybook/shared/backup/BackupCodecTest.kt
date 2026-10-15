package com.moneybook.shared.backup

import com.moneybook.shared.dto.AttachmentDto
import com.moneybook.shared.dto.BudgetDto
import com.moneybook.shared.dto.AccountDto
import com.moneybook.shared.dto.AccountGroupDto
import com.moneybook.shared.dto.BackupDto
import com.moneybook.shared.dto.BookmarkDto
import com.moneybook.shared.dto.CategoryDto
import com.moneybook.shared.dto.CurrencyDto
import com.moneybook.shared.dto.MemoDto
import com.moneybook.shared.dto.RepeatRuleDto
import com.moneybook.shared.dto.SettingsDto
import com.moneybook.shared.dto.TxnDto
import com.moneybook.shared.model.AccountGroupType
import com.moneybook.shared.model.ArchiveState
import com.moneybook.shared.model.CategoryType
import com.moneybook.shared.model.RepeatFrequency
import com.moneybook.shared.model.TxnType
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BackupCodecTest {
    private fun sample(version: Int = BackupCodec.VERSION) = BackupDto(
        format = BackupCodec.FORMAT, version = version, createdAt = "2026-09-28T10:00", appVersion = "0.2.0",
        settings = SettingsDto(mainCurrency = "CZK", monthStartDay = 5, weekStart = "MONDAY"),
        accountGroups = listOf(AccountGroupDto(id = "g", name = "Cash", type = AccountGroupType.CASH, sortOrder = 0, updatedAt = 1)),
        accounts = listOf(AccountDto(id = "a", groupId = "g", name = "Cash", currency = "CZK", initialBalance = 1_000, sortOrder = 0, updatedAt = 1)),
        categories = listOf(
            CategoryDto(id = "c", type = CategoryType.EXPENSE, name = "🍜 Food", sortOrder = 0, updatedAt = 1),
            CategoryDto(id = "s", type = CategoryType.EXPENSE, parentId = "c", name = "Lunch", sortOrder = 0, updatedAt = 1),
        ),
        txns = listOf(
            TxnDto(
                id = "t", type = TxnType.EXPENSE, dateTime = "2026-09-26T18:40:12.345", accountId = "a", categoryId = "s",
                amount = 52_500, currency = "CZK", amountMain = 52_500, note = "Zelenina z trhu", updatedAt = 1,
            ),
            TxnDto(
                id = "d", type = TxnType.TRANSFER, dateTime = "2026-09-25T08:00:00.000", accountId = "a", toAccountId = "a",
                amount = 100, currency = "CZK", amountMain = 100, updatedAt = 1, deleted = true,
            ),
        ),
    )

    private fun bytes(b: BackupDto) = ByteArrayOutputStream().also { BackupCodec.write(b, it) }.toByteArray()

    private fun zipWith(name: String, content: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry(name))
            z.write(content.toByteArray())
            z.closeEntry()
        }
        return out.toByteArray()
    }

    private fun readError(bytes: ByteArray): String =
        assertFailsWith<BackupException> { BackupCodec.read(ByteArrayInputStream(bytes)) }.message.orEmpty()

    @Test fun `round trip keeps every field`() {
        assertEquals(sample(), BackupCodec.read(ByteArrayInputStream(bytes(sample()))))
    }

    @Test fun `rejects files that are not MoneyBook backups`() {
        assertEquals("This is not a MoneyBook backup.", readError("plain text".toByteArray()))
        assertEquals("This is not a MoneyBook backup.", readError(zipWith("other.json", "{}")))
    }

    @Test fun `rejects backups from a newer version`() {
        assertEquals("This backup was made by a newer version of MoneyBook.", readError(bytes(sample(BackupCodec.VERSION + 1))))
    }

    @Test fun `a newer backup this version cannot decode still reports the version`() {
        val newer = """{"format":"${BackupCodec.FORMAT}","version":${BackupCodec.VERSION + 1},"accountGroups":[{"type":"CRYPTO"}]}"""
        assertEquals("This backup was made by a newer version of MoneyBook.", readError(zipWith(BackupCodec.ENTRY, newer)))
    }

    @Test fun `a foreign JSON file is not a MoneyBook backup`() {
        assertEquals("This is not a MoneyBook backup.", readError(zipWith(BackupCodec.ENTRY, """{"name":"something else"}""")))
    }

    @Test fun `rejects damaged JSON`() {
        assertEquals("The backup file is damaged.", readError(zipWith(BackupCodec.ENTRY, "{not json")))
    }

    @Test fun `rejects entries that point at missing rows`() {
        val orphan = sample().let { it.copy(txns = it.txns + it.txns.first().copy(id = "x", accountId = "missing")) }
        assertTrue(readError(bytes(orphan)).startsWith("The backup file is damaged"))
    }

    private fun withExtras() = sample().copy(
        settings = SettingsDto(mainCurrency = "CZK", autocomplete = false, autocompleteSince = 1_234),
        memos = listOf(MemoDto(id = "m", date = "2026-09-26", title = "Plan", text = "Koupit chleba", color = -16_711_936, pinned = true, updatedAt = 1)),
        bookmarks = listOf(
            BookmarkDto(
                id = "b", name = "Oběd", type = TxnType.EXPENSE, accountId = "a", categoryId = "c", amount = 17_400,
                currency = "CZK", note = "Oběd", createdAt = 1, updatedAt = 1,
            ),
        ),
    )

    @Test fun `version 2 keeps memos, bookmarks and the autocomplete settings`() {
        assertEquals(withExtras(), BackupCodec.read(ByteArrayInputStream(bytes(withExtras()))))
    }

    @Test fun `a version 1 backup restores with no memos or bookmarks`() {
        val v1 = """{"format":"moneybook-backup","version":1,"createdAt":"2026-09-28T10:00","appVersion":"0.1.0",
            "settings":{"mainCurrency":"CZK"},
            "accountGroups":[{"id":"g","name":"Cash","type":"CASH","sortOrder":0,"updatedAt":1}],
            "accounts":[],"categories":[],"txns":[]}"""
        val b = BackupCodec.read(ByteArrayInputStream(zipWith(BackupCodec.ENTRY, v1)))
        assertEquals(emptyList<MemoDto>(), b.memos)
        assertEquals(emptyList<BookmarkDto>(), b.bookmarks)
        assertEquals(emptyList<RepeatRuleDto>(), b.repeatRules)
        assertEquals(true, b.settings.autocomplete)
        assertEquals("DAILY", b.settings.startScreen)
        assertEquals("INPUT_DESC", b.settings.timeInput)
        assertEquals(true, b.settings.noteButton)
    }

    @Test fun `a bookmark pointing at a missing account is damaged`() {
        val broken = withExtras().copy(bookmarks = listOf(withExtras().bookmarks.single().copy(accountId = "nope")))
        assertTrue("bookmark" in readError(bytes(broken)))
    }

    private val repeatRule = RepeatRuleDto(
        id = "r", frequency = RepeatFrequency.MONTHLY, startDate = "2026-07-26", lastDate = "2026-09-26", type = TxnType.EXPENSE,
        accountId = "a", categoryId = "c", amount = 10_000, currency = "CZK", note = "Nájem", time = "09:30", updatedAt = 1,
    )

    @Test fun `version 3 keeps repeat rules and installment plans`() {
        val b = withExtras().copy(repeatRules = listOf(repeatRule, repeatRule.copy(id = "p", installments = 6, deleted = true)))
        assertEquals(b, BackupCodec.read(ByteArrayInputStream(bytes(b))))
    }

    @Test fun `a repeat rule pointing at a missing category is damaged`() {
        val broken = withExtras().copy(repeatRules = listOf(repeatRule.copy(categoryId = "nope")))
        assertTrue("repeat" in readError(bytes(broken)))
    }

    @Test fun `a repeat rule with a malformed date, time or plan is damaged`() {
        listOf(repeatRule.copy(startDate = "nope"), repeatRule.copy(lastDate = "2026-13-01"), repeatRule.copy(time = "25:00"), repeatRule.copy(installments = 1))
            .forEach { bad -> assertTrue("repeat" in readError(bytes(withExtras().copy(repeatRules = listOf(bad))))) }
    }

    private val eur = CurrencyDto(code = "EUR", symbol = "€", position = "START", decimals = 2, rate = "1.1", sortOrder = 0, updatedAt = 1)

    @Test fun `version 4 keeps currencies and the account side of entries`() {
        assertTrue(BackupCodec.VERSION >= 4)
        val b = withExtras().copy(
            currencies = listOf(eur, eur.copy(code = "JPY", symbol = "JP￥", decimals = 0, rate = "0.0067", deleted = true)),
            txns = withExtras().txns.map { it.copy(accountAmount = 417) },
        )
        assertEquals(b, BackupCodec.read(ByteArrayInputStream(bytes(b))))
    }

    @Test fun `a currency with a bad rate or position is damaged`() {
        listOf(eur.copy(rate = "0"), eur.copy(rate = "x"), eur.copy(position = "MIDDLE"))
            .forEach { bad -> assertTrue("currency" in readError(bytes(withExtras().copy(currencies = listOf(bad))))) }
    }

    @Test fun budgetsRoundTripAtVersion5() {
        val b = sample().copy(budgets = listOf(BudgetDto("budget-total-0", null, 0, 30_000, 1), BudgetDto("budget-c-202610", "c", 202610, 5_000, 1)))
        assertEquals(b.budgets, BackupCodec.read(ByteArrayInputStream(bytes(b))).budgets)
        assertTrue(BackupCodec.VERSION >= 5)
    }

    @Test fun aBudgetForAMissingCategoryIsDamaged() {
        val b = sample().copy(budgets = listOf(BudgetDto("budget-x-0", "missing", 0, 1, 1)))
        assertFailsWith<BackupException> { BackupCodec.read(ByteArrayInputStream(bytes(b))) }
    }

    @Test fun aBadBudgetMonthIsDamaged() {
        val b = sample().copy(budgets = listOf(BudgetDto("budget-total-202613", null, 202613, 1, 1)))
        assertFailsWith<BackupException> { BackupCodec.read(ByteArrayInputStream(bytes(b))) }
    }

    @Test fun `version 6 keeps account and group states, the Transfer-Expense flag and the two settings`() {
        val base = sample()
        val b = base.copy(
            settings = base.settings.copy(carryOver = true, subcategory = true),
            accountGroups = base.accountGroups.map { it.copy(state = ArchiveState.DELETED) },
            accounts = base.accounts.map { it.copy(state = ArchiveState.PURGED, transferExpense = true) },
        )
        assertTrue(BackupCodec.VERSION >= 6)
        assertEquals(b, BackupCodec.read(ByteArrayInputStream(bytes(b))))
    }

    @Test fun `a version 5 file restores with the defaults`() {
        val v5 = """{"format":"${BackupCodec.FORMAT}","version":5,"createdAt":"x","appVersion":"x","settings":{"mainCurrency":"CZK"},
            |"accountGroups":[{"id":"g","name":"Cash","type":"CASH","sortOrder":0,"updatedAt":1}],
            |"accounts":[{"id":"a","groupId":"g","name":"Cash","currency":"CZK","initialBalance":0,"sortOrder":0,"updatedAt":1}],
            |"categories":[],"txns":[]}""".trimMargin()
        val b = BackupCodec.read(ByteArrayInputStream(zipWith(BackupCodec.ENTRY, v5)))
        assertEquals(ArchiveState.ACTIVE, b.accountGroups.single().state)
        assertEquals(ArchiveState.ACTIVE, b.accounts.single().state)
        assertEquals(false, b.accounts.single().transferExpense)
        assertEquals(false, b.settings.carryOver)
        assertEquals(false, b.settings.subcategory)
    }

    @Test fun `an unknown account state is damaged`() {
        val bad = """{"format":"${BackupCodec.FORMAT}","version":6,"createdAt":"x","appVersion":"x","settings":{"mainCurrency":"CZK"},
            |"accountGroups":[{"id":"g","name":"Cash","type":"CASH","sortOrder":0,"updatedAt":1,"state":"GONE"}],
            |"accounts":[],"categories":[],"txns":[]}""".trimMargin()
        assertEquals("The backup file is damaged.", readError(zipWith(BackupCodec.ENTRY, bad)))
    }

    private val photoA = "a".repeat(32) + ".jpg"
    private val photoB = "b".repeat(32) + ".jpg"

    @Test fun `version 7 round trips two photos and the copy setting`() {
        val b = sample().copy(
            settings = sample().settings.copy(copyDateBy = "ENTRY_DATE"),
            attachments = listOf(AttachmentDto("t/$photoA", "t", photoA, 0, 1), AttachmentDto("t/$photoB", "t", photoB, 1, 1)),
        )
        val files = mapOf(photoA to byteArrayOf(1, 2, 3), photoB to byteArrayOf(4, 5))
        val out = ByteArrayOutputStream().also { o -> BackupCodec.write(b, o) { files[it]?.let(::ByteArrayInputStream) } }
        val dir = kotlin.io.path.createTempDirectory("photos").toFile()
        try {
            assertEquals(b, BackupCodec.read(ByteArrayInputStream(out.toByteArray()), dir))
            assertEquals(files.keys, dir.list()!!.toSet())
            assertEquals(listOf<Byte>(4, 5), dir.resolve(photoB).readBytes().toList())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun `photo entries outside photos-name-jpg are ignored and never written`() {
        val b = sample().copy(attachments = listOf(AttachmentDto("t/$photoA", "t", photoA, 0, 1)))
        val good = ByteArrayOutputStream().also { o -> BackupCodec.write(b, o) { ByteArrayInputStream(byteArrayOf(1)) } }.toByteArray()
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            ZipInputStream(ByteArrayInputStream(good)).use { zin ->
                while (true) {
                    val e = zin.nextEntry ?: break
                    z.putNextEntry(ZipEntry(e.name)); z.write(zin.readBytes()); z.closeEntry()
                }
            }
            listOf("photos/../x.jpg", "photos/a/b.jpg", "photos/${photoB}.tmp").forEach {
                z.putNextEntry(ZipEntry(it)); z.write(9); z.closeEntry()
            }
        }
        val parent = kotlin.io.path.createTempDirectory("restore").toFile()
        val dir = parent.resolve("photos")
        try {
            assertEquals(b, BackupCodec.read(ByteArrayInputStream(out.toByteArray()), dir))
            assertEquals(setOf(photoA), dir.list()!!.toSet())
            assertEquals(setOf("photos"), parent.list()!!.toSet()) // nothing escaped to x.jpg beside it
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test fun `an oversized photo entry is refused`() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry(BackupCodec.PHOTOS + photoA))
            val zeros = ByteArray(1024 * 1024)
            repeat(17) { z.write(zeros) } // 17 MiB of zeros compress to a few KiB: a zip bomb in miniature
            z.closeEntry()
        }
        val dir = kotlin.io.path.createTempDirectory("photos").toFile()
        try {
            val message = assertFailsWith<BackupException> { BackupCodec.read(ByteArrayInputStream(out.toByteArray()), dir) }.message
            assertEquals("The backup file is too large.", message)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun `a version 6 file restores with no photos`() {
        val v6 = """{"format":"${BackupCodec.FORMAT}","version":6,"createdAt":"x","appVersion":"x","settings":{"mainCurrency":"CZK"},
            |"accountGroups":[{"id":"g","name":"Cash","type":"CASH","sortOrder":0,"updatedAt":1}],
            |"accounts":[],"categories":[],"txns":[]}""".trimMargin()
        val b = BackupCodec.read(ByteArrayInputStream(zipWith(BackupCodec.ENTRY, v6)))
        assertEquals(emptyList(), b.attachments)
        assertEquals("TODAY", b.settings.copyDateBy)
    }

    @Test fun `a live photo missing from the zip is damaged, a tombstoned one is not`() {
        val live = sample().copy(attachments = listOf(AttachmentDto("x", "t", photoA, 0, 1)))
        assertEquals("The backup file is damaged (photo x is missing).", readError(bytes(live)))
        val gone = sample().copy(attachments = listOf(AttachmentDto("x", "t", photoA, 0, 1, deleted = true)))
        assertEquals(gone, BackupCodec.read(ByteArrayInputStream(bytes(gone))))
    }

    @Test fun `a photo of a missing entry or with a bad file name is damaged`() {
        val noTxn = sample().copy(attachments = listOf(AttachmentDto("x", "nope", photoA, 0, 1, deleted = true)))
        assertEquals("The backup file is damaged (photo x has no entry).", readError(bytes(noTxn)))
        val bad = sample().copy(attachments = listOf(AttachmentDto("x", "t", "../evil.jpg", 0, 1, deleted = true)))
        assertEquals("The backup file is damaged (photo x has a bad file name).", readError(bytes(bad)))
    }
}
