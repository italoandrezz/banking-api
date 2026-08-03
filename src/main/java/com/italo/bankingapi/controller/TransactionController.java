package com.italo.bankingapi.controller;

import com.italo.bankingapi.dto.transaction.TransactionResponse;
import com.italo.bankingapi.service.TransactionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/accounts")
@RequiredArgsConstructor
public class TransactionController {

    private final TransactionService transactionService;

    @GetMapping("/{id}/transactions")
    public ResponseEntity<List<TransactionResponse>> findTransactionsByAccountId(
            @PathVariable UUID id) {

        return ResponseEntity.ok(
                transactionService.findTransactionsByAccountId(id)
        );
    }
}