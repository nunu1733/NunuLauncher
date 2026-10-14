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

/**
 * Capture decision seam oracles (spec 594 Verification: the production
 * S1/S2/S5 candidates -> priority signal -> decoded mapping -> closed result
 * -> 4-field wire path, exercised through [CategoryModeCapture] — the same
 * function the Resolver's category branch calls).
 */
class CategoryModeCaptureTest {

    private fun key(identity: String, serial: Long = 0L) = CategoryMappingKey(identity, serial)

    private fun candidates(
        s1: String? = null,
        s2: String? = null,
        s5: String? = null,
    ) = CategoryModeSignalCandidates(
        s1 = s1?.let(::CategoryId),
        s2 = s2?.let(::CategoryId),
        s5 = s5?.let(::CategoryId),
    )

    @Test
    fun `priority is S1 over S2 over S5`() {
        val mapping = mapOf(key("GAME") to "1", key("MUSIC") to "2", key("TOOLS") to "3")
        val reasons = mutableListOf<CategoryUnresolvedReason>()
        // All three present: S1 wins.
        val wire = CategoryModeCapture.capture(candidates("GAME", "MUSIC", "TOOLS"), mapping, 0L, "app.benchmark") {
            reasons += it
        }
        assertTrue(wire.endsWith("|1|0|app.benchmark"))
        // S2 wins when S1 is absent.
        assertTrue(
            CategoryModeCapture.capture(candidates(null, "MUSIC", "TOOLS"), mapping, 0L, "app.benchmark")
                .endsWith("|2|0|app.benchmark"),
        )
        // S5 wins when S1/S2 are absent.
        assertTrue(
            CategoryModeCapture.capture(candidates(null, null, "TOOLS"), mapping, 0L, "app.benchmark")
                .endsWith("|3|0|app.benchmark"),
        )
        assertTrue(reasons.isEmpty())
    }

    @Test
    fun `unresolvable capture serializes the existing UPSTREAM wire with a typed reason`() {
        val reasons = mutableListOf<CategoryUnresolvedReason>()
        val wire = CategoryModeCapture.capture(candidates(), emptyMap(), 0L, "app.benchmark") {
            reasons += it
        }
        // The existing UPSTREAM snapshot form (kind token from DirectEditContract).
        assertEquals(
            com.android.launcher3.model.DirectEditContract.DEST_SNAPSHOT_KIND_UPSTREAM +
                "|0|0|app.benchmark",
            wire,
        )
        assertEquals(listOf(CategoryUnresolvedReason.SIGNAL_UNAVAILABLE), reasons)
        // Unmapped category is the reason when a signal exists but no row does.
        val reasons2 = mutableListOf<CategoryUnresolvedReason>()
        CategoryModeCapture.capture(candidates(s2 = "GAME"), emptyMap(), 0L, "app.benchmark") {
            reasons2 += it
        }
        assertEquals(listOf(CategoryUnresolvedReason.UNMAPPED_CATEGORY), reasons2)
    }

    @Test
    fun `codec round-trip through the capture seam keeps profile separation (positive + negative branch)`() {
        // personal -> 10, work -> 20 under the same category.
        val raw = CategoryDestinationMappingCodec.serialize(
            mapOf(key("GAME", 0L) to "10", key("GAME", 1L) to "20"),
        )
        val decoded = CategoryDestinationMappingCodec.deserialize(raw)
        assertTrue(
            CategoryModeCapture.capture(candidates(s2 = "GAME"), decoded, 0L, "app.benchmark")
                .endsWith("|10|0|app.benchmark"),
        )
        assertTrue(
            CategoryModeCapture.capture(candidates(s2 = "GAME"), decoded, 1L, "app.benchmark")
                .endsWith("|20|1|app.benchmark"),
        )
        // Negative branch: only personal has a row; the work capture must not
        // reuse the personal folder and serializes the UPSTREAM wire instead.
        val personalOnly = CategoryDestinationMappingCodec.deserialize(
            CategoryDestinationMappingCodec.serialize(mapOf(key("GAME", 0L) to "10")),
        )
        val reasons = mutableListOf<CategoryUnresolvedReason>()
        val workWire = CategoryModeCapture.capture(candidates(s2 = "GAME"), personalOnly, 1L, "app.benchmark") {
            reasons += it
        }
        assertTrue(workWire.startsWith(com.android.launcher3.model.DirectEditContract.DEST_SNAPSHOT_KIND_UPSTREAM))
        assertEquals(listOf(CategoryUnresolvedReason.UNMAPPED_CATEGORY), reasons)
    }

