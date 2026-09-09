package com.ojt.board.user;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.test.util.ReflectionTestUtils;

class UserTest {

    @Test
    void newPasswordAndGoogleAccountsHaveOnlyUserAuthority() {
        for (User user : new User[] {
                new User("password@example.com", "일반 회원", "encoded-password"),
                new User("google@example.com", "Google 회원", null)}) {
            assertEquals(UserRole.USER, user.getRole());
            assertEquals(Set.of("ROLE_USER"), authorities(user));
        }
    }

    @Test
    void administratorRetainsOrdinaryBoardAuthority() {
        User user = new User("admin@example.com", "관리자", "encoded-password");
        ReflectionTestUtils.setField(user, "role", UserRole.ADMIN);

        assertEquals(Set.of("ROLE_USER", "ROLE_ADMIN"), authorities(user));
    }

    private Set<String> authorities(User user) {
        return user.getAuthorities().stream().map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
    }
}
