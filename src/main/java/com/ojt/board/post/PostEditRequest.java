package com.ojt.board.post;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import org.hibernate.validator.constraints.UniqueElements;

public record PostEditRequest(
        @NotBlank @Size(max = 200) String title,
        @NotBlank @Size(max = 10000)
        @Pattern(
                regexp = "(?is)^(?!.*<\\s*/?\\s*script\\b).*$",
                message = "게시글 본문에는 script 태그를 사용할 수 없습니다."
        )
        String content,
        @NotNull Instant updatedAt,
        @NotNull @Size(max = 1000) @UniqueElements List<@NotNull @Positive Long> attachmentIds,
        @NotNull @Size(max = 1000) @UniqueElements List<@NotNull @Positive Long> deletedFileIds) {
}
