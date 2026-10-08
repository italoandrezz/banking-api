package com.italo.bankingapi.controller;

import com.italo.bankingapi.dto.transaction.ReversalRequest;
import com.italo.bankingapi.dto.transaction.ReversalResponse;
import com.italo.bankingapi.dto.error.ErrorResponse;
import com.italo.bankingapi.service.TransactionReversalService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/admin/transactions")
@RequiredArgsConstructor
@Tag(name = "Administrative transactions")
@SecurityRequirement(name = "bearerAuth")
public class AdminTransactionController {
    private final TransactionReversalService service;

    @PostMapping("/{id}/reversal")
    @Operation(summary = "Fully reverse a transaction", description = "ADMIN only. Creates an inverse movement with audit metadata. Accounts must be ACTIVE. Original transaction ID prevents duplicate reversals; retries return 409.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Reversal committed"),
            @ApiResponse(responseCode = "400", description = "Invalid UUID or reason", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Authentication required", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "ADMIN required", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Transaction not found", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Already reversed, reversal of reversal, inactive account, insufficient balance or balance limit", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<ReversalResponse> reverse(@PathVariable UUID id, @Valid @RequestBody ReversalRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.reverse(id, request));
    }
}
