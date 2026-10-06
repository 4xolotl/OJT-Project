package com.ojt.board.auth;

import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ojt.board.user.User;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "인증")
public class AuthController {

    private final AuthService authService;
    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContextRepository;
    private final CompositeSessionAuthenticationStrategy sessionAuthenticationStrategy;
    private final CsrfTokenRateLimiter csrfTokenRateLimiter;

    @GetMapping("/csrf")
    @Operation(summary = "CSRF 토큰 발급", description = "서버 세션을 만들지 않고 쿠키 기반 CSRF 토큰을 발급합니다. "
            + "로그인·로그아웃 후 다시 조회할 수 있습니다. 기본 설정은 출발지 주소별 1분에 20회입니다.")
    @ApiResponse(responseCode = "429", description = "CSRF 토큰 요청 제한 초과",
            headers = @Header(name = HttpHeaders.RETRY_AFTER,
                    description = "다시 요청할 때까지 남은 초",
                    schema = @Schema(type = "integer", format = "int64", minimum = "1")))
    public ResponseEntity<?> csrf(HttpServletRequest request) {
        CsrfTokenRateLimiter.Decision decision = csrfTokenRateLimiter.acquire(request.getRemoteAddr());
        if (!decision.permitted()) {
            if (decision.shouldLog()) {
                log.warn("CSRF token issuance rate limit exceeded: path=/api/auth/csrf source={}",
                        request.getRemoteAddr());
            }
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header(HttpHeaders.RETRY_AFTER, Long.toString(decision.retryAfterSeconds()))
                    .body(Map.of("message", "CSRF 토큰 요청이 너무 많습니다. 잠시 후 다시 시도해 주세요."));
        }

        CsrfToken csrfToken = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        return ResponseEntity.ok(csrfToken);
    }

    @PostMapping("/signup")
    @Operation(summary = "회원가입", description = "회원가입 후 로그인은 별도로 수행합니다.")
    @ApiResponse(responseCode = "201", description = "회원가입 완료")
    public ResponseEntity<AuthResponse> signup(@Valid @RequestBody AuthRequest.Signup request) {
        User user = authService.signup(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(AuthResponse.from(user));
    }

    @PostMapping("/login")
    @Operation(summary = "로그인", description = "인증 성공 시 JSESSIONID 쿠키로 인증을 유지합니다.")
    public AuthResponse login(
            @Valid @RequestBody AuthRequest.Login request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse
    ) {
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.email(), request.password())
        );

        sessionAuthenticationStrategy.onAuthentication(authentication, httpRequest, httpResponse);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, httpRequest, httpResponse);

        return AuthResponse.from((BoardPrincipal) authentication.getPrincipal());
    }

    @GetMapping("/me")
    @Operation(summary = "로그인 사용자 조회")
    public AuthResponse me(Authentication authentication) {
        return AuthResponse.from((BoardPrincipal) authentication.getPrincipal());
    }
}
