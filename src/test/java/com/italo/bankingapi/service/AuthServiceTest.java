package com.italo.bankingapi.service;

import com.italo.bankingapi.dto.auth.LoginRequest;
import com.italo.bankingapi.dto.auth.LoginResponse;
import com.italo.bankingapi.entity.Customer;
import com.italo.bankingapi.exception.UnauthorizedException;
import com.italo.bankingapi.repository.CustomerRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private CustomerRepository customerRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtService jwtService;

    @InjectMocks
    private AuthService authService;

    @Test
    void shouldLoginSuccessfullyWhenCredentialsAreValid() {
        // Arrange
        LoginRequest request = new LoginRequest("italo@test.com", "plain-password");
        Customer customer = Customer.builder()
                .email(request.getEmail())
                .password("hashed-password")
                .build();
        when(customerRepository.findByEmail(request.getEmail())).thenReturn(Optional.of(customer));
        when(passwordEncoder.matches(request.getPassword(), customer.getPassword())).thenReturn(true);
        when(jwtService.generateToken(customer.getEmail())).thenReturn("jwt-token");

        // Act
        LoginResponse response = authService.login(request);

        // Assert
        assertEquals("jwt-token", response.getToken());
        verify(passwordEncoder, times(1)).matches(request.getPassword(), customer.getPassword());
        verify(jwtService, times(1)).generateToken(customer.getEmail());
    }

    @Test
    void shouldThrowUnauthorizedExceptionWhenUserDoesNotExist() {
        // Arrange
        LoginRequest request = new LoginRequest("missing@test.com", "plain-password");
        when(customerRepository.findByEmail(request.getEmail())).thenReturn(Optional.empty());

        // Act
        UnauthorizedException exception = assertThrows(
                UnauthorizedException.class,
                () -> authService.login(request)
        );

        // Assert
        assertEquals("Invalid e-mail or password.", exception.getMessage());
        verify(passwordEncoder, never()).matches(any(), any());
        verify(jwtService, never()).generateToken(any());
    }

    @Test
    void shouldThrowUnauthorizedExceptionWhenPasswordIsIncorrect() {
        // Arrange
        LoginRequest request = new LoginRequest("italo@test.com", "wrong-password");
        Customer customer = Customer.builder()
                .email(request.getEmail())
                .password("hashed-password")
                .build();
        when(customerRepository.findByEmail(request.getEmail())).thenReturn(Optional.of(customer));
        when(passwordEncoder.matches(request.getPassword(), customer.getPassword())).thenReturn(false);

        // Act
        UnauthorizedException exception = assertThrows(
                UnauthorizedException.class,
                () -> authService.login(request)
        );

        // Assert
        assertEquals("Invalid e-mail or password.", exception.getMessage());
        verify(passwordEncoder, times(1)).matches(request.getPassword(), customer.getPassword());
        verify(jwtService, never()).generateToken(any());
    }
}
