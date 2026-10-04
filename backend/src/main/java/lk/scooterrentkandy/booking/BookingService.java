package lk.scooterrentkandy.booking;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.scooterrentkandy.billing.BillingService;
import lk.scooterrentkandy.booking.BookingDtos.BookingResponse;
import lk.scooterrentkandy.booking.BookingDtos.ContractResponse;
import lk.scooterrentkandy.booking.BookingDtos.CreateBookingRequest;
import lk.scooterrentkandy.booking.BookingDtos.CustomerSummary;
import lk.scooterrentkandy.booking.BookingDtos.GearLineResponse;
import lk.scooterrentkandy.booking.BookingDtos.GearSelectionRequest;
import lk.scooterrentkandy.booking.BookingDtos.PaymentSummary;
import lk.scooterrentkandy.booking.BookingDtos.Quote;
import lk.scooterrentkandy.booking.BookingDtos.QuoteRequest;
import lk.scooterrentkandy.booking.BookingDtos.ScooterSummary;
import lk.scooterrentkandy.booking.BookingDtos.SignContractRequest;
import lk.scooterrentkandy.booking.BookingDtos.Stage;
import lk.scooterrentkandy.booking.PricingService.FinalBill;
import lk.scooterrentkandy.booking.PricingService.GearSelection;
import lk.scooterrentkandy.booking.PricingService.InitialCharge;
import lk.scooterrentkandy.common.ApiException;
import lk.scooterrentkandy.common.References;
import lk.scooterrentkandy.config.AppProperties;
import lk.scooterrentkandy.contract.Contract;
import lk.scooterrentkandy.contract.ContractService;
import lk.scooterrentkandy.gear.GearItem;
import lk.scooterrentkandy.gear.GearService;
import lk.scooterrentkandy.gps.GpsService;
import lk.scooterrentkandy.maintenance.MaintenanceService;
import lk.scooterrentkandy.notification.NotificationService;
import lk.scooterrentkandy.payment.Payment;
import lk.scooterrentkandy.payment.PaymentEvents.BalancePaid;
import lk.scooterrentkandy.payment.PaymentEvents.InitialPaymentSucceeded;
import lk.scooterrentkandy.payment.PaymentService;
import lk.scooterrentkandy.scooter.Scooter;
import lk.scooterrentkandy.scooter.ScooterRepository;
import lk.scooterrentkandy.scooter.ScooterStatus;
import lk.scooterrentkandy.security.AuthUser;
import lk.scooterrentkandy.user.User;
import lk.scooterrentkandy.user.UserRepository;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The SDS rental lifecycle (decisions E1, E2, E3):
 * PENDING (booked, contract signed, initial charge being paid) → ACTIVE (paid; rental runs from startTime)
 * → COMPLETED (the customer ended it and the final total was collected), or CANCELLED.
 */
@Service
public class BookingService {

    public static final int MAX_ADVANCE_DAYS = 365;
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm");

    private final BookingRepository bookings;
    private final ScooterRepository scooters;
    private final UserRepository users;
    private final GearService gearService;
    private final PricingService pricing;
    private final ContractService contracts;
    private final PaymentService payments;
    private final BillingService billing;
    private final GpsService gps;
    private final MaintenanceService maintenance;
    private final NotificationService notifications;
    private final Clock clock;
    private final String currency;

    public BookingService(BookingRepository bookings, ScooterRepository scooters, UserRepository users,
            GearService gearService, PricingService pricing, ContractService contracts, PaymentService payments,
            BillingService billing, GpsService gps, MaintenanceService maintenance,
            NotificationService notifications, Clock clock, AppProperties props) {
        this.bookings = bookings;
        this.scooters = scooters;
        this.users = users;
        this.gearService = gearService;
        this.pricing = pricing;
        this.contracts = contracts;
        this.payments = payments;
        this.billing = billing;
        this.gps = gps;
        this.maintenance = maintenance;
        this.notifications = notifications;
        this.clock = clock;
        this.currency = props.billing().currency();
    }

