package app.lawnchair.organizer.rules

import app.lawnchair.organizer.planning.CategoryId
import app.lawnchair.organizer.planning.FallbackCategoryPolicy
import app.lawnchair.organizer.planning.FolderPolicy
import app.lawnchair.organizer.planning.NewFolderProfileScope
import app.lawnchair.organizer.planning.OrderingPolicy
import app.lawnchair.organizer.planning.OverflowPolicy
import app.lawnchair.organizer.planning.RuleSemantics
import app.lawnchair.organizer.planning.RuleVersion
import app.lawnchair.organizer.planning.TaxonomyContract
import app.lawnchair.organizer.planning.TaxonomyVersion
import java.security.MessageDigest

enum class PolicySourceKind {
    ORGANIZER_POLICY_BUNDLE,
    CATEGORY_OVERRIDE_SNAPSHOT,
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

data class OrganizerPolicyBundle(
    val identity: PolicyBundleIdentity,
    val rules: RuleSemantics,
    val taxonomy: TaxonomyContract,
    val classification: ClassificationPolicy,
    val fullOrganizationTargets: FullOrganizationTargetPolicy,
) {
    /**
     * Canonical, complete representation of every identity-bearing v1 policy
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
        append(";rule.ordering=").append(rules.orderingPolicy.name)
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
        return null
    }

    companion object {
        const val POLICY_BUNDLE_VERSION = "organization-policy-v1"
        val RULE_VERSION = RuleVersion("v1")
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

internal fun v1RuleSemantics() = RuleSemantics(
    version = OrganizerPolicyBundle.RULE_VERSION,
    folderPolicy = FolderPolicy(minGroupSize = 2, newFolderProfileScope = NewFolderProfileScope.SAME_PROFILE_ONLY),
    dockPolicy = app.lawnchair.organizer.planning.DockPolicy.PRESERVE,
    overflowPolicy = OverflowPolicy.ADD_PAGES_FOR_ITEMS_THAT_FIT_EMPTY_PAGE,
    fallbackCategoryPolicy = FallbackCategoryPolicy.KEEP_AS_SINGLETON,
    orderingPolicy = OrderingPolicy.CANONICAL_V1,
)

private val SHA_256 = Regex("[0-9a-f]{64}")
