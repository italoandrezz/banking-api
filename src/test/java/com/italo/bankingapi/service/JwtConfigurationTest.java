package com.italo.bankingapi.service;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JwtConfigurationTest {

    private static final String SECRET = "test-signing-secret-at-least-32-bytes";
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(JwtService.class);

    @ParameterizedTest
    @ValueSource(longs = {1, 3_600_000, 86_400_000})
    void shouldStartWithSupportedLifetime(long lifetime) {
        runner.withPropertyValues("jwt.secret=" + SECRET, "jwt.expiration=" + lifetime)
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(JwtService.class));
    }

    @Test
    void shouldUseExistingDefaultLifetime() {
        runner.withPropertyValues("jwt.secret=" + SECRET).run(context -> {
            assertThat(context).hasNotFailed();
            var claims = Jwts.parser().verifyWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                    .build().parseSignedClaims(context.getBean(JwtService.class).generateToken(UUID.randomUUID()))
                    .getPayload();
            assertThat(claims.getExpiration().getTime() - claims.getIssuedAt().getTime()).isEqualTo(86_400_000);
        });
    }

    @Test
    void shouldFailStartupWhenSecretIsMissing() {
        runner.run(context -> assertThat(context).hasFailed()
                .getFailure().hasRootCauseMessage("JWT_SECRET (jwt.secret) must be present and non-blank."));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t", "                                "})
    void shouldFailStartupWhenSecretIsBlank(String secret) {
        runner.withPropertyValues("jwt.secret=" + secret).run(context -> assertThat(context).hasFailed()
                .getFailure().hasRootCauseMessage("JWT_SECRET (jwt.secret) must be present and non-blank."));
    }

    @ParameterizedTest
    @ValueSource(strings = {"short-secret-marker", "0123456789012345678901234567890"})
    void shouldFailStartupWithShortKeyWithoutDisclosingIt(String secret) {
        runner.withPropertyValues("jwt.secret=" + secret).run(context -> {
            assertThat(context).hasFailed().getFailure()
                    .hasRootCauseMessage("JWT_SECRET (jwt.secret) must contain at least 32 UTF-8 bytes for HMAC signing.");
            assertThat(stackTrace(context.getStartupFailure())).doesNotContain(secret);
        });
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, Long.MIN_VALUE, 86_400_001, Long.MAX_VALUE})
    void shouldFailStartupWithInvalidLifetime(long lifetime) {
        runner.withPropertyValues("jwt.secret=" + SECRET, "jwt.expiration=" + lifetime).run(context -> {
            assertThat(context).hasFailed().getFailure()
                    .hasRootCauseMessage("JWT_EXPIRATION (jwt.expiration) must be between 1 and 86400000 milliseconds (24 hours).");
            assertThat(stackTrace(context.getStartupFailure())).doesNotContain(SECRET);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "invalid", "9223372036854775808"})
    void shouldFailStartupWithNonLongLifetime(String lifetime) {
        runner.withPropertyValues("jwt.secret=" + SECRET, "jwt.expiration=" + lifetime).run(context -> {
            assertThat(context).hasFailed();
            assertThat(stackTrace(context.getStartupFailure())).doesNotContain(SECRET);
        });
    }

    @ParameterizedTest
    @ValueSource(ints = {32, 48, 64})
    void shouldPreserveHmacAlgorithmSelectionAndExistingTokens(int bytes) {
        String secret = "k".repeat(bytes);
        UUID customerId = UUID.randomUUID();
        var key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        String existingToken = Jwts.builder().subject(customerId.toString())
                .expiration(new Date(System.currentTimeMillis() + 60_000)).signWith(key).compact();
        runner.withPropertyValues("jwt.secret=" + secret).run(context -> {
            assertThat(context).hasNotFailed();
            JwtService service = context.getBean(JwtService.class);
            assertThat(service.isTokenValid(existingToken, customerId)).isTrue();
            var parsed = Jwts.parser().verifyWith(key).build().parseSignedClaims(service.generateToken(customerId));
            assertThat(parsed.getHeader().getAlgorithm()).isEqualTo("HS" + (bytes * 8));
        });
    }

    @Test
    void shouldMeasureKeyLengthInUtf8Bytes() {
        runner.withPropertyValues("jwt.secret=" + "é".repeat(16)).run(context -> {
            assertThat(context).hasNotFailed();
            UUID customerId = UUID.randomUUID();
            JwtService service = context.getBean(JwtService.class);
            assertThat(service.isTokenValid(service.generateToken(customerId), customerId)).isTrue();
        });
    }

    private static String stackTrace(Throwable failure) {
        StringWriter output = new StringWriter();
        failure.printStackTrace(new PrintWriter(output));
        return output.toString();
    }
}
