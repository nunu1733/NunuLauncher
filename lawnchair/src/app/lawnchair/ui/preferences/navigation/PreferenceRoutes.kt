package app.lawnchair.ui.preferences.navigation

import androidx.annotation.Keep
import app.lawnchair.organizer.diagnostics.model.Trigger
import app.lawnchair.ui.preferences.components.search.SearchProviderId
import app.lawnchair.ui.preferences.destinations.SearchRoute
import kotlinx.serialization.Serializable

/**
 * Represents a route in the Lawnchair preferences navigation graph.
 *
 * This sealed interface is the base for all navigation destinations within the preferences.
 * Each implementing object or data class defines a specific screen or action.
 *
 * The `@Serializable` annotation indicates that this interface and its implementations
 * can be serialized, which is useful for state saving and deep linking.
 */
@Serializable
sealed interface PreferenceRoute

/**
 * determines whether this is one of the root routes shown in the preference dashboard
 */
@Serializable
sealed interface PreferenceRootRoute : PreferenceRoute

// Misc routes

@Serializable
data object Root : PreferenceRootRoute

@Serializable
data object Dummy : PreferenceRootRoute

// Top-level destinations
@Serializable
data object General : PreferenceRootRoute

@Serializable
data object HomeScreen : PreferenceRootRoute

@Serializable
data object Dock : PreferenceRootRoute

@Serializable
data object AppDrawer : PreferenceRootRoute

// technically the search screen, selectedId selects the default tab inside this
@Serializable
data class Search(val selectedId: SearchRoute = SearchRoute.DOCK_SEARCH) : PreferenceRootRoute

@Serializable
data object Folders : PreferenceRootRoute

@Serializable
data object Quickstep : PreferenceRootRoute

@Serializable
data object Gestures : PreferenceRootRoute

@Serializable
data object Smartspace : PreferenceRootRoute

@Serializable
data object About : PreferenceRootRoute

@Serializable
data object ExperimentalFeatures : PreferenceRootRoute

@Serializable
data object DebugMenu : PreferenceRootRoute

@Serializable
data object FeatureFlags : PreferenceRoute

// General section routes
@Serializable
data class GeneralFontSelection(val prefKey: String) : PreferenceRoute

@Serializable
data object GeneralIconPack : PreferenceRoute

@Serializable
data object GeneralIconShape : PreferenceRoute

@Serializable
data object GeneralCustomIconShapeCreator : PreferenceRoute

// Home Screen section routes
@Serializable
data object HomeScreenGrid : PreferenceRoute

@Serializable
data object HomeScreenPopupEditor : PreferenceRoute

// Issue #38: placement lock management and unknown-state review.
@Serializable
data object HomeScreenPlacementLocks : PreferenceRoute

// Issue #99: route carries no app/profile identity or write authorization.
@Serializable
data object HomeScreenCategoryOverrides : PreferenceRoute

// Issue #336: management surface for user-defined categories; carries no
// category identity, write authorization, or catalog content.
@Serializable
data object HomeScreenCustomCategories : PreferenceRoute

// Issue #138: supported release Settings route exposing the organizer
// diagnostics journal export without the developer debug menu.
@Serializable
data object HomeScreenOrganizerDiagnostics : PreferenceRoute

// Issue #366: Organizer hub (T-01) — the persistent organizing workspace.
// Argument-less like the other organizer destinations: it carries no run
// state and no write authority.
@Serializable
data object HomeScreenOrganizer : PreferenceRoute

// Issue #368: strategy materials surface (TO-BE T-05) — the permanent home
// of the strategy picker, reached only from the hub materials section.
// Argument-less: it carries no run state; write admission goes through the
// shared OrganizationOperationLease domain, not navigation.
@Serializable
data object HomeScreenOrganizerStrategy : PreferenceRoute

// Issues #52/#53: persist only the stable caller context, never run state or write authority.
// Issue #116: typed Navigation resolves this enum argument by its default fully qualified
// name at runtime, so minification must not rename or remove the class identity.
@Keep // This is refed by a Kotlin serializer, we must keep it's fully qualified name.
@Serializable
enum class OrganizationEntry {
    MANUAL,
    ONBOARDING,
}

@Serializable
data class HomeScreenManualOrganization(
    val entry: OrganizationEntry = OrganizationEntry.MANUAL,
    // Issue #376 (D-15): the hub's restore CTA lands here with this flag set;
    // the run destination then owns the durable-entry admission (and pops
    // itself on a silent rejection). Persisted only as part of the nav back
    // stack, never as run state or write authority.
    val durableRecovery: Boolean = false,
) : PreferenceRoute {
    val trigger: Trigger
        get() = when (entry) {
            OrganizationEntry.MANUAL -> Trigger.MANUAL_FULL
            OrganizationEntry.ONBOARDING -> Trigger.ONBOARDING_PROPOSAL
        }
}

// Dock section routes
@Serializable
data object DockSearchProvider : PreferenceRoute

// App Drawer section routes
@Serializable
data object AppDrawerHiddenApps : PreferenceRoute

@Serializable
data object AppDrawerFolder : PreferenceRoute

@Serializable
data class AppDrawerAppListToFolder(val id: Int) : PreferenceRoute

// Search section routes
@Serializable
data class SearchProviderPreference(val id: SearchProviderId) : PreferenceRoute

// Smartspace section routes
@Serializable
data object SmartspaceWidget : PreferenceRoute

// Gestures section routes
@Serializable
data object GesturesPickApp : PreferenceRoute

// About section routes
@Serializable
data object AboutLicenses : PreferenceRoute

// Data/Action oriented routes (might be used across sections or are specific actions)
// These are intentionally not prefixed as per your instruction,
// as they might be used across different sections or are standalone actions.
@Serializable
data class SelectIcon(
    // assuming componentKey is a ComponentKey.toString()
    val componentKey: String,
) : PreferenceRoute

// default to empty
@Serializable
data class IconPicker(val packageName: String = "") : PreferenceRoute

@Serializable
data class ColorSelection(val prefKey: String) : PreferenceRoute

@Serializable
data object CreateBackup : PreferenceRoute

@Serializable
data class RestoreBackup(val base64Uri: String) : PreferenceRoute

@Serializable
data class RestoreNovaBackup(val base64Uri: String) : PreferenceRoute
