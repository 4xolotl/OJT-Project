package com.ojt.board.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class AuthRequest {

    private AuthRequest() {
    }

    public record Signup(
            @NotBlank @Email @Size(max = 100) String email,
            @NotBlank @Size(min = 2, max = 100) String nickname,
            @Schema(description = "8자 이상, UTF-8 기준 72바이트 이하", example = "password123")
            @NotBlank @Size(min = 8, max = 72) @PasswordByteLength String password
    ) {
    }

    public record Login(
            @NotBlank @Email @Size(max = 100) String email,
            @Schema(description = "UTF-8 기준 72바이트 이하", example = "password123")
            @NotBlank @PasswordByteLength String password
    ) {
    }
}
