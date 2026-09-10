package com.ojt.board.post;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record PostRequest(@NotBlank @Size(max = 200) String title,
                          @NotBlank @Size(max = 10000)
                          @Pattern(
                                  regexp = "(?is)^(?!.*<\\s*/?\\s*script\\b).*$",
                                  message = "게시글 본문에는 script 태그를 사용할 수 없습니다."
                          )
                          String content) {
}
