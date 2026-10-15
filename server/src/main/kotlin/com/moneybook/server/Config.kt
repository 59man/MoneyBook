package com.moneybook.server

class Config(
    val dbUrl: String,
    val dbUser: String,
    val dbPassword: String,
    val jwtSecret: String,
    val apiKey: String,
    val dataDir: String,
    val port: Int,
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
            val apiKey = required("MB_API_KEY")
            check(apiKey.length >= MIN_API_KEY_CHARS) { "MB_API_KEY must be at least $MIN_API_KEY_CHARS characters" }
            val port = env["MB_PORT"]?.let { it.toIntOrNull()?.takeIf { p -> p in 1..65535 } ?: error("MB_PORT must be a port number") } ?: 8080
            return Config(
                dbUrl = required("MB_DB_URL"),
                dbUser = required("MB_DB_USER"),
                dbPassword = required("MB_DB_PASSWORD"),
                jwtSecret = secret,
                apiKey = apiKey,
                dataDir = env["MB_DATA"] ?: "/data",
                port = port,
            )
        }
    }
}
