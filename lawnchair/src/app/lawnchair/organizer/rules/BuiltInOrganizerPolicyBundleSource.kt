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
        val canonical = buildString {
            append("rules=").append(OrganizerPolicyBundle.RULE_VERSION.value)
            append(";taxonomy=").append(categories.joinToString(",") { it.value })
            append(";fallback=").append(OrganizerPolicyBundle.OTHER.value)
            append(";classification=").append(classification.version)
            append(";android=").append(
                classification.androidCategoryMapping.entries.sortedBy { it.key }
                    .joinToString(",") { "${it.key}:${it.value.value}" },
            )
            append(";s3=").append(classification.packageRules)
            append(";s4=").append(classification.intentRules)
            append(";target=").append(targets.version)
        }
        OrganizerPolicyBundle(
            identity = PolicyBundleIdentity("organization-policy-v1", sha256Canonical(canonical)),
            rules = v1RuleSemantics(),
            taxonomy = app.lawnchair.organizer.planning.TaxonomyContract(
                version = OrganizerPolicyBundle.TAXONOMY_VERSION,
                allowedCategories = categories,
                fallbackCategory = OrganizerPolicyBundle.OTHER,
            ),
            classification = classification,
            fullOrganizationTargets = targets,
        )
    }

    override fun readActive(): BundleReadResult = bundle.validate() ?: BundleReadResult.Ready(bundle)
}
