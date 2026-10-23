package com.italo.bankingapi.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;
import java.util.UUID;

@Service
public class JwtService {

    // Access tokens have no individual revocation; cap their lifetime at the existing 24h default.
    private static final long MAX_EXPIRATION_MILLIS = Duration.ofHours(24).toMillis();

    private final SecretKey signingKey;
    private final long expirationTime;

    public JwtService(@Value("${jwt.secret:}") String secret,
                      @Value("${jwt.expiration:86400000}") long expirationTime) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("JWT_SECRET (jwt.secret) must be present and non-blank.");
        }
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            throw new IllegalArgumentException("JWT_SECRET (jwt.secret) must contain at least 32 UTF-8 bytes for HMAC signing.");
        }
        if (expirationTime <= 0 || expirationTime > MAX_EXPIRATION_MILLIS) {
            throw new IllegalArgumentException("JWT_EXPIRATION (jwt.expiration) must be between 1 and 86400000 milliseconds (24 hours).");
        }
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
        this.expirationTime = expirationTime;
    }
    public String generateToken(UUID customerId) {
        Date issuedAt = new Date();
        return Jwts.builder()
                .subject(customerId.toString())
                .issuedAt(issuedAt)
                .expiration(new Date(issuedAt.getTime() + expirationTime))
                .signWith(signingKey)
                .compact();
    }
    private Claims extractAllClaims(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
    public UUID extractCustomerId(String token) {
        String subject = extractAllClaims(token).getSubject();
        if (subject == null) {
            throw new IllegalArgumentException("Missing customer ID.");
        }
        UUID customerId = UUID.fromString(subject);
        if (!customerId.toString().equalsIgnoreCase(subject)) {
            throw new IllegalArgumentException("Invalid customer ID.");
        }
        return customerId;
    }
    private boolean isTokenExpired(String token) {
        Date expiration = extractAllClaims(token).getExpiration();
        return expiration == null || expiration.before(new Date());
    }
    public boolean isTokenValid(String token, UUID customerId) {
        UUID extractedCustomerId = extractCustomerId(token);

        return extractedCustomerId.equals(customerId)
                && !isTokenExpired(token);
    }
}
