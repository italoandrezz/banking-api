package com.italo.bankingapi.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;
import javax.sql.DataSource;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "server.address=127.0.0.1",
    "spring.datasource.hikari.connection-init-sql=SET search_path TO banking_api_creation_tests",
    "spring.flyway.schemas=banking_api_creation_tests",
    "spring.flyway.default-schema=banking_api_creation_tests",
    "spring.jpa.properties.hibernate.default_schema=banking_api_creation_tests"
})
@ActiveProfiles("test")
@DirtiesContext
class AccountCreationHttpTest {
    @LocalServerPort int port;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    private final HttpClient client = HttpClient.newHttpClient();
    private String customerId;
    private String token;

    @BeforeEach
    void prepare() throws Exception {
        clean();
        var created = send("POST", "/customers", Map.of("fullName", "Creation fixture", "cpf", "12345678901",
                "email", "creation@test.com", "password", "test-password", "phone", "81999999999", "birthDate", "2000-01-01"));
        assertEquals(201, created.statusCode());
        customerId = mapper.readTree(created.body()).path("id").asText();
        var login = send("POST", "/auth/login", Map.of("email", "creation@test.com", "password", "test-password"));
        assertEquals(200, login.statusCode());
        token = mapper.readTree(login.body()).path("token").asText();
    }

    @AfterEach
    void clean() {
        assertEquals("banking_api_creation_tests", jdbc.queryForObject("SELECT current_schema()", String.class));
        jdbc.execute("DROP TRIGGER IF EXISTS force_collision ON banking_api_creation_tests.accounts");
        jdbc.execute("DROP FUNCTION IF EXISTS banking_api_creation_tests.force_collision()");
        jdbc.execute("DROP SEQUENCE IF EXISTS banking_api_creation_tests.collision_attempts");
        jdbc.execute("TRUNCATE banking_api_creation_tests.transactions, banking_api_creation_tests.accounts, "
                + "banking_api_creation_tests.addresses, banking_api_creation_tests.customers, banking_api_creation_tests.financial_idempotency");
    }

    @Test
    void returnsNotFoundWhenDeletionWinsBeforeAccountInsert() throws Exception {
        var pool = Executors.newSingleThreadExecutor();
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var deletion = connection.prepareStatement("DELETE FROM banking_api_creation_tests.customers WHERE id=?::uuid")) {
                deletion.setString(1, customerId);
                deletion.executeUpdate();
            }
            var request = pool.submit(() -> send("POST", "/accounts", Map.of("customerId", customerId)));
            boolean waiting = false;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < deadline) {
                waiting = jdbc.queryForObject("SELECT count(*) > 0 FROM pg_stat_activity WHERE pid<>pg_backend_pid() AND wait_event_type='Lock' AND query LIKE '%insert into banking_api_creation_tests.accounts%'", Boolean.class);
                if (waiting) break;
                Thread.sleep(50);
            }
            connection.commit();
            assertTrue(waiting, "Account insert must wait for the concurrent deletion");
            var response = request.get(15, TimeUnit.SECONDS);
            assertEquals(404, response.statusCode(), response.body());
            assertEquals("Customer not found.", mapper.readTree(response.body()).path("message").asText());
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM accounts", Integer.class));
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void preservesAccountWhenCreationCommitsBeforeDeletion() throws Exception {
        assertEquals(201, send("POST", "/accounts", Map.of("customerId", customerId)).statusCode());
        assertEquals(409, send("DELETE", "/customers/" + customerId, null).statusCode());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM accounts", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM customers", Integer.class));
    }

    @Test
    void retriesDatabaseCollisionInFreshTransaction() throws Exception {
        forceCollisions(1);
        var response = send("POST", "/accounts", Map.of("customerId", customerId));
        assertEquals(201, response.statusCode(), response.body());
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM accounts", Integer.class));
        assertEquals(2L, jdbc.queryForObject("SELECT last_value FROM collision_attempts", Long.class));
        assertEquals(0, mapper.readTree(response.body()).path("balance").decimalValue().signum());
    }

    @Test
    void stopsAfterBoundedCollisionsWithoutPartialAccounts() throws Exception {
        forceCollisions(10);
        var response = send("POST", "/accounts", Map.of("customerId", customerId));
        assertEquals(409, response.statusCode(), response.body());
        assertEquals("Unable to allocate an account number. Please retry.", mapper.readTree(response.body()).path("message").asText());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM accounts", Integer.class));
        assertEquals(10L, jdbc.queryForObject("SELECT last_value FROM collision_attempts", Long.class));
    }

    private void forceCollisions(int count) {
        // Real unique-index violations after the availability check. The sequence survives rollback.
        jdbc.update("INSERT INTO accounts(id, customer_id, account_number, agency, balance, status) VALUES (?::uuid, ?::uuid, 'reserved-test-number', '0001', 0, 'ACTIVE')",
                UUID.randomUUID().toString(), customerId);
        jdbc.execute("CREATE SEQUENCE banking_api_creation_tests.collision_attempts");
        jdbc.execute("CREATE FUNCTION banking_api_creation_tests.force_collision() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                + "IF nextval('banking_api_creation_tests.collision_attempts') <= " + count
                + " THEN NEW.account_number := 'reserved-test-number'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER force_collision BEFORE INSERT ON banking_api_creation_tests.accounts FOR EACH ROW EXECUTE FUNCTION banking_api_creation_tests.force_collision()");
    }

    private HttpResponse<String> send(String method, String path, Object body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json");
        if (token != null) request.header("Authorization", "Bearer " + token);
        return client.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
    }
}
