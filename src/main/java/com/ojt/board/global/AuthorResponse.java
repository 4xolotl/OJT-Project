package com.ojt.board.global;

import com.ojt.board.user.User;

public record AuthorResponse(Long id, String nickname) {

    public static AuthorResponse from(User user) {
        return new AuthorResponse(user.getId(), user.getNickname());
    }
}
