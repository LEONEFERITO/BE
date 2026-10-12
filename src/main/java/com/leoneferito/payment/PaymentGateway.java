package com.leoneferito.payment;

import java.time.Instant;

public interface PaymentGateway {
   String name();
    String clientKey();
    boolean isReady();
    Payment approve(String key, String orderId, long amount);
    Payment fetch(String key);
    Payment cancel(String key, String orderId, String reason, String requestId, Long amount);

    enum Status {
        PAID,
        CANCELLED,
        PARTIAL_CANCELLED,
        OTHER
    }

    record Payment(String key, String orderId, Status status, String method, Long totalAmount, Long balanceAmount, Instant approvedAt) {
        public long refundedAmount() {
            return totalAmount == null || balanceAmount == null ? 0 : totalAmount - balanceAmount;
        }
    }
class PaymentException extends RuntimeException {
        private final String code;
        public PaymentException(String code, String message) {
            super(message);
            this.code = code;
        }
        public String getCode() {
            return code;
        }
    }
}