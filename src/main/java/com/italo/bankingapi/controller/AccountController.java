package com.italo.bankingapi.controller;

import com.italo.bankingapi.dto.account.*;
import com.italo.bankingapi.dto.error.ErrorResponse;
import com.italo.bankingapi.service.AccountService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/accounts")
@RequiredArgsConstructor
@Tag(
        name = "Accounts",
        description = "Endpoints for bank account management"
)
@SecurityRequirement(name = "bearerAuth")
public class AccountController {

    private final AccountService accountService;

    @Operation(summary = "Block own account", description = "Changes ACTIVE to BLOCKED. Balance and history are preserved.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Account blocked"),
            @ApiResponse(responseCode = "400", description = "Invalid account UUID"),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "403", description = "Account belongs to another customer"),
            @ApiResponse(responseCode = "404", description = "Account not found"),
            @ApiResponse(responseCode = "409", description = "Account is not ACTIVE")
    })
    @PatchMapping("/{id}/block")
    public ResponseEntity<AccountResponse> block(@PathVariable UUID id) {
        return ResponseEntity.ok(accountService.block(id));
    }

    @Operation(summary = "Unblock own account", description = "Changes BLOCKED to ACTIVE. CLOSED accounts cannot be reopened.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Account unblocked"),
            @ApiResponse(responseCode = "400", description = "Invalid account UUID"),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "403", description = "Account belongs to another customer"),
            @ApiResponse(responseCode = "404", description = "Account not found"),
            @ApiResponse(responseCode = "409", description = "Account is not BLOCKED")
    })
    @PatchMapping("/{id}/unblock")
    public ResponseEntity<AccountResponse> unblock(@PathVariable UUID id) {
        return ResponseEntity.ok(accountService.unblock(id));
    }

    @Operation(
            summary = "Create a new account",
            description = "Creates an account for the authenticated customer. customerId must match the authenticated customer UUID."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "401", description = "Authentication required or invalid token",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Access denied",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(
                    responseCode = "201",
                    description = "Account created successfully"
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Invalid request data",
                    content = @Content(
                            schema = @Schema(implementation = ErrorResponse.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Customer not found",
                    content = @Content(
                            schema = @Schema(implementation = ErrorResponse.class)
                    )
            )
    })
    @PostMapping
    public ResponseEntity<AccountResponse> createAccount(@Valid @RequestBody CreateAccountRequest request) {
        AccountResponse response = accountService.createAccount(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
    @Operation(
            summary = "List own accounts",
            description = "Returns only accounts belonging to the authenticated customer."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "401", description = "Authentication required or invalid token",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Access denied",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(
                    responseCode = "200",
                    description = "Accounts retrieved successfully"
            )
    })
    @GetMapping
    public ResponseEntity<List<AccountResponse>> findAllAccounts() {
        return ResponseEntity.ok(accountService.findAllAccounts());
    }
    @Operation(
            summary = "Find account by ID",
            description = "Returns an account owned by the authenticated customer."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "401", description = "Authentication required or invalid token",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Access denied",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(
                    responseCode = "200",
                    description = "Account found successfully"
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Account not found",
                    content = @Content(
                            schema = @Schema(implementation = ErrorResponse.class)
                    )
            )
    })
    @GetMapping("/{id}")
    public ResponseEntity<AccountResponse> findAccountById(
            @Parameter(description = "Account UUID")
            @PathVariable UUID id) {
        AccountResponse response = accountService.findAccountById(id);
        return ResponseEntity.ok(response);
    }
    @Operation(
            summary = "Deposit money",
            description = "Deposits money only into an ACTIVE account owned by the authenticated customer."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "401", description = "Authentication required or invalid token",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Access denied",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(
                    responseCode = "200",
                    description = "Deposit completed successfully"
            ),
            @ApiResponse(responseCode = "409", description = "Account is not ACTIVE or resulting balance exceeds the limit",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(
                    responseCode = "400",
                    description = "Invalid request data",
                    content = @Content(
                            schema = @Schema(implementation = ErrorResponse.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Account not found",
                    content = @Content(
                            schema = @Schema(implementation = ErrorResponse.class)
                    )
            )
    })
    @PostMapping("/{id}/deposit")
    public ResponseEntity<AccountResponse> deposit(
            @Parameter(description = "Account UUID")
            @PathVariable UUID id,
            @Valid @RequestBody DepositRequest request) {
        AccountResponse response = accountService.deposit(id, request);
        return ResponseEntity.ok(response);
    }
    @Operation(
            summary = "Withdraw money",
            description = "Withdraws money only from an ACTIVE account owned by the authenticated customer."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "401", description = "Authentication required or invalid token",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Access denied",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(
                    responseCode = "200",
                    description = "Withdrawal completed successfully"
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Invalid request data",
                    content = @Content(
                            schema = @Schema(implementation = ErrorResponse.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Account not found",
                    content = @Content(
                            schema = @Schema(implementation = ErrorResponse.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "409",
                    description = "Insufficient balance or account is not ACTIVE",
                    content = @Content(
                            schema = @Schema(implementation = ErrorResponse.class)
                    )
            )
    })
    @PostMapping("/{id}/withdraw")
    public ResponseEntity<AccountResponse> withdraw(
            @Parameter(description = "Account UUID")
            @PathVariable UUID id,
            @Valid @RequestBody WithdrawRequest request) {
        AccountResponse response = accountService.withdraw(id, request);
        return ResponseEntity.ok(response);
    }
    @Operation(
            summary = "Transfer money",
            description = "Both accounts must be ACTIVE. The source must belong to the authenticated customer; the destination may belong to another customer."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "401", description = "Authentication required or invalid token",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Access denied",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(
                    responseCode = "200",
                    description = "Transfer completed successfully"
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Invalid request data",
                    content = @Content(
                            schema = @Schema(implementation = ErrorResponse.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Source or destination account not found",
                    content = @Content(
                            schema = @Schema(implementation = ErrorResponse.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "409",
                    description = "Insufficient balance or invalid transfer operation",
                    content = @Content(
                            schema = @Schema(implementation = ErrorResponse.class)
                    )
            )
    })
    @PostMapping("/transfer")
    public ResponseEntity<AccountResponse> transfer(@Valid @RequestBody TransferRequest request) {
        AccountResponse response = accountService.transfer(request);
        return ResponseEntity.ok(response);
    }
}
