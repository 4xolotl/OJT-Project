package com.ojt.board.auth;

public record AuthResponse(Long id, String email, String nickname) {

    public static AuthResponse from(BoardPrincipal user) {
        return new AuthResponse(user.getId(), user.getEmail(), user.getNickname());
    }
}
