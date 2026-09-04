package app.lawnchair.organizer.application.public

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
