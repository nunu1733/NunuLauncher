package app.lawnchair.organizer.application.adapter

import app.lawnchair.organizer.application.public.FolderTitleResolver
import app.lawnchair.organizer.planning.FolderNaming

/**
 * Issue #201 test fake: resolves every naming identity to a deterministic
 * synthetic title and records each invocation so contract tests can assert
 * the resolve-once rule (N planned folders -> exactly N resolve calls) and
 * the blank fail-closed path.
 */
class RecordingFolderTitleResolver(
    private val titleFor: (FolderNaming) -> String = { naming ->
        when (naming) {
            is FolderNaming.FromCategory -> "synthetic:${naming.category.value}"
        }
    },
) : FolderTitleResolver {

    val resolved = mutableListOf<FolderNaming>()

    override fun resolve(naming: FolderNaming): String {
        resolved += naming
        return titleFor(naming)
    }
}
