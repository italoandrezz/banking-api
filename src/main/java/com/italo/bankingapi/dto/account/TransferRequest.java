package com.italo.bankingapi.dto.account;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class TransferRequest {
    @NotNull(message = "Source account ID is required.")
    private UUID sourceAccountId;

    @NotNull(message = "Destination account ID is required.")
    private UUID destinationAccountId;

    @NotNull(message = "Amount is required.")
    @DecimalMin(value = "0.01", message = "Amount must be greater than zero.")
    private BigDecimal amount;
}
