package com.italo.bankingapi.exception;

public class InvalidIdempotencyKeyException extends BusinessException {
    public InvalidIdempotencyKeyException(String message) { super(message); }
}
