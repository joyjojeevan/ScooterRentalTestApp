package lk.scooterrentkandy.payment;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** No request type in this API carries card data: cards are confirmed with the payment provider, not with us. */
public final class PaymentDtos {

    private PaymentDtos() {
    }

    /** What the browser needs to confirm a payment intent with the provider (Stripe.js confirmCardPayment). */
    public record PaymentIntentResponse(
            UUID paymentId,
            UUID bookingId,
            String purpose,
            String intentId,
            String clientSecret,
            BigDecimal amount,
            String currency,
            String provider) {
    }

    /** Which client-side payment library to load; {@code publishableKey} is null for the mock provider. */
    public record PaymentConfigResponse(String provider, String publishableKey, String currency) {
    }

    public record PaymentResponse(
            UUID id,
            UUID bookingId,
            String bookingReference,
            String customerName,
            BigDecimal amount,
            Payment.Method method,
            Payment.Status status,
            String provider,
            String stripePaymentId,
            String cardLast4,
            BigDecimal balanceDue,
            String failureReason,
            LocalDateTime paidAt,
            LocalDateTime createdAt,
            /** Provider calls for this payment; included for a single booking, omitted (null) in lists. */
            List<TransactionResponse> transactions) {

        public static PaymentResponse from(Payment p, boolean includeBooking) {
            return from(p, includeBooking, null);
        }

        public static PaymentResponse from(Payment p, boolean includeBooking, List<TransactionResponse> transactions) {
            var b = p.getBooking();
            return new PaymentResponse(p.getId(), b.getId(), includeBooking ? b.getReference() : null,
                    includeBooking ? b.getCustomer().getFullName() : null, p.getAmount(), p.getMethod(),
                    p.getStatus(), p.getProvider(), p.getStripePaymentId(), p.getCardLast4(), p.getBalanceDue(),
                    p.getFailureReason(), p.getPaidAt(), p.getCreatedAt(), transactions);
        }
    }

    public record TransactionResponse(UUID id, PaymentTransaction.Kind kind, String providerRef, BigDecimal amount,
            PaymentTransaction.Status status, String failureReason, LocalDateTime createdAt) {

        static TransactionResponse from(PaymentTransaction t) {
            return new TransactionResponse(t.getId(), t.getKind(), t.getProviderRef(), t.getAmount(), t.getStatus(),
                    t.getFailureReason(), t.getCreatedAt());
        }
    }
}
