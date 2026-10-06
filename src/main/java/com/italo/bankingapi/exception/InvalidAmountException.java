package com.italo.bankingapi.exception;

public class InvalidAmountException extends BusinessException {
    public InvalidAmountException(String message) {
        super(message);
    }
}
