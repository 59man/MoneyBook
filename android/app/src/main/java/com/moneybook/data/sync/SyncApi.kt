package com.moneybook.data.sync

import com.moneybook.shared.sync.LoginRequest
import com.moneybook.shared.sync.SyncRequest
import com.moneybook.shared.sync.SyncResponse
import com.moneybook.shared.sync.TokenResponse
import java.io.File

/** The M3 server's API as the app uses it. Every call throws [SyncError] on failure. */
interface SyncApi {
    /** Keeps the access token in memory; the caller stores the url and refresh token. */
    suspend fun login(serverUrl: String, request: LoginRequest): TokenResponse

    /** Best effort: POST /v1/auth/logout with the stored refresh token; errors are swallowed. */
    suspend fun logout()
    suspend fun sync(request: SyncRequest): SyncResponse

    /** HEAD: 200 true, 404 false. */
    suspend fun attachmentExists(fileName: String): Boolean

    /** Streamed PUT; 201 and 204 are both success. */
    suspend fun putAttachment(fileName: String, file: File)

    /** False on 404; the engine verifies the MD5. */
    suspend fun getAttachment(fileName: String, into: File): Boolean
}
