package com.alpian.paymentservice.service;

import com.alpian.paymentservice.api.dto.PaymentRequest;
import com.alpian.paymentservice.api.dto.PaymentResponse;
import com.alpian.paymentservice.api.exception.AccountNotFoundOrForbiddenException;
import com.alpian.paymentservice.api.exception.DuplicatePaymentException;
import com.alpian.paymentservice.api.exception.InsufficientFundsException;
import com.alpian.paymentservice.kafka.PaymentNotification;
import com.alpian.paymentservice.persistence.entity.*;
import com.alpian.paymentservice.persistence.repository.AccountRepository;
import com.alpian.paymentservice.persistence.repository.OutboxRepository;
import com.alpian.paymentservice.persistence.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final AccountRepository accountRepository;
    private final PaymentRepository paymentRepository;
    private final OutboxRepository outboxRepository;
    private final JsonService jsonService;

    @Transactional
    public PaymentResponse createPayment(PaymentRequest request, UUID userId) {
        final Instant now = Instant.now();

        logRequest(request, userId);

        AccountEntity account = lockAccountOrThrow(request.accountId(), userId);

        PaymentEntity payment = createPaymentIntentionOrThrow(request, now);

        debitOrThrow(account, request.amount(), request.accountId(), userId);

        markPaymentCompleted(payment);

        createOutboxEvent(payment, now);

        return PaymentResponse.from(payment);
    }

    private void logRequest(PaymentRequest request, UUID userId) {
        log.info("Create payment requested: accountId={}, userId={}, paymentId={}, amount={}, currency={}",
                request.accountId(), userId, request.paymentId(), request.amount(), request.currency());
    }

    /**
     * Step 1: Lock account row (SELECT ... FOR UPDATE) to serialize concurrent debits.
     */
    private AccountEntity lockAccountOrThrow(UUID accountId, UUID userId) {
        return accountRepository.findForUpdate(accountId, userId)
                .orElseThrow(() -> {
                    log.warn("Account not found or not owned by user: accountId={}, userId={}", accountId, userId);
                    return new AccountNotFoundOrForbiddenException("Account not found or not owned by user");
                });
    }

    /**
     * Step 2: Insert payment "pending" to leverage DB uniqueness (idempotency) and persist transaction.
     * If duplicate -> we deliberately fail fast.
     */
    private PaymentEntity createPaymentIntentionOrThrow(PaymentRequest request, Instant now) {
        try {
            PaymentEntity payment = PaymentEntity.builder()
                    .accountId(request.accountId())
                    .amount(request.amount())
                    .currency(request.currency())
                    .paymentId(request.paymentId())
                    .status(PaymentStatus.PENDING)
                    .createdAt(now)
                    .build();

            PaymentEntity saved = paymentRepository.saveAndFlush(payment);

            log.info("Payment created (PENDING): internalId={}, accountId={}, paymentId={}",
                    saved.getId(), saved.getAccountId(), saved.getPaymentId());

            return saved;
        } catch (DataIntegrityViolationException ex) {
            log.warn("Duplicate payment request rejected: accountId={}, paymentId={}",
                    request.accountId(), request.paymentId(), ex);
            throw new DuplicatePaymentException("Duplicate payment request detected");
        }
    }

    /**
     * Step 3: Check business rules + apply debit on the locked row.
     * Easy to extend later if buisness logic need
     */
    private void debitOrThrow(AccountEntity account, java.math.BigDecimal amount, UUID accountId, UUID userId) {
        if (account.getBalance().compareTo(amount) < 0) {
            log.warn("Debit rejected (insufficient funds): accountId={}, userId={}, balance={}, amount={}",
                    accountId, userId, account.getBalance(), amount);
            throw new InsufficientFundsException("Insufficient funds");
        }

        account.setBalance(account.getBalance().subtract(amount));
        accountRepository.save(account);

        log.info("Account debited successfully: accountId={}, amount={}, newBalance={}",
                accountId, amount, account.getBalance());
    }

    /**
     * Step 4: Finalize payment.
     */
    private void markPaymentCompleted(PaymentEntity payment) {
        payment.setStatus(PaymentStatus.COMPLETED);
        paymentRepository.save(payment);

        log.info("Payment completed: internalId={}, accountId={}, paymentId={}",
                payment.getId(), payment.getAccountId(), payment.getPaymentId());
    }

    /**
     * Step 5: Transactional outbox write (Worker will poll + process outbox table).
     */
    private void createOutboxEvent(PaymentEntity payment, Instant now) {
        PaymentNotification notification = new PaymentNotification(
                payment.getPaymentId(),
                payment.getAccountId(),
                payment.getAmount(),
                payment.getCurrency(),
                payment.getCreatedAt()
        );

        String payloadJson = jsonService.toJson(notification);

        OutboxEventEntity event = OutboxEventEntity.builder()
                .aggregateType("PAYMENT")
                .aggregateId(payment.getId())
                .eventType(EventType.PAYMENT_CREATED)
                .eventStatus(EventStatus.PENDING)
                .payload(payloadJson)
                .createdAt(now)
                .build();

        outboxRepository.save(event);

        log.info("Outbox event created: outboxId={}, paymentInternalId={}, eventType={}",
                event.getId(), payment.getId(), event.getEventType());
    }
}
