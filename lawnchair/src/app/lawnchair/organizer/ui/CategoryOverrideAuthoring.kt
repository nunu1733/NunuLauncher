package app.lawnchair.organizer.ui

import android.content.Context
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import app.lawnchair.organizer.planning.CategoryId
import app.lawnchair.organizer.planning.PackageName
import app.lawnchair.organizer.planning.ProfileId
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
import com.android.launcher3.pm.UserCache

/** Stable, profile-scoped identity rendered by the local settings surface. */
internal data class CategoryOverrideApp(
    val key: CategoryOverrideKey,
    val label: String,
    val profileLabel: String,
    val assignedCategory: CategoryId?,
)

internal sealed interface CategoryOverrideAuthoringResult {
    data class Loaded(val apps: List<CategoryOverrideApp>) : CategoryOverrideAuthoringResult
    data class Saved(val stored: CategoryOverrideStoredIdentity) : CategoryOverrideAuthoringResult
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
    context: Context,
    private val store: CategoryOverrideStore = CategoryOverrideStoreModule.get(context),
    private val bundleSource: OrganizerPolicyBundleSource = BuiltInOrganizerPolicyBundleSource,
) {
    private val appContext = context.applicationContext
    private val userCache = UserCache.INSTANCE.get(appContext)
    private val userManager = requireNotNull(appContext.getSystemService(UserManager::class.java))

    fun categories(): List<CategoryId>? {
        val bundle = (bundleSource.readActive() as? BundleReadResult.Ready)?.bundle ?: return null
        return bundle.takeIf { it.validate() == null }?.taxonomy?.allowedCategories
    }

    fun load(): CategoryOverrideAuthoringResult {
        val available = availableApps()
        val overrides = when (val stored = store.readStored()) {
            is CategoryOverrideStoredReadResult.Ready -> stored.snapshot.assignments
            CategoryOverrideStoredReadResult.Unreadable -> return CategoryOverrideAuthoringResult.StoreUnreadable
            CategoryOverrideStoredReadResult.UnsupportedSchema -> return CategoryOverrideAuthoringResult.UnsupportedSchema
            CategoryOverrideStoredReadResult.MigrationBarrierUncertain -> return CategoryOverrideAuthoringResult.MigrationBarrierUncertain
        }
        return CategoryOverrideAuthoringResult.Loaded(
            available.map { app -> app.copy(assignedCategory = overrides[app.key]) },
        )
    }

    fun save(target: CategoryOverrideApp, category: CategoryId?): CategoryOverrideAuthoringResult {
        val lease = OrganizationOperationLease.tryAcquire(OrganizationOperationLease.Kind.AUTHORING)
            ?: return CategoryOverrideAuthoringResult.OrganizationRunActive
        return try {
            val current = availableApps().firstOrNull { it.key == target.key }
                ?: return CategoryOverrideAuthoringResult.TargetUnavailable
            val expected = when (val stored = store.readStored()) {
                is CategoryOverrideStoredReadResult.Ready -> stored.snapshot.identity
                CategoryOverrideStoredReadResult.Unreadable -> return CategoryOverrideAuthoringResult.StoreUnreadable
                CategoryOverrideStoredReadResult.UnsupportedSchema -> return CategoryOverrideAuthoringResult.UnsupportedSchema
                CategoryOverrideStoredReadResult.MigrationBarrierUncertain -> return CategoryOverrideAuthoringResult.MigrationBarrierUncertain
            }
            val request = category?.let { CategoryOverrideMutation.Set(current.key, it) }
                ?: CategoryOverrideMutation.Remove(current.key)
            when (val result = store.mutate(request, expected, availableApps().mapTo(linkedSetOf()) { it.key.profile })) {
                is CategoryOverrideWriteResult.Committed -> CategoryOverrideAuthoringResult.Saved(result.stored)
                is CategoryOverrideWriteResult.NoChange -> CategoryOverrideAuthoringResult.Saved(result.stored)
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

    private fun availableApps(): List<CategoryOverrideApp> {
        val launcherApps = requireNotNull(appContext.getSystemService(android.content.pm.LauncherApps::class.java))
        val users = (userCache.userProfiles + Process.myUserHandle()).distinct()
        return users.flatMap { user ->
            val profile = profileId(user) ?: return@flatMap emptyList()
            if (!userManager.isUserUnlocked(user) || userManager.isQuietModeEnabled(user)) return@flatMap emptyList()
            launcherApps.getActivityList(null, user).map { activity ->
                CategoryOverrideApp(
                    key = CategoryOverrideKey(PackageName(activity.componentName.packageName), profile),
                    label = activity.label?.toString()?.takeIf { it.isNotBlank() } ?: activity.componentName.packageName,
                    profileLabel = if (user == Process.myUserHandle()) "Personal" else "Work profile",
                    assignedCategory = null,
                )
            }
        }.distinctBy { it.key }.sortedWith(compareBy({ it.profileLabel }, { it.label }, { it.key.packageName.value }))
    }

    /** Matches LauncherLayoutAdapter's canonical UserCache serial-to-ProfileId mapping. */
    private fun profileId(user: UserHandle): ProfileId? = try {
        userCache.getSerialNumberForUser(user).takeIf { it >= 0L }?.let { ProfileId(it.toString()) }
    } catch (_: RuntimeException) {
        null
    }
}
