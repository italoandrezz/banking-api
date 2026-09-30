package com.italo.bankingapi.config.security;

import com.italo.bankingapi.entity.Customer;
import com.italo.bankingapi.exception.UnauthorizedException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class AuthenticatedCustomer {

    public Customer getCustomer() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof Customer customer)
                || customer.getId() == null) {
            throw new UnauthorizedException("Authentication is required.");
        }
        return customer;
    }

    public void requireOwner(UUID customerId) {
        if (!getCustomer().getId().equals(customerId)) {
            throw new AccessDeniedException("Access denied.");
        }
    }
}
