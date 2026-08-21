package app.lawnchair.organizer.rules

import app.lawnchair.organizer.planning.CategoryId
import app.lawnchair.organizer.planning.PackageName
import app.lawnchair.organizer.planning.ProfileId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CategoryOverrideFullStoreCodecTest {
    @Test
    fun roundTripUsesCanonicalProfilePackageOrderAndDigest() {
        val assignments = linkedMapOf(
            CategoryOverrideKey(PackageName("com.zeta"), ProfileId("20")) to CategoryId("TOOLS"),
            CategoryOverrideKey(PackageName("com.alpha"), ProfileId("10")) to CategoryId("SOCIAL"),
        )
        val snapshot = CategoryOverrideStoredSnapshot(
            CategoryOverrideStoredIdentity(
                schemaVersion = 1,
                generation = 7L,
                sha256 = sha256Canonical("com.alpha|10|SOCIAL\ncom.zeta|20|TOOLS"),
            ),
            assignments,
        )

        val encoded = CategoryOverrideFullStoreCodec.encode(snapshot)
        val decoded = CategoryOverrideFullStoreCodec.decode(encoded)

        assertEquals(snapshot.identity, decoded?.identity)
        assertEquals(
            listOf(
                CategoryOverrideKey(PackageName("com.alpha"), ProfileId("10")),
                CategoryOverrideKey(PackageName("com.zeta"), ProfileId("20")),
            ),
            decoded?.assignments?.keys?.toList(),
        )
    }

    @Test
    fun roundTripAllowsAnEmptyOverrideSnapshot() {
        val snapshot = CategoryOverrideStoredSnapshot(
            CategoryOverrideStoredIdentity(1, 0L, sha256Canonical("")),
            emptyMap(),
        )

        assertEquals(snapshot, CategoryOverrideFullStoreCodec.decode(CategoryOverrideFullStoreCodec.encode(snapshot)))
    }

    @Test
    fun rejectsDigestMismatchDuplicateKeysAndUnsupportedSchema() {
        assertNull(
            CategoryOverrideFullStoreCodec.decode(
                "schema=1\ngeneration=0\ndigest=${"0".repeat(64)}\nentries\ncom.app|0|SOCIAL\n".toByteArray(),
            ),
        )
        assertNull(
            CategoryOverrideFullStoreCodec.decode(
                "schema=1\ngeneration=0\ndigest=${sha256Canonical("com.app|0|SOCIAL")}\nentries\ncom.app|0|SOCIAL\ncom.app|0|SOCIAL\n".toByteArray(),
            ),
        )
        assertNull(
            CategoryOverrideFullStoreCodec.decode(
                "schema=9\ngeneration=0\ndigest=${"0".repeat(64)}\nentries\n\n".toByteArray(),
            ),
        )
    }
}
