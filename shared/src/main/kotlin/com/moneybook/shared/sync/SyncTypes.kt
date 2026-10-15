package com.moneybook.shared.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

// The sync wire format shared by the server (M3) and the app (M4). [payload] is the entity's DTO from `shared/dto` as JSON.

const val SYNC_MAX_CHANGES = 1000
const val SYNC_MAX_PAYLOAD_BYTES = 64 * 1024
const val SYNC_MAX_BODY_BYTES = 16 * 1024 * 1024
const val SYNC_MAX_ID = 200

/** The one `settings` row's id. */
const val SETTINGS_ID = "app"

/** PhotoStore's file names: an MD5 in lowercase hex plus `.jpg`. Match the whole string. */
val ATTACHMENT_NAME_REGEX = Regex("[0-9a-f]{32}\\.jpg")

@Serializable
data class SyncChange(
    val entity: String,
    val id: String,
    val updatedAt: Long,
    val deleted: Boolean,
    /** The last writer; set by the server on the way out. */
    val deviceId: String = "",
    val payload: JsonObject,
)

@Serializable
data class SyncRequest(val cursor: Long, val changes: List<SyncChange> = emptyList())

@Serializable
data class SyncResponse(
    val changes: List<SyncChange>,
    val newCursor: Long,
    val hasMore: Boolean,
    /** Changes when the server's data was restored from a dump; the client then resets its cursor and re-pushes everything. */
    val epoch: String = "",
)

@Serializable
data class LoginRequest(val apiKey: String, val deviceId: String, val deviceName: String)

@Serializable
data class RefreshRequest(val refreshToken: String)

@Serializable
data class TokenResponse(val accessToken: String, val refreshToken: String, val expiresIn: Long)
