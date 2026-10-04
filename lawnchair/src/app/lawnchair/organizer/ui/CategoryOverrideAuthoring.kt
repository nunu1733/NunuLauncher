package app.lawnchair.organizer.ui

import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Process
import android.os.UserManager
import app.lawnchair.organizer.application.adapter.canonicalProfileId
import app.lawnchair.organizer.planning.CategoryIdentity
import app.lawnchair.organizer.planning.PackageName
import app.lawnchair.organizer.planning.ProfileId
import app.lawnchair.organizer.planning.UserDefinedCategory
import app.lawnchair.organizer.rules.BuiltInOrganizerPolicyBundleSource
import app.lawnchair.organizer.rules.BundleReadResult
import app.lawnchair.organizer.rules.CategoryOverrideKey
import app.lawnchair.organizer.rules.CategoryOverrideMutation
import app.lawnchair.organizer.rules.CategoryOverrideStore
import app.lawnchair.organizer.rules.CategoryOverrideStoreModule
import app.lawnchair.organizer.rules.CategoryOverrideStoredIdentity
import app.lawnchair.organizer.rules.CategoryOverrideStoredReadResult
import app.lawnchair.organizer.rules.CategoryOverrideWriteResult
import app.lawnchair.organizer.rules.OrganizerPolicyBundleSource
import app.lawnchair.organizer.rules.PolicyInputIdentity
import app.lawnchair.organizer.rules.UserDefinedCategoryCatalogReadResult
import app.lawnchair.organizer.rules.UserDefinedCategoryCatalogSource
import app.lawnchair.organizer.rules.UserDefinedCategoryStoreModule
import com.android.launcher3.pm.UserCache

/** Product-safe profile discriminator; the serial-based ProfileId remains persistence-only. */
internal enum class CategoryOverrideProfile { PERSONAL, WORK }

/** Stable, profile-scoped app identity rendered by the local settings surface. */
internal data class CategoryOverrideApp(
    val key: CategoryOverrideKey,
    val label: String?,
    val profile: CategoryOverrideProfile,
    val icon: Drawable?,
    /** Issue #336: the assigned value is identity-typed (built-in or user-defined). */
    val assignedCategory: CategoryIdentity?,
)

/** Platform inventory boundary; it deliberately returns typed package/profile pairs only. */
internal fun interface CategoryOverrideAppInventory {
    fun availableApps(): List<CategoryOverrideApp>
}

internal sealed interface CategoryOverrideAuthoringResult {
    data class Loaded(val apps: List<CategoryOverrideApp>) : CategoryOverrideAuthoringResult
    data class Saved(
        val stored: CategoryOverrideStoredIdentity,
        val verificationVisible: PolicyInputIdentity,
    ) : CategoryOverrideAuthoringResult
    data class NoChange(
        val stored: CategoryOverrideStoredIdentity,
        val verificationVisible: PolicyInputIdentity,
    ) : CategoryOverrideAuthoringResult
    data object TargetUnavailable : CategoryOverrideAuthoringResult
    data object OrganizationRunActive : CategoryOverrideAuthoringResult
    data object StoreUnreadable : CategoryOverrideAuthoringResult
    data object UnsupportedSchema : CategoryOverrideAuthoringResult
    data object MigrationBarrierUncertain : CategoryOverrideAuthoringResult
    data object Conflict : CategoryOverrideAuthoringResult
    data object InvalidCategory : CategoryOverrideAuthoringResult
    data object TaxonomyUnavailable : CategoryOverrideAuthoringResult
    data object WriteFailed : CategoryOverrideAuthoringResult
    data object VerificationFailed : CategoryOverrideAuthoringResult
}

/**
 * UI/coordinator-side facade. It owns platform availability validation and the
 * organization-operation lease, while Rule Management owns persistence and
 * taxonomy validation.
 */
