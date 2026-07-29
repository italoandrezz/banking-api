package com.italo.bankingapi.controller;

import com.italo.bankingapi.dto.account.AccountResponse;
import com.italo.bankingapi.dto.account.CreateAccountRequest;
import com.italo.bankingapi.service.AccountService;
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
public class AccountController {

    private final AccountService accountService;

    @PostMapping
    public ResponseEntity<AccountResponse> createAccount(@Valid @RequestBody CreateAccountRequest request) {
        AccountResponse response = accountService.createAccount(request);
        return  ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
    @GetMapping
    public ResponseEntity<List<AccountResponse>> findAllAccounts() {
        return ResponseEntity.ok(accountService.findAllAccounts());
    }
    @GetMapping("/{id}")
    public ResponseEntity<AccountResponse> findAccountById(@PathVariable UUID id) {
        AccountResponse response = accountService.findAccountById(id);
        return ResponseEntity.ok(response);
    }
}
