package com.italo.bankingapi.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "server.address=127.0.0.1",
    "spring.datasource.hikari.connection-init-sql=SET search_path TO banking_api_input_tests",
    "spring.flyway.schemas=banking_api_input_tests",
    "spring.flyway.default-schema=banking_api_input_tests",
    "spring.jpa.properties.hibernate.default_schema=banking_api_input_tests"
})
@ActiveProfiles("test")
@DirtiesContext
class InputBoundariesHttpTest {
    @LocalServerPort int port;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    private final HttpClient client = HttpClient.newHttpClient();
    private String token;
    private String customerId;

    @BeforeEach @AfterEach
    void clean() {
        assertEquals("banking_api_input_tests", jdbc.queryForObject("SELECT current_schema()", String.class));
        jdbc.execute("TRUNCATE banking_api_input_tests.transactions, banking_api_input_tests.accounts, banking_api_input_tests.addresses, banking_api_input_tests.customers, banking_api_input_tests.financial_idempotency");
    }

    @ParameterizedTest
    @ValueSource(strings = {"fullName", "email", "phone"})
    void rejectsNullCharacterInRegistrationAndUpdate(String field) throws Exception {
        var body = registration();
        body.put(field, "bad\0value");
        assertBadRequest(send("POST", "/customers", body), "/customers");
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM customers", Integer.class));
        registerAndLogin();
        assertBadRequest(send("PUT", "/customers/" + customerId, body), "/customers/" + customerId);
        assertEquals("Input fixture", jdbc.queryForObject("SELECT full_name FROM customers", String.class));
        assertEquals("input@test.com", jdbc.queryForObject("SELECT email FROM customers", String.class));
        assertEquals("81999999999", jdbc.queryForObject("SELECT phone FROM customers", String.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"-10000-01-01", "0000-01-01", "+10000-01-01", "2020-02-30"})
    void rejectsUnsupportedBirthDatesWithoutWrites(String date) throws Exception {
        var body = registration();
        body.put("birthDate", date);
        assertBadRequest(send("POST", "/customers", body), "/customers");
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM customers", Integer.class));
        registerAndLogin();
        assertBadRequest(send("PUT", "/customers/" + customerId, body), "/customers/" + customerId);
        assertEquals("2000-01-01", jdbc.queryForObject("SELECT birth_date::text FROM customers", String.class));
    }

    @Test
    void acceptsMinimumBirthDateAndPreservesUnicodeText() throws Exception {
        var body = registration();
        String name = "Jos\u00e9 \uD83D\uDE00\nSilva";
        body.put("fullName", name);
        body.put("birthDate", "0001-01-01");
        var response = send("POST", "/customers", body);
        assertEquals(201, response.statusCode(), response.body());
        assertEquals(name, jdbc.queryForObject("SELECT full_name FROM customers", String.class));
        assertEquals("0001-01-01", jdbc.queryForObject("SELECT birth_date::text FROM customers", String.class));
    }

    @Test
    void rejectsNullInLoginEmail() throws Exception {
        assertBadRequest(send("POST", "/auth/login", Map.of("email", "bad\0@test.com", "password", "test-password")), "/auth/login");
    }

    @Test
    void validatesStatementDateRange() throws Exception {
        registerAndLogin();
        String accountId = createAccount();
        String path = "/accounts/" + accountId + "/transactions";
        for (String field : List.of("startDate", "endDate")) {
            for (String date : List.of("-10000-01-01", "0000-01-01", "%2B10000-01-01")) {
                assertBadRequest(send("GET", path + "?" + field + "=" + date, null), path);
            }
        }
        assertEquals(200, send("GET", path + "?startDate=0001-01-01&endDate=9999-12-31", null).statusCode());
    }

    @Test
    void rejectsNullReversalReasonWithoutFinancialChanges() throws Exception {
        registerAndLogin();
        jdbc.update("UPDATE customers SET role='ADMIN' WHERE id=?::uuid", customerId);
        String accountId = createAccount();
        assertEquals(200, send("POST", "/accounts/" + accountId + "/deposit", Map.of("amount", 10)).statusCode());
        String transactionId = jdbc.queryForObject("SELECT id::text FROM transactions", String.class);
        String path = "/admin/transactions/" + transactionId + "/reversal";
        assertBadRequest(send("POST", path, Map.of("reason", "Invalid\0reason")), path);
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM transactions", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT balance FROM accounts", java.math.BigDecimal.class).compareTo(java.math.BigDecimal.TEN));
        String reason = "Corre\u00e7\u00e3o \uD83D\uDE00\nconfirmada";
        var accepted = send("POST", path, Map.of("reason", reason));
        assertEquals(201, accepted.statusCode(), accepted.body());
        assertEquals(reason, jdbc.queryForObject("SELECT reversal_reason FROM transactions WHERE original_transaction_id IS NOT NULL", String.class));
    }

    private Map<String, Object> registration() {
        return new HashMap<>(Map.of("fullName", "Input fixture", "cpf", "12345678901", "email", "input@test.com",
                "password", "test-password", "phone", "81999999999", "birthDate", "2000-01-01"));
    }

    private void registerAndLogin() throws Exception {
        var created = send("POST", "/customers", registration());
        assertEquals(201, created.statusCode(), created.body());
        customerId = mapper.readTree(created.body()).path("id").asText();
        var login = send("POST", "/auth/login", Map.of("email", "input@test.com", "password", "test-password"));
        assertEquals(200, login.statusCode());
        token = mapper.readTree(login.body()).path("token").asText();
    }

    private String createAccount() throws Exception {
        var response = send("POST", "/accounts", Map.of("customerId", customerId));
        assertEquals(201, response.statusCode(), response.body());
        return mapper.readTree(response.body()).path("id").asText();
    }

    private void assertBadRequest(HttpResponse<String> response, String path) throws Exception {
        assertEquals(400, response.statusCode(), response.body());
        var error = mapper.readTree(response.body());
        assertEquals(400, error.path("status").asInt());
        assertEquals("Bad Request", error.path("error").asText());
        assertEquals(path, error.path("path").asText());
        assertFalse(error.path("message").asText().isBlank());
        assertFalse(error.has("trace"));
    }

    private HttpResponse<String> send(String method, String path, Object body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json");
        if (token != null) request.header("Authorization", "Bearer " + token);
        return client.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
    }
}
