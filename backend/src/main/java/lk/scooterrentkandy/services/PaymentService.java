package lk.scooterrentkandy.services;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lk.scooterrentkandy.config.AppProperties;
import lk.scooterrentkandy.dto.PaymentDtos.PaymentConfigResponse;
import lk.scooterrentkandy.dto.PaymentDtos.PaymentIntentResponse;
import lk.scooterrentkandy.dto.PaymentDtos.PaymentResponse;
import lk.scooterrentkandy.exception.ApiException;
import lk.scooterrentkandy.mappers.PaymentMapper;
import lk.scooterrentkandy.models.Booking;
import lk.scooterrentkandy.models.BookingStatus;
import lk.scooterrentkandy.models.Payment;
import lk.scooterrentkandy.models.PaymentTransaction;
import lk.scooterrentkandy.repository.BookingRepository;
import lk.scooterrentkandy.repository.PaymentRepository;
import lk.scooterrentkandy.repository.PaymentTransactionRepository;
import lk.scooterrentkandy.security.AuthUser;
import lk.scooterrentkandy.services.PaymentEvents.BalancePaid;
import lk.scooterrentkandy.services.PaymentEvents.InitialPaymentSucceeded;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * One Payment per booking (SDS 2.2, decision E4), following the Stripe PaymentIntent flow (SDS 4.2, E6):
 * the initial charge (1 hour + 1 gear day) is an intent the customer confirms in the browser; the webhook
 * activates the booking. When the rental ends, the remaining balance is charged to the saved payment method
 * and added to the same row. If that fails, the balance stays due on the row and can be paid online.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    static final String INITIAL = "INITIAL";
    static final String BALANCE = "BALANCE";

    private final PaymentRepository payments;
    private final PaymentTransactionRepository transactions;
    private final BookingRepository bookings;
    private final PaymentGateway gateway;
    private final PricingService pricing;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final String currency;

    public PaymentService(PaymentRepository payments, PaymentTransactionRepository transactions,
            BookingRepository bookings, PaymentGateway gateway, PricingService pricing,
            ApplicationEventPublisher events, Clock clock, AppProperties props) {
        this.payments = payments;
        this.transactions = transactions;
        this.bookings = bookings;
        this.gateway = gateway;
        this.pricing = pricing;
        this.events = events;
        this.clock = clock;
        this.currency = props.billing().currency();
    }

    public PaymentConfigResponse config() {
        return new PaymentConfigResponse(gateway.name(), null, currency);
    }

    /** SDS 4.2 steps 7-10: create the payment intent for the initial charge and hand its client secret back. */
    @Transactional
    public PaymentIntentResponse createInitialIntent(UUID bookingId, AuthUser user) {
        Booking b = bookings.findByIdForUpdate(bookingId).orElseThrow(() -> ApiException.notFound("Booking"));
        requireOwner(b, user);
        if (b.getStatus() != BookingStatus.PENDING) {
            throw ApiException.conflict("This booking is not awaiting payment (status " + b.getStatus() + ")");
        }
        if (!b.isContractSigned()) {
            throw ApiException.badRequest("Please sign the rental agreement before paying");
        }
        BigDecimal amount = pricing.initialCharge(b.getScooter(), PricingService.selectionsOf(b)).total();

        Payment p = payments.findByBookingId(bookingId).orElse(null);
        if (p == null) {
            p = new Payment();
            p.setBooking(b);
            p.setMethod(Payment.Method.CARD);
            p.setProvider(gateway.name());
        } else if (p.getStatus() == Payment.Status.SUCCESS || p.getStatus() == Payment.Status.REFUNDED) {
            throw ApiException.conflict("This booking has already been paid");
        } else if (p.getStripePaymentId() != null) {
            gateway.cancelIntent(p.getStripePaymentId());
            settle(p, p.getStripePaymentId(), PaymentTransaction.Status.FAILED, "Replaced by a new payment attempt");
        }
        PaymentGateway.Intent intent = gateway.createIntent(amount, currency,
                "Scooter Rent Kandy booking " + b.getReference(), metadata(b, INITIAL));
        p.setAmount(amount);
        p.setStatus(Payment.Status.PENDING);
        p.setStripePaymentId(intent.id());
        p.setFailureReason(null);
        payments.save(p);
        record(p, PaymentTransaction.Kind.INITIAL, intent.id(), amount, PaymentTransaction.Status.PENDING, null);
        return new PaymentIntentResponse(p.getId(), b.getId(), INITIAL, intent.id(), intent.clientSecret(), amount,
                currency, gateway.name());
    }

    /** Pay a remaining balance online, after the off-session charge at completion failed. */
    @Transactional
    public PaymentIntentResponse createBalanceIntent(UUID bookingId, AuthUser user) {
        Booking b = bookings.findByIdForUpdate(bookingId).orElseThrow(() -> ApiException.notFound("Booking"));
        requireOwner(b, user);
        Payment p = payments.findByBookingId(bookingId).orElse(null);
        if (b.getStatus() != BookingStatus.ACTIVE || p == null || p.getBalanceDue() == null) {
            throw ApiException.conflict("This booking has no balance due");
        }
        if (p.getBalanceIntentId() != null) {
            gateway.cancelIntent(p.getBalanceIntentId());
            settle(p, p.getBalanceIntentId(), PaymentTransaction.Status.FAILED, "Replaced by a new payment attempt");
        }
        PaymentGateway.Intent intent = gateway.createIntent(p.getBalanceDue(), currency,
                "Scooter Rent Kandy balance " + b.getReference(), metadata(b, BALANCE));
        p.setBalanceIntentId(intent.id());
        record(p, PaymentTransaction.Kind.BALANCE, intent.id(), p.getBalanceDue(), PaymentTransaction.Status.PENDING,
                null);
        return new PaymentIntentResponse(p.getId(), b.getId(), BALANCE, intent.id(), intent.clientSecret(),
                p.getBalanceDue(), currency, gateway.name());
    }

    /** SDS 4.2 step 12: the provider's webhook. Idempotent: repeated or stale deliveries are ignored. */
    @Transactional
    public void handleWebhook(String payload, String signature) {
        PaymentGateway.Event e;
        try {
            e = gateway.parseWebhook(payload, signature);
        } catch (IllegalArgumentException ex) {
            throw ApiException.badRequest("Invalid webhook: " + ex.getMessage());
        }
        if (!PaymentGateway.Event.SUCCEEDED.equals(e.type()) && !PaymentGateway.Event.FAILED.equals(e.type())) {
            return;
        }
        UUID bookingId = parseId(e.metadata().get("bookingId"));
        Payment p = bookingId == null ? null : payments.findByBookingIdForUpdate(bookingId).orElse(null);
        if (p == null) {
            log.warn("Webhook for unknown booking {} ignored", e.metadata().get("bookingId"));
            return;
        }
        String purpose = e.metadata().get("purpose");
        if (INITIAL.equals(purpose) && e.intentId().equals(p.getStripePaymentId())) {
            onInitial(p, e);
        } else if (BALANCE.equals(purpose) && e.intentId().equals(p.getBalanceIntentId())) {
            onBalance(p, e);
        } else {
            log.info("Stale webhook for intent {} ignored", e.intentId());
        }
    }

    private void onInitial(Payment p, PaymentGateway.Event e) {
        if (p.getStatus() != Payment.Status.PENDING && p.getStatus() != Payment.Status.FAILED) {
            return; // already handled
        }
        if (e.succeeded()) {
            p.setStatus(Payment.Status.SUCCESS);
            p.setPaidAt(LocalDateTime.now(clock));
            p.setPaymentMethodRef(e.paymentMethodRef());
            p.setCardLast4(e.cardLast4());
            p.setFailureReason(null);
            settle(p, e.intentId(), PaymentTransaction.Status.SUCCESS, null);
            events.publishEvent(new InitialPaymentSucceeded(p.getBooking().getId()));
        } else {
            p.setStatus(Payment.Status.FAILED);
            p.setFailureReason(e.failureReason());
            settle(p, e.intentId(), PaymentTransaction.Status.FAILED, e.failureReason());
        }
    }

    private void onBalance(Payment p, PaymentGateway.Event e) {
        if (p.getBalanceDue() == null) {
            return; // already handled
        }
        if (e.succeeded()) {
            settle(p, e.intentId(), PaymentTransaction.Status.SUCCESS, null);
            p.setAmount(p.getAmount().add(p.getBalanceDue()));
            p.setBalanceDue(null);
            p.setBalanceIntentId(null);
            p.setPaidAt(LocalDateTime.now(clock));
            p.setPaymentMethodRef(e.paymentMethodRef());
            p.setCardLast4(e.cardLast4());
            p.setFailureReason(null);
            events.publishEvent(new BalancePaid(p.getBooking().getId()));
        } else {
            p.setFailureReason(e.failureReason());
            settle(p, e.intentId(), PaymentTransaction.Status.FAILED, e.failureReason());
        }
    }

    /**
     * SDS 4.3 step 11 chargeRemainingBalance: charges {@code remaining} to the saved payment method and adds it
     * to the booking's payment. On failure the balance is recorded as due on the same row. Returns success.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean chargeRemainingBalance(Booking b, BigDecimal remaining) {
        Payment p = paid(b);
        PaymentGateway.ChargeResult r = gateway.chargeSaved(p.getPaymentMethodRef(), remaining, currency,
                "Scooter Rent Kandy balance " + b.getReference(), "balance-" + b.getId() + "-" + UUID.randomUUID());
        record(p, PaymentTransaction.Kind.BALANCE, r.reference(), remaining,
                r.success() ? PaymentTransaction.Status.SUCCESS : PaymentTransaction.Status.FAILED, r.failureReason());
        if (r.success()) {
            p.setAmount(p.getAmount().add(remaining));
            p.setBalanceDue(null);
            if (p.getBalanceIntentId() != null) {
                gateway.cancelIntent(p.getBalanceIntentId());
                settle(p, p.getBalanceIntentId(), PaymentTransaction.Status.FAILED, "Balance paid with the saved card");
                p.setBalanceIntentId(null);
            }
            p.setPaidAt(LocalDateTime.now(clock));
            p.setFailureReason(null);
            return true;
        }
        p.setBalanceDue(remaining);
        p.setFailureReason(r.failureReason());
        return false;
    }

    /**
     * Only for bookings paid under the earlier prepaid/deposit model, which can have paid more than the final
     * cost: the difference is refunded and the payment amount becomes the final cost.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void refundExcess(Booking b, BigDecimal excess) {
        Payment p = paid(b);
        PaymentGateway.ChargeResult r = gateway.refund(p.getStripePaymentId(), excess,
                "Final cost below amount paid for " + b.getReference());
        record(p, PaymentTransaction.Kind.REFUND, r.reference(), excess,
                r.success() ? PaymentTransaction.Status.SUCCESS : PaymentTransaction.Status.FAILED, r.failureReason());
        if (r.success()) {
            p.setAmount(p.getAmount().subtract(excess));
            p.setFailureReason(null);
        } else {
            p.setFailureReason("Refund of " + currency + " " + excess + " failed: " + r.failureReason());
        }
    }

    /** Refunds the whole payment (a permitted cancellation). Returns the payment, or null if nothing was paid. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Payment refundInFull(Booking b, String reason) {
        Payment p = payments.findByBookingIdForUpdate(b.getId()).orElse(null);
        if (p == null || p.getStatus() != Payment.Status.SUCCESS) {
            return null;
        }
        PaymentGateway.ChargeResult r = gateway.refund(p.getStripePaymentId(), p.getAmount(), reason);
        if (!r.success()) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Refund failed: " + r.failureReason());
        }
        record(p, PaymentTransaction.Kind.REFUND, r.reference(), p.getAmount(), PaymentTransaction.Status.SUCCESS, null);
        p.setStatus(Payment.Status.REFUNDED);
        return p;
    }

    /** A booking cancelled before payment: close its open intent so it can no longer be paid. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void cancelOpenIntent(Booking b) {
        payments.findByBookingIdForUpdate(b.getId()).ifPresent(p -> {
            if (p.getStatus() == Payment.Status.PENDING) {
                gateway.cancelIntent(p.getStripePaymentId());
                p.setStatus(Payment.Status.FAILED);
                p.setFailureReason("Booking cancelled before payment was completed");
                settle(p, p.getStripePaymentId(), PaymentTransaction.Status.FAILED, p.getFailureReason());
            }
        });
    }

    @Transactional(readOnly = true)
    public Optional<Payment> find(UUID bookingId) {
        return payments.findByBookingId(bookingId);
    }

    /** The booking's payment with its full provider transaction history. */
    @Transactional(readOnly = true)
    public Optional<PaymentResponse> forBooking(UUID bookingId) {
        return payments.findByBookingId(bookingId).map(p -> PaymentMapper.toResponse(p, false,
                transactions.findByPaymentIdOrderByCreatedAtAsc(p.getId()).stream()
                        .map(PaymentMapper::toResponse).toList()));
    }

    /** Audit trail: one row per provider call (SDS 6.2, 8.3). */
    private void record(Payment p, PaymentTransaction.Kind kind, String ref, BigDecimal amount,
            PaymentTransaction.Status status, String failureReason) {
        PaymentTransaction t = new PaymentTransaction();
        t.setPayment(p);
        t.setKind(kind);
        t.setProviderRef(ref);
        t.setAmount(amount);
        t.setStatus(status);
        t.setFailureReason(failureReason);
        t.setCreatedAt(LocalDateTime.now(clock));
        transactions.save(t);
    }

    /** Records the outcome of an intent's transaction (created when the intent was created). */
    private void settle(Payment p, String intentId, PaymentTransaction.Status status, String failureReason) {
        transactions.findFirstByPaymentIdAndProviderRefOrderByCreatedAtDesc(p.getId(), intentId).ifPresent(t -> {
            if (t.getStatus() != PaymentTransaction.Status.SUCCESS) {
                t.setStatus(status);
                t.setFailureReason(failureReason);
            }
        });
    }

    @Transactional(readOnly = true)
    public List<PaymentResponse> recent() {
        return payments.findTop300ByOrderByCreatedAtDesc().stream().map(p -> PaymentMapper.toResponse(p, true)).toList();
    }

    private Payment paid(Booking b) {
        Payment p = payments.findByBookingIdForUpdate(b.getId())
                .orElseThrow(() -> new IllegalStateException("Active booking " + b.getId() + " has no payment"));
        if (p.getStatus() != Payment.Status.SUCCESS) {
            throw new IllegalStateException("Payment for booking " + b.getId() + " is " + p.getStatus());
        }
        return p;
    }

    private static void requireOwner(Booking b, AuthUser user) {
        if (!b.getCustomer().getId().equals(user.id())) {
            if (user.isAdmin()) {
                throw ApiException.forbidden();
            }
            throw ApiException.notFound("Booking");
        }
    }

    private static Map<String, String> metadata(Booking b, String purpose) {
        return Map.of("bookingId", String.valueOf(b.getId()), "bookingReference", b.getReference(),
                "purpose", purpose);
    }

    private static UUID parseId(String value) {
        try {
            return value == null ? null : UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
