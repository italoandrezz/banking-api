package com.italo.bankingapi.service;

import com.italo.bankingapi.dto.auth.LoginRequest;
import com.italo.bankingapi.dto.auth.LoginResponse;
import com.italo.bankingapi.entity.Customer;
import com.italo.bankingapi.exception.UnauthorizedException;
import com.italo.bankingapi.repository.CustomerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final CustomerRepository customerRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public LoginResponse login(LoginRequest request) {
        Customer customer = customerRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new UnauthorizedException("Invalid e-mail or password."));
        boolean passwordMatches = passwordEncoder.matches(
                request.getPassword(),
                customer.getPassword()
        );
        if (!passwordMatches) {
            throw new UnauthorizedException("Invalid e-mail or password.");
        }
        String token = jwtService.generateToken(customer.getEmail());
        return new LoginResponse(token);
    }
}
