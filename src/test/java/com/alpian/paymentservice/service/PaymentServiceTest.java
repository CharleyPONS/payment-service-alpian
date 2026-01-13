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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PaymentServiceTest {

    private AccountRepository accountRepository;
    private PaymentRepository paymentRepository;
    private OutboxRepository outboxRepository;
    private JsonService jsonService;

    private PaymentService paymentService;

    @BeforeEach
    void setUp() {
        accountRepository = mock(AccountRepository.class);
        paymentRepository = mock(PaymentRepository.class);
        outboxRepository = mock(OutboxRepository.class);
        jsonService = mock(JsonService.class);

        paymentService = new PaymentService(accountRepository, paymentRepository, outboxRepository, jsonService);
    }

    @Test
    void createPayment_success_locksAccount_debits_createsPaymentAndOutbox() {
        UUID accountId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();

        PaymentRequest req = new PaymentRequest(accountId, new BigDecimal("80.00"), "CHF", paymentId);

        AccountEntity lockedAccount = AccountEntity.builder()
                .id(accountId)
                .userId(userId)
                .balance(new BigDecimal("500.00"))
                .build();
        when(accountRepository.findForUpdate(accountId, userId)).thenReturn(Optional.of(lockedAccount));
        when(accountRepository.save(any(AccountEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        PaymentEntity persistedPayment = PaymentEntity.builder()
                .id(UUID.randomUUID())
                .accountId(accountId)
                .amount(req.amount())
                .currency(req.currency())
                .paymentId(paymentId)
                .status(PaymentStatus.PENDING)
                .createdAt(Instant.now())
                .build();
        when(paymentRepository.saveAndFlush(any(PaymentEntity.class))).thenReturn(persistedPayment);
        when(paymentRepository.save(any(PaymentEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        when(jsonService.toJson(any(PaymentNotification.class))).thenReturn("{\"ok\":true}");

        ArgumentCaptor<OutboxEventEntity> outboxCaptor = ArgumentCaptor.forClass(OutboxEventEntity.class);
        when(outboxRepository.save(outboxCaptor.capture())).thenAnswer(inv -> inv.getArgument(0));

        PaymentResponse resp = paymentService.createPayment(req, userId);

        assertThat(resp.paymentId()).isEqualTo(paymentId);
        assertThat(resp.status()).isEqualTo(PaymentStatus.COMPLETED.name());

        // lock
        verify(accountRepository).findForUpdate(accountId, userId);

        // debit persisted
        ArgumentCaptor<AccountEntity> accountSaveCaptor = ArgumentCaptor.forClass(AccountEntity.class);
        verify(accountRepository).save(accountSaveCaptor.capture());
        assertThat(accountSaveCaptor.getValue().getBalance()).isEqualByComparingTo("420.00");

        // payment completed
        ArgumentCaptor<PaymentEntity> paymentSaveCaptor = ArgumentCaptor.forClass(PaymentEntity.class);
        verify(paymentRepository).save(paymentSaveCaptor.capture());
        assertThat(paymentSaveCaptor.getValue().getStatus()).isEqualTo(PaymentStatus.COMPLETED);

        // outbox event created
        OutboxEventEntity outbox = outboxCaptor.getValue();
        assertThat(outbox.getEventType()).isEqualTo(EventType.PAYMENT_CREATED);
        assertThat(outbox.getEventStatus()).isEqualTo(EventStatus.PENDING);
        assertThat(outbox.getAggregateType()).isEqualTo("PAYMENT");
        assertThat(outbox.getAggregateId()).isEqualTo(persistedPayment.getId());
        assertThat(outbox.getPayload()).isEqualTo("{\"ok\":true}");

        verify(outboxRepository).save(any(OutboxEventEntity.class));
        verifyNoMoreInteractions(outboxRepository);
    }

    @Test
    void createPayment_accountNotFound_throws_andDoesNotInsertPaymentOrOutbox() {
        UUID accountId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();

        PaymentRequest req = new PaymentRequest(accountId, new BigDecimal("10.00"), "CHF", paymentId);

        when(accountRepository.findForUpdate(accountId, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> paymentService.createPayment(req, userId))
                .isInstanceOf(AccountNotFoundOrForbiddenException.class);

        verify(accountRepository).findForUpdate(accountId, userId);

        verifyNoInteractions(paymentRepository);
        verifyNoInteractions(outboxRepository);
        verifyNoInteractions(jsonService);
    }

    @Test
    void createPayment_duplicate_throws_andDoesNotDebitCompleteOrCreateOutbox() {
        UUID accountId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();

        PaymentRequest req = new PaymentRequest(accountId, new BigDecimal("10.00"), "CHF", paymentId);

        AccountEntity lockedAccount = AccountEntity.builder()
                .id(accountId)
                .userId(userId)
                .balance(new BigDecimal("500.00"))
                .build();
        when(accountRepository.findForUpdate(accountId, userId)).thenReturn(Optional.of(lockedAccount));

        when(paymentRepository.saveAndFlush(any(PaymentEntity.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate"));

        assertThatThrownBy(() -> paymentService.createPayment(req, userId))
                .isInstanceOf(DuplicatePaymentException.class);

        verify(accountRepository).findForUpdate(accountId, userId);
        verify(paymentRepository).saveAndFlush(any(PaymentEntity.class));

        // no debit persisted, no completion, no outbox
        verify(accountRepository, never()).save(any());
        verify(paymentRepository, never()).save(any());
        verifyNoInteractions(outboxRepository);
        verifyNoInteractions(jsonService);
    }

    @Test
    void createPayment_insufficientFunds_throws_andDoesNotCompletePaymentOrCreateOutbox() {
        UUID accountId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();

        PaymentRequest req = new PaymentRequest(accountId, new BigDecimal("80.00"), "CHF", paymentId);

        AccountEntity lockedAccount = AccountEntity.builder()
                .id(accountId)
                .userId(userId)
                .balance(new BigDecimal("50.00")) // < 80
                .build();
        when(accountRepository.findForUpdate(accountId, userId)).thenReturn(Optional.of(lockedAccount));

        PaymentEntity persistedPayment = PaymentEntity.builder()
                .id(UUID.randomUUID())
                .accountId(accountId)
                .amount(req.amount())
                .currency(req.currency())
                .paymentId(paymentId)
                .status(PaymentStatus.PENDING)
                .createdAt(Instant.now())
                .build();
        when(paymentRepository.saveAndFlush(any(PaymentEntity.class))).thenReturn(persistedPayment);

        assertThatThrownBy(() -> paymentService.createPayment(req, userId))
                .isInstanceOf(InsufficientFundsException.class);

        verify(accountRepository).findForUpdate(accountId, userId);
        verify(paymentRepository).saveAndFlush(any(PaymentEntity.class));

        // no debit persisted, no completion, no outbox
        verify(accountRepository, never()).save(any());
        verify(paymentRepository, never()).save(any());
        verifyNoInteractions(outboxRepository);
        verifyNoInteractions(jsonService);
    }
}
