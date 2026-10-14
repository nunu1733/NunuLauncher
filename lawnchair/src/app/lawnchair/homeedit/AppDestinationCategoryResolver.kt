/*
 * Issue #594: category-mode destination resolution (ADR-0017 Decisions 2/3).
 * Android-free pure functions mirroring the existing S1 -> S2 -> S5 signal
 * priority of OrganizationInputComposer.materializeSignals: the resolver
 * consumes an already-read classification signal and the persisted mapping
 * and returns the mapping target. Capture-side validation stops at folderId
 * recovery — folder liveness/profile/dock/constraints stay in the stage-2
 * planner (spec 594 Scope; review round 1 high 2). Category identities are
 * the existing stable ids (built-in CategoryId value, user-defined u:<uuid>);
 * display names are never persistence keys (ADR-0017 Decision 3).
 */
package app.lawnchair.homeedit

import app.lawnchair.organizer.planning.CategoryId
import com.android.launcher3.model.DirectEditContract

/** capture時点でresolverが読める分類signal（優先順位の適用結果。空なら解決不能）。 */
data class CategoryModeSignal(
    /** S1 override / S2 / S5 のいずれかで解決できたカテゴリ。複数読めた場合は優先順位第1位のみ。 */
    val category: CategoryId,
)

/** カテゴリモードのmapping入力のidentity（ADR-0017 Decision 3。userSerial込みでprofile分離）。 */
data class CategoryMappingKey(
    /** built-inは生ID、user-definedは `u:<uuid>` canonical value。表示名は載らない。 */
    val categoryIdentity: String,
    val userSerial: Long,
)

/**
 * capture時の解決結果（closed result）。folderIdを復元できた場合は既存4field
 * `FOLDER` snapshotの対象になり、解決不能は既存`UPSTREAM` snapshotへ落ちる。
 * folderの有効性はこの段では検証しない（spec 594 Scenario「capture時点で
 * mapping先フォルダのlivenessは検証しない」）。
 */
sealed interface CategoryDestinationResult {
    /** mapping照合に成功。folderのliveness検証はstage-2に委ねる。 */
    data class FolderTarget(val folderId: Int) : CategoryDestinationResult

    /**
     * 解決不能（signal不在・未mapping・mapping記録破損・catalog消失）。
     * captureは既存`UPSTREAM` snapshotを永続化する。typed理由はFileLogに出る
     * （package名は出力しない）。
     */
    data class Unresolved(val reason: CategoryUnresolvedReason) : CategoryDestinationResult
}

/** カテゴリモードcapture時の解決不能理由（FileLogのtyped記録用）。 */
enum class CategoryUnresolvedReason {
    /** 分類signalが読めなかった（promise経路の予防的fallback契約を含む。spec 594 Scenario 3）。 */
    SIGNAL_UNAVAILABLE,

    /** 分類はできたが、そのカテゴリへのmappingが未設定。 */
    UNMAPPED_CATEGORY,

    /** mappingの保存値が破損している（folderIdが復元不能）。 */
    MAPPING_CORRUPT,
}

/**
 * mapping照合の純粋関数（決定性・冪等性。JVM testのcanonical対象）。signalと
 * mappingのid同士の照合のみを行い、時刻・乱数・Android依存を持たない。
 */
object AppDestinationCategoryResolver {

    fun resolve(
        signal: CategoryModeSignal?,
        mapping: Map<CategoryMappingKey, String>,
        userSerial: Long,
    ): CategoryDestinationResult {
        val category = signal?.category ?: return CategoryDestinationResult.Unresolved(
            CategoryUnresolvedReason.SIGNAL_UNAVAILABLE,
        )
        val stored = mapping[CategoryMappingKey(category.value, userSerial)]
            ?: return CategoryDestinationResult.Unresolved(CategoryUnresolvedReason.UNMAPPED_CATEGORY)
        val folderId = stored.toIntOrNull() ?: return CategoryDestinationResult.Unresolved(
            CategoryUnresolvedReason.MAPPING_CORRUPT,
        )
        return CategoryDestinationResult.FolderTarget(folderId)
    }
}

/**
 * capture時の分類signal候補（S1 override / S2 / S5のraw読取結果）。優先順位の
 * 適用自体は純粋関数側で行い、Android依存の読取はreaderが担う（spec 594
 * Verification「実際のmapping codec/resolver→capture seam」をJVM testで
 * 固定するためのtest可能なcapture decision seam）。
 */
data class CategoryModeSignalCandidates(
    val s1: CategoryId?,
    val s2: CategoryId?,
    val s5: CategoryId?,
)

