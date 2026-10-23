package com.italo.bankingapi.exception;

public class ConflictException extends BusinessException{

    public ConflictException(String message) {
        super(message);
    }
}
