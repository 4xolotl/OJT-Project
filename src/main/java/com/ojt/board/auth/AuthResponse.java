package com.ojt.board.auth;

import com.ojt.board.user.User;

public record AuthResponse(Long id, String email, String nickname) {

    public static AuthResponse from(User user) {
        return new AuthResponse(user.getId(), user.getEmail(), user.getNickname());
    }
}
