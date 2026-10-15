/*
 * Issue #594: capture-time classification signal read for the category
 * destination mode. Reuses the organizer's existing signal sources
 * (CategoryOverrideStoreModule = S1, AndroidClassificationSignalSnapshotSource
 * = S2/S5) with the same S1 -> S2 -> S5 priority as
 * OrganizationInputComposer.materializeSignals; it never starts an organizer
 * run/snapshot/recovery. Unreadable evidence fails closed (no category),
 * matching AndroidClassificationSignalSnapshotSource. Package names never
 * appear in any output (organizer-diagnostics §7 Never classification).
 */
package app.lawnchair.homeedit

import android.content.Context
import app.lawnchair.organizer.integration.AndroidClassificationSignalSnapshotSource
import app.lawnchair.organizer.integration.ClassificationEvidenceRequest
import app.lawnchair.organizer.planning.CategoryId
import app.lawnchair.organizer.planning.ItemId
import app.lawnchair.organizer.planning.PackageName
import app.lawnchair.organizer.planning.ProfileId
import app.lawnchair.organizer.rules.BuiltInOrganizerPolicyBundleSource
import app.lawnchair.organizer.rules.CategoryOverrideKey
import app.lawnchair.organizer.rules.CategoryOverrideStoreModule
import app.lawnchair.organizer.rules.OverrideSnapshotReadResult

/** capture時点の分類signal読込結果（優先順位適用前のS1/S2/S5候補）。 */
class CategoryModeSignalReader(private val appContext: Context) {

    /**
     * S1/S2/S5のraw候補を読む（優先順位適用は純粋関数 [CategoryModeCapture]
     * 側で行う。spec 594 Verificationのcapture decision seam）。読めない
     * evidenceはfail-closedでnull（対応signalに載らない）。
     */
    fun readCandidates(
        packageName: String,
        userSerial: Long,
    ): CategoryModeSignalCandidates {
        val profile = ProfileId(userSerial.toString())
        val pkg = PackageName(packageName)

        // S1: the existing per-package override store, validated against the
        // active catalog on write. Read failure fails closed to no signal.
        val s1 = run {
            val result = CategoryOverrideStoreModule.source(appContext).read(setOf(profile))
            (result as? OverrideSnapshotReadResult.Ready)?.snapshot?.assignments
                ?.get(CategoryOverrideKey(pkg, profile))
                ?.let { CategoryId(it.canonicalValue) }
        }

        // S2/S5: the existing platform evidence read (LauncherApps per-profile;
        // unreadable = fail closed). The policy is the existing built-in bundle.
        val (s2, s5) = run {
            val bundle = when (
                val bundleResult = BuiltInOrganizerPolicyBundleSource.readActive()
            ) {
                is app.lawnchair.organizer.rules.BundleReadResult.Ready -> bundleResult.bundle
                else -> return CategoryModeSignalCandidates(s1, null, null)
            }
            val item = ItemId(packageName)
            val request = ClassificationEvidenceRequest(item, pkg, profile)
            val evidence = when (
                val read = AndroidClassificationSignalSnapshotSource(appContext)
                    .read(listOf(request), bundle.classification)
            ) {
                is app.lawnchair.organizer.integration.PlatformEvidenceReadResult.Ready -> read.evidence

                app.lawnchair.organizer.integration.PlatformEvidenceReadResult.Unreadable ->
                    return CategoryModeSignalCandidates(s1, null, null)
            }
            evidence.s2[item] to evidence.s5[item]
        }
        return CategoryModeSignalCandidates(s1, s2, s5)
    }
}
