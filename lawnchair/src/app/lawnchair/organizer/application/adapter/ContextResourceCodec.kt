package app.lawnchair.organizer.application.adapter

import app.lawnchair.organizer.application.canonical.PersistentResource
import app.lawnchair.organizer.application.canonical.PersistentResourceKind
import app.lawnchair.organizer.application.public.DeviceCapabilities
import app.lawnchair.organizer.application.public.ProfileState
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Deterministic encoding of externally-owned context that recovery may only preserve. */
internal object ContextResourceCodec {
    private const val FORMAT_VERSION = 1

    fun encode(
        profiles: List<ProfileState>,
        capabilities: DeviceCapabilities,
    ): List<PersistentResource> = buildList {
        profiles.sortedBy { it.id.value }.forEachIndexed { index, profile ->
            add(
                PersistentResource(
                    PersistentResourceKind.PROFILE_INVENTORY,
                    profile.id,
                    index.toLong(),
                    byteArrayOf(FORMAT_VERSION.toByte(), profile.availability.ordinal.toByte()),
                ),
            )
        }
        add(
            PersistentResource(
                PersistentResourceKind.DEVICE_PROFILE,
                null,
                profiles.size.toLong(),
                ByteBuffer.allocate(Int.SIZE_BYTES * 7).order(ByteOrder.BIG_ENDIAN)
                    .putInt(FORMAT_VERSION)
                    .putInt(capabilities.columns)
                    .putInt(capabilities.rows)
                    .putInt(capabilities.hotseatSlots)
                    .putInt(capabilities.folderMaxColumns)
                    .putInt(capabilities.folderMaxRows)
                    .putInt(capabilities.orientation.ordinal)
                    .array(),
            ),
        )
    }
}
