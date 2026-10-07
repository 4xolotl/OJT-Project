package com.ojt.board.auth;

import com.ojt.board.user.EmailCanonicalizer;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class AuthRequest {

    private static final int LOCAL_EMAIL_MAX_LENGTH = 100;

    private AuthRequest() {
    }

    public record Signup(
            @NotBlank @Email @Size(max = 100) @CanonicalEmail(max = 100) String email,
            @NotBlank @Size(min = 2, max = 100) String nickname,
            @Schema(description = "15~72자. 출력 가능한 ASCII 문자와 공백을 허용하며 문자 종류별 필수 조합은 없습니다. "
                    + "널리 사용되거나 쉽게 추측할 수 있는 값은 거부합니다.",
                    example = "violet river 829")
            @NotBlank(message = "비밀번호를 입력해 주세요.")
            @Size(min = SignupPasswordPolicy.MIN_LENGTH, max = SignupPasswordPolicy.MAX_LENGTH,
                    message = "비밀번호는 15~72자로 입력해 주세요.")
            @PasswordByteLength
            @Pattern(regexp = "[\\x20-\\x7E]+", message = "비밀번호는 출력 가능한 ASCII 문자와 공백만 사용할 수 있습니다.")
            String password
    ) {
        public Signup {
            email = canonicalizeEmailWithinLimit(email);
        }
    }

    public record Login(
            @NotBlank @Email @Size(max = 100) @CanonicalEmail(max = 100) String email,
            @Schema(description = "1~72자. 출력 가능한 ASCII 문자와 공백을 허용합니다.", example = "violet river 829")
            @NotBlank(message = "비밀번호를 입력해 주세요.") @PasswordByteLength
            @Pattern(regexp = "[\\x20-\\x7E]+", message = "비밀번호는 출력 가능한 ASCII 문자와 공백만 사용할 수 있습니다.")
            String password
    ) {
        public Login {
            email = canonicalizeEmailWithinLimit(email);
        }
    }

    private static String canonicalizeEmailWithinLimit(String email) {
        if (email == null || email.length() > LOCAL_EMAIL_MAX_LENGTH) {
            return email;
        }
        return EmailCanonicalizer.canonicalize(email);
    }
}
