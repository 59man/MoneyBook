package com.moneybook.server

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.sql.Connection
import javax.sql.DataSource
import org.flywaydb.core.Flyway

fun createDataSource(config: Config): HikariDataSource = HikariDataSource(HikariConfig().apply {
    jdbcUrl = config.dbUrl
    username = config.dbUser
    password = config.dbPassword
    maximumPoolSize = 10
    isAutoCommit = false // every statement runs inside inTransaction
    transactionIsolation = "TRANSACTION_READ_COMMITTED" // sync relies on it, whatever the server default
    connectionTimeout = 5_000
})

fun migrate(ds: DataSource) {
    Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate()
}

/** Blocking: call it from a route inside `withContext(Dispatchers.IO)`. Commits on return, rolls back on any throw. */
fun <T> DataSource.inTransaction(block: (Connection) -> T): T = connection.use { c ->
    try {
        block(c).also { c.commit() }
    } catch (e: Throwable) {
        runCatching { c.rollback() }
        throw e
    }
}
