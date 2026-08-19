package app.lawnchair.organizer.integration

import app.lawnchair.organizer.planning.Availability
import app.lawnchair.organizer.planning.CapturedItem
import app.lawnchair.organizer.planning.CapturedPlacement
import app.lawnchair.organizer.planning.ExistingRole
import app.lawnchair.organizer.planning.ExistingTargetMembership
import app.lawnchair.organizer.planning.ItemKind
import app.lawnchair.organizer.planning.TargetSet
import app.lawnchair.organizer.rules.FullOrganizationTargetPolicy
import app.lawnchair.organizer.rules.PolicyInputIdentity
import app.lawnchair.organizer.rules.PolicySourceKind
import app.lawnchair.organizer.rules.sha256Canonical

class FullTargetSetMaterializer {
    fun materialize(
        items: List<CapturedItem>,
        policy: FullOrganizationTargetPolicy,
    ): TargetMaterializationResult {
        if (policy.version != FULL_ORGANIZATION_TARGET_POLICY_VERSION) return TargetMaterializationResult.Invalid
        if (items.distinctBy { it.id }.size != items.size) return TargetMaterializationResult.Invalid
        val memberships = ArrayList<ExistingTargetMembership>(items.size)
        for (item in items.sortedBy { it.id }) {
            val role = when {
                item.locked -> ExistingRole.Preserved

                item.availability != Availability.AVAILABLE -> ExistingRole.Preserved

                item.placement is CapturedPlacement.Dock -> ExistingRole.Preserved

                item.placement is CapturedPlacement.FolderMember -> ExistingRole.Preserved

                item.placement is CapturedPlacement.AppPairMember -> ExistingRole.Preserved

                item.kind is ItemKind.Unknown -> return TargetMaterializationResult.Invalid

                item.placement is CapturedPlacement.UnsupportedContainer -> return TargetMaterializationResult.Invalid

                item.kind == ItemKind.APPWIDGET || item.kind == ItemKind.CUSTOM_APPWIDGET ||
                    item.kind == ItemKind.APP_PAIR || item.kind == ItemKind.SHORTCUT_LEGACY -> ExistingRole.Preserved

                item.placement is CapturedPlacement.Workspace &&
                    (item.kind == ItemKind.APPLICATION || item.kind == ItemKind.DEEP_SHORTCUT || item.kind == ItemKind.FOLDER) -> ExistingRole.Movable

                else -> ExistingRole.Preserved
            }
            memberships += ExistingTargetMembership(item.id, role)
        }
        val canonical = memberships.joinToString("\n") { "${it.item.value}:${it.role.name}" }
        return TargetMaterializationResult.Ready(
            MaterializedTargetSet(
                TargetSet(existing = memberships, additions = emptyList()),
                PolicyInputIdentity(
                    PolicySourceKind.MATERIALIZED_FULL_TARGET_SET,
                    policy.version,
                    sha256Canonical(canonical),
                ),
            ),
        )
    }

    companion object {
        const val FULL_ORGANIZATION_TARGET_POLICY_VERSION = "full-target-v1"
    }
}

sealed interface TargetMaterializationResult {
    data class Ready(val value: MaterializedTargetSet) : TargetMaterializationResult
    data object Invalid : TargetMaterializationResult
}
