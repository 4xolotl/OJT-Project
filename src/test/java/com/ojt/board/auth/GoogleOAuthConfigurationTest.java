package com.ojt.board.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.ojt.board.auth.oauth.GoogleOAuthConfiguration;
import com.ojt.board.auth.oauth.GoogleOAuthProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;

class GoogleOAuthConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(GoogleOAuthConfiguration.class);

    @Test
    void disabledConfigurationNeedsNoKeysOrProviderNetwork() {
        runner.withPropertyValues("app.oauth.google.enabled=false").run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(ClientRegistrationRepository.class);
            assertThat(context.getBean(GoogleOAuthProperties.class).enabled()).isFalse();
        });
    }

    @Test
    void enabledConfigurationRejectsMissingCredentialsWithoutLoggingTheirValues() {
        runner.withPropertyValues("app.oauth.google.enabled=true", "app.oauth.google.client-id=dummy-client")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseMessage(
                            "Google 로그인을 켜려면 GOOGLE_CLIENT_ID와 GOOGLE_CLIENT_SECRET을 설정해야 합니다.");
                });
    }

    @Test
    void rejectsExternalPlainHttpCallbackAndMasksPropertiesString() {
        runner.withPropertyValues("app.oauth.google.enabled=true", "app.oauth.google.client-id=dummy-client",
                        "app.oauth.google.client-secret=dummy-secret",
                        "app.oauth.google.redirect-uri=http://example.com/login/oauth2/code/google")
                .run(context -> assertThat(context).hasFailed());
        assertThat(new GoogleOAuthProperties(true, "dummy-client", "dummy-secret", "http://localhost").toString())
                .doesNotContain("dummy-client", "dummy-secret");
    }
}
