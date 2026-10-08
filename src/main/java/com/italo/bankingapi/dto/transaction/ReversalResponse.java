package com.italo.bankingapi.dto.transaction;

import com.italo.bankingapi.enums.TransactionType;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record ReversalResponse(UUID id, UUID originalTransactionId, TransactionType type,
                               UUID originAccountId, UUID destinationAccountId, BigDecimal amount,
                               UUID adminId, String reason, LocalDateTime createdAt) {}
