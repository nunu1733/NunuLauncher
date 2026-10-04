package app.lawnchair.organizer.application.public

import app.lawnchair.organizer.planning.ActiveCategoryCatalog
import app.lawnchair.organizer.planning.FolderNaming

/**
 * Issue #201: resolves a planned folder's semantic naming identity into the
 * single user-facing title that preview and apply both read from the
 * materialized plan. The materializer is the only call site, so the resolved
 * string is fixed once per run (creation-time locale snapshot).
 *
 * Contract: implementations return a non-blank, locale-appropriate title and
 * never expose raw identifiers (category IDs, packages, item IDs, ordinals).
 * Unknown naming content falls back to a safe generic title inside the
 * resolver; a blank result is a port violation and fails closed.
 */
fun interface FolderTitleResolver {
    fun resolve(naming: FolderNaming): String
}

/**
 * Issue #336: binds the injected presentation resolver to ONE composition's
 * catalog snapshot. The two `materialize` call sites
 * ([app.lawnchair.organizer.application.protocol.LayoutApplicationModule]'s
 * manual materialization and
 * [app.lawnchair.organizer.application.protocol.PlanPreviewProtocol]) wrap the
 * per-call injected resolver with the total display-name lookup over
 * `input.catalog` — the same `OrganizationInput` the plan was planned from,
 * never a fresh store read — so preview and apply consume the same
 * creation-time title from the same snapshot.
 *
 * A user-defined folder naming resolves the snapshot's display name; an ID
 * absent from the snapshot delegates to the base resolver, which applies the
 * unchanged generic-fallback policy without exposing the raw ID. Built-in
 * namings and the [FolderTitleResolver] contract (non-blank localized title,
 * total lookup, blank fails closed) are unchanged: a blank display name
 * propagates to the materializer's existing blank check.
 */
internal fun FolderTitleResolver.withCompositionCatalog(catalog: ActiveCategoryCatalog): FolderTitleResolver = FolderTitleResolver { naming ->
    when (naming) {
        is FolderNaming.FromUserCategory -> catalog.displayNameOf(naming.id) ?: resolve(naming)
        else -> resolve(naming)
    }
}
