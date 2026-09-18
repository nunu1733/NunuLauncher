package app.lawnchair.organizer.personalization

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Issue #204: the transport-independent JSON codec of the context export
 * (spec 204 "Contract 1"). The accepted exchange format — field names,
 * `schemaVersion`, and optional-field omission rules — is owned HERE, once,
 * so #205 transports exactly this payload instead of inventing its own
 * serialization. Encoding measures the 256 KiB content limit (Q4) and fails
 * closed with `Oversize`.
 */
object ContextExportCodec {

    private val json = Json

    fun encode(export: PersonalizationContextExportV1): ContextExportResult {
        val root = JsonObject(
            buildMap {
                put("schemaVersion", JsonPrimitive(ContextExportContract.SCHEMA_VERSION))
                put("exportId", JsonPrimitive(export.exportId))
                put("tier", JsonPrimitive(export.tier.name))
                put(
                    "gridContext",
                    JsonObject(
                        buildMap {
                            put("columns", JsonPrimitive(export.grid.columns))
                            put("rows", JsonPrimitive(export.grid.rows))
                            put("pageCount", JsonPrimitive(export.grid.pageCount))
                        },
                    ),
                )
                put(
                    "categories",
                    JsonArray(
                        export.categories.map { category ->
                            JsonObject(
                                buildMap {
                                    put("ref", JsonPrimitive(category.ref))
                                    put("kind", JsonPrimitive(category.kind.name))
                                    category.taxonomyId?.let { put("taxonomyId", JsonPrimitive(it)) }
                                    category.displayName?.let { put("displayName", JsonPrimitive(it)) }
                                },
                            )
                        },
                    ),
                )
                put(
                    "items",
                    JsonArray(
                        export.items.map { item ->
                            JsonObject(
                                buildMap {
                                    put("ref", JsonPrimitive(item.ref))
                                    put("kind", JsonPrimitive(item.role.name))
                                    put("subject", JsonPrimitive(item.subject.name))
                                    item.categoryRef?.let { put("categoryRef", JsonPrimitive(it)) }
                                    item.folderCategoryRef?.let { put("folderCategoryRef", JsonPrimitive(it)) }
                                    item.label?.let { put("label", JsonPrimitive(it.value)) }
                                    item.pageAffinity?.let { put("pageAffinity", JsonPrimitive(it.pageOrdinal)) }
                                    item.regionAffinity?.let { put("regionAffinity", JsonPrimitive(it.name)) }
                                    put("mobility", JsonPrimitive(item.mobility.name))
                                    item.fixReason?.let { put("fixReason", JsonPrimitive(it.name)) }
                                    item.usage?.let { usage ->
                                        put(
                                            "usage",
                                            JsonObject(
                                                buildMap {
                                                    usage.foreground30d?.let { put("foreground30d", JsonPrimitive(it)) }
                                                    usage.foreground7d?.let { put("foreground7d", JsonPrimitive(it)) }
                                                    usage.recency?.let { put("recency", JsonPrimitive(it)) }
                                                    usage.activeDays?.let { put("activeDays", JsonPrimitive(it)) }
                                                    usage.launcherCount?.let { put("launcherCount", JsonPrimitive(it)) }
                                                    usage.launcherRecency?.let { put("launcherRecency", JsonPrimitive(it)) }
                                                },
                                            ),
                                        )
                                    }
                                },
                            )
                        },
                    ),
                )
                put(
                    "preservedConstraints",
                    JsonObject(
                        buildMap {
                            put(
                                "reservedRegions",
                                JsonArray(
                                    export.preservedConstraints.reservedRegions.map { region ->
                                        JsonObject(
                                            buildMap {
                                                put("pageOrdinal", JsonPrimitive(region.pageOrdinal))
                                                put("cellX", JsonPrimitive(region.cellX))
                                                put("cellY", JsonPrimitive(region.cellY))
                                                put("spanWidth", JsonPrimitive(region.spanWidth))
                                                put("spanHeight", JsonPrimitive(region.spanHeight))
                                            },
                                        )
                                    },
                                ),
                            )
                            put(
                                "preservedCounts",
                                JsonObject(
                                    buildMap {
                                        for ((reason, count) in export.preservedConstraints.preservedCounts) {
                                            put(reason.name, JsonPrimitive(count))
                                        }
                                    },
                                ),
                            )
                        },
                    ),
                )
                export.usageSignals?.let { section ->
                    put(
                        "usageSignals",
                        JsonObject(
                            buildMap {
                                put(
                                    "entries",
                                    JsonArray(
                                        section.entries.map { entry ->
                                            JsonObject(
                                                buildMap {
                                                    put("ref", JsonPrimitive(entry.ref))
                                                    put(
                                                        "usage",
                                                        JsonObject(
                                                            buildMap {
                                                                entry.usage.foreground30d?.let { put("foreground30d", JsonPrimitive(it)) }
                                                                entry.usage.foreground7d?.let { put("foreground7d", JsonPrimitive(it)) }
                                                                entry.usage.recency?.let { put("recency", JsonPrimitive(it)) }
                                                                entry.usage.activeDays?.let { put("activeDays", JsonPrimitive(it)) }
                                                                entry.usage.launcherCount?.let { put("launcherCount", JsonPrimitive(it)) }
                                                                entry.usage.launcherRecency?.let { put("launcherRecency", JsonPrimitive(it)) }
                                                            },
                                                        ),
                                                    )
                                                },
                                            )
                                        },
                                    ),
                                )
                            },
                        ),
                    )
                }
                put(
                    "capabilities",
                    JsonObject(
                        buildMap {
                            put("intentSchemaVersion", JsonPrimitive(export.capabilities.intentSchemaVersion))
                            put("functions", JsonArray(export.capabilities.functions.map { JsonPrimitive(it.name) }))
                        },
                    ),
                )
            },
        )
        val bytes = json.encodeToString(JsonObject.serializer(), root).encodeToByteArray()
        if (bytes.size > ContextExportContract.MAX_EXPORT_BYTES) {
            return ContextExportResult.Failure(ExportEncodeProblem.Oversize)
        }
        return ContextExportResult.Success(bytes)
    }

