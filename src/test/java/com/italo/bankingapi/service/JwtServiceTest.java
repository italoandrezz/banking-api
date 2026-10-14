package com.italo.bankingapi.service;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwtServiceTest {

    private static final String SECRET = "01234567890123456789012345678901";

    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        jwtService = new JwtService(SECRET, 86_400_000L);
    }

    @Test
    void shouldGenerateTokenSuccessfully() {
        // Arrange
        UUID customerId = UUID.randomUUID();

        // Act
        String token = jwtService.generateToken(customerId);

        // Assert
        assertNotNull(token);
        assertFalse(token.isBlank());
        assertEquals(3, token.split("\\.").length);
        var claims = Jwts.parser()
                .verifyWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .build().parseSignedClaims(token).getPayload();
        assertEquals(customerId.toString(), claims.getSubject());
        assertEquals(86_400_000L, claims.getExpiration().getTime() - claims.getIssuedAt().getTime());
    }

    @Test
    void shouldExtractCustomerIdFromTokenSuccessfully() {
        // Arrange
        UUID customerId = UUID.randomUUID();
        String token = jwtService.generateToken(customerId);

        // Act
        UUID extractedCustomerId = jwtService.extractCustomerId(token);

        // Assert
        assertEquals(customerId, extractedCustomerId);
    }

    @Test
    void shouldValidateTokenForSameCustomerId() {
        // Arrange
        UUID customerId = UUID.randomUUID();
        String token = jwtService.generateToken(customerId);

        // Act
        boolean valid = jwtService.isTokenValid(token, customerId);

        // Assert
        assertTrue(valid);
    }

    @Test
    void shouldRejectTokenForDifferentCustomerId() {
        // Arrange
        String token = jwtService.generateToken(UUID.randomUUID());

        // Act
        boolean valid = jwtService.isTokenValid(token, UUID.randomUUID());

        // Assert
        assertFalse(valid);
    }

    @Test
    void shouldThrowJwtExceptionWhenTokenIsTampered() {
        // Arrange
        String token = jwtService.generateToken(UUID.randomUUID()) + "x";

        // Act
        JwtException exception = assertThrows(
                JwtException.class,
                () -> jwtService.extractCustomerId(token)
        );

        // Assert
        assertNotNull(exception);
    }

    @Test
    void shouldRejectExpiredToken() {
        UUID customerId = UUID.randomUUID();
        String token = Jwts.builder().subject(customerId.toString())
                .expiration(new Date(System.currentTimeMillis() - 60_000))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();

        assertThrows(ExpiredJwtException.class, () -> jwtService.isTokenValid(token, customerId));
    }

    @Test
    void shouldRejectTokenWithoutExpiration() {
        UUID customerId = UUID.randomUUID();
        String token = Jwts.builder().subject(customerId.toString())
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();

        assertFalse(jwtService.isTokenValid(token, customerId));
    }
}
