package com.moneybook.server

import com.moneybook.server.testutil.PostgresTestBase
import kotlin.test.Test
import kotlin.test.assertEquals

class MigrationTest : PostgresTestBase() {
    private fun names(sql: String): Set<String> = ds.inTransaction { c ->
        c.createStatement().use { st ->
            st.executeQuery(sql).use { rs -> buildSet { while (rs.next()) add(rs.getString(1)) } }
        }
    }

    @Test
    fun `schema exists`() {
        val tables = names("select table_name from information_schema.tables where table_schema = 'public'")
        assertEquals(setOf("app_user", "device", "refresh_token", "server_meta", "sync_record"), tables - "flyway_schema_history")
        assertEquals(setOf("sync_version"), names("select sequencename from pg_sequences where schemaname = 'public'"))
        assertEquals(
            setOf("sync_record_user_version"),
            names("select indexname from pg_indexes where tablename = 'sync_record' and indexname not like '%pkey'"),
        )
    }

    @Test
    fun `migrating twice changes nothing`() {
        val before = names("select version from flyway_schema_history")
        migrate(ds)
        assertEquals(before, names("select version from flyway_schema_history"))
        assertEquals(setOf("1", "2", "3", "4"), before)
    }

    @Test
    fun `server_meta holds exactly one epoch`() {
        assertEquals(setOf("1"), names("select count(*)::text from server_meta where epoch is not null"))
    }
}
