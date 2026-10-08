package com.italo.bankingapi.dto.transaction;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ReversalRequest(
        @NotBlank(message = "Reversal reason is required.")
        @Size(max = 255, message = "Reversal reason must not exceed 255 characters.") String reason) {}
