package com.italo.bankingapi.controller;

import com.italo.bankingapi.dto.auth.LoginRequest;
import com.italo.bankingapi.dto.auth.LoginResponse;
import com.italo.bankingapi.service.AuthService;
import com.italo.bankingapi.config.security.AuthenticatedCustomer;
import com.italo.bankingapi.dto.auth.CurrentCustomerResponse;
import org.springframework.web.bind.annotation.GetMapping;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {
    private final AuthService authService;
    private final AuthenticatedCustomer authenticatedCustomer;

    @GetMapping("/me")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Get authenticated customer ID and current role")
    public CurrentCustomerResponse currentCustomer() {
        var customer = authenticatedCustomer.getCustomer();
        return new CurrentCustomerResponse(customer.getId(), customer.getRole());
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        LoginResponse response = authService.login(request);
        return ResponseEntity.ok(response);
    }
}
