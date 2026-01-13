package com.alpian.paymentservice.api.dto;

import com.alpian.paymentservice.persistence.entity.PaymentEntity;

import java.util.UUID;

public record PaymentResponse(
        UUID paymentId,
        String status
) {
    public static PaymentResponse from(PaymentEntity paymentEntity) {
        return new PaymentResponse(
                paymentEntity.getPaymentId(),
                paymentEntity.getStatus().name()
        );
    }
}

