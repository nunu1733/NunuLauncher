/*
 * Issue #594: pure JVM oracles for the category destination mode (spec 594
 * Verification). Determinism/idempotence of the resolver, profile separation
 * (positive + negative branch), delete-then-recreate identity behavior, and
 * the mapping codec round-trip / corrupt-entry fail-closed behavior.
 * Android-free: mirrors the AppDestinationPlannerTest fixture style.
 */
package app.lawnchair.homeedit

import app.lawnchair.organizer.planning.CategoryId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppDestinationCategoryResolverTest {

    private fun signal(value: String) = CategoryModeSignal(CategoryId(value))

    private fun key(identity: String, serial: Long = 0L) = CategoryMappingKey(identity, serial)

    // --- resolver: closed result (spec scenario 1/2) ---

    @Test
    fun `mapped category resolves to the folder target`() {
        val mapping = mapOf(key("GAME") to "42")
        assertEquals(
            CategoryDestinationResult.FolderTarget(42),
            AppDestinationCategoryResolver.resolve(signal("GAME"), mapping, 0L),
        )
    }

    @Test
    fun `no signal resolves to SIGNAL_UNAVAILABLE`() {
        // Promise-route preventive fallback: a capture before install cannot
        // read the signal and falls upstream without ever touching a mapping.
        assertEquals(
            CategoryDestinationResult.Unresolved(CategoryUnresolvedReason.SIGNAL_UNAVAILABLE),
            AppDestinationCategoryResolver.resolve(null, mapOf(key("GAME") to "42"), 0L),
        )
    }

    @Test
    fun `unmapped category resolves to UNMAPPED_CATEGORY`() {
        assertEquals(
            CategoryDestinationResult.Unresolved(CategoryUnresolvedReason.UNMAPPED_CATEGORY),
            AppDestinationCategoryResolver.resolve(signal("GAME"), emptyMap(), 0L),
        )
        // A different serial is a different mapping key (profile separation).
        assertEquals(
            CategoryDestinationResult.Unresolved(CategoryUnresolvedReason.UNMAPPED_CATEGORY),
            AppDestinationCategoryResolver.resolve(signal("GAME"), mapOf(key("GAME") to "42"), 1L),
        )
    }

    @Test
    fun `corrupt stored folderId resolves to MAPPING_CORRUPT`() {
        // A non-numeric stored value must not fall back to another mapping or
        // an implicit default; it fails closed upstream.
        assertEquals(
            CategoryDestinationResult.Unresolved(CategoryUnresolvedReason.MAPPING_CORRUPT),
            AppDestinationCategoryResolver.resolve(signal("GAME"), mapOf(key("GAME") to "abc"), 0L),
        )
    }

    // --- determinism / idempotence (spec Verification) ---

    @Test
    fun `resolution is deterministic and idempotent`() {
        val mapping = mapOf(key("PRODUCTIVITY") to "7")
        val first = AppDestinationCategoryResolver.resolve(signal("PRODUCTIVITY"), mapping, 0L)
        repeat(5) {
            assertEquals(first, AppDestinationCategoryResolver.resolve(signal("PRODUCTIVITY"), mapping, 0L))
        }
    }

    @Test
    fun `changing the mapping after capture does not change a serialized destination`() {
        // Reflush determinism: capture freezes the snapshot; later mapping
        // edits are never re-read for the same install (spec scenario 4).
        val before = AppDestinationCategoryResolver.resolve(signal("GAME"), mapOf(key("GAME") to "42"), 0L)
        val after = AppDestinationCategoryResolver.resolve(
            signal("GAME"),
            mapOf(key("GAME") to "99", key("MUSIC") to "5"),
            0L,
        )
        val wire = com.android.launcher3.model.DirectEditContract.serializeDestinationSnapshot(
            com.android.launcher3.model.DirectEditContract.DEST_SNAPSHOT_KIND_FOLDER,
            (before as CategoryDestinationResult.FolderTarget).folderId,
            0L,
            "app.benchmark",
        )
        // The frozen wire snapshot carries the pre-change folder id (42),
        // byte-identical regardless of the post-capture mapping state.
        assertTrue(wire.endsWith("|42|0|app.benchmark"))
        assertNotEquals(
            (after as CategoryDestinationResult.FolderTarget).folderId,
            (before as CategoryDestinationResult.FolderTarget).folderId,
        )
    }

    // --- profile separation (spec scenarios: positive + negative branch) ---

    @Test
    fun `same category maps per profile (positive branch)`() {
        val mapping = mapOf(
            key("GAME", 0L) to "10",
            key("GAME", 1L) to "20",
        )
        assertEquals(
            CategoryDestinationResult.FolderTarget(10),
            AppDestinationCategoryResolver.resolve(signal("GAME"), mapping, 0L),
        )
        assertEquals(
            CategoryDestinationResult.FolderTarget(20),
            AppDestinationCategoryResolver.resolve(signal("GAME"), mapping, 1L),
        )
    }

    @Test
    fun `work capture without a work mapping never uses the personal folder (negative branch)`() {
        // Personal has GAME->10; work (serial 1) has nothing. The work capture
        // must go upstream, never reuse the personal row.
        val mapping = mapOf(key("GAME", 0L) to "10")
        assertEquals(
            CategoryDestinationResult.Unresolved(CategoryUnresolvedReason.UNMAPPED_CATEGORY),
            AppDestinationCategoryResolver.resolve(signal("GAME"), mapping, 1L),
        )
    }

    // --- identity semantics (spec scenario: delete -> same-name recreate) ---

    @Test
    fun `recreated same-name category gets a new identity and the old mapping never attaches`() {
        // User-defined identities are u:<uuid>; a delete + same-display-name
        // recreate mints a new UUID. The old mapping key must not match.
        val oldMapping = mapOf(key("u:aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa") to "30")
        assertEquals(
            CategoryDestinationResult.FolderTarget(30),
            AppDestinationCategoryResolver.resolve(
                CategoryModeSignal(CategoryId("u:aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")),
                oldMapping,
                0L,
            ),
        )
        assertEquals(
            CategoryDestinationResult.Unresolved(CategoryUnresolvedReason.UNMAPPED_CATEGORY),
            AppDestinationCategoryResolver.resolve(
                // New identity after the recreate (different UUID).
                CategoryModeSignal(CategoryId("u:bbbbbbbb-bbbb-4bbb-9bbb-bbbbbbbbbbbb")),
                oldMapping,
                0L,
            ),
        )
    }

    @Test
    fun `built-in and user-defined identity namespaces never collide`() {
        // Built-ins are uppercase words; user-defined canonical values carry
        // the u: prefix. The same folderId under both keys stays distinct.
        val mapping = mapOf(
            key("TOOLS") to "1",
            key("u:aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa") to "2",
        )
        assertEquals(
            CategoryDestinationResult.FolderTarget(1),
            AppDestinationCategoryResolver.resolve(signal("TOOLS"), mapping, 0L),
        )
        assertEquals(
            CategoryDestinationResult.FolderTarget(2),
            AppDestinationCategoryResolver.resolve(
                CategoryModeSignal(CategoryId("u:aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")),
                mapping,
                0L,
            ),
        )
    }
}