    // ---------------------------------------------------------------- booking

    @Transactional(readOnly = true)
    public Quote quote(QuoteRequest req) {
        Scooter scooter = scooters.findById(req.scooterId()).orElseThrow(() -> ApiException.notFound("Scooter"));
        InitialCharge c = pricing.initialCharge(scooter, resolveGear(req.gear()));
        return new Quote(scooter.getId(), currency, c.hourlyRate(), c.perKmRate(), c.gear(), c.gearCost(), c.total());
    }

    /** SDS 4.2 steps 1-6: validate availability and create the booking as PENDING. */
    @Transactional
    public BookingResponse create(UUID customerId, CreateBookingRequest req) {
        LocalDateTime now = now();
        if (req.startTime().isBefore(now.minusMinutes(1))) {
            throw ApiException.badRequest("Start time cannot be in the past");
        }
        if (req.startTime().isAfter(now.plusDays(MAX_ADVANCE_DAYS))) {
            throw ApiException.badRequest("Bookings open up to " + MAX_ADVANCE_DAYS + " days ahead");
        }
        User customer = users.findById(customerId).orElseThrow(() -> ApiException.notFound("User"));
        // Lock the scooter row so two customers can't take the same scooter concurrently.
        Scooter scooter = scooters.findByIdForUpdate(req.scooterId())
                .orElseThrow(() -> ApiException.notFound("Scooter"));
        if (scooter.isDeleted()) {
            throw ApiException.badRequest("This scooter is no longer available for rent");
        }
        if (scooter.getStatus() != ScooterStatus.AVAILABLE
                || bookings.existsByScooterIdAndStatusIn(scooter.getId(), BookingStatus.BLOCKING)) {
            throw ApiException.conflict("This scooter is not available right now; choose another scooter");
        }
        List<GearSelection> gear = resolveGear(req.gear());

        Booking b = new Booking();
        b.setReference(References.next("BK"));
        b.setCustomer(customer);
        b.setScooter(scooter);
        b.setStartTime(req.startTime().isBefore(now) ? now : req.startTime());
        b.setStatus(BookingStatus.PENDING);
        b.setPickupLocation(req.pickupLocation().trim());
        b.setNotes(blankToNull(req.notes()));
        for (GearSelection g : gear) {
            BookingGear line = new BookingGear();
            line.setGearItem(g.item());
            line.setQuantity(g.quantity());
            b.addGear(line);
        }
        bookings.save(b);
        contracts.createFor(b);
        return toResponse(b);
    }

    @Transactional
    public ContractResponse signContract(UUID bookingId, AuthUser user, SignContractRequest req, String ip) {
        Booking b = findOwned(bookingId, user);
        if (!b.getCustomer().getId().equals(user.id())) {
            throw ApiException.forbidden();
        }
        if (!req.agree()) {
            throw ApiException.badRequest("You must agree to the rental terms");
        }
        return toResponse(contracts.sign(b, req.signedName(), ip));
    }

    @Transactional(readOnly = true)
    public ContractResponse contract(UUID bookingId, AuthUser user) {
        findOwned(bookingId, user);
        return toResponse(contracts.forBooking(bookingId));
    }

    // ---------------------------------------------------------------- payment events

