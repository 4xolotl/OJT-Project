package com.ojt.board.post;

import java.time.Instant;

import com.ojt.board.global.AuthorResponse;

public record PostResponse(Long id, String title, String content, AuthorResponse author,
                           Instant createdAt, Instant updatedAt) {

    public static PostResponse from(Post post) {
        return new PostResponse(post.getId(), post.getTitle(), post.getContent(),
                AuthorResponse.from(post.getAuthor()), post.getCreatedAt(), post.getUpdatedAt());
    }
}
