package com.alpian.paymentservice.kafka;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

    public record PaymentNotification(
            UUID paymentId,           // ID unique de la transaction (idempotency key)
            UUID accountId,           // Compte depuis lequel le paiement est effectué
            BigDecimal amount,        // Montant du paiement
            String currency,          // Devise du paiement
            Instant createdAt         // Timestamp de création du paiement
    ) {}