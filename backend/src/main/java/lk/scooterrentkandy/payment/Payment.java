package lk.scooterrentkandy.payment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import lk.scooterrentkandy.booking.Booking;
import lombok.Getter;
import lombok.Setter;

/**
 * SDS 2.2 Payment: exactly one per booking (unique booking_id). {@code amount} is what the booking is charged:
 * the initial charge until the rental ends, then the final total once the remaining balance is collected.
 */
@Entity
@Table(name = "payments")
@Getter
@Setter
public class Payment {

    /** SDS 3.2 PaymentMethod. */
    public enum Method { CARD, ONLINE }

    /** SDS 3.2 PaymentStatus. */
    public enum Status { PENDING, SUCCESS, FAILED, REFUNDED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "booking_id", unique = true)
    private Booking booking;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Method method;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(nullable = false)
    private String provider;

    /** The payment intent of the initial charge (SDS 2.2 stripePaymentId). */
    @Column(name = "stripe_payment_id")
    private String stripePaymentId;

    /** Saved payment method, used to charge the remaining balance when the rental ends. */
    @Column(name = "payment_method_ref")
    private String paymentMethodRef;

    /** Remaining balance still to collect after a failed completion charge; null when nothing is owed. */
    @Column(name = "balance_due", precision = 10, scale = 2)
    private BigDecimal balanceDue;

    /** Payment intent the customer is confirming to pay {@code balanceDue}, if any. */
    @Column(name = "balance_intent_id")
    private String balanceIntentId;

    @Column(name = "card_last4")
    private String cardLast4;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
