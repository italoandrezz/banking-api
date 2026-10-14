package com.italo.bankingapi.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.address=127.0.0.1",
        "spring.datasource.hikari.connection-init-sql=SET search_path TO banking_api_password_tests",
        "spring.flyway.schemas=banking_api_password_tests",
        "spring.flyway.default-schema=banking_api_password_tests",
        "spring.jpa.properties.hibernate.default_schema=banking_api_password_tests"
})
@ActiveProfiles("test")
@DirtiesContext
class PasswordValidationHttpTest {
    @LocalServerPort int port;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    @AfterEach
    void clean() {
        assertEquals("banking_api_password_tests", jdbc.queryForObject("SELECT current_schema()", String.class));
        jdbc.execute("TRUNCATE banking_api_password_tests.transactions, banking_api_password_tests.accounts, "
                + "banking_api_password_tests.addresses, banking_api_password_tests.customers, banking_api_password_tests.financial_idempotency");
    }

    static Stream<String> invalidPasswords() {
        return Stream.of(null, "", "        ", "a", "a".repeat(7), "\uD83D\uDE00".repeat(7),
                "a".repeat(73), "\u00e9".repeat(36) + "a", "\uD83D\uDE00".repeat(18) + "a");
    }

    static Stream<String> validPasswords() {
        return Stream.of("a".repeat(8), "a".repeat(72), "\u00e9".repeat(36),
                "\uD83D\uDE00".repeat(18), "\uD83D\uDE00".repeat(8), " abcd123 ");
    }

    static Stream<String> oversizedPasswords() {
        return Stream.of("a".repeat(73), "\u00e9".repeat(36) + "a", "\uD83D\uDE00".repeat(18) + "a");
    }

    @ParameterizedTest
    @MethodSource("invalidPasswords")
    void rejectsInvalidRegistrationWithoutPersistingCustomer(String password) throws Exception {
        assertBadRequest(post("/customers", registration(password)), "/customers");
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM customers", Integer.class));
    }

    @ParameterizedTest
    @MethodSource("validPasswords")
    void acceptsBoundaryPasswordsAndAuthenticatesWithoutChangingThem(String password) throws Exception {
        var created = post("/customers", registration(password));
        assertEquals(201, created.statusCode(), created.body());
        assertFalse(mapper.readTree(created.body()).has("password"));
        String hash = jdbc.queryForObject("SELECT password FROM customers", String.class);
        assertNotEquals(password, hash);
        assertTrue(encoder.matches(password, hash));
        var login = post("/auth/login", credentials("password@test.com", password));
        assertEquals(200, login.statusCode(), login.body());
        assertFalse(mapper.readTree(login.body()).path("token").asText().isBlank());
        assertEquals(401, post("/auth/login", credentials("password@test.com", "wrong-password")).statusCode());
    }

    @ParameterizedTest
    @MethodSource("oversizedPasswords")
    void rejectsOversizedLoginForExistingAndUnknownCustomers(String password) throws Exception {
        assertEquals(201, post("/customers", registration("valid-password")).statusCode());
        String originalHash = jdbc.queryForObject("SELECT password FROM customers", String.class);
        assertBadRequest(post("/auth/login", credentials("password@test.com", password)), "/auth/login");
        assertBadRequest(post("/auth/login", credentials("unknown@test.com", password)), "/auth/login");
        assertEquals(originalHash, jdbc.queryForObject("SELECT password FROM customers", String.class));
    }

    @Test
    void preservesLoginForExistingShortPassword() throws Exception {
        assertEquals(201, post("/customers", registration("valid-password")).statusCode());
        // Model a customer registered before the new minimum was introduced.
        jdbc.update("UPDATE customers SET password=? WHERE email=?", encoder.encode("a"), "password@test.com");
        assertEquals(200, post("/auth/login", credentials("password@test.com", "a")).statusCode());
        assertEquals(401, post("/auth/login", credentials("password@test.com", "b")).statusCode());
        assertEquals(401, post("/auth/login", credentials("unknown@test.com", "a")).statusCode());
    }

    private void assertBadRequest(HttpResponse<String> response, String path) throws Exception {
        assertEquals(400, response.statusCode(), response.body());
        var error = mapper.readTree(response.body());
        assertEquals(400, error.path("status").asInt());
        assertEquals("Bad Request", error.path("error").asText());
        assertEquals(path, error.path("path").asText());
        assertTrue(error.path("message").asText().startsWith("Password"));
        assertFalse(error.has("trace"));
        assertFalse(error.has("password"));
    }

    private Map<String, Object> registration(String password) {
        var body = new HashMap<String, Object>(Map.of("fullName", "Password fixture", "cpf", "12345678901",
                "email", "password@test.com", "phone", "81999999999", "birthDate", "2000-01-01"));
        body.put("password", password);
        return body;
    }

    private Map<String, String> credentials(String email, String password) {
        return Map.of("email", email, "password", password);
    }

    private HttpResponse<String> post(String path, Object body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
