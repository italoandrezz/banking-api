package com.italo.bankingapi.controller;

import com.italo.bankingapi.dto.error.ErrorResponse;
import com.italo.bankingapi.dto.transaction.TransactionResponse;
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

import java.util.List;
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
            description = "Returns all transactions related to the provided account UUID."
    )
    @ApiResponses({
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
    public ResponseEntity<List<TransactionResponse>> findTransactionsByAccountId(
            @Parameter(description = "Account UUID")
            @PathVariable UUID id) {

        return ResponseEntity.ok(
                transactionService.findTransactionsByAccountId(id)
        );
    }
}