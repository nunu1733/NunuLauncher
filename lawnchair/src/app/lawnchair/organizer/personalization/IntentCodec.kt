package app.lawnchair.organizer.personalization

import app.lawnchair.organizer.rules.UserDefinedCategoryNameRules
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Issue #204: transport-independent JSON codec of `PersonalizedIntentV1`
 * (spec 204 "Contract 2"). Closed schema, strict allow-list decoding,
 * fail-closed: every problem is a typed failure, malformed input is never
 * partially interpreted.
 */
object IntentCodec {

    /** Authority expressions that are schema-forbidden in any intent payload. */
    internal val FORBIDDEN_KEYS = setOf(
        "page", "x", "y", "cell", "span", "dbMutation", "dbRow", "sql",
        "script", "code", "command", "reservation", "occupy", "favorites",
    )

    private val ALLOWED_TOP_KEYS = IntentWireContract.topLevel.map { it.name }.toSet()
    private val ALLOWED_ITEM_KEYS = IntentWireContract.item.map { it.name }.toSet()
    private val ALLOWED_GLOBAL_KEYS = IntentWireContract.globalPreference.map { it.name }.toSet()
    private val ALLOWED_SEMANTIC_KEYS = IntentWireContract.groupSemantic.map { it.name }.toSet()

    private val json = Json

    fun decode(bytes: ByteArray): IntentDecodeResult {
        if (bytes.size > ContextExportContract.MAX_INTENT_BYTES) {
            return IntentDecodeResult.Failure(IntentValidationFailure.Oversize)
        }
        val root = runCatching { json.parseToJsonElement(bytes.decodeToString()) }
            .getOrNull()
            ?: return IntentDecodeResult.Failure(IntentValidationFailure.SchemaMismatch)
        val obj = root as? JsonObject
            ?: return IntentDecodeResult.Failure(IntentValidationFailure.SchemaMismatch)

        decodeEnvelope(obj).let { failure -> if (failure != null) return IntentDecodeResult.Failure(failure) }

        val schemaVersion = obj.optString("schemaVersion")
            ?: return IntentDecodeResult.Failure(IntentValidationFailure.SchemaMismatch)
        if (schemaVersion != ContextExportContract.INTENT_SCHEMA_VERSION) {
            return IntentDecodeResult.Failure(IntentValidationFailure.SchemaMismatch)
        }
        val exportId = obj.optString("exportId")
            ?: return IntentDecodeResult.Failure(IntentValidationFailure.SchemaMismatch)

        val itemIntents = when (val decoded = decodeItemIntents(obj)) {
            is Decoded.Failure -> return IntentDecodeResult.Failure(decoded.value)
            is Decoded.Ok -> decoded.value
        }
        val unresolvedRefs = when (
            val decoded = decodeStringList(obj, "unresolvedRefs", ContextExportContract.MAX_INTENT_UNRESOLVED)
        ) {
            is Decoded.Failure -> return IntentDecodeResult.Failure(
                if (decoded.value == ListDecodeProblem.TooMany) {
                    IntentValidationFailure.Oversize
                } else {
                    IntentValidationFailure.SchemaMismatch
                },
            )

            is Decoded.Ok -> decoded.value
        }

        val globalPreference = when (val decoded = decodeGlobalPreference(obj)) {
            is Decoded.Failure -> return IntentDecodeResult.Failure(decoded.value)
            is Decoded.Ok -> decoded.value
        }
        val rationale = obj.optString("rationale")
        if (rationale != null && rationale.length > ContextExportContract.MAX_RATIONALE_CHARS) {
            return IntentDecodeResult.Failure(IntentValidationFailure.Oversize)
        }
        val confidence = when (val decoded = obj.optInt("confidence")) {
            is Optional.Invalid -> return IntentDecodeResult.Failure(IntentValidationFailure.InvalidEnum)

            is Optional.Present -> decoded.value?.takeIf { it in ContextExportContract.CONFIDENCE_MIN..ContextExportContract.CONFIDENCE_MAX }
                ?: return IntentDecodeResult.Failure(IntentValidationFailure.InvalidEnum)

            is Optional.Absent -> null
        }
        val intent = runCatching {
            PersonalizedIntentV1(
                exportId = exportId,
                itemIntents = itemIntents,
                unresolvedRefs = unresolvedRefs,
                globalPreference = globalPreference,
                rationale = rationale,
                confidence = confidence,
            )
        }.getOrElse { return IntentDecodeResult.Failure(IntentValidationFailure.Oversize) }
        return IntentDecodeResult.Success(intent)
    }

