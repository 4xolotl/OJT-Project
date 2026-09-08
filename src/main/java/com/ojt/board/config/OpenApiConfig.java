package com.ojt.board.config;

import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI boardOpenApi() {
        return new OpenAPI().info(new Info()
                .title("OJT 게시판 API")
                .version("v1")
                .description("세션 쿠키(JSESSIONID)로 인증합니다. 먼저 GET /api/auth/csrf로 CSRF 토큰을 발급받은 뒤 "
                        + "회원가입과 로그인을 진행하세요. 상태를 변경하는 요청에는 XSRF-TOKEN 쿠키와 같은 값을 "
                        + "X-XSRF-TOKEN 헤더로 전송해야 합니다. Swagger UI는 발급된 쿠키에서 이 헤더를 설정합니다. "
                        + "로그인 및 로그아웃 후에는 GET /api/auth/csrf를 다시 호출하세요."));
    }

    @Bean
    public OpenApiCustomizer logoutOpenApiCustomizer() {
        // 로그아웃은 MVC 컨트롤러 대신 Spring Security 필터가 처리하므로 직접 문서화한다.
        return openApi -> openApi.path("/api/auth/logout", new PathItem().post(new Operation()
                .operationId("logout")
                .addTagsItem("인증")
                .summary("로그아웃")
                .description("현재 세션을 무효화하고 세션 및 CSRF 쿠키를 삭제합니다. "
                        + "XSRF-TOKEN 쿠키와 일치하는 X-XSRF-TOKEN 헤더가 필요합니다. "
                        + "로그아웃 후 다시 요청을 전송하려면 GET /api/auth/csrf에서 새 토큰을 발급받으세요.")
                .responses(new ApiResponses()
                        .addApiResponse("204", new ApiResponse().description("로그아웃 완료. 응답 본문 없음."))
                        .addApiResponse("403", new ApiResponse().description("CSRF 토큰 누락 또는 불일치.")))));
    }
}
