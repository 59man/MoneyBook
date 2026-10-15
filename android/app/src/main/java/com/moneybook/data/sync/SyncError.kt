package com.moneybook.data.sync

/** Why a sync call failed. Messages never carry tokens, passwords or response bodies. */
sealed class SyncError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Offline(cause: Throwable?) : SyncError("offline", cause)
    object Unauthorized : SyncError("unauthorized")
    class Server(val status: Int) : SyncError("server $status")
    class Rejected(val status: Int, val error: String) : SyncError("rejected $status")
    class Other(cause: Throwable?) : SyncError("other", cause)

    /** What `DeviceSettings.lastSyncError` stores. */
    val code: String
        get() = when (this) {
            is Offline -> "offline"
            Unauthorized -> "session_expired"
            is Server -> "server"
            is Rejected -> "rejected"
            is Other -> "other"
        }
}
