package com.italo.bankingapi.dto.auth;

import com.italo.bankingapi.enums.CustomerRole;
import java.util.UUID;

public record CurrentCustomerResponse(UUID customerId, CustomerRole role) {}