internal class CategoryOverrideAuthoringCoordinator internal constructor(
    private val store: CategoryOverrideStore,
    private val bundleSource: OrganizerPolicyBundleSource,
    private val inventory: CategoryOverrideAppInventory,
    // Issue #336: the Rule Management-owned user-defined catalog joins the
    // selector surface; a failed read leaves the editor unavailable (never an
    // implicit built-in-only catalog).
    private val catalogSource: UserDefinedCategoryCatalogSource? = null,
) {
    constructor(context: Context) : this(
        store = CategoryOverrideStoreModule.get(context),
        bundleSource = BuiltInOrganizerPolicyBundleSource,
        inventory = AndroidCategoryOverrideAppInventory(context),
        catalogSource = UserDefinedCategoryStoreModule.source(context),
    )

    /**
     * The active category catalog as selectable identities in canonical order:
     * built-in categories in bundle order first, then user-defined entries in
     * stable-ID order. `null` is the fail-closed "editor unavailable" state
     * (invalid bundle, or a user-defined catalog read failure).
     */
    fun categories(): List<CategoryIdentity>? {
        val bundle = (bundleSource.readActive() as? BundleReadResult.Ready)?.bundle ?: return null
        if (bundle.validate() != null) return null
        val builtIn = bundle.taxonomy.allowedCategories.map { CategoryIdentity.BuiltIn(it) }
        val userDefined = when (val catalog = catalogSource?.read()) {
            null -> emptyList()

            is UserDefinedCategoryCatalogReadResult.Ready -> catalog.snapshot.categories.map { CategoryIdentity.UserDefined(it.id) }

            UserDefinedCategoryCatalogReadResult.Unreadable,
            UserDefinedCategoryCatalogReadResult.UnsupportedSchema,
            -> return null
        }
        return builtIn + userDefined
    }

    /** User-defined entries for presenting the "Custom" marker and names. */
    fun userDefinedEntries(): List<UserDefinedCategory>? {
        val catalog = catalogSource?.read() ?: return null
        return (catalog as? UserDefinedCategoryCatalogReadResult.Ready)?.snapshot?.categories
    }

    fun load(): CategoryOverrideAuthoringResult {
        val allowedCategories = categories()?.toSet() ?: return CategoryOverrideAuthoringResult.TaxonomyUnavailable
        val available = inventory.availableApps()
        val overrides = when (val stored = store.readStored()) {
            is CategoryOverrideStoredReadResult.Ready -> {
                // Issue #336: values are identity-typed and validated against
                // the ACTIVE catalog (both namespaces); anything else is a
                // fail-closed unreadable store.
                if (stored.snapshot.assignments.values.any { assignment -> assignment !in allowedCategories }) {
                    return CategoryOverrideAuthoringResult.StoreUnreadable
                }
                stored.snapshot.assignments
            }

            CategoryOverrideStoredReadResult.Unreadable -> return CategoryOverrideAuthoringResult.StoreUnreadable

            CategoryOverrideStoredReadResult.UnsupportedSchema -> return CategoryOverrideAuthoringResult.UnsupportedSchema

            CategoryOverrideStoredReadResult.MigrationBarrierUncertain -> return CategoryOverrideAuthoringResult.MigrationBarrierUncertain
        }
        return CategoryOverrideAuthoringResult.Loaded(
            available.map { app ->
                app.copy(assignedCategory = overrides[app.key])
            },
        )
    }

    fun save(target: CategoryOverrideApp, category: CategoryIdentity?): CategoryOverrideAuthoringResult {
        val allowedCategories = categories()?.toSet() ?: return CategoryOverrideAuthoringResult.TaxonomyUnavailable
        val lease = OrganizationOperationLease.tryAcquire(OrganizationOperationLease.Kind.AUTHORING)
            ?: return CategoryOverrideAuthoringResult.OrganizationRunActive
        return try {
            val expected = when (val stored = store.readStored()) {
                is CategoryOverrideStoredReadResult.Ready -> {
                    if (stored.snapshot.assignments.values.any { assignment -> assignment !in allowedCategories }) {
                        return CategoryOverrideAuthoringResult.StoreUnreadable
                    }
                    stored.snapshot.identity
                }

                CategoryOverrideStoredReadResult.Unreadable -> return CategoryOverrideAuthoringResult.StoreUnreadable

                CategoryOverrideStoredReadResult.UnsupportedSchema -> return CategoryOverrideAuthoringResult.UnsupportedSchema

                CategoryOverrideStoredReadResult.MigrationBarrierUncertain -> return CategoryOverrideAuthoringResult.MigrationBarrierUncertain
            }
            // Take one final platform snapshot immediately before dispatching the mutation.
            // The target and verification profile set must describe that same availability cut.
            val finalInventory = inventory.availableApps()
            val current = finalInventory.firstOrNull { it.key == target.key }
                ?: return CategoryOverrideAuthoringResult.TargetUnavailable
            // Issue #336: the assignment target is the identity; membership in
            // the active catalog is enforced by the store's write-time
            // validation.
            val request = category
                ?.let { CategoryOverrideMutation.Set(current.key, it) }
                ?: CategoryOverrideMutation.Remove(current.key)
            when (val result = store.mutate(request, expected, finalInventory.mapTo(linkedSetOf()) { it.key.profile })) {
                is CategoryOverrideWriteResult.Committed -> CategoryOverrideAuthoringResult.Saved(result.stored, result.verificationVisible)
                is CategoryOverrideWriteResult.NoChange -> CategoryOverrideAuthoringResult.NoChange(result.stored, result.verificationVisible)
                CategoryOverrideWriteResult.InvalidCategory -> CategoryOverrideAuthoringResult.InvalidCategory
                CategoryOverrideWriteResult.TaxonomyUnavailable -> CategoryOverrideAuthoringResult.TaxonomyUnavailable
                CategoryOverrideWriteResult.StoreUnreadable -> CategoryOverrideAuthoringResult.StoreUnreadable
                CategoryOverrideWriteResult.UnsupportedSchema -> CategoryOverrideAuthoringResult.UnsupportedSchema
                CategoryOverrideWriteResult.MigrationBarrierUncertain -> CategoryOverrideAuthoringResult.MigrationBarrierUncertain
                CategoryOverrideWriteResult.Conflict -> CategoryOverrideAuthoringResult.Conflict
                CategoryOverrideWriteResult.WriteFailed -> CategoryOverrideAuthoringResult.WriteFailed
                CategoryOverrideWriteResult.VerificationFailed -> CategoryOverrideAuthoringResult.VerificationFailed
            }
        } finally {
            lease.close()
        }
    }
}