    fun encode(intent: PersonalizedIntentV1): ByteArray {
        val root = buildJsonObject {
            put("schemaVersion", JsonPrimitive(ContextExportContract.INTENT_SCHEMA_VERSION))
            put("exportId", JsonPrimitive(intent.exportId))
            put(
                "itemIntents",
                JsonArray(
                    intent.itemIntents.map { item ->
                        buildJsonObject {
                            put("ref", JsonPrimitive(item.ref))
                            item.importance?.let { put("importance", JsonPrimitive(it.name)) }
                            item.desiredGroupRefs?.let { refs ->
                                put("desiredGroup", JsonArray(refs.map { JsonPrimitive(it) }))
                            }
                            item.groupSemantic?.let { semantic ->
                                put(
                                    "groupSemantic",
                                    buildJsonObject {
                                        semantic.categoryRef?.let { put("categoryRef", JsonPrimitive(it)) }
                                        semantic.proposalLabel?.let { put("proposalLabel", JsonPrimitive(it)) }
                                    },
                                )
                            }
                            item.pageAffinity?.let { put("pageAffinity", JsonPrimitive(it)) }
                            item.regionAffinity?.let { put("regionAffinity", JsonPrimitive(it.name)) }
                            item.preserve?.let { put("preserve", JsonPrimitive(it)) }
                        }
                    },
                ),
            )
            if (intent.unresolvedRefs.isNotEmpty()) {
                put("unresolvedRefs", JsonArray(intent.unresolvedRefs.map { JsonPrimitive(it) }))
            }
            intent.globalPreference?.minimizeMovement?.let { minimize ->
                put("globalPreference", buildJsonObject { put("minimizeMovement", JsonPrimitive(minimize)) })
            }
            intent.rationale?.let { put("rationale", JsonPrimitive(it)) }
            intent.confidence?.let { put("confidence", JsonPrimitive(it)) }
        }
        return json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), root).encodeToByteArray()
    }

    private fun decodeEnvelope(obj: JsonObject): IntentValidationFailure? {
        for (key in obj.keys) {
            when {
                key in FORBIDDEN_KEYS -> return IntentValidationFailure.ForbiddenContent
                key !in ALLOWED_TOP_KEYS -> return IntentValidationFailure.SchemaMismatch
            }
        }
        return null
    }

    private fun decodeItemIntents(obj: JsonObject): Decoded<List<ItemIntent>, IntentValidationFailure> {
        val raw = obj["itemIntents"] ?: return Decoded.Ok(emptyList())
        if (raw is JsonNull) return Decoded.Ok(emptyList())
        val array = raw as? JsonArray ?: return Decoded.Failure(IntentValidationFailure.SchemaMismatch)
        if (array.size > ContextExportContract.MAX_INTENT_ENTRIES) {
            return Decoded.Failure(IntentValidationFailure.Oversize)
        }
        val items = ArrayList<ItemIntent>(array.size)
        for (element in array) {
            val itemObj = element as? JsonObject ?: return Decoded.Failure(IntentValidationFailure.SchemaMismatch)
            for (key in itemObj.keys) {
                when {
                    key in FORBIDDEN_KEYS -> return Decoded.Failure(IntentValidationFailure.ForbiddenContent)
                    key !in ALLOWED_ITEM_KEYS -> return Decoded.Failure(IntentValidationFailure.SchemaMismatch)
                }
            }
            val ref = itemObj.optString("ref")
                ?: return Decoded.Failure(IntentValidationFailure.SchemaMismatch)
            val importance = when (val decoded = itemObj.optEnum("importance", Importance.entries)) {
                is Optional.Present -> decoded.value
                is Optional.Absent -> null
                is Optional.Invalid -> return Decoded.Failure(IntentValidationFailure.InvalidEnum)
            }
            val desiredGroupRefs = when (val decoded = decodeStringList(itemObj, "desiredGroup", Int.MAX_VALUE)) {
                is Decoded.Failure -> return Decoded.Failure(IntentValidationFailure.SchemaMismatch)
                is Decoded.Ok -> decoded.value
            }
            val groupSemantic = when (val decoded = decodeGroupSemantic(itemObj)) {
                is Decoded.Failure -> return Decoded.Failure(decoded.value)
                is Decoded.Ok -> decoded.value
            }
            val pageAffinity = when (val decoded = itemObj.optInt("pageAffinity")) {
                is Optional.Invalid -> return Decoded.Failure(IntentValidationFailure.InvalidEnum)
                is Optional.Present -> decoded.value
                is Optional.Absent -> null
            }
            val regionAffinity = when (val decoded = itemObj.optEnum("regionAffinity", ExportRegionKind.entries)) {
                is Optional.Present -> decoded.value
                is Optional.Absent -> null
                is Optional.Invalid -> return Decoded.Failure(IntentValidationFailure.InvalidEnum)
            }
            val preserve = when (val decoded = itemObj.optBoolean("preserve")) {
                is Optional.Invalid -> return Decoded.Failure(IntentValidationFailure.SchemaMismatch)
                is Optional.Present -> decoded.value
                is Optional.Absent -> null
            }
            items += runCatching {
                ItemIntent(
                    ref = ref,
                    importance = importance,
                    desiredGroupRefs = desiredGroupRefs.takeIf { it.isNotEmpty() },
                    groupSemantic = groupSemantic,
                    pageAffinity = pageAffinity,
                    regionAffinity = regionAffinity,
                    preserve = preserve,
                )
            }.getOrElse { return Decoded.Failure(IntentValidationFailure.SchemaMismatch) }
        }
        return Decoded.Ok(items)
    }

    private fun decodeGroupSemantic(itemObj: JsonObject): Decoded<GroupSemantic?, IntentValidationFailure> {
        val raw = itemObj["groupSemantic"] ?: return Decoded.Ok(null)
        if (raw is JsonNull) return Decoded.Ok(null)
        val semanticObj = raw as? JsonObject ?: return Decoded.Failure(IntentValidationFailure.SchemaMismatch)
        for (key in semanticObj.keys) {
            if (key !in ALLOWED_SEMANTIC_KEYS) return Decoded.Failure(IntentValidationFailure.SchemaMismatch)
        }
        val categoryRef = semanticObj.optString("categoryRef")
        val rawLabel = semanticObj.optString("proposalLabel")
        // Issue #337 (spec 337 D-4): exactly one of the two fields. Both set
        // (or neither) leaves the grouping authority undecided; the shape
        // violation mirrors the pre-v4 empty-object case.
        if ((categoryRef == null) == (rawLabel == null)) {
            return Decoded.Failure(IntentValidationFailure.SchemaMismatch)
        }
        if (categoryRef != null && categoryRef.isEmpty()) {
            return Decoded.Failure(IntentValidationFailure.SchemaMismatch)
        }
        // The proposal label adopts the #336 category-name domain verbatim
        // (single canonical rule, so promotion is a pass-through): normalize
        // (trim + NFC), then bound (OVERSIZE) and validate the rest of the
        // domain (non-empty, no '|', no line break).
        val label = rawLabel?.let { UserDefinedCategoryNameRules.normalize(it) }
        if (label != null && label.codePointCount(0, label.length) > UserDefinedCategoryNameRules.MAX_CODE_POINTS) {
            return Decoded.Failure(IntentValidationFailure.Oversize)
        }
        if (label != null && !UserDefinedCategoryNameRules.isValid(label)) {
            return Decoded.Failure(IntentValidationFailure.SchemaMismatch)
        }
        return Decoded.Ok(GroupSemantic(categoryRef = categoryRef, proposalLabel = label))
    }

    private fun decodeGlobalPreference(obj: JsonObject): Decoded<GlobalPreference?, IntentValidationFailure> {
        val raw = obj["globalPreference"] ?: return Decoded.Ok(null)
        if (raw is JsonNull) return Decoded.Ok(null)
        val globalObj = raw as? JsonObject ?: return Decoded.Failure(IntentValidationFailure.SchemaMismatch)
        for (key in globalObj.keys) {
            if (key !in ALLOWED_GLOBAL_KEYS) return Decoded.Failure(IntentValidationFailure.SchemaMismatch)
        }
        val minimize = when (val decoded = globalObj.optBoolean("minimizeMovement")) {
            is Optional.Invalid -> return Decoded.Failure(IntentValidationFailure.SchemaMismatch)
            is Optional.Present -> decoded.value
            is Optional.Absent -> null
        }
        return Decoded.Ok(GlobalPreference(minimizeMovement = minimize))
    }

    private fun decodeStringList(obj: JsonObject, key: String, maxEntries: Int): Decoded<List<String>, ListDecodeProblem> {
        val raw = obj[key] ?: return Decoded.Ok(emptyList())
        if (raw is JsonNull) return Decoded.Ok(emptyList())
        val array = raw as? JsonArray ?: return Decoded.Failure(ListDecodeProblem.NotAnArray)
        if (array.size > maxEntries) return Decoded.Failure(ListDecodeProblem.TooMany)
        val values = ArrayList<String>(array.size)
        for (element in array) {
            val primitive = element as? JsonPrimitive ?: return Decoded.Failure(ListDecodeProblem.NotAnArray)
            values += primitive.jsonPrimitive.content
        }
        return Decoded.Ok(values)
    }

    private fun JsonObject.optString(key: String): String? {
        val raw = this[key] ?: return null
        if (raw is JsonNull) return null
        val primitive = raw as? JsonPrimitive ?: return null
        return primitive.jsonPrimitive.content
    }

    private fun JsonObject.optInt(key: String): Optional<Int> {
        val raw = this[key] ?: return Optional.Absent
        if (raw is JsonNull) return Optional.Absent
        val primitive = raw as? JsonPrimitive ?: return Optional.Invalid
        val value = primitive.intOrNull ?: return Optional.Invalid
        return Optional.Present(value)
    }

    private fun JsonObject.optBoolean(key: String): Optional<Boolean> {
        val raw = this[key] ?: return Optional.Absent
        if (raw is JsonNull) return Optional.Absent
        val primitive = raw as? JsonPrimitive ?: return Optional.Invalid
        val value = primitive.booleanOrNull ?: return Optional.Invalid
        return Optional.Present(value)
    }

    private fun <E : Enum<E>> JsonObject.optEnum(key: String, values: List<E>): Optional<E> {
        val raw = this[key] ?: return Optional.Absent
        if (raw is JsonNull) return Optional.Absent
        val primitive = raw as? JsonPrimitive ?: return Optional.Invalid
        val value = values.firstOrNull { it.name == primitive.jsonPrimitive.content } ?: return Optional.Invalid
        return Optional.Present(value)
    }
}

sealed interface IntentDecodeResult {
    data class Success(val intent: PersonalizedIntentV1) : IntentDecodeResult
    data class Failure(val failure: IntentValidationFailure) : IntentDecodeResult
}

internal sealed interface Decoded<out V, out F> {
    data class Ok<V>(val value: V) : Decoded<V, Nothing>
    data class Failure<F>(val value: F) : Decoded<Nothing, F>
}

private enum class ListDecodeProblem { NotAnArray, TooMany }

internal sealed interface Optional<out V> {
    data class Present<V>(val value: V) : Optional<V>
    data object Absent : Optional<Nothing>
    data object Invalid : Optional<Nothing>
}
