package com.ojt.board.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class AuthRequest {

    private AuthRequest() {
    }

    public record Signup(
            @NotBlank @Email @Size(max = 100) String email,
            @NotBlank @Size(min = 2, max = 100) String nickname,
            @Schema(description = "4~72자. 공백 없이 영문 대소문자, 숫자, ASCII 특수문자만 허용합니다. 문자 종류별 필수 조합은 없습니다.",
                    example = "password123")
            @NotBlank @Size(min = 4, max = 72) @PasswordByteLength
            @Pattern(regexp = "[\\x21-\\x7E]+", message = "비밀번호는 공백 없이 영문, 숫자, ASCII 특수문자만 사용할 수 있습니다.")
            String password
    ) {
    }

    public record Login(
            @NotBlank @Email @Size(max = 100) String email,
            @Schema(description = "1~72자. 공백 없이 영문 대소문자, 숫자, ASCII 특수문자만 허용합니다.", example = "password123")
            @NotBlank @PasswordByteLength
            @Pattern(regexp = "[\\x21-\\x7E]+", message = "비밀번호는 공백 없이 영문, 숫자, ASCII 특수문자만 사용할 수 있습니다.")
            String password
    ) {
    }
}
