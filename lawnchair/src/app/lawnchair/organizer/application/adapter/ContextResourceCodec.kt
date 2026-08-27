package app.lawnchair.organizer.application.adapter

import app.lawnchair.organizer.application.canonical.PersistentResource
import app.lawnchair.organizer.application.canonical.PersistentResourceKind
import app.lawnchair.organizer.application.public.DeviceCapabilities
import app.lawnchair.organizer.application.public.ProfileState
import app.lawnchair.organizer.planning.PageId
import app.lawnchair.organizer.planning.ReservedWorkspaceRegion
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Deterministic encoding of externally-owned context that recovery may only preserve. */
internal object ContextResourceCodec {
    private const val DEVICE_PROFILE_FORMAT_VERSION = 1
    private const val WORKSPACE_RESERVATION_FORMAT_VERSION = 1

    fun encode(
        profiles: List<ProfileState>,
        capabilities: DeviceCapabilities,
        pages: List<PageId> = emptyList(),
        reservedWorkspaceRegions: List<ReservedWorkspaceRegion> = emptyList(),
    ): List<PersistentResource> = buildList {
        profiles.sortedBy { it.id.value }.forEachIndexed { index, profile ->
            add(
                PersistentResource(
                    PersistentResourceKind.PROFILE_INVENTORY,
                    profile.id,
                    index.toLong(),
                    byteArrayOf(DEVICE_PROFILE_FORMAT_VERSION.toByte(), profile.availability.ordinal.toByte()),
                ),
            )
        }
        add(
            PersistentResource(
                PersistentResourceKind.DEVICE_PROFILE,
                null,
                profiles.size.toLong(),
                ByteBuffer.allocate(Int.SIZE_BYTES * 7).order(ByteOrder.BIG_ENDIAN)
                    .putInt(DEVICE_PROFILE_FORMAT_VERSION)
                    .putInt(capabilities.columns)
                    .putInt(capabilities.rows)
                    .putInt(capabilities.hotseatSlots)
                    .putInt(capabilities.folderMaxColumns)
                    .putInt(capabilities.folderMaxRows)
                    .putInt(capabilities.orientation.ordinal)
                    .array(),
            ),
        )
        add(
            PersistentResource(
                PersistentResourceKind.WORKSPACE_RESERVATION,
                null,
                (profiles.size + 1).toLong(),
                encodeWorkspaceReservationContext(pages, reservedWorkspaceRegions),
            ),
        )
    }

    /**
     * Recovery may change the row-derived logical page list while restoring a
     * prior layout. The QSB/platform reservation itself must remain exact:
     * enabled/disabled state, page, cell, and span are all a stale fence.
     */
    fun recoveryContextsMatch(
        target: List<PersistentResource>,
        reviewedCurrent: List<PersistentResource>,
    ): Boolean {
        if (target.size != reviewedCurrent.size) return false
        return target.zip(reviewedCurrent).all { (expected, actual) ->
            expected.kind == actual.kind &&
                expected.profileId == actual.profileId &&
                expected.order == actual.order &&
                when (expected.kind) {
                    PersistentResourceKind.WORKSPACE_RESERVATION -> {
                        val expectedConstraints = reservationConstraints(expected.payload) ?: return false
                        val actualConstraints = reservationConstraints(actual.payload) ?: return false
                        expectedConstraints == actualConstraints
                    }

                    else -> expected == actual
                }
        }
    }

    private fun reservationConstraints(payload: ByteArray): List<ReservationConstraint>? = runCatching {
        DataInputStream(ByteArrayInputStream(payload)).use { stream ->
            if (stream.readInt() != WORKSPACE_RESERVATION_FORMAT_VERSION) return null
            val pageCount = stream.readInt()
            if (pageCount < 0 || pageCount > payload.size) return null
            repeat(pageCount) { stream.readUtf8OrNull() ?: return null }
            val regionCount = stream.readInt()
            if (regionCount < 0 || regionCount > payload.size / Int.SIZE_BYTES) return null
            buildList {
                repeat(regionCount) {
                    add(
                        ReservationConstraint(
                            pageId = stream.readUtf8OrNull() ?: return null,
                            cellX = stream.readInt(),
                            cellY = stream.readInt(),
                            spanX = stream.readInt(),
                            spanY = stream.readInt(),
                        ),
                    )
                }
            }.also {
                if (stream.available() != 0) return null
            }
        }
    }.getOrNull()

    private fun DataInputStream.readUtf8OrNull(): String? {
        val size = readInt()
        if (size < 0 || size > available()) return null
        return ByteArray(size).also(::readFully).toString(Charsets.UTF_8)
    }

    private data class ReservationConstraint(
        val pageId: String,
        val cellX: Int,
        val cellY: Int,
        val spanX: Int,
        val spanY: Int,
    )

    private fun encodeWorkspaceReservationContext(
        pages: List<PageId>,
        regions: List<ReservedWorkspaceRegion>,
    ): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { stream ->
            stream.writeInt(WORKSPACE_RESERVATION_FORMAT_VERSION)
            stream.writeInt(pages.size)
            pages.forEach { stream.writeUtf8(it.value) }
            val orderedRegions = regions.sortedWith(
                compareBy<ReservedWorkspaceRegion>(
                    { it.page.pageId },
                    { it.cell.x },
                    { it.cell.y },
                    { it.span.width },
                    { it.span.height },
                ),
            )
            stream.writeInt(orderedRegions.size)
            orderedRegions.forEach { region ->
                stream.writeUtf8(region.page.pageId.value)
                stream.writeInt(region.cell.x)
                stream.writeInt(region.cell.y)
                stream.writeInt(region.span.width)
                stream.writeInt(region.span.height)
            }
        }
        return output.toByteArray()
    }

    private fun DataOutputStream.writeUtf8(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }
}
