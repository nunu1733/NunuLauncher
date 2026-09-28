/*
 * Issue #449: the thin window from the edit surface to the organizer
 * application module — a read-only capture and one apply, nothing else. The
 * window shares the process's single module instance (no second instance, no
 * second recovery-store binding) and never touches the organizer run state
 * machine. UI code reaches organizer protocol types only through this file.
 */
package app.lawnchair.homeedit

import android.content.Context
import app.lawnchair.organizer.application.protocol.CapturedSnapshot
import app.lawnchair.organizer.application.public.ApplyResult
import app.lawnchair.organizer.application.public.RunId
import app.lawnchair.organizer.application.public.ValidatedLayoutPlan
import app.lawnchair.organizer.planning.RuleVersion
import app.lawnchair.organizer.planning.TaxonomyVersion
import app.lawnchair.organizer.rules.BuiltInOrganizerPolicyBundleSource
import app.lawnchair.organizer.rules.BundleReadResult
import app.lawnchair.organizer.ui.ManualOrganizationApplication
import app.lawnchair.organizer.ui.ManualOrganizationModule

class HomeEditSurfaceAccess private constructor(
    private val application: ManualOrganizationApplication,
) {

    /**
     * 編集セッションの開始とstale時の開き直しに使う読み取り専用capture
     * （零書込み。plan preview seam族と同契約）。nullは未ready・競合・capture失敗
     * （fail-closed。UIは再試行可能な待ちを示す）。
     */
    fun inspectCapture(): CapturedSnapshot? = application.inspectCapture()

    /** 適用のrunId（既存 newRunId 経路。結合点4）。 */
    fun newRunId(): RunId = application.newRunId()

    /**
     * 確定時の1回の適用（既存の安全な適用経路。ORGANIZER lease、checkpoint 1個、
     * 1 transaction、相関reload + 検証。1セッション = 1適用 = 1復元点）。
     */
    fun apply(plan: ValidatedLayoutPlan, runId: RunId): ApplyResult = application.apply(plan, runId)

    /**
     * 適用計画のprovenance列に記録する現行policy bundleのversion（結合点3）。
     * bundle検証が失敗する場合はnull（静的bundleでは起きない。fail-closedで
     * 適用を構築しない）。
     */
    fun policyVersions(): Pair<RuleVersion, TaxonomyVersion>? = when (val read = BuiltInOrganizerPolicyBundleSource.readActive()) {
        is BundleReadResult.Ready -> read.bundle.rules.version to read.bundle.taxonomy.version
        else -> null
    }

    companion object {
        fun get(context: Context): HomeEditSurfaceAccess = HomeEditSurfaceAccess(
            ManualOrganizationModule.applicationForEditSurface(context),
        )
    }
}