/**
 * カテゴリモードcaptureの決定（純粋関数。本番capture pathとJVM testの共通正）。
 * signal候補→優先signal（S1→S2→S5、`OrganizationInputComposer.materializeSignals`
 * と同一順序）→decoded mapping→closed result→4field wire snapshotまでを
 * 1箇所に固定する。folderのlivenessは検証しない（stage-2に一本化。spec 594）。
 * 解決不能は既存`UPSTREAM` snapshot wireを返し、typed理由はreason引数へ出る
 * （package名はwireへ載るが、FileLog等の出力面には出ない）。
 */
object CategoryModeCapture {

    fun firstSignal(candidates: CategoryModeSignalCandidates): CategoryModeSignal? = when {
        candidates.s1 != null -> CategoryModeSignal(candidates.s1)
        candidates.s2 != null -> CategoryModeSignal(candidates.s2)
        candidates.s5 != null -> CategoryModeSignal(candidates.s5)
        else -> null
    }

    fun capture(
        candidates: CategoryModeSignalCandidates,
        mapping: Map<CategoryMappingKey, String>,
        userSerial: Long,
        packageName: String,
        reason: (CategoryUnresolvedReason) -> Unit = {},
    ): String {
        val result = AppDestinationCategoryResolver.resolve(
            firstSignal(candidates),
            mapping,
            userSerial,
        )
        return when (result) {
            is CategoryDestinationResult.FolderTarget ->
                DirectEditContract
                    .serializeDestinationSnapshot(
                        DirectEditContract.DEST_SNAPSHOT_KIND_FOLDER,
                        result.folderId,
                        userSerial,
                        packageName,
                    )

            is CategoryDestinationResult.Unresolved -> {
                reason(result.reason)
                DirectEditContract.serializeDestinationSnapshot(
                    DirectEditContract.DEST_SNAPSHOT_KIND_UPSTREAM,
                    0,
                    userSerial,
                    packageName,
                )
            }
        }
    }
}

/**
 * カテゴリモードのmapping保存値のcodec（ADR-0017 Decision 5。既存
 * `pref_new_app_destination` の値域拡張）。値域は既存2種に `category` を加えた
 * 3種であり、旧値のdecode互換は `AppDestinationPolicyPrefs` が維持する。
 * mapping本体は同一prefキーに保存しない（policy選択値とmapping集を分離し、
 * 既存値の読み手への影響を避ける。schema/migrationは発生しない）。
 */
object CategoryDestinationMappingCodec {

    const val VALUE_CATEGORY = "category"
    private const val ENTRY_SEPARATOR = ";"

    // `|` matches the platform wire separator and never appears inside a
    // built-in id or a u:<uuid> canonical value, so identity fields stay intact.
    private const val FIELD_SEPARATOR = "|"
    private const val MAX_ENTRIES = 64

    /** `category` 選択値（mapping集は別キー）。 */
    const val VALUE_CATEGORY_PREFIX = "category"

    /** mapping集を1行文字列へ直列化する（決定的。idのbyte orderで安定化）。 */
    fun serialize(entries: Map<CategoryMappingKey, String>): String = entries.entries
        .sortedBy { "${it.key.userSerial}:${it.key.categoryIdentity}" }
        .joinToString(ENTRY_SEPARATOR) { (key, folderId) ->
            "${key.userSerial}$FIELD_SEPARATOR${key.categoryIdentity}$FIELD_SEPARATOR$folderId"
        }

    /**
     * 1行文字列からmapping集を復元する。破損entry（field数不足・serial非数値・
     * folderId非数値・identity空）はentry単位で落とす（fail-closed: 欠けた
     * entryはUNMAPPED_CATEGORYとして上流既定へ落ち、誤配送は起こらない）。
     * 同keyの重複は後勝ち（決定的）。
     */
    fun deserialize(raw: String?): Map<CategoryMappingKey, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        val parsed = LinkedHashMap<CategoryMappingKey, String>()
        for (entry in raw.split(ENTRY_SEPARATOR)) {
            val fields = entry.split(FIELD_SEPARATOR)
            if (fields.size != 3) continue
            val serial = fields[0].toLongOrNull() ?: continue
            val identity = fields[1].takeIf { it.isNotEmpty() } ?: continue
            val folderId = fields[2].toIntOrNull() ?: continue
            parsed[CategoryMappingKey(identity, serial)] = fields[2]
        }
        return parsed
    }

    fun entryCount(entries: Map<CategoryMappingKey, String>): Int = entries.size

    fun isWithinCapacity(entries: Map<CategoryMappingKey, String>): Boolean = entries.size <= MAX_ENTRIES
}
