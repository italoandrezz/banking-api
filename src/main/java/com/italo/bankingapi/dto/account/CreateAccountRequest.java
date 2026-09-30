package com.italo.bankingapi.dto.account;

import jakarta.validation.constraints.NotNull;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class CreateAccountRequest {

    @Schema(description = "Must match the authenticated customer UUID.")
    @NotNull(message = "Customer ID is required.")
    private UUID customerId;
}
