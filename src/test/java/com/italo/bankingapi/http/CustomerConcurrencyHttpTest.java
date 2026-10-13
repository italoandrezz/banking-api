package com.italo.bankingapi.http;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;
import javax.sql.DataSource;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "server.address=127.0.0.1",
    "spring.datasource.hikari.connection-init-sql=SET search_path TO banking_api_customer_concurrency_tests",
    "spring.flyway.schemas=banking_api_customer_concurrency_tests",
    "spring.flyway.default-schema=banking_api_customer_concurrency_tests",
    "spring.jpa.properties.hibernate.default_schema=banking_api_customer_concurrency_tests"
})
@ActiveProfiles("test")
@DirtiesContext
class CustomerConcurrencyHttpTest {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired ObjectMapper mapper;
    HttpClient client = HttpClient.newHttpClient();

    @BeforeEach @AfterEach
    void clean() {
        assertEquals("banking_api_customer_concurrency_tests", jdbc.queryForObject("SELECT current_schema()", String.class));
        jdbc.execute("TRUNCATE banking_api_customer_concurrency_tests.transactions, banking_api_customer_concurrency_tests.accounts, banking_api_customer_concurrency_tests.addresses, banking_api_customer_concurrency_tests.customers, banking_api_customer_concurrency_tests.financial_idempotency");
    }

    @Test
    void concurrentCpfRegistrationsReturnConflict() throws Exception {
        assertConcurrentRegistrations(false);
    }

    @Test
    void concurrentEmailRegistrationsReturnConflict() throws Exception {
        assertConcurrentRegistrations(true);
    }

    private void assertConcurrentRegistrations(boolean sameEmail) throws Exception {
        var pool = Executors.newFixedThreadPool(6);
        var gate = new CountDownLatch(1);
        try {
            List<Future<HttpResponse<String>>> requests = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                final int index = i;
                requests.add(pool.submit(() -> {
                    assertTrue(gate.await(5, TimeUnit.SECONDS));
                    return send("POST", "/customers", registration(
                            sameEmail ? "3333333333" + index : "33333333333",
                            sameEmail ? "shared@test.com" : "race" + index + "@test.com", "test-password"), null);
                }));
            }
            gate.countDown();
            int created = 0;
            for (var request : requests) {
                var response = request.get(20, TimeUnit.SECONDS);
                if (response.statusCode() == 201) created++;
                else assertConflict(response, sameEmail ? "E-mail already registered." : "CPF already registered.");
            }
            assertEquals(1, created);
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM customers", Integer.class));
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void profileEmailCollisionAtCommitReturnsConflict() throws Exception {
        var first = send("POST", "/customers", registration("11111111111", "first@test.com", "test-password"), null);
        var second = send("POST", "/customers", registration("22222222222", "second@test.com", "test-password"), null);
        assertEquals(201, first.statusCode());
        assertEquals(201, second.statusCode());
        String firstId = mapper.readTree(first.body()).path("id").asText();
        String secondId = mapper.readTree(second.body()).path("id").asText();
        var login = send("POST", "/auth/login", Map.of("email", "second@test.com", "password", "test-password"), null);
        assertEquals(200, login.statusCode());
        String token = mapper.readTree(login.body()).path("token").asText();
        var pool = Executors.newSingleThreadExecutor();
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var update = connection.prepareStatement("UPDATE banking_api_customer_concurrency_tests.customers SET email='claimed@test.com' WHERE id=?::uuid")) {
                update.setString(1, firstId);
                update.executeUpdate();
            }
            var response = pool.submit(() -> send("PUT", "/customers/" + secondId,
                    Map.of("fullName", "Changed", "email", "claimed@test.com", "phone", "81999999999", "birthDate", "2000-01-01"), token));
            boolean waiting = false;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < deadline) {
                waiting = jdbc.queryForObject("SELECT count(*) > 0 FROM pg_stat_activity WHERE pid <> pg_backend_pid() AND wait_event_type='Lock' AND query LIKE '%update%banking_api_customer_concurrency_tests.customers%'", Boolean.class);
                if (waiting) break;
                Thread.sleep(50);
            }
            connection.commit();
            assertTrue(waiting, "Profile write must wait on the competing email claim");
            assertConflict(response.get(15, TimeUnit.SECONDS), "E-mail already registered.");
            assertEquals("second@test.com", jdbc.queryForObject("SELECT email FROM customers WHERE id=?::uuid", String.class, secondId));
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private void assertConflict(HttpResponse<String> response, String message) throws Exception {
        assertEquals(409, response.statusCode(), response.body());
        var error = mapper.readTree(response.body());
        assertEquals(409, error.path("status").asInt());
        assertEquals("Conflict", error.path("error").asText());
        assertEquals(message, error.path("message").asText());
        assertTrue(error.path("path").asText().startsWith("/customers"));
    }