    /** SDS 4.2 steps 13-14: a successful initial payment activates the booking; the rental is under way. */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onInitialPayment(InitialPaymentSucceeded event) {
        Booking b = find(event.bookingId());
        if (b.getStatus() != BookingStatus.PENDING || !b.isContractSigned()) {
            // Paid after the booking expired or was cancelled: give the money back.
            payments.refundInFull(b, "Payment received for booking " + b.getReference() + " after it was "
                    + b.getStatus().name().toLowerCase());
            notifications.notify(b.getCustomer(), "Payment refunded: " + b.getReference(),
                    "Your payment arrived after the booking had expired, so it has been refunded in full.");
            return;
        }
        b.setStatus(BookingStatus.ACTIVE);
        Scooter s = b.getScooter();
        s.setStatus(ScooterStatus.RENTED);
        notifications.notify(b.getCustomer(), "Booking confirmed: " + b.getReference(),
                "Your " + s.getModel() + " (" + s.getPlateNumber() + ") is yours from " + b.getStartTime().format(WHEN)
                        + ". You were charged " + currency + " " + payments.find(b.getId()).map(Payment::getAmount)
                        .orElse(BigDecimal.ZERO) + " now; the rest is billed by the hour and kilometre when you end "
                        + "the rental.");
        notifications.notifyAdmins("New booking " + b.getReference(),
                b.getCustomer().getFullName() + " booked " + s.getCode() + " from " + b.getStartTime().format(WHEN)
                        + ".");
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onBalancePaid(BalancePaid event) {
        Booking b = find(event.bookingId());
        if (b.getStatus() == BookingStatus.ACTIVE && b.getEndedAt() != null) {
            finishRental(b);
        }
    }

    // ---------------------------------------------------------------- ending a rental (SDS 4.3, E2)

    /**
     * The customer (or an admin on their behalf) ends the rental. The first call fixes the end time, the GPS
     * distance and the final total, and issues the final invoice; then the remaining balance is charged. If
     * that charge fails the booking stays ACTIVE with the balance due, and calling this again retries the same
     * amount (nothing is recalculated).
     */
    @Transactional(noRollbackFor = ApiException.class)
    public BookingResponse complete(UUID bookingId, AuthUser user) {
        bookings.findByIdForUpdate(bookingId).orElseThrow(() -> ApiException.notFound("Booking"));
        Booking b = findOwned(bookingId, user);
        if (b.getStatus() != BookingStatus.ACTIVE) {
            throw ApiException.conflict("Only an active rental can be ended (status " + b.getStatus() + ")");
        }
        if (b.getEndedAt() == null) {
            LocalDateTime end = now();
            if (end.isBefore(b.getStartTime())) {
                throw ApiException.conflict("This rental starts at " + b.getStartTime().format(WHEN)
                        + "; cancel the booking instead of ending it");
            }
            BigDecimal km = gps.rentalDistanceKm(b.getId(), b.getStartTime(), end);
            FinalBill bill = pricing.finalBill(b.getScooter(), PricingService.selectionsOf(b), b.getStartTime(), end,
                    km);
            // Frozen settlement: kept on the ACTIVE booking until the balance is collected (SDS: endTime and
            // totalCost stay NULL until COMPLETED).
            b.setEndedAt(end);
            b.setPendingDistanceKm(bill.distanceKm());
            b.setPendingTotalCost(bill.total());
            billing.issueFinalInvoice(b, bill);
        }

        Payment p = payments.find(b.getId()).orElseThrow(() -> new IllegalStateException("No payment"));
        BigDecimal remaining = b.getPendingTotalCost().subtract(p.getAmount());
        if (remaining.signum() > 0) {
            if (!payments.chargeRemainingBalance(b, remaining)) {
                Payment failed = payments.find(b.getId()).orElseThrow();
                notifications.notify(b.getCustomer(), "Payment needed: " + b.getReference(),
                        "Your rental ended but the remaining " + currency + " " + remaining
                                + " could not be charged (" + failed.getFailureReason()
                                + "). Please pay it online to finish your rental.");
                throw new ApiException(HttpStatus.PAYMENT_REQUIRED, "Rental ended, but the remaining balance of "
                        + currency + " " + remaining + " could not be charged: " + failed.getFailureReason()
                        + ". Pay it online to finish.");
            }
        } else if (remaining.signum() < 0) {
            payments.refundExcess(b, remaining.negate());
        }
        finishRental(b);
        return toResponse(b);
    }

    private void finishRental(Booking b) {
        // The settlement becomes final: move it into the SDS fields.
        b.setEndTime(b.getEndedAt());
        b.setDistanceKm(b.getPendingDistanceKm());
        b.setTotalCost(b.getPendingTotalCost());
        b.setEndedAt(null);
        b.setPendingDistanceKm(null);
        b.setPendingTotalCost(null);
        b.setStatus(BookingStatus.COMPLETED);
        b.setDropLocation(gps.dropLocation(b.getId(), b.getScooter()));
        Scooter s = b.getScooter();
        s.setStatus(ScooterStatus.AVAILABLE);
        s.setTotalMileage(s.getTotalMileage().add(b.getDistanceKm()));
        maintenance.scheduleServiceIfDue(s);
        notifications.notify(b.getCustomer(), "Rental completed: " + b.getReference(),
                "Thanks for riding with us! " + PricingService.billableHours(b.getStartTime(), b.getEndTime())
                        + " hour(s), " + b.getDistanceKm() + " km. Total " + currency + " " + b.getTotalCost()
                        + ". Your invoice is in your booking.");
    }

    // ---------------------------------------------------------------- cancellation (approved E3 rules)

    @Transactional
    public BookingResponse cancel(UUID bookingId, AuthUser user) {
        bookings.findByIdForUpdate(bookingId).orElseThrow(() -> ApiException.notFound("Booking"));
        Booking b = findOwned(bookingId, user);
        switch (b.getStatus()) {
            case PENDING -> {
                markCancelled(b);
                payments.cancelOpenIntent(b);
            }
            case ACTIVE -> {
                // Only before the rental starts: before startTime and with no GPS logs. Full refund.
                if (!now().isBefore(b.getStartTime()) || gps.hasRentalLogs(b.getId())) {
                    throw ApiException.conflict("This rental has started and can no longer be cancelled; end it instead");
                }
                markCancelled(b);
                Payment refund = payments.refundInFull(b, "Booking " + b.getReference() + " cancelled");
                b.getScooter().setStatus(ScooterStatus.AVAILABLE);
                billing.voidEarlierRentalInvoice(b.getId());
                BigDecimal refunded = refund == null ? BigDecimal.ZERO : refund.getAmount();
                notifications.notify(b.getCustomer(), "Booking cancelled: " + b.getReference(),
                        "Your booking has been cancelled. A refund of " + currency + " " + refunded
                                + " has been issued.");
                notifications.notifyAdmins("Booking cancelled " + b.getReference(),
                        b.getCustomer().getFullName() + " cancelled their booking for " + b.getScooter().getCode());
            }
            default -> throw ApiException.conflict("A booking that is " + b.getStatus() + " cannot be cancelled");
        }
        return toResponse(b);
    }

    /** Releases unpaid bookings so they stop holding the scooter. */
    @Transactional
    public int expireUnpaid(LocalDateTime createdBefore) {
        List<Booking> stale = bookings.findByStatusAndCreatedAtBefore(BookingStatus.PENDING, createdBefore);
        stale.forEach(b -> {
            markCancelled(b);
            payments.cancelOpenIntent(b);
        });
        return stale.size();
    }

    // ---------------------------------------------------------------- queries

    @Transactional(readOnly = true)
    public List<BookingResponse> listForCustomer(UUID customerId) {
        return bookings.findByCustomerIdOrderByCreatedAtDesc(customerId).stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public BookingResponse get(UUID bookingId, AuthUser user) {
        return toResponse(findOwned(bookingId, user));
    }

    @Transactional(readOnly = true)
    public List<BookingResponse> listAll(BookingStatus status) {
        var list = status == null ? bookings.findAllByOrderByCreatedAtDesc()
                : bookings.findByStatusOrderByStartTimeAsc(status);
        return list.stream().map(this::toResponse).toList();
    }

    // ---------------------------------------------------------------- helpers

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private void markCancelled(Booking b) {
        b.setStatus(BookingStatus.CANCELLED);
        b.setCancelledAt(now());
    }

    private List<GearSelection> resolveGear(List<GearSelectionRequest> requested) {
        if (requested == null || requested.isEmpty()) {
            return List.of();
        }
        Map<UUID, Integer> merged = new LinkedHashMap<>();
        requested.forEach(r -> merged.merge(r.gearItemId(), r.quantity(), Integer::sum));
        List<GearSelection> result = new ArrayList<>();
        merged.forEach((id, qty) -> {
            GearItem item = gearService.find(id);
            if (!item.isActive()) {
                throw ApiException.badRequest(item.getName() + " is not available for rent");
            }
            int available = gearService.available(item);
            if (qty > available) {
                throw ApiException.conflict("Only " + available + " x " + item.getName() + " available right now");
            }
            result.add(new GearSelection(item, qty));
        });
        return result;
    }

    private Booking find(UUID id) {
        return bookings.findWithDetailsById(id).orElseThrow(() -> ApiException.notFound("Booking"));
    }

    private Booking findOwned(UUID id, AuthUser user) {
        Booking b = find(id);
        if (!user.isAdmin() && !b.getCustomer().getId().equals(user.id())) {
            // Don't reveal that someone else's booking exists.
            throw ApiException.notFound("Booking");
        }
        return b;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    BookingResponse toResponse(Booking b) {
        var c = b.getCustomer();
        var s = b.getScooter();
        Payment p = payments.find(b.getId()).orElse(null);
        List<GearLineResponse> gear = b.getGear().stream()
                .map(g -> new GearLineResponse(g.getGearItem().getId(), g.getGearItem().getName(), g.getQuantity(),
                        g.getGearItem().getDailyRate()))
                .toList();
        BigDecimal initialCharge = pricing.initialCharge(s, PricingService.selectionsOf(b)).total();
        LocalDateTime ended = b.getEndTime() != null ? b.getEndTime() : b.getEndedAt();
        Long hours = ended == null ? null : PricingService.billableHours(b.getStartTime(), ended);
        return new BookingResponse(b.getId(), b.getReference(), b.getStatus(), stage(b, p),
                new CustomerSummary(c.getId(), c.getFullName(), c.getEmail(), c.getPhone()),
                new ScooterSummary(s.getId(), s.getCode(), s.getModel(), s.getPlateNumber(), s.getImageUrl(),
                        s.getHourlyRate(), s.getPerKmRate()),
                b.getStartTime(), b.getEndTime(), hours, b.getDistanceKm(), b.getTotalCost(), b.getEndedAt(),
                b.getPendingDistanceKm(), b.getPendingTotalCost(), initialCharge, gear,
                p == null ? null : new PaymentSummary(p.getStatus(), p.getAmount(), p.getBalanceDue(),
                        p.getCardLast4(), p.getFailureReason(), p.getPaidAt()),
                b.getPickupLocation(), b.getDropLocation(), b.getNotes(), b.isContractSigned(), b.getCancelledAt(),
                b.getCreatedAt());
    }

    private Stage stage(Booking b, Payment p) {
        return switch (b.getStatus()) {
            case PENDING -> p != null && p.getStatus() == Payment.Status.FAILED ? Stage.PAYMENT_FAILED
                    : Stage.AWAITING_PAYMENT;
            case ACTIVE -> b.getEndedAt() != null ? Stage.BALANCE_DUE
                    : now().isBefore(b.getStartTime()) ? Stage.UPCOMING : Stage.IN_PROGRESS;
            case COMPLETED -> Stage.COMPLETED;
            case CANCELLED -> p != null && p.getStatus() == Payment.Status.REFUNDED ? Stage.REFUNDED
                    : Stage.CANCELLED;
        };
    }

    private ContractResponse toResponse(Contract c) {
        return new ContractResponse(c.getId(), c.getContractNumber(), c.getTermsVersion(), c.getTermsText(),
                c.isSigned(), c.getSignedName(), c.getSignedAt());
    }
}
