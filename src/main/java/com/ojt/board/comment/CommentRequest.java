package com.ojt.board.comment;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CommentRequest(
        @Schema(description = "댓글 내용 (1~2,000자)", example = "좋은 글 감사합니다.")
        @NotBlank @Size(max = 2000) String content
) {
}
