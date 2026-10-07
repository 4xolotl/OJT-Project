package com.ojt.board.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class EmailCanonicalizerTest {

    @Test
    void canonicalizesAsciiWhitespaceAndCaseWithoutRewritingUnicodeIdentity() {
        assertEquals("user+tag@xn--bcher-kva.de",
                EmailCanonicalizer.canonicalize("  USER+TAG@XN--BCHER-KVA.DE  "));
        assertTrue(EmailCanonicalizer.isValidCanonical("user+tag@xn--bcher-kva.de", 100));
        assertFalse(EmailCanonicalizer.isValidCanonical("(1)@a.co", 100));
        assertFalse(EmailCanonicalizer.isValidCanonical("t\u00e9st@example.com", 100));
        assertFalse(EmailCanonicalizer.isValidCanonical("user@b\u00fccher.de", 100));
        assertFalse(EmailCanonicalizer.isValidCanonical("user@fa\u00df.de", 100));
        assertFalse(EmailCanonicalizer.isValidCanonical("\u039f\u03a3@example.com", 100));
        assertFalse(EmailCanonicalizer.isValidCanonical("\u03bf\u03c3@example.com", 100));
        assertFalse(EmailCanonicalizer.isValidCanonical("\u3000user@example.com\u3000", 100));
        assertEquals("user@fa\u00df.de", EmailCanonicalizer.canonicalize("user@fa\u00df.de"));
        assertEquals("T\u00c9ST@EXAMPLE.COM",
                EmailCanonicalizer.canonicalize("T\u00c9ST@EXAMPLE.COM"));
        assertEquals("", EmailCanonicalizer.canonicalize(null));
    }

    @Test
    void passwordAccountsUseTheLoginEmailLengthLimit() {
        String longEmail = "a".repeat(64) + "@" + "b".repeat(36) + ".co";
        assertEquals(104, longEmail.length());
        assertThrows(IllegalArgumentException.class,
                () -> new User(longEmail, "tester", "encoded-password"));
    }
}