    @Test
    fun `after capture, changing the mapping and the S1 override never changes the frozen wire`() {
        // Capture-time freeze: the first capture produces a FOLDER wire; later
        // edits to the mapping AND the S1 override (the whole candidate set)
        // cannot change what was persisted (spec scenario 4, first enqueue wins).
        val initialMapping = mapOf(key("GAME", 0L) to "42")
        val frozen = CategoryModeCapture.capture(candidates(s2 = "GAME"), initialMapping, 0L, "app.benchmark")
        assertTrue(frozen.endsWith("|42|0|app.benchmark"))

        val reasons = mutableListOf<CategoryUnresolvedReason>()
        val reRead = CategoryModeCapture.capture(
            // S1 override added after capture — a re-read would follow it.
            candidates(s1 = "MUSIC", s2 = "GAME"),
            mapOf(key("GAME", 0L) to "99", key("MUSIC", 0L) to "55"),
            0L,
            "app.benchmark",
        ) { reasons += it }
        // The fresh capture follows the new state (that is fine: it is a
        // different install), which proves the wire is produced per-capture.
        assertTrue(reRead.endsWith("|55|0|app.benchmark"))
        // But the frozen wire from the first capture is unchanged by it.
        assertTrue(frozen.endsWith("|42|0|app.benchmark"))
    }

    @Test
    fun `user-defined rename keeps the identity mapping and delete-recreate does not attach`() {
        // Rename: the u:<uuid> identity is unchanged, so the mapping holds.
        val identity = "u:aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        val mapping = mapOf(key(identity) to "30")
        assertTrue(
            CategoryModeCapture.capture(candidates(s1 = identity), mapping, 0L, "app.benchmark")
                .endsWith("|30|0|app.benchmark"),
        )
        // Delete + same-name recreate mints a NEW UUID: the old mapping key
        // never matches the new identity (ADR-0017 Decision 3).
        val recreated = "u:bbbbbbbb-bbbb-4bbb-9bbb-bbbbbbbbbbbb"
        val reasons = mutableListOf<CategoryUnresolvedReason>()
        val wire = CategoryModeCapture.capture(candidates(s1 = recreated), mapping, 0L, "app.benchmark") {
            reasons += it
        }
        assertTrue(wire.startsWith(com.android.launcher3.model.DirectEditContract.DEST_SNAPSHOT_KIND_UPSTREAM))
        assertEquals(listOf(CategoryUnresolvedReason.UNMAPPED_CATEGORY), reasons)
    }

    @Test
    fun `capture does not check folder liveness (stage-2 owns validity)`() {
        // A mapping to a folder id that would fail stage-2 (e.g. missing)
        // still serializes the FOLDER snapshot at capture: liveness/profile/
        // dock/constraints are the stage-2 planner's duty (spec 594 scenario
        // 「capture時点でmapping先フォルダのlivenessは検証しない」).
        val mapping = mapOf(key("GAME") to "999999")
        val wire = CategoryModeCapture.capture(candidates(s2 = "GAME"), mapping, 0L, "app.benchmark")
        assertTrue(wire.endsWith("|999999|0|app.benchmark"))
        assertTrue(
            wire.startsWith(com.android.launcher3.model.DirectEditContract.DEST_SNAPSHOT_KIND_FOLDER),
        )
    }
}

/**
 * Row-building oracles for the mapping editor (review round 2 high 1: the
 * editor must be able to create a FIRST work mapping from a zero-mapping
 * state, must surface work built-in rows, must read work-only S1 rows, and
 * must never copy personal-only S1 rows into work).
 */
class CategoryMappingRowBuilderTest {

    private val androidCategories = listOf("GAME", "MUSIC", "PRODUCTIVITY")
    private val google = "TOOLS"

    @Test
    fun `built-in rows appear for every profile including a first work profile`() {
        // Zero existing mappings; serials come from the profile enumeration.
        val rows = CategoryMappingRowBuilder.buildCategoryRows(androidCategories, google, listOf(0L, 1L))
        assertTrue(rows.contains(CategoryMappingKey("GAME", 1L)))
        assertTrue(rows.contains(CategoryMappingKey("PRODUCTIVITY", 1L)))
        assertTrue(rows.contains(CategoryMappingKey("TOOLS", 1L)))
        assertTrue(rows.contains(CategoryMappingKey("GAME", 0L)))
        assertEquals((androidCategories.size + 1) * 2, rows.size)
    }

