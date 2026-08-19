package app.lawnchair.organizer.diagnostics.journal

import app.lawnchair.organizer.diagnostics.model.RunEvent
import kotlinx.serialization.json.Json

/**
 * JSON codec for the diagnostics journal schema version 1.
 *
 * Configuration:
 * - encodeDefaults = false: only populate non-default fields are serialized.
 * - ignoreUnknownKeys = false: unknown keys are rejected at decode time.
 *   The contract requires fail-closed schema handling; silent
 *   reinterpretation of future schema additions is not allowed.
 * - classDiscriminator = "#class": not used (no polymorphic serialization).
 *
 * Schema version 1 is the only accepted version. Decoding a payload with
 * any other schemaVersion value is rejected.
 */
object RunEventSerializer {

    val json: Json = Json {
        encodeDefaults = false
        ignoreUnknownKeys = false
        classDiscriminator = "#class"
    }

    fun encode(event: RunEvent): ByteArray = json.encodeToString(event).toByteArray(Charsets.UTF_8)

    fun decode(bytes: ByteArray): RunEvent {
        val event = json.decodeFromString<RunEvent>(bytes.toString(Charsets.UTF_8))
        require(event.schemaVersion == 1) {
            "Unsupported schema version ${event.schemaVersion}; only version 1 is accepted"
        }
        return event
    }

    fun encodeToString(event: RunEvent): String = json.encodeToString(event)
}
