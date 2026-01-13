package com.alpian.paymentservice.api.exception;

public class DuplicatePaymentException extends RuntimeException {
    public DuplicatePaymentException(String message) { super(message); }
}
