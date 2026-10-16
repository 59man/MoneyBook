package com.moneybook.server.testutil

import com.moneybook.server.Config
import com.moneybook.server.createDataSource
import com.moneybook.server.inTransaction
import com.moneybook.server.migrate
import com.moneybook.server.module
import com.moneybook.server.prepareAuth
import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.time.Clock
import java.util.UUID
import javax.sql.DataSource
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import org.testcontainers.postgresql.PostgreSQLContainer

/** One PostgreSQL container per test class; the tables are emptied before each test. */
abstract class PostgresTestBase {
    companion object {
        private lateinit var container: PostgreSQLContainer
        lateinit var ds: HikariDataSource
            private set
        val dataDir = Files.createTempDirectory("mb-data").toFile().also { it.deleteOnExit() }

        /** A fixed 48-character secret. */
        const val SECRET = "test-secret-0123456789-test-secret-0123456789-ab"

        /** A fixed 48-character API key. */
        const val API_KEY = "test-api-key-0123456789-test-api-key-0123456789-ab"

        fun config(apiKey: String? = API_KEY) = Config(
            dbUrl = container.jdbcUrl,
            dbUser = container.username,
            dbPassword = container.password,
            jwtSecret = SECRET,
            apiKey = apiKey,
            dataDir = dataDir.path,
            port = 0,
        )

        val testConfig: Config get() = config()

        /** Inserts a user row directly (the server itself has only the owner), for isolation tests. */
        fun insertUser(name: String): UUID = UUID.randomUUID().also { id ->
            ds.inTransaction { c ->
                c.prepareStatement("insert into app_user (id, username) values (?, ?)").use {
                    it.setObject(1, id); it.setString(2, name); it.executeUpdate()
                }
            }
        }

        @BeforeClass
        @JvmStatic
        fun startDatabase() {
            container = PostgreSQLContainer("postgres:17-alpine").also { it.start() }
            ds = createDataSource(testConfig)
            migrate(ds)
        }

        @AfterClass
        @JvmStatic
        fun stopDatabase() {
            ds.close()
            container.stop()
        }
    }

    @Before
    fun truncateTables() {
        ds.inTransaction { c ->
            c.createStatement().use {
                it.execute("truncate sync_record, refresh_token, device, app_user")
                it.execute("update server_meta set api_key = null, key_hash = null")
            }
        }
    }

    /**
     * Runs the app on [dataSource] (default: the container's) and [clock]. [extra] can add test-only routes or
     * plugins after `module`.
     */
    fun withApp(
        dataSource: DataSource = ds,
        clock: Clock = Clock.systemUTC(),
        apiKey: String? = API_KEY,
        peer: (ApplicationCall) -> String = { "127.0.0.1" },
        extra: Application.() -> Unit = {},
        block: suspend ApplicationTestBuilder.(client: HttpClient) -> Unit,
    ) {
        if (dataSource === ds) prepareAuth(config(apiKey), ds) // what main() does before serving
        runApp(dataSource, clock, apiKey, peer, extra, block)
    }

    private fun runApp(
        dataSource: DataSource,
        clock: Clock,
        apiKey: String?,
        peer: (ApplicationCall) -> String,
        extra: Application.() -> Unit,
        block: suspend ApplicationTestBuilder.(client: HttpClient) -> Unit,
    ) = testApplication {
        application {
            module(config(apiKey), dataSource, clock, peer)
            extra()
        }
        val client = createClient {
            install(ContentNegotiation) { json() }
            // the test engine sends no Host header and the server refuses a missing one; a test's own Host overrides this
            defaultRequest { header(HttpHeaders.Host, "localhost") }
        }
        block(client)
    }
}