class CategoryDestinationMappingCodecTest {

    private fun key(identity: String, serial: Long = 0L) = CategoryMappingKey(identity, serial)

    @Test
    fun `round-trip preserves all entries`() {
        val entries = mapOf(
            key("GAME") to "10",
            key("PRODUCTIVITY") to "20",
            key("u:aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa") to "30",
        )
        assertEquals(entries, CategoryDestinationMappingCodec.deserialize(CategoryDestinationMappingCodec.serialize(entries)))
    }

    @Test
    fun `serialization is order-independent (deterministic output)`() {
        val a = mapOf(key("GAME") to "10", key("MUSIC") to "20")
        val b = mapOf(key("MUSIC") to "20", key("GAME") to "10")
        assertEquals(
            CategoryDestinationMappingCodec.serialize(a),
            CategoryDestinationMappingCodec.serialize(b),
        )
    }

    @Test
    fun `corrupt entries are dropped, not fatal (fail-closed per entry)`() {
        val raw = "0|GAME|10;bad;0|BROKEN|notanumber;1|MUSIC|20"
        val restored = CategoryDestinationMappingCodec.deserialize(raw)
        assertEquals(mapOf(key("GAME") to "10", key("MUSIC", 1L) to "20"), restored)
        // Dropped entries resolve as UNMAPPED_CATEGORY upstream, never as a
        // wrong folder (spec: zero misdelivery).
        assertEquals(
            CategoryDestinationResult.Unresolved(CategoryUnresolvedReason.UNMAPPED_CATEGORY),
            AppDestinationCategoryResolver.resolve(
                CategoryModeSignal(CategoryId("BROKEN")),
                restored,
                0L,
            ),
        )
    }

    @Test
    fun `empty and null raw decode to an empty mapping`() {
        assertTrue(CategoryDestinationMappingCodec.deserialize(null).isEmpty())
        assertTrue(CategoryDestinationMappingCodec.deserialize("").isEmpty())
        assertTrue(CategoryDestinationMappingCodec.deserialize("  ").isEmpty())
    }

    @Test
    fun `capacity bound is enforced by the helper`() {
        val entries = (0 until CategoryDestinationMappingCodec.let { 64 }.coerceAtLeast(0)).associate {
            key("GAME$it") to it.toString()
        }
        assertTrue(CategoryDestinationMappingCodec.isWithinCapacity(entries))
        assertTrue(
            !CategoryDestinationMappingCodec.isWithinCapacity(
                entries + (key("OVERFLOW") to "999"),
            ),
        )
    }

    @Test
    fun `value category constant matches the policy value domain`() {
        assertEquals("category", AppDestinationPolicyPrefs.VALUE_CATEGORY)
        assertEquals("upstream", AppDestinationPolicyPrefs.VALUE_UPSTREAM)
        assertEquals("folder:", AppDestinationPolicyPrefs.VALUE_FOLDER_PREFIX)
    }
}
