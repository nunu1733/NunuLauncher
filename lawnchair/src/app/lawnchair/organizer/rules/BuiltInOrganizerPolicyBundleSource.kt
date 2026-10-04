package app.lawnchair.organizer.rules

import app.lawnchair.organizer.planning.CategoryId

/**
 * The sole v1 policy authority for manual FullOrganization.
 * Package and intent tables are deliberately empty bundle content, not fallback.
 */
object BuiltInOrganizerPolicyBundleSource : OrganizerPolicyBundleSource {
    private val categories = listOf(
        "ART", "AUTO", "BEAUTY", "BOOKS", "BUSINESS", "COMICS", "COMMUNICATION", "DATING",
        "EDUCATION", "ENTERTAINMENT", "EVENTS", "FINANCE", "FOOD", "GAME", "HEALTH", "HOUSE",
        "LIBRARIES", "LIFESTYLE", "MAPS", "MEDICAL", "MUSIC", "NEWS", "OTHER", "PARENTING",
        "PERSONALIZATION", "PHOTOGRAPHY", "PRODUCTIVITY", "SHOPPING", "SOCIAL", "SPORTS", "TOOLS",
        "TRAVEL", "VIDEO", "WEATHER",
    ).map(::CategoryId)

    private val bundle = run {
        val classification = ClassificationPolicy(
            version = OrganizerPolicyBundle.CLASSIFICATION_VERSION,
            // ApplicationInfo.CATEGORY_GAME .. CATEGORY_PRODUCTIVITY, held as platform-free ints.
            androidCategoryMapping = mapOf(
                0 to CategoryId("GAME"),
                1 to CategoryId("MUSIC"),
                2 to CategoryId("VIDEO"),
                3 to CategoryId("PHOTOGRAPHY"),
                4 to CategoryId("SOCIAL"),
                5 to CategoryId("NEWS"),
                6 to CategoryId("MAPS"),
                7 to CategoryId("PRODUCTIVITY"),
            ),
            packageRules = emptyMap(),
            intentRules = emptyMap(),
            googleCategory = CategoryId("TOOLS"),
            systemCategory = OrganizerPolicyBundle.OTHER,
        )
        val targets = FullOrganizationTargetPolicy(OrganizerPolicyBundle.TARGET_VERSION)
        // Spec 182: runtime-supported set names only implemented strategies.
        // Enabling a later strategy publishes a new bundle identity per ADR-0007 §8
        // (child 4 enabled STABLE_PAGE_TIDY_V1: new digest, unchanged schema).
        val layoutStrategies = LayoutStrategyCatalog(
            runtimeSupported = listOf(
                app.lawnchair.organizer.planning.StrategyId("CANONICAL_PAGE_COMPACT_V1"),
                app.lawnchair.organizer.planning.StrategyId("STABLE_PAGE_TIDY_V1"),
            ),
            default = app.lawnchair.organizer.planning.StrategyId("CANONICAL_PAGE_COMPACT_V1"),
        )
        val rules = v2RuleSemantics(layoutStrategies)
        val taxonomy = app.lawnchair.organizer.planning.TaxonomyContract(
            version = OrganizerPolicyBundle.TAXONOMY_VERSION,
            allowedCategories = categories,
            fallbackCategory = OrganizerPolicyBundle.OTHER,
        )
        val provisional = OrganizerPolicyBundle(
            identity = PolicyBundleIdentity(OrganizerPolicyBundle.POLICY_BUNDLE_VERSION, sha256Canonical("provisional")),
            rules = rules,
            taxonomy = taxonomy,
            classification = classification,
            fullOrganizationTargets = targets,
            layoutStrategies = layoutStrategies,
        )
        provisional.copy(
            identity = PolicyBundleIdentity(OrganizerPolicyBundle.POLICY_BUNDLE_VERSION, provisional.canonicalDigest()),
        )
    }

    override fun readActive(): BundleReadResult = bundle.validate() ?: BundleReadResult.Ready(bundle)
}
