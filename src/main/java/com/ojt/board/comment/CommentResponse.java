package com.ojt.board.comment;

import java.time.Instant;

import com.ojt.board.global.AuthorResponse;

public record CommentResponse(
        Long id,
        Long postId,
        String content,
        AuthorResponse author,
        Instant createdAt,
        Instant updatedAt
) {
    public static CommentResponse from(Comment comment) {
        return new CommentResponse(
                comment.getId(),
                comment.getPost().getId(),
                comment.getContent(),
                AuthorResponse.from(comment.getAuthor()),
                comment.getCreatedAt(),
                comment.getUpdatedAt()
        );
    }
}
