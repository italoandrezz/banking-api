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
    void profileUpdatePreservesConcurrentRoleAndPasswordChanges() throws Exception {
        var created=send("POST", "/customers", registration("44444444444", "admin@audit.test", "test-password"), null);
        assertEquals(201, created.statusCode());
        String id=mapper.readTree(created.body()).path("id").asText();
        jdbc.update("UPDATE customers SET role='ADMIN' WHERE id=?::uuid", id);
        var login=send("POST", "/auth/login", Map.of("email","admin@audit.test","password","test-password"),null);
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
            assertEquals("CUSTOMER",role); assertEquals("CUSTOMER", mapper.readTree(me.body()).path("role").asText()); assertEquals("concurrently-changed-hash", jdbc.queryForObject("SELECT password FROM customers WHERE id=?::uuid", String.class, id)); assertEquals(403, send("POST", "/admin/transactions/" + UUID.randomUUID() + "/reversal", Map.of("reason", "Forbidden after revocation"), token).statusCode());
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
