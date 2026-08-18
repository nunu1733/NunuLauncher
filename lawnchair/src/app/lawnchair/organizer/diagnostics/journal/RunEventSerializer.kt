package app.lawnchair.organizer.diagnostics.journal

import app.lawnchair.organizer.diagnostics.model.RunEvent
import kotlinx.serialization.json.Json

/**
 * JSON codec for the diagnostics journal schema version 1.
 *
 * Configuration:
 * - encodeDefaults = false: only populate non-default fields are serialized.
 * - ignoreUnknownKeys = true: forward-compatible with future schema additions.
 * - classDiscriminator = "#class": not used (no polymorphic serialization).
 */
object RunEventSerializer {

    val json: Json = Json {
        encodeDefaults = false
        ignoreUnknownKeys = true
        classDiscriminator = "#class"
    }

    fun encode(event: RunEvent): ByteArray = json.encodeToString(event).toByteArray(Charsets.UTF_8)

    fun decode(bytes: ByteArray): RunEvent = json.decodeFromString(bytes.toString(Charsets.UTF_8))

    fun encodeToString(event: RunEvent): String = json.encodeToString(event)
}
