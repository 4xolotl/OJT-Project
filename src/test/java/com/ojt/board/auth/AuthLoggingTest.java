package com.ojt.board.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = {
        "logging.level.root=DEBUG",
        "spring.datasource.url=jdbc:h2:mem:auth-logging;MODE=MariaDB;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class AuthLoggingTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @ParameterizedTest
    @ValueSource(strings = {"signup", "login"})
    void invalidPasswordDoesNotReachLogsWhenRootDebugIsEnabled(String operation, CapturedOutput output)
            throws Exception {
        assertTrue(LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME).isDebugEnabled());
        for (String loggerName : List.of("org.springframework.web",
                "org.springframework.web.servlet.mvc.method.annotation.RequestResponseBodyMethodProcessor",
                "org.springframework.web.servlet.mvc.method.annotation.ExceptionHandlerExceptionResolver")) {
            Logger logger = LoggerFactory.getLogger(loggerName);
            assertTrue(logger.isInfoEnabled());
            assertFalse(logger.isDebugEnabled(), "Sensitive web loggers must not inherit root DEBUG");
            assertFalse(logger.isTraceEnabled(), "Sensitive web loggers must not enable TRACE");
        }

        MvcResult csrfResult = mockMvc.perform(get("/api/auth/csrf"))
                .andExpect(status().isOk()).andReturn();
        JsonNode csrf = objectMapper.readTree(csrfResult.getResponse().getContentAsByteArray());
        Cookie csrfCookie = csrfResult.getResponse().getCookie("XSRF-TOKEN");
        assertNotNull(csrfCookie);

        // Generate the marker only after checking log levels; never print it in assertion messages.
        String marker = "private-" + UUID.randomUUID();
        String invalidPassword = marker + " ";
        Object request = operation.equals("signup")
                ? new AuthRequest.Signup("logging@example.com", "tester", invalidPassword)
                : new AuthRequest.Login("logging@example.com", invalidPassword);
        MvcResult result = mockMvc.perform(post("/api/auth/" + operation)
                        .cookie(csrfCookie)
                        .header(csrf.path("headerName").asText(), csrf.path("token").asText())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andReturn();

        String logs = output.getAll();
        assertFalse(logs.contains(marker), "Password markers must not appear in captured logs");
        assertFalse(logs.contains("Signup[email="), "Signup request records must not appear in logs");
        assertFalse(logs.contains("Login[email="), "Login request records must not appear in logs");
        assertFalse(logs.contains("rejected value ["), "Rejected request values must not appear in logs");
        assertFalse(result.getResponse().getContentAsString().contains(marker),
                "Password markers must not appear in validation responses");
    }
}
