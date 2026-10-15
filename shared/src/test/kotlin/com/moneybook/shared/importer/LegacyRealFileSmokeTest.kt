package com.moneybook.shared.importer

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

class LegacyRealFileSmokeTest {
    /** Opt-in: MB_LEGACY_EXPORT points at the converted .xlsx of the user's old-app export (never committed). */
    @Test fun `old app export parses completely`() {
        val path = System.getenv("MB_LEGACY_EXPORT")
        assumeTrue("set MB_LEGACY_EXPORT to run", !path.isNullOrBlank())
        val batch = File(path!!).inputStream().use { LegacyExcelImporter.parse(it) }
        val categories = batch.rows.map { it.type to it.category }.distinct().size
        println("legacy export: ${batch.rows.size} rows, $categories categories, ${batch.rows.minOf { it.date }}..${batch.rows.maxOf { it.date }}, skipped=${batch.skipped}")
        assertTrue(batch.rows.isNotEmpty())
        assertTrue(batch.skipped.isEmpty(), "skipped rows: ${batch.skipped}")
    }
}
