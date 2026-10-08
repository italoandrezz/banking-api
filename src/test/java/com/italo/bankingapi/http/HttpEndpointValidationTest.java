package com.italo.bankingapi.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real TCP requests to embedded Tomcat; no MockMvc, mocks or test transaction. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.address=127.0.0.1",
        "spring.datasource.hikari.connection-init-sql=SET search_path TO banking_api_http_tests",
        "spring.flyway.schemas=banking_api_http_tests",
        "spring.flyway.default-schema=banking_api_http_tests",
        "spring.jpa.properties.hibernate.default_schema=banking_api_http_tests"
})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class HttpEndpointValidationTest {
    @LocalServerPort int port;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    static final List<String> results = new CopyOnWriteArrayList<>();
    static final Set<String> visited = ConcurrentHashMap.newKeySet();
    static final String PASSWORD = "http-test-password";
    User owner;
    User other;
    String account;
    String recipient;
    String scenario;

    @BeforeAll
    static void resetReport() { results.clear(); visited.clear(); }

    @BeforeEach
    void setUp(TestInfo info) throws Exception {
        scenario = info.getTestMethod().orElseThrow().getName();
        clean();
        owner = register("11111111111", "owner@http.test");
        other = register("22222222222", "other@http.test");
        account = createAccount(owner);
        recipient = createAccount(other);
    }

    @AfterEach
    void clean() {
        assertEquals("banking_api_http_tests", jdbc.queryForObject("SELECT current_schema()", String.class));
        jdbc.execute("""
                TRUNCATE TABLE banking_api_http_tests.financial_idempotency, banking_api_http_tests.transactions,
                banking_api_http_tests.accounts, banking_api_http_tests.addresses, banking_api_http_tests.customers
                """);
    }

    @Test @Order(1)
    void completeCustomerAndAccountLifecycle() throws Exception {
        JsonNode me = call("GET", "/auth/me", owner.token, null, 200);
        assertEquals(owner.id, me.path("customerId").asText());
        assertEquals("CUSTOMER", me.path("role").asText());
        JsonNode profile = call("GET", "/customers/" + owner.id, owner.token, null, 200);
        assertFalse(profile.has("password"));
        call("GET", "/customers", owner.token, null, 403);
        call("PUT", "/customers/" + owner.id, owner.token, Map.of("fullName", "Changed Name",
                "email", "changed@http.test", "phone", "81999999999", "birthDate", "2000-01-01", "role", "ADMIN"), 200);
        assertEquals("CUSTOMER", call("GET", "/auth/me", owner.token, null, 200).path("role").asText());
        assertEquals("Changed Name", call("GET", "/customers/" + owner.id, owner.token, null, 200).path("fullName").asText());
        assertTrue(call("POST", "/auth/login", null, Map.of("email", "changed@http.test", "password", PASSWORD), 200).hasNonNull("token"));
        JsonNode listed = call("GET", "/accounts", owner.token, null, 200);
        assertEquals(1, listed.size());
        assertEquals(account, listed.get(0).path("id").asText());
        balance(account, owner, "0.00");
        call("PATCH", "/accounts/" + account + "/block", owner.token, null, 200);
        call("PATCH", "/accounts/" + account + "/unblock", owner.token, null, 200);
        assertEquals("CLOSED", call("PATCH", "/accounts/" + account + "/close", owner.token, null, 200).path("status").asText());
        call("GET", "/accounts/" + account + "/transactions", owner.token, null, 200);
        User deletable = register("33333333333", "delete@http.test");
        call("DELETE", "/customers/" + deletable.id, deletable.token, null, 204);
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM customers WHERE id = ?::uuid", Integer.class, deletable.id));
        call("GET", "/auth/me", deletable.token, null, 401);
    }

    @Test @Order(2)
    void financialFlowChecksCommittedBalancesAndFilteredHistory() throws Exception {
        call("POST", "/accounts/" + account + "/deposit", owner.token, money("100.00"), 200);
        call("POST", "/accounts/" + account + "/withdraw", owner.token, money("10.00"), 200);
        call("POST", "/accounts/transfer", owner.token, transfer("25.00"), 200);
        balance(account, owner, "65.00");
        balance(recipient, other, "25.00");
        assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM transactions", Integer.class));
        JsonNode page = call("GET", "/accounts/" + account + "/transactions?page=0&size=2", owner.token, null, 200);
        assertEquals(3, page.path("totalElements").asInt());
        assertEquals(2, page.path("totalPages").asInt());
        assertEquals(2, page.path("content").size());
        JsonNode next = call("GET", "/accounts/" + account + "/transactions?page=1&size=2", owner.token, null, 200);
        assertEquals(1, next.path("content").size());
        Set<String> ids = new HashSet<>();
        page.path("content").forEach(t -> ids.add(t.path("id").asText()));
        assertTrue(ids.add(next.path("content").get(0).path("id").asText()));
        String date = jdbc.queryForObject("SELECT created_at::date::text FROM transactions LIMIT 1", String.class);
        JsonNode filtered = call("GET", "/accounts/" + recipient + "/transactions?type=TRANSFER&startDate=" + date + "&endDate=" + date,
                other.token, null, 200);
        assertEquals(1, filtered.path("totalElements").asInt());
        assertEquals(account, filtered.path("content").get(0).path("originAccountId").asText());
        call("POST", "/accounts/" + account + "/withdraw", owner.token, money("100.00"), 409);
        call("POST", "/accounts/transfer", owner.token, transfer("100.00"), 409);
        assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM transactions", Integer.class));
        balance(account, owner, "65.00");
        balance(recipient, other, "25.00");
    }

    @Test @Order(3)
    void everyProtectedEndpointRequiresAuthenticationAndOwnership() throws Exception {
        Map<String, Object> update = Map.of("fullName", "Valid User", "email", "valid@http.test", "phone", "81999999999", "birthDate", "2000-01-01");
        List<Route> routes = List.of(new Route("GET", "/auth/me", null), new Route("GET", "/customers", null),
                new Route("GET", "/customers/" + owner.id, null), new Route("PUT", "/customers/" + owner.id, update),
                new Route("DELETE", "/customers/" + owner.id, null), new Route("POST", "/accounts", Map.of("customerId", owner.id)),
                new Route("GET", "/accounts", null), new Route("GET", "/accounts/" + account, null),
                new Route("POST", "/accounts/" + account + "/deposit", money("1")),
                new Route("POST", "/accounts/" + account + "/withdraw", money("1")),
                new Route("POST", "/accounts/transfer", transfer("1")),
                new Route("PATCH", "/accounts/" + account + "/block", null),
                new Route("PATCH", "/accounts/" + account + "/unblock", null),
                new Route("PATCH", "/accounts/" + account + "/close", null),
                new Route("GET", "/accounts/" + account + "/transactions", null));
        for (Route route : routes) {
            call(route.method, route.path, null, route.body, 401);
            call(route.method, route.path, "invalid-token", route.body, 401);
            if (!Set.of("/auth/me", "/accounts").contains(route.path) || route.method.equals("POST"))
                call(route.method, route.path, other.token, route.body, 403);
        }
        balance(account, owner, "0.00");
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM transactions", Integer.class));
    }

    @Test @Order(4)
    void rejectsInvalidInputsMissingResourcesAndInvalidTransitions() throws Exception {
        call("POST", "/auth/login", null, Map.of("email", "owner@http.test", "password", "wrong"), 401);
        call("POST", "/auth/login", null, Map.of(), 400);
        call("POST", "/customers", null, Map.of(), 400);
        call("POST", "/customers", null, registration("11111111111", "owner@http.test"), 409);
        call("POST", "/accounts", owner.token, Map.of(), 400);
        call("PUT", "/customers/" + owner.id, owner.token, Map.of(), 400);
        String missing = UUID.randomUUID().toString();
        for (String root : List.of("/customers/", "/accounts/")) {
            call("GET", root + missing, owner.token, null, 404);
            call("GET", root + "invalid", owner.token, null, 400);
        }
        for (String operation : List.of("deposit", "withdraw")) {
            call("POST", "/accounts/" + missing + "/" + operation, owner.token, money("1"), 404);
            for (String amount : List.of("0", "-1", "1.001", "10000000000000"))
                call("POST", "/accounts/" + account + "/" + operation, owner.token, money(amount), 400);
        }
        call("POST", "/accounts/transfer", owner.token, Map.of(), 400);
        call("POST", "/accounts/transfer", owner.token, Map.of("sourceAccountId", account, "destinationAccountId", account, "amount", 1), 409);
        call("POST", "/accounts/transfer", owner.token, Map.of("sourceAccountId", account, "destinationAccountId", missing, "amount", 1), 404);
        for (String query : List.of("page=-1", "size=101", "type=INVALID", "startDate=2026-02-30", "startDate=2026-10-02&endDate=2026-10-01"))
            call("GET", "/accounts/" + account + "/transactions?" + query, owner.token, null, 400);
        call("PATCH", "/accounts/" + account + "/unblock", owner.token, null, 409);
        call("PATCH", "/accounts/" + account + "/block", owner.token, null, 200);
        call("POST", "/accounts/" + account + "/deposit", owner.token, money("1"), 409);
        call("PATCH", "/accounts/" + account + "/close", owner.token, null, 409);
        call("PATCH", "/accounts/" + account + "/unblock", owner.token, null, 200);
        call("POST", "/accounts/" + account + "/deposit", owner.token, money("1"), 200);
        call("PATCH", "/accounts/" + account + "/close", owner.token, null, 409);
        call("POST", "/accounts/" + account + "/withdraw", owner.token, money("1"), 200);
        call("PATCH", "/accounts/" + account + "/close", owner.token, null, 200);
        call("PATCH", "/accounts/" + account + "/unblock", owner.token, null, 409);
        call("POST", "/accounts/" + account + "/deposit", owner.token, money("1"), 409);
    }

    @Test @Order(5)
    void concurrentHttpRetriesCommitOneTransferAndReturnOriginalResponse() throws Exception {
        call("POST", "/accounts/" + account + "/deposit", owner.token, money("100"), 200);
        var pool = Executors.newFixedThreadPool(6);
        var ready = new CountDownLatch(6);
        var start = new CountDownLatch(1);
        try {
            List<Future<JsonNode>> futures = new ArrayList<>();
            for (int i = 0; i < 6; i++) futures.add(pool.submit(() -> {
                ready.countDown(); assertTrue(start.await(10, TimeUnit.SECONDS));
                return call("POST", "/accounts/transfer", owner.token, transfer("10.00"), 200, "http-concurrent");
            }));
            assertTrue(ready.await(10, TimeUnit.SECONDS)); start.countDown();
            JsonNode original = futures.get(0).get(30, TimeUnit.SECONDS);
            for (var future : futures) assertEquals(original, future.get(30, TimeUnit.SECONDS));
            call("POST", "/accounts/" + account + "/deposit", owner.token, money("5"), 200);
            assertEquals(original, call("POST", "/accounts/transfer", owner.token, transfer("10"), 200, "http-concurrent"));
            call("POST", "/accounts/transfer", owner.token, transfer("20"), 409, "http-concurrent");
            call("POST", "/accounts/transfer", owner.token, transfer("10"), 400, "bad key");
            balance(account, owner, "95.00"); balance(recipient, other, "10.00");
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM financial_idempotency", Integer.class));
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM transactions WHERE type = 'TRANSFER'", Integer.class));
        } finally { start.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS)); }
    }

    @Test @Order(6)
    void databaseRoleChangesApplyToSameTokenOverHttp() throws Exception {
        call("GET", "/error", null, null, 401);
        call("GET", "/admin/unimplemented", null, null, 401);
        call("GET", "/admin/unimplemented", owner.token, null, 403);
        jdbc.update("UPDATE customers SET role = 'ADMIN' WHERE id = ?::uuid", owner.id);
        assertEquals("ADMIN", call("GET", "/auth/me", owner.token, null, 200).path("role").asText());
        // No administrative business endpoint exists yet: authorized users reach routing (404).
        call("GET", "/admin/unimplemented", owner.token, null, 404);
        call("GET", "/accounts/" + recipient, owner.token, null, 403);
        jdbc.update("UPDATE customers SET role = 'CUSTOMER' WHERE id = ?::uuid", owner.id);
        call("GET", "/admin/unimplemented", owner.token, null, 403);
    }

    @Test @Order(7)
    void cannotDeleteCustomerWithFinancialAccounts() throws Exception {
        call("DELETE", "/customers/" + owner.id, owner.token, null, 409);
        call("GET", "/auth/me", owner.token, null, 200);
        balance(account, owner, "0.00");
    }

    @Test @Order(8)
    void depositAndWithdrawalRetriesAreIdempotentOverHttp() throws Exception {
        String deposit = "/accounts/" + account + "/deposit";
        String withdraw = "/accounts/" + account + "/withdraw";
        JsonNode first = call("POST", deposit, owner.token, money("100.00"), 200, "deposit-key");
        assertEquals(first, call("POST", deposit, owner.token, money("100"), 200, "deposit-key"));
        JsonNode debit = call("POST", withdraw, owner.token, money("10.00"), 200, "withdraw-key");
        assertEquals(debit, call("POST", withdraw, owner.token, money("10"), 200, "withdraw-key"));
        call("POST", withdraw, owner.token, money("10"), 409, "deposit-key");
        call("POST", withdraw, owner.token, money("95"), 409, "failed-key");
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM financial_idempotency", Integer.class));
        call("POST", deposit, owner.token, money("5"), 200);
        call("POST", withdraw, owner.token, money("95"), 200, "failed-key");
        balance(account, owner, "0.00");
        assertEquals(4, jdbc.queryForObject("SELECT count(*) FROM transactions", Integer.class));
    }

    @Test @Order(9)
    void commitFailureReturns500AndRollsBackMoneyHistoryAndKey() throws Exception {
        call("POST", "/accounts/" + account + "/deposit", owner.token, money("100"), 200);
        jdbc.execute("""
                CREATE FUNCTION banking_api_http_tests.reject_http_commit() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'Simulated HTTP commit failure'; END; $$
                """);
        try {
            jdbc.execute("""
                    CREATE CONSTRAINT TRIGGER reject_http_commit AFTER INSERT ON banking_api_http_tests.financial_idempotency
                    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION banking_api_http_tests.reject_http_commit()
                    """);
            call("POST", "/accounts/transfer", owner.token, transfer("10"), 500, "commit-retry");
            balance(account, owner, "100.00"); balance(recipient, other, "0.00");
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM transactions", Integer.class));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM financial_idempotency", Integer.class));
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS reject_http_commit ON banking_api_http_tests.financial_idempotency");
            jdbc.execute("DROP FUNCTION banking_api_http_tests.reject_http_commit()");
        }
        call("POST", "/accounts/transfer", owner.token, transfer("10"), 200, "commit-retry");
        balance(account, owner, "90.00"); balance(recipient, other, "10.00");
    }

    @Test @Order(10)
    void adminReversesAllFinancialTypesAndStatementsExposeLinks() throws Exception {
        User admin = register("33333333333", "admin@http.test");
        jdbc.update("UPDATE customers SET role = 'ADMIN' WHERE id = ?::uuid", admin.id);
        call("POST", "/accounts/" + account + "/deposit", owner.token, money("100"), 200);
        for (String operation : List.of("deposit", "withdraw", "transfer")) {
            if (operation.equals("transfer")) call("POST", "/accounts/transfer", owner.token, transfer("10"), 200);
            else call("POST", "/accounts/" + account + "/" + operation, owner.token, money("10"), 200);
            String originalId = jdbc.queryForObject("SELECT id::text FROM transactions WHERE original_transaction_id IS NULL ORDER BY created_at DESC, id DESC LIMIT 1", String.class);
            String route = "/admin/transactions/" + originalId + "/reversal";
            Map<String, String> request = Map.of("reason", "Approved HTTP correction");
            call("POST", route, null, request, 401);
            call("POST", route, owner.token, request, 403);
            JsonNode inverse = call("POST", route, admin.token, request, 201);
            assertEquals(originalId, inverse.path("originalTransactionId").asText());
            assertEquals(admin.id, inverse.path("adminId").asText());
            assertEquals("Approved HTTP correction", inverse.path("reason").asText());
            assertEquals(0, new BigDecimal("10").compareTo(inverse.path("amount").decimalValue()));
            assertEquals(operation.equals("deposit") ? "WITHDRAW" : operation.equals("withdraw") ? "DEPOSIT" : "TRANSFER", inverse.path("type").asText());
            String reversalId = inverse.path("id").asText();
            assertEquals(admin.id, jdbc.queryForObject("SELECT reversal_admin_id::text FROM transactions WHERE id = ?::uuid", String.class, reversalId));
            call("POST", route, admin.token, request, 409);
            call("POST", "/admin/transactions/" + reversalId + "/reversal", admin.token, request, 409);
            balance(account, owner, "100.00"); balance(recipient, other, "0.00");
            JsonNode history = call("GET", "/accounts/" + account + "/transactions", owner.token, null, 200).path("content");
            boolean foundOriginal = false;
            boolean foundInverse = false;
            for (JsonNode entry : history) {
                if (entry.path("id").asText().equals(originalId)) {
                    assertEquals(reversalId, entry.path("reversalTransactionId").asText()); foundOriginal = true;
                }
                if (entry.path("id").asText().equals(reversalId)) {
                    assertEquals(originalId, entry.path("originalTransactionId").asText()); foundInverse = true;
                    assertFalse(entry.has("adminId")); assertFalse(entry.has("reversalReason"));
                }
            }
            assertTrue(foundOriginal && foundInverse);
        }
    }

    @Test @Order(11)
    void reversalValidatesReasonAccountStateBalanceAndAdminRevocation() throws Exception {
        User admin = register("33333333333", "admin@http.test");
        jdbc.update("UPDATE customers SET role = 'ADMIN' WHERE id = ?::uuid", admin.id);
        call("POST", "/accounts/" + account + "/deposit", owner.token, money("10"), 200);
        String originalId = jdbc.queryForObject("SELECT id::text FROM transactions", String.class);
        String route = "/admin/transactions/" + originalId + "/reversal";
        call("POST", route, admin.token, Map.of(), 400);
        for (String reason : List.of("", "  ", "a".repeat(256)))
            call("POST", route, admin.token, Map.of("reason", reason), 400);
        Map<String, String> request = Map.of("reason", "Correction");
        call("POST", "/admin/transactions/invalid/reversal", admin.token, request, 400);
        call("POST", "/admin/transactions/" + UUID.randomUUID() + "/reversal", admin.token, request, 404);
        call("PATCH", "/accounts/" + account + "/block", owner.token, null, 200);
        call("POST", route, admin.token, request, 409);
        call("PATCH", "/accounts/" + account + "/unblock", owner.token, null, 200);
        call("POST", "/accounts/" + account + "/withdraw", owner.token, money("10"), 200);
        call("POST", route, admin.token, request, 409);
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM transactions WHERE original_transaction_id IS NOT NULL", Integer.class));
        call("POST", "/accounts/" + account + "/deposit", owner.token, money("10"), 200);
        jdbc.update("UPDATE customers SET role = 'CUSTOMER' WHERE id = ?::uuid", admin.id);
        call("POST", route, admin.token, request, 403);
        jdbc.update("UPDATE customers SET role = 'ADMIN' WHERE id = ?::uuid", admin.id);
        call("POST", route, admin.token, request, 201);
        balance(account, owner, "0.00");
    }

    @Test @Order(12)
    void replayingOriginalIdempotencyKeyDoesNotUndoReversal() throws Exception {
        User admin = register("33333333333", "admin@http.test");
        jdbc.update("UPDATE customers SET role = 'ADMIN' WHERE id = ?::uuid", admin.id);
        String deposit = "/accounts/" + account + "/deposit";
        JsonNode originalResponse = call("POST", deposit, owner.token, money("10"), 200, "original-key");
        String id = jdbc.queryForObject("SELECT id::text FROM transactions", String.class);
        call("POST", "/admin/transactions/" + id + "/reversal", admin.token, Map.of("reason", "Correction"), 201);
        assertEquals(originalResponse, call("POST", deposit, owner.token, money("10"), 200, "original-key"));
        balance(account, owner, "0.00");
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM transactions", Integer.class));
    }

    @Test @Order(99)
    void everyDocumentedBusinessEndpointWasExercised() throws Exception {
        JsonNode paths = call("GET", "/v3/api-docs", null, null, 200).path("paths");
        Set<String> documented = new TreeSet<>();
        paths.fields().forEachRemaining(path -> {
            if (path.getKey().startsWith("/accounts") || path.getKey().startsWith("/customers") || path.getKey().startsWith("/auth") || path.getKey().startsWith("/admin"))
                path.getValue().fieldNames().forEachRemaining(method -> {
                    if (Set.of("get", "post", "put", "patch", "delete").contains(method)) documented.add(method.toUpperCase() + " " + path.getKey());
                });
        });
        assertEquals(18, documented.size());
        assertTrue(visited.containsAll(documented), () -> "Uncovered endpoints: " + documented.stream().filter(p -> !visited.contains(p)).toList());
    }

    JsonNode call(String method, String path, String token, Object body, int expected) throws Exception {
        return call(method, path, token, body, expected, null);
    }
    JsonNode call(String method, String path, String token, Object body, int expected, String key) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(20));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (key != null) builder.header("Idempotency-Key", key);
        builder.header("Content-Type", "application/json").method(method,
                body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
        var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        String route = method + " " + path.split("\\?")[0].replaceAll("[0-9a-fA-F]{8}-(?:[0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}", "{id}");
        visited.add(route);
        results.add("| " + scenario + " | " + route + " | " + expected + " | " + response.statusCode() + " | " + (expected == response.statusCode() ? "PASS" : "FAIL") + " |");
        assertEquals(expected, response.statusCode(), method + " " + path);
        JsonNode result = response.body().isBlank() ? mapper.nullNode() : mapper.readTree(response.body());
        if (expected >= 400) assertEquals(expected, result.path("status").asInt(), "Error payload status");
        return result;
    }
    User register(String cpf, String email) throws Exception {
        JsonNode created = call("POST", "/customers", null, registration(cpf, email), 201);
        String token = call("POST", "/auth/login", null, Map.of("email", email, "password", PASSWORD), 200).path("token").asText();
        assertFalse(token.isBlank());
        return new User(created.path("id").asText(), token);
    }
    Map<String, Object> registration(String cpf, String email) {
        return Map.of("fullName", "HTTP Test", "cpf", cpf, "email", email, "password", PASSWORD,
                "phone", "81999999999", "birthDate", "2000-01-01", "role", "ADMIN");
    }
    String createAccount(User user) throws Exception {
        return call("POST", "/accounts", user.token, Map.of("customerId", user.id), 201).path("id").asText();
    }
    Map<String, Object> money(String amount) { return Map.of("amount", new BigDecimal(amount)); }
    Map<String, Object> transfer(String amount) { return Map.of("sourceAccountId", account, "destinationAccountId", recipient, "amount", new BigDecimal(amount)); }
    void balance(String id, User user, String expected) throws Exception {
        assertEquals(0, new BigDecimal(expected).compareTo(call("GET", "/accounts/" + id, user.token, null, 200).path("balance").decimalValue()));
        assertEquals(new BigDecimal(expected), jdbc.queryForObject("SELECT balance FROM accounts WHERE id = ?::uuid", BigDecimal.class, id));
    }
    @AfterAll
    static void writeReport() throws Exception {
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target/http-validation.md"), "# Real HTTP request results\n\n"
                + "Transport: Java HttpClient -> TCP loopback -> embedded Tomcat -> PostgreSQL. No mocks.\n\n"
                + "This table records HTTP status checks; Surefire also reports payload, persistence and concurrency assertions.\n\n"
                + "Requests executed: " + results.size() + ".\n\n"
                + "| Scenario | Request | Expected | Actual | Status |\n|---|---|---|---|---|\n" + String.join("\n", results) + "\n");
    }
    record User(String id, String token) {}
    record Route(String method, String path, Object body) {}
}
