package com.alpian.paymentservice.api.exception;

public class AccountNotFoundOrForbiddenException extends RuntimeException {
    public AccountNotFoundOrForbiddenException(String message) {
        super(message);
    }
}
