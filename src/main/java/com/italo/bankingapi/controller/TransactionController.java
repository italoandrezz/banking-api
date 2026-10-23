package com.italo.bankingapi.controller;

import com.italo.bankingapi.dto.error.ErrorResponse;
import com.italo.bankingapi.dto.transaction.TransactionPageResponse;
import com.italo.bankingapi.enums.TransactionType;
import org.springframework.format.annotation.DateTimeFormat;
import java.time.LocalDate;
import com.italo.bankingapi.service.TransactionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/accounts")
@RequiredArgsConstructor
@Tag(
        name = "Transactions",
        description = "Endpoints for transaction history"
)
@SecurityRequirement(name = "bearerAuth")
public class TransactionController {

    private final TransactionService transactionService;

    @Operation(
            summary = "List account transactions",
            description = "Returns own account transactions, including received transfers, ordered by createdAt DESC and id DESC. Dates are inclusive and use the stored timestamp calendar."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "400", description = "Invalid pagination, date range or transaction type",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Authentication required or invalid token",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Access denied",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(
                    responseCode = "200",
                    description = "Transactions retrieved successfully"
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Account not found",
                    content = @Content(
                            schema = @Schema(implementation = ErrorResponse.class)
                    )
            )
    })
    @GetMapping("/{id}/transactions")
    public ResponseEntity<TransactionPageResponse> findTransactionsByAccountId(
            @Parameter(description = "Account UUID")
            @PathVariable UUID id,
            @Parameter(description = "Zero-based page") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size, from 1 to 100") @RequestParam(defaultValue = "20") int size,
            @Parameter(description = "Inclusive start date, yyyy-MM-dd")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @Parameter(description = "Inclusive end date, yyyy-MM-dd")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) TransactionType type) {

        return ResponseEntity.ok(
                transactionService.findTransactionsByAccountId(id, page, size, startDate, endDate, type)
        );
    }
}
