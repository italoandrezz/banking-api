package com.italo.bankingapi.service;

import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

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
        jwtService = new JwtService();
        ReflectionTestUtils.setField(jwtService, "secret", SECRET);
        ReflectionTestUtils.setField(jwtService, "expirationTime", 86_400_000L);
    }

    @Test
    void shouldGenerateTokenSuccessfully() {
        // Arrange
        String email = "italo@test.com";

        // Act
        String token = jwtService.generateToken(email);

        // Assert
        assertNotNull(token);
        assertFalse(token.isBlank());
        assertEquals(3, token.split("\\.").length);
    }

    @Test
    void shouldExtractEmailFromTokenSuccessfully() {
        // Arrange
        String email = "italo@test.com";
        String token = jwtService.generateToken(email);

        // Act
        String extractedEmail = jwtService.extractEmail(token);

        // Assert
        assertEquals(email, extractedEmail);
    }

    @Test
    void shouldValidateTokenForSameEmail() {
        // Arrange
        String email = "italo@test.com";
        String token = jwtService.generateToken(email);

        // Act
        boolean valid = jwtService.isTokenValid(token, email);

        // Assert
        assertTrue(valid);
    }

    @Test
    void shouldRejectTokenForDifferentEmail() {
        // Arrange
        String token = jwtService.generateToken("italo@test.com");

        // Act
        boolean valid = jwtService.isTokenValid(token, "other@test.com");

        // Assert
        assertFalse(valid);
    }

    @Test
    void shouldThrowJwtExceptionWhenTokenIsTampered() {
        // Arrange
        String token = jwtService.generateToken("italo@test.com") + "x";

        // Act
        JwtException exception = assertThrows(
                JwtException.class,
                () -> jwtService.extractEmail(token)
        );

        // Assert
        assertNotNull(exception);
    }
}
