package com.moneybook.shared.sync

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * [payload] with `updatedAt` and `deleted` set to the envelope's values, only for keys it already has
 * (`SettingsDto` has neither). Unknown keys are kept; the input is not changed.
 */
fun overwriteEnvelope(payload: JsonObject, updatedAt: Long, deleted: Boolean): JsonObject =
    JsonObject(
        payload.mapValues { (key, value) ->
            when (key) {
                "updatedAt" -> JsonPrimitive(updatedAt)
                "deleted" -> JsonPrimitive(deleted)
                else -> value
            }
        },
    )