    fun decode(bytes: ByteArray): ContextExportResult {
        if (bytes.size > ContextExportContract.MAX_EXPORT_BYTES) {
            return ContextExportResult.Failure(ExportEncodeProblem.Oversize)
        }
        val root = runCatching { json.parseToJsonElement(bytes.decodeToString()) }
            .getOrNull() as? JsonObject ?: return ContextExportResult.Failure(ExportEncodeProblem.SchemaMismatch)
        val schemaVersion = root.optString("schemaVersion")
        if (schemaVersion != ContextExportContract.SCHEMA_VERSION) {
            return ContextExportResult.Failure(ExportEncodeProblem.SchemaMismatch)
        }
        val export = runCatching {
            val grid = root.obj("gridContext")
            val itemsJson = (root["items"] as? JsonArray)?.jsonArray() ?: emptyList()
            val preserved = root.obj("preservedConstraints")
            PersonalizationContextExportV1(
                exportId = requireNotNull(root.optString("exportId")),
                tier = PrivacyTier.valueOf(requireNotNull(root.optString("tier"))),
                grid = ExportGridContext(
                    columns = requireNotNull(grid?.optInt("columns")),
                    rows = requireNotNull(grid?.optInt("rows")),
                    pageCount = requireNotNull(grid?.optInt("pageCount")),
                ),
                categories = ((root["categories"] as? JsonArray)?.jsonArray() ?: emptyList()).map { raw ->
                    val category = raw as? JsonObject ?: throw IllegalArgumentException()
                    ExportCategory(
                        ref = requireNotNull(category.optString("ref")),
                        kind = CategoryRefKind.valueOf(requireNotNull(category.optString("kind"))),
                        taxonomyId = category.optString("taxonomyId"),
                        displayName = category.optString("displayName"),
                    )
                },
                items = itemsJson.mapNotNull { raw ->
                    val item = raw as? JsonObject ?: throw IllegalArgumentException()
                    ExportItem(
                        ref = requireNotNull(item.optString("ref")),
                        role = ExportItemRole.valueOf(requireNotNull(item.optString("kind"))),
                        subject = item.optString("subject")?.let { ExportItemSubject.valueOf(it) }
                            ?: ExportItemSubject.PLACED,
                        categoryRef = item.optString("categoryRef"),
                        folderCategoryRef = item.optString("folderCategoryRef"),
                        label = item.optString("label")?.let { ExportItemLabel(FreeTextClass.APP_LABEL, it) },
                        pageAffinity = item.optInt("pageAffinity")?.let { ExportPageAffinity(it) },
                        regionAffinity = item.optString("regionAffinity")?.let { ExportRegionKind.valueOf(it) },
                        mobility = Mobility.valueOf(requireNotNull(item.optString("mobility"))),
                        fixReason = item.optString("fixReason")?.let { FixReason.valueOf(it) },
                        usage = item.obj("usage")?.let { usage ->
                            UsageProjection(
                                foreground30d = usage.optInt("foreground30d"),
                                foreground7d = usage.optInt("foreground7d"),
                                recency = usage.optInt("recency"),
                                activeDays = usage.optInt("activeDays"),
                                launcherCount = usage.optInt("launcherCount"),
                                launcherRecency = usage.optInt("launcherRecency"),
                            )
                        },
                    )
                },
                preservedConstraints = PreservedConstraints(
                    reservedRegions = ((preserved?.get("reservedRegions") as? JsonArray)?.jsonArray() ?: emptyList()).map { raw ->
                        val region = raw as? JsonObject ?: throw IllegalArgumentException()
                        ReservedRegionProjection(
                            pageOrdinal = requireNotNull(region.optInt("pageOrdinal")),
                            cellX = requireNotNull(region.optInt("cellX")),
                            cellY = requireNotNull(region.optInt("cellY")),
                            spanWidth = requireNotNull(region.optInt("spanWidth")),
                            spanHeight = requireNotNull(region.optInt("spanHeight")),
                        )
                    },
                    preservedCounts = preserved?.obj("preservedCounts")?.mapKeys { (key, _) ->
                        PreservedReason.valueOf(key)
                    }?.mapValues { (_, value) ->
                        (value as? JsonPrimitive)?.intOrNull ?: throw IllegalArgumentException()
                    } ?: emptyMap(),
                ),
                capabilities = root.obj("capabilities")?.let { caps ->
                    ExportCapabilities(
                        intentSchemaVersion = requireNotNull(caps.optString("intentSchemaVersion")),
                        functions = ((caps["functions"] as? JsonArray)?.jsonArray() ?: emptyList()).map { fn ->
                            IntentCapability.valueOf((fn as? JsonPrimitive)?.jsonPrimitive?.content ?: throw IllegalArgumentException())
                        }.toSet(),
                    )
                } ?: throw IllegalArgumentException(),
                usageSignals = root.obj("usageSignals")?.let { section ->
                    UsageSignalsSection(
                        entries = ((section["entries"] as? JsonArray)?.jsonArray() ?: emptyList()).map { raw ->
                            val entry = raw as? JsonObject ?: throw IllegalArgumentException()
                            UsageSignalEntry(
                                ref = requireNotNull(entry.optString("ref")),
                                usage = UsageProjection(
                                    foreground30d = entry.obj("usage")?.optInt("foreground30d"),
                                    foreground7d = entry.obj("usage")?.optInt("foreground7d"),
                                    recency = entry.obj("usage")?.optInt("recency"),
                                    activeDays = entry.obj("usage")?.optInt("activeDays"),
                                    launcherCount = entry.obj("usage")?.optInt("launcherCount"),
                                    launcherRecency = entry.obj("usage")?.optInt("launcherRecency"),
                                ),
                            )
                        },
                    )
                },
            )
        }.getOrElse { return ContextExportResult.Failure(ExportEncodeProblem.SchemaMismatch) }
        return ContextExportResult.Success(bytes)
    }
}

sealed interface ContextExportResult {
    data class Success(val bytes: ByteArray) : ContextExportResult
    data class Failure(val problem: ExportEncodeProblem) : ContextExportResult
}

enum class ExportEncodeProblem { Oversize, SchemaMismatch }

private fun JsonObject.obj(key: String): JsonObject? {
    val raw = this[key] ?: return null
    if (raw is JsonNull) return null
    return raw as? JsonObject
}

private fun JsonObject.optString(key: String): String? {
    val raw = this[key] ?: return null
    if (raw is JsonNull) return null
    val primitive = raw as? JsonPrimitive ?: return null
    return primitive.jsonPrimitive.content
}

private fun JsonObject.optInt(key: String): Int? {
    val raw = this[key] ?: return null
    if (raw is JsonNull) return null
    val primitive = raw as? JsonPrimitive ?: return null
    return primitive.intOrNull
}

private fun kotlinx.serialization.json.JsonElement.jsonArray(): List<kotlinx.serialization.json.JsonElement> = (this as? JsonArray)?.toList() ?: emptyList()