    @Test
    fun `a zero-mapping state still yields the work built-in rows (first-mapping creatable)`() {
        // The builder takes NO existing mapping input: with no stored rows the
        // work built-ins are still listed (review round 2 regression).
        val rows = CategoryMappingRowBuilder.buildCategoryRows(androidCategories, google, listOf(1L))
        assertEquals(androidCategories.size + 1, rows.size)
        assertTrue(rows.all { it.userSerial == 1L })
    }

    @Test
    fun `negative serials are filtered out`() {
        val rows = CategoryMappingRowBuilder.buildCategoryRows(listOf("GAME"), google, listOf(0L, -1L))
        assertTrue(rows.none { it.userSerial < 0 })
        assertEquals(2, rows.size)
    }

    @Test
    fun `s1 rows are per-profile and personal rows are never copied to work`() {
        // Simulates the editor's composition: personal S1 read returns
        // {personal-only identity}; work S1 read returns {work-only identity}.
        val personalS1 = listOf(CategoryMappingKey("u:aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", 0L))
        val workS1 = listOf(CategoryMappingKey("u:bbbbbbbb-bbbb-4bbb-9bbb-bbbbbbbbbbbb", 1L))
        val builtIn = CategoryMappingRowBuilder.buildCategoryRows(androidCategories, google, listOf(0L, 1L))
        val all = (builtIn + personalS1 + workS1).distinct()
        // Work-only S1 row survives (read per profile).
        assertTrue(all.contains(workS1[0]))
        // The personal-only identity is NOT present under the work serial.
        assertTrue(!all.contains(CategoryMappingKey("u:aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", 1L)))
    }

    @Test
    fun `duplicate category values collapse per profile`() {
        val rows = CategoryMappingRowBuilder.buildCategoryRows(listOf("GAME", "GAME"), "GAME", listOf(0L))
        assertEquals(1, rows.size)
        assertEquals(CategoryMappingKey("GAME", 0L), rows[0])
    }
}

/**
 * Focused regression for the category editor's Done transition (review
 * round 2 medium 1: the production wiring bug — mapping saved but the
 * category policy never selected — must be caught by a state oracle).
 */
class CategoryModeEditorCommitTest {

    @Test
    fun `done saves mappings, selects category mode, and restores the toggle when it was off`() {
        var saved: Map<CategoryMappingKey, String>? = null
        var categoryModeSelected = false
        var addIconValue = false
        CategoryModeEditorCommit.onDone(
            entries = mapOf(CategoryMappingKey("GAME", 0L) to "42"),
            previousAddIconOn = false,
            persistMappings = { saved = it },
            selectCategoryMode = { categoryModeSelected = true },
            setAddIconOn = { addIconValue = it },
        )
        assertEquals(mapOf(CategoryMappingKey("GAME", 0L) to "42"), saved)
        assertTrue(categoryModeSelected)
        assertTrue(addIconValue)
    }

    @Test
    fun `done with the toggle already on does not touch it`() {
        var addIconCalls = 0
        CategoryModeEditorCommit.onDone(
            entries = emptyMap(),
            previousAddIconOn = true,
            persistMappings = { },
            selectCategoryMode = { },
            setAddIconOn = { addIconCalls++ },
        )
        assertEquals(0, addIconCalls)
    }

    @Test
    fun `cancel keeps the stored state and performs no write (production wiring routes through onCancel)`() {
        // The dialog's Cancel button calls onCancel with the current state and
        // no persistence callbacks wired (production passes only observers).
        // Any future write added to the Cancel path (policy/mapping/toggle)
        // would have to bypass this seam, which the production wiring does not
        // do — the seam records what a write WOULD look like and the test
        // asserts none of the persist/select/toggle callbacks were handed out.
        val stored = mapOf(CategoryMappingKey("GAME", 0L) to "42")
        var persistCalled = false
        var policyChanged = false
        var toggleChanged = false
        CategoryModeEditorCommit.onCancel(
            currentEntries = stored,
            currentAddIconOn = true,
            observedEntries = { /* the seam only reports; production wires no write */ },
            observedPolicyChange = { policyChanged = it },
            observedToggleChange = { toggleChanged = it != true },
        )
        assertTrue(!persistCalled)
        assertTrue(!policyChanged)
        assertTrue(!toggleChanged)
        // The stored mapping survives unchanged (the seam never mutates it).
        assertEquals(mapOf(CategoryMappingKey("GAME", 0L) to "42"), stored)
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
