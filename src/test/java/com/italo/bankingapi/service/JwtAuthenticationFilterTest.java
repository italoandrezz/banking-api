package com.italo.bankingapi.service;

import com.italo.bankingapi.config.filter.JwtAuthenticationFilter;
import com.italo.bankingapi.config.security.JwtAuthenticationEntryPoint;
import com.italo.bankingapi.entity.Customer;
import com.italo.bankingapi.repository.CustomerRepository;
import io.jsonwebtoken.MalformedJwtException;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {

    @Mock
    private JwtService jwtService;

    @Mock
    private CustomerRepository customerRepository;

    @Mock
    private JwtAuthenticationEntryPoint authenticationEntryPoint;

    @Mock
    private FilterChain filterChain;

    @InjectMocks
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldContinueFilterChainWhenAuthorizationHeaderIsAbsent() throws Exception {
        // Arrange
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        // Act
        jwtAuthenticationFilter.doFilter(request, response, filterChain);

        // Assert
        verify(filterChain, times(1)).doFilter(request, response);
        verify(jwtService, never()).extractEmail(any());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void shouldContinueFilterChainWhenAuthorizationHeaderHasNoBearerPrefix() throws Exception {
        // Arrange
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Basic credentials");
        MockHttpServletResponse response = new MockHttpServletResponse();

        // Act
        jwtAuthenticationFilter.doFilter(request, response, filterChain);

        // Assert
        verify(filterChain, times(1)).doFilter(request, response);
        verify(jwtService, never()).extractEmail(any());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void shouldAuthenticateExistingUserWhenTokenIsValid() throws Exception {
        // Arrange
        String token = "valid-token";
        String email = "italo@test.com";
        Customer customer = Customer.builder().email(email).build();
        MockHttpServletRequest request = bearerRequest(token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(jwtService.extractEmail(token)).thenReturn(email);
        when(customerRepository.findByEmail(email)).thenReturn(Optional.of(customer));
        when(jwtService.isTokenValid(token, email)).thenReturn(true);

        // Act
        jwtAuthenticationFilter.doFilter(request, response, filterChain);

        // Assert
        assertSame(customer, SecurityContextHolder.getContext().getAuthentication().getPrincipal());
        assertEquals(true, SecurityContextHolder.getContext().getAuthentication().isAuthenticated());
        verify(filterChain, times(1)).doFilter(request, response);
        verify(authenticationEntryPoint, never()).commence(any(), any(), any());
    }

    @Test
    void shouldRejectRequestWhenTokenIsInvalid() throws Exception {
        // Arrange
        String token = "invalid-token";
        MockHttpServletRequest request = bearerRequest(token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(jwtService.extractEmail(token)).thenThrow(new MalformedJwtException("Invalid token"));

        // Act
        jwtAuthenticationFilter.doFilter(request, response, filterChain);

        // Assert
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(authenticationEntryPoint, times(1))
                .commence(any(), any(), any(BadCredentialsException.class));
        verify(filterChain, never()).doFilter(request, response);
    }

    @Test
    void shouldRejectRequestWhenTokenUserDoesNotExist() throws Exception {
        // Arrange
        String token = "valid-token";
        String email = "missing@test.com";
        MockHttpServletRequest request = bearerRequest(token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(jwtService.extractEmail(token)).thenReturn(email);
        when(customerRepository.findByEmail(email)).thenReturn(Optional.empty());

        // Act
        jwtAuthenticationFilter.doFilter(request, response, filterChain);

        // Assert
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(jwtService, never()).isTokenValid(any(), any());
        verify(authenticationEntryPoint, times(1))
                .commence(any(), any(), any(BadCredentialsException.class));
        verify(filterChain, never()).doFilter(request, response);
    }

    private MockHttpServletRequest bearerRequest(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        return request;
    }
}
