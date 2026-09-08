package com.ojt.board.auth;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ojt.board.user.User;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Tag(name = "인증")
public class AuthController {

    private final AuthService authService;
    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContextRepository;
    private final SessionAuthenticationStrategy sessionAuthenticationStrategy;

    @GetMapping("/csrf")
    @Operation(summary = "CSRF 토큰 발급", description = "POST 요청 전에 호출합니다. 로그인·로그아웃 후 다시 발급받습니다.")
    public CsrfToken csrf(@Parameter(hidden = true) CsrfToken csrfToken) {
        return csrfToken;
    }

    @PostMapping("/signup")
    @Operation(summary = "회원가입", description = "회원가입 후 로그인은 별도로 수행합니다. CSRF 헤더가 필요합니다.")
    @ApiResponse(responseCode = "201", description = "회원가입 완료")
    public ResponseEntity<AuthResponse> signup(@Valid @RequestBody AuthRequest.Signup request) {
        User user = authService.signup(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(AuthResponse.from(user));
    }

    @PostMapping("/login")
    @Operation(summary = "로그인", description = "인증 성공 시 세션 ID를 교체하고 JSESSIONID 쿠키로 인증을 유지합니다. CSRF 헤더가 필요합니다.")
    public AuthResponse login(
            @Valid @RequestBody AuthRequest.Login request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse
    ) {
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.email(), request.password())
        );

        // A controller-based login must apply session protection explicitly.
        sessionAuthenticationStrategy.onAuthentication(authentication, httpRequest, httpResponse);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, httpRequest, httpResponse);

        return AuthResponse.from((User) authentication.getPrincipal());
    }

    @GetMapping("/me")
    @Operation(summary = "로그인 사용자 조회")
    public AuthResponse me(Authentication authentication) {
        return AuthResponse.from((User) authentication.getPrincipal());
    }
}
