package com.ojt.board.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.ojt.board.auth.oauth.BoardReturnUrl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class BoardReturnUrlTest {
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "https://example.com", "//example.com", "/\\example.com", "/%5cexample.com",
            "/login.html", "/api/posts", "/post.html", "/post.html?id=-1", "/post.html?id=01",
            "/post.html?id=9223372036854775808", "/write.html?keyword=%0d%0aLocation:evil", "/write.html?keyword=%XX",
            "/edit.html", "/edit.html?id=0", "/edit.html?id=-1", "/edit.html?id=01", "/edit.html?id=1.5",
            "/edit.html?id=1e2", "/edit.html?id=9223372036854775808", "/edit.html?id=99999999999999999999",
            "/edit.html?id=2&keyword=%0aInjected", "/edit.html?id=2&keyword=%", "/%65dit.html?id=2",
            "/unknown/../edit.html?id=2", "//example.com/edit.html?id=2", "/edit.html\\evil?id=2"})
    void rejectsUnsafeOrUnknownDestinations(String input) {
        assertEquals("/", BoardReturnUrl.sanitize(input));
    }

    @Test
    void preservesOnlyKnownWriteAndListParameters() {
        assertEquals("/write.html?keyword=hello+world&page=3&size=50", BoardReturnUrl.sanitize(
                "/write.html?keyword=hello%20world&page=3&size=50&id=2&preview=1&returnTo=https://example.com#comments"));
        assertEquals("/?keyword=test", BoardReturnUrl.sanitize("/index.html?keyword=test&page=0&size=20"));
        assertEquals("/write.html", BoardReturnUrl.sanitize("/write.html?page=2147483648&size=200"));
    }

    @Test
    void preservesValidLongIdAndOnlyCommentsFragment() {
        assertEquals("/post.html?id=9223372036854775807&keyword=%ED%95%9C%EA%B8%80#comments", BoardReturnUrl.sanitize(
                "/post.html?id=9223372036854775807&keyword=%ED%95%9C%EA%B8%80#comments"));
        assertEquals("/post.html?id=3", BoardReturnUrl.sanitize("/post.html?id=3#anything"));
    }

    @Test
    void preservesEditIdAndListContextButDropsPreviewAndFragments() {
        assertEquals("/edit.html?id=17&keyword=Spring+Boot&page=2&size=50", BoardReturnUrl.sanitize(
                "/edit.html?id=17&keyword=%20Spring%20Boot%20&page=2&size=50&preview=1&returnTo=https://example.com#comments"));
        assertEquals("/edit.html?id=9223372036854775807", BoardReturnUrl.sanitize(
                "/edit.html?id=9223372036854775807#anything"));
        assertEquals("/edit.html?id=3", BoardReturnUrl.sanitize("/edit.html?id=3&page=0&size=20"));
    }

    @Test
    void oauthFailureKeepsOnlyTheSafeEditDestination() {
        assertEquals("/login.html?oauthError=cancelled&returnTo=%2Fedit.html%3Fid%3D17%26keyword%3DSpring%26page%3D2%26size%3D10",
                BoardReturnUrl.loginFailure("cancelled", "/edit.html?id=17&keyword=Spring&page=2&size=10&preview=1#comments"));
        assertEquals("/login.html?oauthError=failed&returnTo=%2F",
                BoardReturnUrl.loginFailure("failed", "/edit.html?id=-1"));
    }
}
