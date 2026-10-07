package db.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class V4__Canonical_email_identityTest {

    @Test
    void freezesAsciiCanonicalizationAndRejectsUnicodeIdentityRewrites() {
        String valid = V4__Canonical_email_identity.canonicalizeV4(" USER@EXAMPLE.COM ");
        assertEquals("user@example.com", valid);
        assertTrue(V4__Canonical_email_identity.isSafeCanonicalEmail(valid));

        String[][] fixtures = {
                {"USER@example.com", "user@example.com"},
                {" person+tag@EXAMPLE.COM ", "person+tag@example.com"},
                {"USER@XN--BCHER-KVA.DE", "user@xn--bcher-kva.de"}
        };
        for (String[] fixture : fixtures) {
            String migrationValue = V4__Canonical_email_identity.canonicalizeV4(fixture[0]);
            assertEquals(fixture[1], migrationValue);
            assertTrue(V4__Canonical_email_identity.isSafeCanonicalEmail(migrationValue));
        }

        for (String raw : new String[] {
                "t\u00e9st@example.com",
                "user@b\u00fccher.de",
                "user@fa\u00df.de",
                "\u039f\u03a3@example.com",
                "\u03bf\u03c3@example.com",
                "\uff34\uff25\uff33\uff34@example.com",
                "\u3000user@example.com\u3000",
                "\ufdfa@a.co",
                "\ufb03".repeat(34) + "@a.co"
        }) {
            String canonical = V4__Canonical_email_identity.canonicalizeV4(raw);
            assertFalse(V4__Canonical_email_identity.isSafeCanonicalEmail(canonical), canonical);
        }

        String longEmail = "a".repeat(64) + "@" + "b".repeat(63) + "."
                + "c".repeat(63) + "." + "d".repeat(61);
        assertEquals(254, longEmail.length());
        assertTrue(V4__Canonical_email_identity.isSafeCanonicalEmail(longEmail, 254));
        assertFalse(V4__Canonical_email_identity.isSafeCanonicalEmail(longEmail, 100));
    }

    @Test
    void quotesDiscoveredDatabaseIndexNames() {
        assertEquals("`uk_users_email`",
                V4__Canonical_email_identity.quoteIdentifier("uk_users_email"));
        assertEquals("`unexpected``name`",
                V4__Canonical_email_identity.quoteIdentifier("unexpected`name"));
    }
}
