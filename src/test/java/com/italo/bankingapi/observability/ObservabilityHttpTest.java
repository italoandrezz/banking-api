package com.italo.bankingapi.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.italo.bankingapi.service.JwtService;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.address=127.0.0.1",
        "spring.datasource.hikari.connection-init-sql=SET search_path TO banking_observability_tests",
        "spring.flyway.schemas=banking_observability_tests",
        "spring.flyway.default-schema=banking_observability_tests",
        "spring.jpa.properties.hibernate.default_schema=banking_observability_tests"})
@ActiveProfiles("test") @DirtiesContext
@Import(ObservabilityHttpTest.Fixtures.class)
@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
class ObservabilityHttpTest {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtService jwt;
    @Autowired MeterRegistry meters;
    private final HttpClient client = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    private HttpResponse<String> get(String path, String token, String id) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(10));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (id != null) builder.header("X-Correlation-ID", id);
        return client.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test void healthAndProbesArePublicMinimalAndCorrelated() throws Exception {
        for (String path : new String[]{"/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness"}) {
            String id = UUID.randomUUID().toString();
            var response = get(path, null, id);
            assertEquals(200, response.statusCode());
            assertEquals(id, response.headers().firstValue("X-Correlation-ID").orElseThrow());
            var body = mapper.readTree(response.body());
            assertEquals("UP", body.path("status").asText());
            assertFalse(body.has("components")); assertFalse(body.has("details"));
            if (path.equals("/actuator/health")) {
                assertEquals(2, body.size());
                assertEquals(mapper.readTree("[\"liveness\",\"readiness\"]"), body.path("groups"));
            } else assertEquals(1, body.size());
        }
    }

    @Test void errorsAndDiagnosticsPreserveAuthorizationAndCorrelation(org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        UUID customer = UUID.randomUUID();
        jdbc.update("INSERT INTO customers(id, full_name, cpf, email, password, birth_date) VALUES (?, ?, ?, ?, ?, DATE '2000-01-01')",
                customer, "Synthetic", "12345678901", "observability@example.test", "unused-synthetic-hash");
        try {
            String token = jwt.generateToken(customer);
            String id = UUID.randomUUID().toString();
            var unauthorized = get("/accounts", null, id);
            assertEquals(401, unauthorized.statusCode());
            assertEquals(id, unauthorized.headers().firstValue("X-Correlation-ID").orElseThrow());
            var forbidden = get("/admin/probe", token, id);
            assertEquals(403, forbidden.statusCode());
            assertEquals(id, forbidden.headers().firstValue("X-Correlation-ID").orElseThrow());
            var error = get("/observability-test/failure", token, id);
            assertEquals(500, error.statusCode());
            assertEquals(id, error.headers().firstValue("X-Correlation-ID").orElseThrow());
            assertFalse(error.body().contains("sensitive-fixture"));
            for (String path : new String[]{"/actuator/env", "/actuator/metrics", "/actuator/heapdump", "/actuator/loggers", "/actuator/prometheus"}) {
                assertEquals(401, get(path, null, null).statusCode());
                assertEquals(403, get(path, token, null).statusCode());
            }
            jdbc.update("UPDATE customers SET role='ADMIN' WHERE id=?", customer);
            assertEquals(403, get("/actuator/metrics", token, null).statusCode());
            assertFalse(output.getAll().contains("sensitive-fixture"));
            assertFalse(output.getAll().contains("observability@example.test"));
            assertFalse(output.getAll().contains("unused-synthetic-hash"));
            assertFalse(output.getAll().contains(token));
            assertTrue(output.getAll().contains("http_request_completed"));
            assertTrue(output.getAll().contains(id));
            var statuses = new java.util.HashSet<Integer>();
            for (String line : output.getAll().split("\\R")) {
                if (!line.startsWith("{")) continue;
                var event = mapper.readTree(line);
                if (id.equals(event.path("correlationId").asText())
                        && "http_request_completed".equals(event.path("event").asText())) {
                    statuses.add(event.path("status").asInt());
                    assertTrue(event.path("durationMs").asLong(-1) >= 0);
                }
            }
            assertTrue(statuses.containsAll(java.util.Set.of(401, 403, 500)));
        } finally { jdbc.update("DELETE FROM customers WHERE id=?", customer); }
    }

    @Test void httpJvmAndPoolMetricsAreCollectedWithoutPublicExposure() throws Exception {
        get("/actuator/health", null, null);
        assertNotNull(meters.find("jvm.memory.used").gauge());
        assertNotNull(meters.find("hikaricp.connections.active").gauge());
        assertNotNull(meters.find("http.server.requests").timer());
        assertTrue(meters.find("http.server.requests").timers().stream().mapToLong(t -> t.count()).sum() > 0);
        assertTrue(meters.getMeters().stream().flatMap(m -> m.getId().getTags().stream())
                .noneMatch(t -> t.getKey().equals("correlationId")));
    }

    @TestConfiguration static class Fixtures {
        @Bean FailureEndpoint failureEndpoint() { return new FailureEndpoint(); }
    }
    @RestController static class FailureEndpoint {
        @GetMapping("/observability-test/failure") void fail() { throw new IllegalStateException("sensitive-fixture"); }
    }
}
