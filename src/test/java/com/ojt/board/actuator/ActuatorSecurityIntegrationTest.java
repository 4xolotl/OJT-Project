package com.ojt.board.actuator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.context.ShutdownEndpoint;
import org.springframework.boot.actuate.context.properties.ConfigurationPropertiesReportEndpoint;
import org.springframework.boot.actuate.endpoint.jmx.JmxEndpointsSupplier;
import org.springframework.boot.actuate.endpoint.web.WebEndpointsSupplier;
import org.springframework.boot.actuate.endpoint.web.servlet.WebMvcEndpointHandlerMapping;
import org.springframework.boot.actuate.env.EnvironmentEndpoint;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthContributorRegistry;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.info.InfoEndpoint;
import org.springframework.boot.actuate.logging.LoggersEndpoint;
import org.springframework.boot.actuate.management.HeapDumpWebEndpoint;
import org.springframework.boot.actuate.metrics.MetricsEndpoint;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:h2:mem:actuator-security;MODE=MariaDB;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ActuatorSecurityIntegrationTest {

    private static final List<String> PRIVATE_PATHS = List.of(
            "/actuator", "/actuator/",
            "/actuator/env", "/actuator/configprops", "/actuator/heapdump",
            "/actuator/loggers", "/actuator/shutdown");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private HealthContributorRegistry healthContributors;

    @Autowired
    private WebEndpointsSupplier webEndpoints;

    @Autowired
    private ObjectProvider<JmxEndpointsSupplier> jmxEndpoints;

    @Autowired
    private WebMvcEndpointHandlerMapping endpointMappings;

    @Test
    void anonymousHealthReturnsComponentDetailsWithoutCreatingSession() throws Exception {
        ResponseEntity<JsonNode> response = restTemplate.getForEntity("/actuator/health", JsonNode.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("UP", response.getBody().path("status").asText());
        assertEquals("H2", response.getBody().path("components").path("db").path("details").path("database").asText());
        assertTrue(response.getBody().path("components").path("diskSpace").path("details").has("path"));
        assertFalse(response.getHeaders().getOrEmpty(HttpHeaders.SET_COOKIE).stream()
                .anyMatch(cookie -> cookie.startsWith("JSESSIONID=")));
        MvcResult result = mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk()).andReturn();
        assertNull(result.getRequest().getSession(false));
    }

    @Test
    @WithMockUser(roles = "USER")
    void boardUserReceivesComponentDetails() throws Exception {
        MvcResult response = mockMvc.perform(get("/actuator/health")
                        .param("showDetails", "always").param("showComponents", "always"))
                .andExpect(status().isOk()).andReturn();

        assertStatusAndComponents(response, "UP");
    }

    @Test
    void anonymousHealthSupportsHeadWithoutResponseBody() {
        ResponseEntity<String> response = restTemplate.exchange(
                "/actuator/health", HttpMethod.HEAD, HttpEntity.EMPTY, String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNull(response.getBody());
        assertFalse(response.getHeaders().getOrEmpty(HttpHeaders.SET_COOKIE).stream()
                .anyMatch(cookie -> cookie.startsWith("JSESSIONID=")));
    }

    @Test
    void anonymousRequestsCannotReadDiscoveryComponentsOrOtherEndpoints() throws Exception {
        for (String path : PRIVATE_PATHS) {
            mockMvc.perform(get(path))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.message").isNotEmpty())
                    .andExpect(jsonPath("$._links").doesNotExist());
        }
    }

    @Test
    @WithMockUser(roles = "USER")
    void boardUserCannotReadDiscoveryComponentsOrOtherEndpoints() throws Exception {
        for (String path : PRIVATE_PATHS) {
            mockMvc.perform(get(path))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.message").isNotEmpty())
                    .andExpect(jsonPath("$._links").doesNotExist());
        }
    }

    @Test
    @WithMockUser(roles = "USER")
    void validCsrfAndBoardLoginCannotMutateAnyManagementEndpoint() throws Exception {
        for (HttpMethod method : List.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE)) {
            for (String path : List.of("/actuator/health", "/actuator/loggers", "/actuator/shutdown")) {
                mockMvc.perform(request(method, path).with(csrf()))
                        .andExpect(status().isForbidden());
            }
        }
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void healthWriteRequiresCsrfAndStillRejectsAnonymousRequestWithValidToken() throws Exception {
        mockMvc.perform(post("/actuator/health")).andExpect(status().isForbidden());
        mockMvc.perform(post("/actuator/health").with(csrf())).andExpect(status().isUnauthorized());
    }

    @Test
    void configuredReadEndpointsExistAndOtherEndpointsRemainUnavailable() {
        assertEquals(1, context.getBeansOfType(HealthEndpoint.class).size());
        for (Class<?> endpointType : List.of(EnvironmentEndpoint.class, ConfigurationPropertiesReportEndpoint.class,
                HeapDumpWebEndpoint.class, LoggersEndpoint.class, ShutdownEndpoint.class)) {
            assertTrue(context.getBeansOfType(endpointType).isEmpty(), endpointType.getSimpleName());
        }
        assertEquals(1, context.getBeansOfType(MetricsEndpoint.class).size());
        assertEquals(1, context.getBeansOfType(InfoEndpoint.class).size());
        assertEquals(List.of("health", "info", "mappings", "metrics"), webEndpoints.getEndpoints().stream()
                .map(endpoint -> endpoint.getEndpointId().toString()).sorted().toList());
        assertTrue(jmxEndpoints.stream().allMatch(supplier -> supplier.getEndpoints().isEmpty()));
        assertFalse(endpointMappings.getHandlerMethods().keySet().stream()
                .flatMap(mapping -> mapping.getPatternValues().stream())
                .anyMatch(path -> path.equals("/actuator") || path.equals("/actuator/")),
                "The discovery handler must be absent, independently of the security filter");
    }

    @Test
    void healthIncludesAutomaticDatabaseAndDiskSpaceChecksInternally() {
        assertNotNull(healthContributors.getContributor("db"));
        assertNotNull(healthContributors.getContributor("diskSpace"));
        assertEquals("UP", context.getBean(HealthEndpoint.class).health().getStatus().getCode());
    }

    @Test
    void downIndicatorReturns503WithDetailsToAnonymousAndBoardUser() throws Exception {
        String contributorName = "actuatorTestFailure";
        assertNull(healthContributors.getContributor(contributorName));
        healthContributors.registerContributor(contributorName, (HealthIndicator) () -> Health.down()
                .withDetail("database", "test-only database detail")
                .withDetail("path", "test-only filesystem path")
                .withDetail("error", "test-only diagnostic detail")
                .build());
        try {
            MvcResult anonymous = mockMvc.perform(get("/actuator/health"))
                    .andExpect(status().isServiceUnavailable()).andReturn();
            assertStatusAndComponents(anonymous, "DOWN");
            MvcResult authenticated = mockMvc.perform(get("/actuator/health").with(user("board-user").roles("USER")))
                    .andExpect(status().isServiceUnavailable()).andReturn();
            assertStatusAndComponents(authenticated, "DOWN");
        } finally {
            healthContributors.unregisterContributor(contributorName);
        }
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void anonymousReadRequestsSupportConfiguredManagementEndpoints() throws Exception {
        for (String path : List.of("/actuator/health/db", "/actuator/health/diskSpace",
                "/actuator/info", "/actuator/metrics", "/actuator/metrics/jvm.memory.used", "/actuator/mappings")) {
            mockMvc.perform(get(path)).andExpect(status().isOk());
        }
        mockMvc.perform(get("/actuator/mappings"))
                .andExpect(jsonPath("$.contexts").isNotEmpty());
    }

    private void assertStatusAndComponents(MvcResult response, String expectedStatus) throws Exception {
        JsonNode body = objectMapper.readTree(response.getResponse().getContentAsByteArray());
        assertEquals(expectedStatus, body.path("status").asText());
        assertTrue(body.path("components").has("db"));
        assertTrue(body.path("components").has("diskSpace"));
        if (expectedStatus.equals("DOWN")) {
            assertEquals("test-only diagnostic detail", body.path("components").path("actuatorTestFailure")
                    .path("details").path("error").asText());
        }
    }
}
