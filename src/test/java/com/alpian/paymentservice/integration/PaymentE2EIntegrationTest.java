package com.alpian.paymentservice.integration;

import com.alpian.paymentservice.api.dto.PaymentRequest;
import com.alpian.paymentservice.outbox.OutboxPublisherScheduler;
import com.alpian.paymentservice.persistence.entity.AccountEntity;
import com.alpian.paymentservice.persistence.entity.EventStatus;
import com.alpian.paymentservice.persistence.repository.AccountRepository;
import com.alpian.paymentservice.persistence.repository.OutboxRepository;
import com.alpian.paymentservice.service.PaymentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

class PaymentE2EIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    PaymentService paymentService;

    @Autowired
    AccountRepository accountRepository;

    @Autowired
    OutboxRepository outboxRepository;

    @Autowired
    OutboxPublisherScheduler publisherScheduler;

    @Test
    void should_create_payment_and_outbox_event() {
        UUID userId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();

        // seed account
        accountRepository.save(
                AccountEntity.builder()
                        .id(accountId)
                        .userId(userId)
                        .balance(BigDecimal.valueOf(500))
                        .baseCurrency("CHF")
                        .build()
        );

        PaymentRequest request = new PaymentRequest(
                accountId,
                BigDecimal.valueOf(100),
                "CHF",
                UUID.randomUUID()
        );

        paymentService.createPayment(request, userId);

        var events = outboxRepository.findAll();
        assertThat(events).hasSize(1);
        var outboxId = events.get(0).getId();
        assertThat(events.get(0).getEventStatus()).isEqualTo(EventStatus.PENDING);

        publisherScheduler.publishLoop();

        await()
                .atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> {
                    var refreshed = outboxRepository.findById(outboxId).orElseThrow();
                    assertThat(refreshed.getEventStatus()).isEqualTo(EventStatus.SENT);
                });
    }

    @Test
    void should_prevent_double_spending_with_concurrent_requests() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        outboxRepository.deleteAll();

        accountRepository.save(
                AccountEntity.builder()
                        .id(accountId)
                        .userId(userId)
                        .balance(new BigDecimal("100.00"))
                        .baseCurrency("CHF")
                        .build()
        );

        int threads = 10;
        BigDecimal amount = new BigDecimal("30.00");

        var executor = java.util.concurrent.Executors.newFixedThreadPool(threads);
        var startGate = new java.util.concurrent.CountDownLatch(1);
        var doneGate = new java.util.concurrent.CountDownLatch(threads);

        var success = new java.util.concurrent.atomic.AtomicInteger(0);
        var insufficient = new java.util.concurrent.atomic.AtomicInteger(0);
        var otherErrors = new java.util.concurrent.atomic.AtomicInteger(0);

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    startGate.await();

                    PaymentRequest req = new PaymentRequest(
                            accountId,
                            amount,
                            "CHF",
                            UUID.randomUUID() // unique paymentId each time
                    );

                    paymentService.createPayment(req, userId);
                    success.incrementAndGet();

                } catch (com.alpian.paymentservice.api.exception.InsufficientFundsException ex) {
                    insufficient.incrementAndGet();
                } catch (Exception ex) {
                    otherErrors.incrementAndGet();
                } finally {
                    doneGate.countDown();
                }
                return null;
            });
        }

        startGate.countDown();
        doneGate.await();
        executor.shutdown();

        assertThat(otherErrors.get()).isZero();

        // With 100 and debit 30 -> only 3 can succeed (90), 4th would fail
        assertThat(success.get()).isEqualTo(3);
        assertThat(insufficient.get()).isEqualTo(7);

        // balance must be 10.00
        AccountEntity refreshed = accountRepository.findById(accountId).orElseThrow();
        assertThat(refreshed.getBalance()).isEqualByComparingTo("10.00");

        // outbox events should equal successful payments (one per completed payment)
        assertThat(outboxRepository.findAll()).hasSize(3);
    }

}

