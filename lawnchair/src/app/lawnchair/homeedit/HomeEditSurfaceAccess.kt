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
import app.lawnchair.organizer.application.public.RecoveryRequest
import app.lawnchair.organizer.application.public.RecoveryResult
import app.lawnchair.organizer.application.public.RunId
import app.lawnchair.organizer.application.public.ValidatedLayoutPlan
import app.lawnchair.organizer.planning.RevisionId
import app.lawnchair.organizer.planning.RuleVersion
import app.lawnchair.organizer.planning.TaxonomyVersion
import app.lawnchair.organizer.rules.BuiltInOrganizerPolicyBundleSource
import app.lawnchair.organizer.rules.BundleReadResult
import app.lawnchair.organizer.ui.ManualOrganizationApplication
import app.lawnchair.organizer.ui.ManualOrganizationModule

/**
 * Issue #450: the confirm/undo receipt for the edit surface. The revision is
 * the apply path's verified post-apply revision (the materialized post-state
 * that the post-write verification compared the DB against) — the canonical
 * `expectedCurrentRevision` source for the undo record; null for any
 * non-Applied result.
 */
data class HomeEditApplyReceipt(
    val result: ApplyResult,
    val verifiedPostRevision: RevisionId?,
)

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
     * 確定時の1回の適用と、Undo記録用のverified post revisionを同時に返す
     * （Issue #450。revisionは適用経路が適用後検証に使ったmaterialized
     * post-stateのrevision。正本であり、post-hoc captureは行わない）。
     */
    fun applyForUndo(plan: ValidatedLayoutPlan, runId: RunId): HomeEditApplyReceipt = application.applyWithUndoReceipt(plan, runId).let { (result, revision) ->
        HomeEditApplyReceipt(result, revision)
    }

    /**
     * Undo tapからの復元要求（Issue #450）。既存のorganizer復元mutation
     * entryを流すのみで、新設の書込み経路はない。
     */
    fun recover(request: RecoveryRequest): RecoveryResult = application.recover(request)

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
