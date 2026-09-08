package com.ojt.board.auth.oauth;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@Tag(name = "인증")
public class AuthProviderController {
    private final GoogleOAuthProperties properties;

    @GetMapping("/api/auth/providers")
    @Operation(summary = "소셜 로그인 사용 가능 여부", description = "Google 연결 활성화 여부와 로그인 시작 경로만 제공합니다. 클라이언트 ID와 비밀키는 응답하지 않습니다.")
    public ProviderResponse providers() {
        return new ProviderResponse(new Provider(properties.enabled(), "/oauth2/authorization/google"));
    }

    public record ProviderResponse(Provider google) { }
    public record Provider(boolean enabled, String authorizationUrl) { }
}
