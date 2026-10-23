package com.italo.bankingapi.config.filter;

import com.italo.bankingapi.config.security.JwtAuthenticationEntryPoint;
import com.italo.bankingapi.entity.Customer;
import com.italo.bankingapi.repository.CustomerRepository;
import com.italo.bankingapi.service.JwtService;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final CustomerRepository customerRepository;
    private final JwtAuthenticationEntryPoint authenticationEntryPoint;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String authorizationHeader = request.getHeader("Authorization");
        if (authorizationHeader == null ||
                !authorizationHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }
        String token = authorizationHeader.substring(7);
        UUID customerId;
        try {
            customerId = jwtService.extractCustomerId(token);
        } catch (JwtException | IllegalArgumentException exception) {
            rejectToken(request, response);
            return;
        }
        // Repository/programming failures must propagate as server errors, not invalid credentials.
        Customer customer = customerRepository.findById(customerId).orElse(null);
        boolean valid;
        try {
            valid = customer != null && jwtService.isTokenValid(token, customer.getId());
        } catch (JwtException | IllegalArgumentException exception) {
            rejectToken(request, response);
            return;
        }
        if (!valid) {
            rejectToken(request, response);
            return;
        }
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(
                        customer,
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + customer.getRole().name()))
                );
        SecurityContextHolder.getContext().setAuthentication(authentication);
        filterChain.doFilter(request, response);
    }

    private void rejectToken(HttpServletRequest request, HttpServletResponse response)
            throws IOException, ServletException {
        SecurityContextHolder.clearContext();
        authenticationEntryPoint.commence(request, response,
                new BadCredentialsException("Invalid or expired token."));
    }
}