    @Test
    void profileUpdatePreservesConcurrentRoleAndPasswordChanges() throws Exception {
        var created=send("POST", "/customers", registration("44444444444", "admin@audit.test", "test-password"), null);
        assertEquals(201, created.statusCode());
        String id=mapper.readTree(created.body()).path("id").asText();
        jdbc.update("UPDATE customers SET role='ADMIN' WHERE id=?::uuid", id);
        var login=send("POST", "/auth/login", Map.of("email","admin@audit.test","password","test-password"),null);
        assertEquals(200, login.statusCode());
        String token=mapper.readTree(login.body()).path("token").asText();
        var pool=Executors.newSingleThreadExecutor();
        try (var connection=dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var update=connection.prepareStatement("UPDATE banking_api_customer_concurrency_tests.customers SET role='CUSTOMER', password='concurrently-changed-hash' WHERE id=?::uuid")) {
                update.setString(1,id); update.executeUpdate();
            }
            Future<HttpResponse<String>> profile=pool.submit(() -> send("PUT","/customers/"+id,
                    Map.of("fullName","Edited profile","email","admin@audit.test","phone","81999999999","birthDate","2000-01-01"),token));
            boolean waiting=false;
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            while(System.nanoTime()<deadline) {
                waiting=jdbc.queryForObject("SELECT count(*) > 0 FROM pg_stat_activity WHERE pid <> pg_backend_pid() AND wait_event_type='Lock' AND query LIKE '%update%banking_api_customer_concurrency_tests.customers%'",Boolean.class);
                if(waiting) break;
                Thread.sleep(50);
            }
            // Commit the operator's revocation after the profile update is waiting on this row.
            connection.commit();
            var response=profile.get(15,TimeUnit.SECONDS);
            assertTrue(waiting,"HTTP profile update must reach the database lock");
            assertEquals(200,response.statusCode());
            String role=jdbc.queryForObject("SELECT role FROM customers WHERE id=?::uuid",String.class,id);
            var me=send("GET","/auth/me",null,token);
            assertEquals("CUSTOMER", role);
            assertEquals(200, me.statusCode());
            assertEquals("CUSTOMER", mapper.readTree(me.body()).path("role").asText());
            assertEquals("concurrently-changed-hash", jdbc.queryForObject(
                    "SELECT password FROM customers WHERE id=?::uuid", String.class, id));
            assertEquals(403, send("POST", "/admin/transactions/" + UUID.randomUUID() + "/reversal",
                    Map.of("reason", "Forbidden after revocation"), token).statusCode());
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS)); }
    }

    Map<String,Object> registration(String cpf,String email,String password) {
        return Map.of("cpf",cpf,"email",email,"password",password,"fullName","Audit fixture","phone","81999999999","birthDate","2000-01-01");
    }
    HttpResponse<String> send(String method,String path,Object payload,String token) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(20)).header("Content-Type","application/json");
        if(token!=null) request.header("Authorization","Bearer "+token);
        request.method(method,payload==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)));
        return client.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
}
