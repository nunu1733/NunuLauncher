package app.lawnchair.organizer.rules

import app.lawnchair.organizer.planning.CategoryId
import app.lawnchair.organizer.planning.FallbackCategoryPolicy
import app.lawnchair.organizer.planning.FolderPolicy
import app.lawnchair.organizer.planning.NewFolderProfileScope
import app.lawnchair.organizer.planning.OverflowPolicy
import app.lawnchair.organizer.planning.RuleSemantics
import app.lawnchair.organizer.planning.RuleVersion
import app.lawnchair.organizer.planning.StrategyId
import app.lawnchair.organizer.planning.TaxonomyContract
import app.lawnchair.organizer.planning.TaxonomyVersion
import java.security.MessageDigest

enum class PolicySourceKind {
    ORGANIZER_POLICY_BUNDLE,
    CATEGORY_OVERRIDE_SNAPSHOT,
    LAYOUT_STRATEGY_SELECTION,
    PLATFORM_CLASSIFICATION_EVIDENCE,
    MATERIALIZED_CLASSIFICATION_SIGNALS,
    MATERIALIZED_FULL_TARGET_SET,
}

data class PolicyInputIdentity(
    val source: PolicySourceKind,
    val versionOrGeneration: String,
    val sha256: String,
) {
    init {
        require(versionOrGeneration.isNotBlank())
        require(SHA_256.matches(sha256))
    }
}

data class PolicyBundleIdentity(
    val semanticVersion: String,
    val sha256: String,
) {
    init {
        require(semanticVersion.isNotBlank())
        require(SHA_256.matches(sha256))
    }
}

data class ClassificationPolicy(
    val version: String,
    val androidCategoryMapping: Map<Int, CategoryId>,
    val packageRules: Map<String, CategoryId>,
    val intentRules: Map<String, CategoryId>,
    val googleCategory: CategoryId,
    val systemCategory: CategoryId,
)

data class FullOrganizationTargetPolicy(val version: String)

/**
 * Spec 182: the strategy capabilities the active binary's bundle declares.
 * `runtimeSupported` names only strategies implemented on this mainline; the
 * default must be a member. The catalog is bundle content and participates in
 * the immutable bundle digest — the user selection never does.
 */
data class LayoutStrategyCatalog(
    val runtimeSupported: List<StrategyId>,
    val default: StrategyId,
) {
    init {
        require(runtimeSupported.isNotEmpty())
        require(runtimeSupported.distinct().size == runtimeSupported.size)
        require(default in runtimeSupported)
    }
}

data class OrganizerPolicyBundle(
    val identity: PolicyBundleIdentity,
    val rules: RuleSemantics,
    val taxonomy: TaxonomyContract,
    val classification: ClassificationPolicy,
    val fullOrganizationTargets: FullOrganizationTargetPolicy,
    val layoutStrategies: LayoutStrategyCatalog,
) {
    /**
     * Canonical, complete representation of every identity-bearing v2 policy
     * projection. Do not add bundle semantics without extending this format.
     */
    fun canonicalRepresentation(): String = buildString {
        append("bundle=").append(identity.semanticVersion)
        append(";rule.version=").append(rules.version.value)
        append(";rule.folder.minGroupSize=").append(rules.folderPolicy.minGroupSize)
        append(";rule.folder.profileScope=").append(rules.folderPolicy.newFolderProfileScope.name)
        append(";rule.dock=").append(rules.dockPolicy.name)
        append(";rule.overflow=").append(rules.overflowPolicy.name)
        append(";rule.fallback=").append(rules.fallbackCategoryPolicy.name)
        append(";rule.strategy=").append(rules.organizationStrategy.value)
        append(";strategy.runtimeSupported=")
            .append(layoutStrategies.runtimeSupported.sorted().joinToString(",") { it.value })
        append(";strategy.default=").append(layoutStrategies.default.value)
        append(";taxonomy.version=").append(taxonomy.version.value)
        append(";taxonomy.categories=").append(taxonomy.allowedCategories.joinToString(",") { it.value })
        append(";taxonomy.fallback=").append(taxonomy.fallbackCategory.value)
        append(";classification.version=").append(classification.version)
        append(";classification.android=").append(
            classification.androidCategoryMapping.entries.sortedBy { it.key }
                .joinToString(",") { "${it.key}:${it.value.value}" },
        )
        append(";classification.package=").append(
            classification.packageRules.entries.sortedBy { it.key }
                .joinToString(",") { "${it.key}:${it.value.value}" },
        )
        append(";classification.intent=").append(
            classification.intentRules.entries.sortedBy { it.key }
                .joinToString(",") { "${it.key}:${it.value.value}" },
        )
        append(";classification.google=").append(classification.googleCategory.value)
        append(";classification.system=").append(classification.systemCategory.value)
        append(";target.version=").append(fullOrganizationTargets.version)
    }

    fun canonicalDigest(): String = sha256Canonical(canonicalRepresentation())

    fun validate(): BundleReadResult? {
        if (identity.semanticVersion != POLICY_BUNDLE_VERSION || rules.version != RULE_VERSION || taxonomy.version != TAXONOMY_VERSION ||
            classification.version != CLASSIFICATION_VERSION || fullOrganizationTargets.version != TARGET_VERSION
        ) {
            return BundleReadResult.UnsupportedVersion(identity)
        }
        if (identity.sha256 != canonicalDigest()) return BundleReadResult.Corrupt
        if (taxonomy.allowedCategories != taxonomy.allowedCategories.sorted() || taxonomy.allowedCategories.distinct().size != taxonomy.allowedCategories.size) {
            return BundleReadResult.Corrupt
        }
        if (taxonomy.fallbackCategory != OTHER || OTHER !in taxonomy.allowedCategories) {
            return BundleReadResult.Corrupt
        }
        if ((classification.androidCategoryMapping.values + classification.googleCategory + classification.systemCategory)
                .any { it !in taxonomy.allowedCategories }
        ) {
            return BundleReadResult.Corrupt
        }
        // ADR-0007 fixes S3/S4 as explicit immutable empty v1 tables.
        if (classification.packageRules.isNotEmpty() || classification.intentRules.isNotEmpty()) {
            return BundleReadResult.Corrupt
        }
        // Spec 182: catalog coherence. The composed rules must carry the
        // declared default, and the default must be declared supported.
        if (rules.organizationStrategy != layoutStrategies.default) {
            return BundleReadResult.Corrupt
        }
        return null
    }

    companion object {
        // child 2 shipped organization-policy-v2 (CANONICAL only); child 4
        // published -v2.1 (STABLE_PAGE_TIDY_V1); child 5 published -v2.2
        // (BOTTOM_FIRST_V1); child 6 published -v2.3 (GLOBAL_COMPACT_V1);
        // child 7 published -v2.4 (CATEGORY_CONTIGUOUS_V1); issue #237
        // published -v2.5 (GLOBAL_COMPACT_V2). Every strategy enablement is a
        // new semantic version/generation per ADR-0007 §8 / ADR-0012 —
        // digest-only expansion was explicitly rejected.
        const val POLICY_BUNDLE_VERSION = "organization-policy-v2.5"
        val RULE_VERSION = RuleVersion("v2")
        val TAXONOMY_VERSION = TaxonomyVersion("v1")
        const val CLASSIFICATION_VERSION = "classification-v1"
        const val TARGET_VERSION = "full-target-v1"
        val OTHER = CategoryId("OTHER")
    }
}

