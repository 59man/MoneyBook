package com.moneybook.server

class Config(
    val dbUrl: String,
    val dbUser: String,
    val dbPassword: String,
    val jwtSecret: String,
    val apiKey: String?,
    val dataDir: String,
    val port: Int,
    val trustedNetworks: List<Cidr> = Cidr.DEFAULTS,
    val allowedHosts: Set<String> = emptySet(),
    val backupDir: String = "/backups",
) {
    // no secrets in logs or stack traces
    override fun toString() = "Config(dbUrl=$dbUrl, dbUser=$dbUser, dataDir=$dataDir, port=$port)"

    companion object {
        private const val MIN_SECRET_BYTES = 32
        private const val MIN_API_KEY_CHARS = 32

        fun fromEnv(env: Map<String, String> = System.getenv()): Config {
            fun required(name: String) = env[name]?.takeIf { it.isNotEmpty() } ?: error("$name is required")
            val secret = required("MB_JWT_SECRET")
            check(secret.toByteArray(Charsets.UTF_8).size >= MIN_SECRET_BYTES) {
                "MB_JWT_SECRET must be at least $MIN_SECRET_BYTES bytes"
            }
            val apiKey = env["MB_API_KEY"]?.takeIf { it.isNotBlank() }?.also {
                check(it.length >= MIN_API_KEY_CHARS) { "MB_API_KEY must be at least $MIN_API_KEY_CHARS characters" }
            }
            // the message never echoes the entry
            val trusted = env["MB_TRUSTED_NETWORKS"]?.takeIf { it.isNotBlank() }
                ?.let { runCatching { Cidr.parseList(it) }.getOrElse { error("MB_TRUSTED_NETWORKS has an invalid entry") } }
                ?.takeIf { it.isNotEmpty() } ?: Cidr.DEFAULTS
            val hosts = env["MB_ALLOWED_HOSTS"].orEmpty().split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
            val port = env["MB_PORT"]?.let { it.toIntOrNull()?.takeIf { p -> p in 1..65535 } ?: error("MB_PORT must be a port number") } ?: 8080
            return Config(
                dbUrl = required("MB_DB_URL"),
                dbUser = required("MB_DB_USER"),
                dbPassword = required("MB_DB_PASSWORD"),
                jwtSecret = secret,
                apiKey = apiKey,
                dataDir = env["MB_DATA"] ?: "/data",
                port = port,
                trustedNetworks = trusted,
                allowedHosts = hosts,
                backupDir = env["MB_BACKUP_DIR"] ?: "/backups",
            )
        }
    }
}
