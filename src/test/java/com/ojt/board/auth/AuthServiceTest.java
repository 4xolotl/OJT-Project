package com.ojt.board.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ojt.board.user.User;
import com.ojt.board.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class AuthServiceTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final AuthService authService =
            new AuthService(userRepository, passwordEncoder, new SignupPasswordPolicy());

    @Test
    void rejectedPasswordNeverReachesTheRepositoryOrPasswordEncoder() {
        AuthRequest.Signup request =
                new AuthRequest.Signup("test@example.com", "tester", "password1234567");

        assertThrows(IllegalArgumentException.class, () -> authService.signup(request));

        verifyNoInteractions(userRepository, passwordEncoder);
    }

    @Test
    void acceptedPasswordIsEncodedWithoutNormalization() {
        String password = "  violet river 829  ";
        when(userRepository.existsByEmail("test@example.com")).thenReturn(false);
        when(passwordEncoder.encode(password)).thenReturn("encoded-password");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        User saved = authService.signup(new AuthRequest.Signup("test@example.com", "tester", password));

        assertEquals("encoded-password", saved.getPassword());
        verify(passwordEncoder).encode(password);
    }
}