sealed interface BundleReadResult {
    data class Ready(val bundle: OrganizerPolicyBundle) : BundleReadResult
    data object Missing : BundleReadResult
    data object Corrupt : BundleReadResult
    data class UnsupportedVersion(val identity: PolicyBundleIdentity?) : BundleReadResult
}

interface OrganizerPolicyBundleSource {
    fun readActive(): BundleReadResult
}

internal fun sha256Canonical(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }

internal fun v2RuleSemantics(catalog: LayoutStrategyCatalog) = RuleSemantics(
    version = OrganizerPolicyBundle.RULE_VERSION,
    folderPolicy = FolderPolicy(minGroupSize = 2, newFolderProfileScope = NewFolderProfileScope.SAME_PROFILE_ONLY),
    dockPolicy = app.lawnchair.organizer.planning.DockPolicy.PRESERVE,
    overflowPolicy = OverflowPolicy.ADD_PAGES_FOR_ITEMS_THAT_FIT_EMPTY_PAGE,
    fallbackCategoryPolicy = FallbackCategoryPolicy.KEEP_AS_SINGLETON,
    organizationStrategy = catalog.default,
)

/**
 * Spec 182: identity of the **effective** `RuleSemantics` the planner receives
 * (bundle rules base with the selected strategy substituted), using exactly the
 * accepted formula — `hash(bundleIdentity.semanticVersion ||
 * bundleIdentity.sha256 || selectionIdentity.sha256 ||
 * canonical(effectiveRuleSemantics))`. The selection *generation* stays out:
 * it is already the fifth provenance row's and the stable cut's concern, and
 * the rules identity must be the identity of the effective semantics content.
 * Segments are length-prefixed so the hash input is unambiguous; raw
 * concatenation is never used.
 */
internal fun effectiveRulesIdentity(
    bundle: PolicyBundleIdentity,
    selection: PolicyInputIdentity,
    effectiveRules: RuleSemantics,
): PolicyInputIdentity = PolicyInputIdentity(
    PolicySourceKind.ORGANIZER_POLICY_BUNDLE,
    effectiveRules.version.value,
    sha256Canonical(
        lengthPrefixed(
            bundle.semanticVersion,
            bundle.sha256,
            selection.sha256,
            effectiveRules.canonicalRepresentation(),
        ),
    ),
)

/** Canonical, unambiguous representation of the rule projection only. */
internal fun RuleSemantics.canonicalRepresentation(): String = buildString {
    append("rule.version=").append(version.value)
    append(";rule.folder.minGroupSize=").append(folderPolicy.minGroupSize)
    append(";rule.folder.profileScope=").append(folderPolicy.newFolderProfileScope.name)
    append(";rule.dock=").append(dockPolicy.name)
    append(";rule.overflow=").append(overflowPolicy.name)
    append(";rule.fallback=").append(fallbackCategoryPolicy.name)
    append(";rule.strategy=").append(organizationStrategy.value)
}

private fun lengthPrefixed(vararg segments: String): String = segments.joinToString("") { segment ->
    "${segment.toByteArray(Charsets.UTF_8).size}:$segment"
}

private val SHA_256 = Regex("[0-9a-f]{64}")
