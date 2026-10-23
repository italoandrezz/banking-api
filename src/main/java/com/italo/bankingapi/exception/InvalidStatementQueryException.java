package com.italo.bankingapi.exception;

public class InvalidStatementQueryException extends BusinessException {
    public InvalidStatementQueryException(String message) {
        super(message);
    }
}