/** Production implementation using the same UserCache serial mapping as canonical layout capture. */
private class AndroidCategoryOverrideAppInventory(
    context: Context,
) : CategoryOverrideAppInventory {
    private val appContext = context.applicationContext
    private val userCache = UserCache.INSTANCE.get(appContext)
    private val userManager = requireNotNull(appContext.getSystemService(UserManager::class.java))

    override fun availableApps(): List<CategoryOverrideApp> {
        val launcherApps = requireNotNull(appContext.getSystemService(android.content.pm.LauncherApps::class.java))
        val currentUser = Process.myUserHandle()
        val users = (userCache.userProfiles + currentUser).distinct()
        return users.flatMap { user ->
            val profile = canonicalProfileId(userCache, user) ?: return@flatMap emptyList()
            if (!userManager.isUserUnlocked(user) || userManager.isQuietModeEnabled(user)) return@flatMap emptyList()
            launcherApps.getActivityList(null, user).map { activity ->
                CategoryOverrideApp(
                    key = CategoryOverrideKey(PackageName(activity.componentName.packageName), profile),
                    label = activity.label?.toString()?.takeIf { it.isNotBlank() },
                    profile = if (user == currentUser) CategoryOverrideProfile.PERSONAL else CategoryOverrideProfile.WORK,
                    icon = activity.getBadgedIcon(0),
                    assignedCategory = null,
                )
            }
        }.distinctBy { it.key }.sortedWith(
            compareBy<CategoryOverrideApp>({ it.profile }, { it.label.orEmpty() }, { it.key.packageName.value }),
        )
    }
}
