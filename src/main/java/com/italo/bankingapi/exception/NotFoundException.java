package com.italo.bankingapi.exception;

public class NotFoundException extends BusinessException {

    public NotFoundException(String message) {
        super(message);
    }
}
