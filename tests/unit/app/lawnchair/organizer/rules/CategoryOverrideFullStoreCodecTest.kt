package app.lawnchair.organizer.rules

import app.lawnchair.organizer.planning.CategoryId
import app.lawnchair.organizer.planning.CategoryIdentity
import app.lawnchair.organizer.planning.PackageName
import app.lawnchair.organizer.planning.ProfileId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CategoryOverrideFullStoreCodecTest {
    @Test
    fun roundTripUsesCanonicalProfilePackageOrderAndDigest() {
        val assignments = linkedMapOf(
            CategoryOverrideKey(PackageName("com.zeta"), ProfileId("20")) to CategoryIdentity.BuiltIn(CategoryId("TOOLS")),
            CategoryOverrideKey(PackageName("com.alpha"), ProfileId("10")) to CategoryIdentity.BuiltIn(CategoryId("SOCIAL")),
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
    fun schemaTwoRoundTripsKindDiscriminatedIdentityEntries() {
        val user = app.lawnchair.organizer.planning.UserCategoryId("0a000000-0000-4000-8000-00000000000a")
        val assignments = linkedMapOf(
            CategoryOverrideKey(PackageName("com.alpha"), ProfileId("10")) to CategoryIdentity.BuiltIn(CategoryId("SOCIAL")),
            CategoryOverrideKey(PackageName("com.user"), ProfileId("0")) to CategoryIdentity.UserDefined(user),
        )
        // Canonical order sorts by profile bytes then package bytes.
        val canonical = "com.user|0|u|${user.value}\ncom.alpha|10|b|SOCIAL"
        val snapshot = CategoryOverrideStoredSnapshot(
            CategoryOverrideStoredIdentity(
                schemaVersion = 2,
                generation = 8L,
                sha256 = sha256Canonical(canonical),
            ),
            assignments,
        )

        val encoded = CategoryOverrideFullStoreCodec.encode(snapshot).toString(Charsets.UTF_8)
        assertTrue(encoded.startsWith("schema=2\n"))
        assertTrue(encoded.contains("com.alpha|10|b|SOCIAL\n"))
        assertTrue(encoded.contains("com.user|0|u|${user.value}\n"))
        assertEquals(snapshot, CategoryOverrideFullStoreCodec.decode(encoded.toByteArray()))
    }

    @Test
    fun schemaOneDecodeMapsValuesToBuiltInIdentitiesWithUnchangedIdentityBytes() {
        val decoded = CategoryOverrideFullStoreCodec.decode(
            "schema=1\ngeneration=5\ndigest=${sha256Canonical("com.app|0|SOCIAL")}\nentries\ncom.app|0|SOCIAL\n".toByteArray(),
        )

        assertEquals(1, decoded?.identity?.schemaVersion)
        assertEquals(5L, decoded?.identity?.generation)
        assertEquals(
            mapOf(CategoryOverrideKey(PackageName("com.app"), ProfileId("0")) to CategoryIdentity.BuiltIn(CategoryId("SOCIAL"))),
            decoded?.assignments,
        )
    }

    @Test
    fun newerSchemaThanCurrentDecodeFailsClosedAsUnreadablePath() {
        // The decode-to-null path is exactly what a pre-336 binary observes on
        // a schema-2 header (typed Unreadable / composer OVERRIDE_UNREADABLE);
        // the current binary behaves identically for any schema it does not
        // know — the observable downgrade outcome stays the unchanged code.
        assertNull(
            CategoryOverrideFullStoreCodec.decode(
                "schema=3\ngeneration=0\ndigest=${sha256Canonical("")}\nentries\n\n".toByteArray(),
            ),
        )
    }

    @Test
    fun schemaTwoRejectsMalformedKindDiscriminatedEntries() {
        // Unknown kind discriminator.
        assertNull(
            CategoryOverrideFullStoreCodec.decode(
                "schema=2\ngeneration=0\ndigest=${sha256Canonical("com.app|0|x|SOCIAL")}\nentries\ncom.app|0|x|SOCIAL\n".toByteArray(),
            ),
        )
        // Malformed user-defined ID.
        assertNull(
            CategoryOverrideFullStoreCodec.decode(
                "schema=2\ngeneration=0\ndigest=${sha256Canonical("com.app|0|u|garbage")}\nentries\ncom.app|0|u|garbage\n".toByteArray(),
            ),
        )
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
