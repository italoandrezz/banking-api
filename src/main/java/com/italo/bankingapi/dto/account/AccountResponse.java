package com.italo.bankingapi.dto.account;

import com.italo.bankingapi.enums.AccountStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AccountResponse {

    private UUID id;
    private UUID customerId;
    private String accountNumber;
    private String agency;
    private BigDecimal balance;
    private AccountStatus status;
    private LocalDateTime createdAt;
}