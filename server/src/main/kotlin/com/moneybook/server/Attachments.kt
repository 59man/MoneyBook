package com.moneybook.server

import com.moneybook.shared.sync.ATTACHMENT_NAME_REGEX
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.authenticate
import io.ktor.server.http.content.LocalFileContent
import io.ktor.server.plugins.autohead.AutoHeadResponse
import io.ktor.server.request.contentLength
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.application.ApplicationCall
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.utils.io.readAvailable
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val MAX_BYTES = 16L * 1024 * 1024

// ponytail: files of tombstoned attachment rows are never deleted; add a sweep like the app's PhotoStore.sweepUnused when disk use matters.
fun Application.installAttachments(config: Config) {
    install(AutoHeadResponse)
    val root = Path.of(config.dataDir, "attachments").toAbsolutePath().normalize()
    routing {
        authenticate(AUTH_PROVIDER) {
            route("/v1/attachments/{fileName}") {
                get {
                    val file = fileFor(call, root)
                    if (!Files.isRegularFile(file)) throw ApiException(HttpStatusCode.NotFound, "not_found")
                    call.respond(LocalFileContent(file.toFile(), ContentType.Application.OctetStream))
                }
                put {
                    val file = fileFor(call, root)
                    if ((call.request.contentLength() ?: 0) > MAX_BYTES) throw ApiException(HttpStatusCode.PayloadTooLarge, "too_large")
                    val created = withContext(Dispatchers.IO) { store(call, file) }
                    call.respond(if (created) HttpStatusCode.Created else HttpStatusCode.NoContent)
                }
            }
        }
    }
}

/** The path for the token's user and the validated name; the user id is never taken from the request. */
private fun fileFor(call: ApplicationCall, root: Path): Path {
    val name = call.parameters["fileName"].orEmpty()
    if (!ATTACHMENT_NAME_REGEX.matches(name)) throw ApiException(HttpStatusCode.BadRequest, "bad_request")
    val dir = root.resolve(call.principalIds().first.toString())
    return dir.resolve(name).normalize().also { check(it.parent == dir) { "attachment path escaped its directory" } }
}

/** true = created, false = the name already existed (nothing stored). Streams into a temp file, so memory stays flat. */
private suspend fun store(call: ApplicationCall, file: Path): Boolean {
    if (Files.exists(file)) return false
    val dir = file.parent
    Files.createDirectories(dir)
    val tmp = Files.createTempFile(dir, ".tmp-", "") // not a valid name, mode 600
    try {
        val input = call.receiveChannel()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        val md5 = MessageDigest.getInstance("MD5") // the name is the MD5 of the bytes (PhotoStore), so a mismatch is a corrupt upload
        FileChannel.open(tmp, StandardOpenOption.WRITE).use { out ->
            while (true) {
                val n = input.readAvailable(buf, 0, buf.size)
                if (n < 0) break
                total += n
                if (total > MAX_BYTES) throw ApiException(HttpStatusCode.PayloadTooLarge, "too_large")
                md5.update(buf, 0, n)
                out.write(ByteBuffer.wrap(buf, 0, n))
            }
            out.force(true)
        }
        if (md5.digest().joinToString("") { "%02x".format(it) } + ".jpg" != file.fileName.toString()) {
            throw ApiException(HttpStatusCode.BadRequest, "bad_hash")
        }
        Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE)
        return true
    } finally {
        Files.deleteIfExists(tmp)
    }
}
