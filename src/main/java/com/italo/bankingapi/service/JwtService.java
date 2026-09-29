package com.italo.bankingapi.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

@Service
public class JwtService {

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.expiration}")
    private long expirationTime;

    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }
    public String generateToken(UUID customerId) {
        return Jwts.builder()
                .subject(customerId.toString())
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expirationTime))
                .signWith(getSigningKey())
                .compact();
    }
    private Claims extractAllClaims(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
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
