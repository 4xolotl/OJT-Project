package com.ojt.board.auth.oauth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import com.ojt.board.user.User;
import com.ojt.board.user.UserRole;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.test.util.ReflectionTestUtils;

class BoardOidcUserTest {

    @Test
    void rolesAreAnImmutableSnapshotOfTheLocalAccountAtLogin() {
        User user = new User("admin@example.com", "관리자", null);
        ReflectionTestUtils.setField(user, "role", UserRole.ADMIN);
        BoardOidcUser principal = new BoardOidcUser(user, mock(OidcUser.class));

        ReflectionTestUtils.setField(user, "role", UserRole.USER);

        assertEquals(Set.of("ROLE_USER", "ROLE_ADMIN"), principal.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority).collect(Collectors.toSet()));
        assertThrows(UnsupportedOperationException.class, () -> principal.getAuthorities().clear());
    }
}
