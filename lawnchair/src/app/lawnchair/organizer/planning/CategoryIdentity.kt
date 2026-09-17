package app.lawnchair.organizer.planning

/**
 * Issue #336: the stable, local, opaque ID of one user-defined category.
 * Rule Management mints it as a canonical lowercase UUID v4 string; it is
 * never derived from the display name, never shown in user UI, and never
 * derived from platform or AI output. Canonical equality and ordering use
 * this ID alone, so a rename never changes identity.
 */
@Suppress("ktlint:standard:max-line-length")
data class UserCategoryId(val value: String) : Comparable<UserCategoryId> {
    init {
        require(USER_CATEGORY_ID_FORMAT.matches(value)) { "UserCategoryId must be a canonical lowercase UUID v4 string" }
    }

    override fun compareTo(other: UserCategoryId): Int = compareUtf8Bytes(value, other.value)

    companion object {
        /** Canonical lowercase UUID v4: `xxxxxxxx-xxxx-4xxx-[89ab]xxx-xxxxxxxxxxxx`. */
        val USER_CATEGORY_ID_FORMAT = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
    }
}

/**
 * Issue #336: the closed planning-domain category identity. Built-in
 * categories keep their immutable bundle-owned [CategoryId]; user-defined
 * categories are identified by a stable [UserCategoryId]. The two namespaces
 * are disjoint by construction (built-in IDs are uppercase words, user IDs
 * are UUID v4 strings), so identity collision is impossible.
 *
 * Canonical ordering is total and rename-invariant: built-in categories in
 * their existing UTF-8 byte order first, then user-defined categories in
 * UTF-8 byte order of their stable IDs.
 */
sealed interface CategoryIdentity : Comparable<CategoryIdentity> {

    /** A built-in taxonomy member (the immutable bundle projection). */
    data class BuiltIn(val id: CategoryId) : CategoryIdentity {
        override fun compareTo(other: CategoryIdentity): Int = when (other) {
            is BuiltIn -> id.compareTo(other.id)
            is UserDefined -> -1
        }
    }

    /** A user-defined catalog entry, identified by its stable opaque ID. */
    data class UserDefined(val id: UserCategoryId) : CategoryIdentity {
        override fun compareTo(other: CategoryIdentity): Int = when (other) {
            is BuiltIn -> 1
            is UserDefined -> id.compareTo(other.id)
        }
    }

    /**
     * The canonical plan/provenance string form: built-in categories keep the
     * raw ID value exactly as today (byte-for-byte plan equality for
     * built-in-only runs); a user-defined identity uses `u:<uuid>`, which
     * cannot collide with any built-in uppercase-word ID. Raw user IDs may
     * flow only into canonical one-way digests and plan identity — never into
     * user-visible surfaces or exchange export fields.
     */
    val canonicalValue: String
        get() = when (this) {
            is BuiltIn -> id.value
            is UserDefined -> "u:${id.value}"
        }
}

/**
 * Issue #336: one user-defined catalog entry. [displayName] is presentation
 * only — canonical equality and ordering use the [id] alone. Rule Management
 * owns the normalization and validation of the display name (trimmed,
 * NFC-normalized, 1–50 code points, no field separators or line breaks,
 * unique within the catalog); this planning-domain type is a pure carrier of
 * the already-normalized entry.
 */
data class UserDefinedCategory(
    val id: UserCategoryId,
    val displayName: String,
)

/**
 * Issue #336: the combined membership surface the planner receives as one
 * input field — the active built-in taxonomy plus the current user-defined
 * entries. The built-in portion is the direct immutable projection of the
 * active [TaxonomyContract] (bundle identity, membership, order, fallback,
 * and digest semantics unchanged); user-defined entries are a separate
 * content-addressed projection. The two are never conflated: nothing here
 * treats the union as bundle content.
 */
data class ActiveCategoryCatalog(
    val builtIn: TaxonomyContract,
    val userDefined: List<UserDefinedCategory>,
) {
    init {
        val ids = userDefined.map { it.id }
        require(ids.distinct().size == ids.size) { "user-defined category IDs must be unique within the catalog" }
    }

    /** The allowed identity set: built-in members plus the user-defined IDs. */
    val allowedIdentities: Set<CategoryIdentity> by lazy {
        builtIn.allowedCategories.mapTo(linkedSetOf()) { CategoryIdentity.BuiltIn(it) } +
            userDefined.mapTo(linkedSetOf()) { CategoryIdentity.UserDefined(it.id) }
    }

    /** The unchanged built-in fallback (`OTHER`), as an identity. */
    val fallback: CategoryIdentity get() = CategoryIdentity.BuiltIn(builtIn.fallbackCategory)

    /** Catalog membership for planner/composer defense-in-depth checks. */
    operator fun contains(identity: CategoryIdentity): Boolean = when (identity) {
        is CategoryIdentity.BuiltIn -> identity.id in builtIn.allowedCategories
        is CategoryIdentity.UserDefined -> userDefined.any { it.id == identity.id }
    }

    /**
     * Total lookup of a user-defined display name by stable ID. Returns null
     * for unknown IDs; callers apply the generic fallback policy (never a raw
     * ID exposure).
     */
    fun displayNameOf(id: UserCategoryId): String? = userDefined.firstOrNull { it.id == id }?.displayName
}
