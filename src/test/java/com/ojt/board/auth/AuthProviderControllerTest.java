package com.ojt.board.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "app.oauth.google.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:oauth-disabled;MODE=MariaDB;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
class AuthProviderControllerTest {
    @Autowired private MockMvc mockMvc;

    @Test
    void configurationStatusIsPublicWithoutClientCredentials() throws Exception {
        mockMvc.perform(get("/api/auth/providers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.google.enabled").value(false))
                .andExpect(jsonPath("$.google.authorizationUrl").value("/oauth2/authorization/google"))
                .andExpect(jsonPath("$.google.clientId").doesNotExist())
                .andExpect(jsonPath("$.google.clientSecret").doesNotExist());
    }

    @Test
    void unconfiguredGoogleRoutesDoNotStartOrAcceptLogin() throws Exception {
        mockMvc.perform(get("/oauth2/authorization/google")).andExpect(status().isNotFound());
        mockMvc.perform(get("/login/oauth2/code/google?code=unused&state=unused")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
    }
}
