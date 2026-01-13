package com.alpian.paymentservice.api.controller;

import com.alpian.paymentservice.api.dto.PaymentRequest;
import com.alpian.paymentservice.api.dto.PaymentResponse;
import com.alpian.paymentservice.service.PaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    @PostMapping
    public ResponseEntity<PaymentResponse> createPayment(
            @RequestHeader("X-User-Id") UUID userId,
            @Valid @RequestBody PaymentRequest request
    ) {
        PaymentResponse result = paymentService.createPayment(request, userId);

        return ResponseEntity.ok(
                new PaymentResponse(
                        result.paymentId(),
                        result.status()
                )
        );
    }
}