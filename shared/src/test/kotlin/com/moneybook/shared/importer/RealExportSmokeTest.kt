package com.moneybook.shared.importer

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

class RealExportSmokeTest {
    @Test fun `real Realbyte export parses without skipped rows`() {
        val path = System.getenv("MB_REAL_EXPORT")
        assumeTrue("set MB_REAL_EXPORT to run", !path.isNullOrBlank())
        val batch = File(path!!).inputStream().use { RealbyteExcelImporter.parse(it) }
        println("real export: ${batch.txns.size} txns, main=${batch.mainCurrency}, skipped=${batch.skipped}")
        assertTrue(batch.txns.isNotEmpty())
        assertTrue(batch.skipped.isEmpty(), "skipped rows: ${batch.skipped}")
    }
}
