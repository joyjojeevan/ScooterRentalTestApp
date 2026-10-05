package lk.scooterrentkandy.services;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lk.scooterrentkandy.config.AppProperties;
import lk.scooterrentkandy.dto.BillingDtos.InvoiceResponse;
import lk.scooterrentkandy.dto.BookingDtos.GearLine;
import lk.scooterrentkandy.exception.ApiException;
import lk.scooterrentkandy.mappers.InvoiceMapper;
import lk.scooterrentkandy.models.Booking;
import lk.scooterrentkandy.models.Invoice;
import lk.scooterrentkandy.models.Payment;
import lk.scooterrentkandy.repository.InvoiceRepository;
import lk.scooterrentkandy.repository.PaymentRepository;
import lk.scooterrentkandy.security.AuthUser;
import lk.scooterrentkandy.services.PricingService.FinalBill;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The final invoice is issued when the rental ends (SDS 4.3 steps 13-14); payment status lives on the Payment. */
@Service
public class BillingService {

    private final InvoiceRepository invoices;
    private final PaymentRepository payments;
    private final Clock clock;
    private final String currency;

    public BillingService(InvoiceRepository invoices, PaymentRepository payments, Clock clock, AppProperties props) {
        this.invoices = invoices;
        this.payments = payments;
        this.clock = clock;
        this.currency = props.billing().currency();
    }

    /** One FINAL invoice per booking: rental hours, GPS distance and gear, exactly as billed. */
    @Transactional
    public Invoice issueFinalInvoice(Booking b, FinalBill bill) {
        if (invoices.findFirstByBookingIdAndKind(b.getId(), Invoice.Kind.FINAL).isPresent()) {
            throw new IllegalStateException("Booking " + b.getReference() + " already has a final invoice");
        }
        Invoice inv = new Invoice();
        inv.setInvoiceNumber(References.next("INV"));
        inv.setBooking(b);
        inv.setKind(Invoice.Kind.FINAL);
        inv.setCurrency(currency);
        inv.setIssuedAt(LocalDateTime.now(clock));
        var s = b.getScooter();
        inv.addLine("Scooter " + s.getModel() + " (" + s.getPlateNumber() + "): rental hours",
                BigDecimal.valueOf(bill.hours()), bill.hourlyRate(), bill.timeCost());
        inv.addLine("Distance from GPS (km)", bill.distanceKm(), bill.perKmRate(), bill.distanceCost());
        for (GearLine g : bill.gear()) {
            inv.addLine(g.name() + " x" + g.quantity() + ", " + g.days() + " day" + (g.days() == 1 ? "" : "s"),
                    BigDecimal.valueOf((long) g.quantity() * g.days()), g.dailyRate(), g.lineTotal());
        }
        inv.setSubtotal(bill.total());
        inv.setTax(BigDecimal.ZERO.setScale(2));
        inv.setTotal(bill.total());
        return invoices.save(inv);
    }

    /** Bookings paid under the earlier model carry a RENTAL invoice; void it when the booking is cancelled. */
    @Transactional
    public void voidEarlierRentalInvoice(UUID bookingId) {
        invoices.findFirstByBookingIdAndKind(bookingId, Invoice.Kind.RENTAL)
                .ifPresent(inv -> inv.setStatus(Invoice.Status.VOID));
    }

    @Transactional(readOnly = true)
    public List<InvoiceResponse> forBooking(UUID bookingId) {
        Payment payment = payment(bookingId);
        return invoices.findByBookingIdOrderByIssuedAtAsc(bookingId).stream()
                .map(i -> InvoiceMapper.toResponse(i, true, payment)).toList();
    }

    @Transactional(readOnly = true)
    public InvoiceResponse get(UUID invoiceId, AuthUser user) {
        Invoice inv = invoices.findWithLinesById(invoiceId).orElseThrow(() -> ApiException.notFound("Invoice"));
        if (!user.isAdmin() && !inv.getBooking().getCustomer().getId().equals(user.id())) {
            throw ApiException.forbidden();
        }
        return InvoiceMapper.toResponse(inv, true, payment(inv.getBooking().getId()));
    }

    @Transactional(readOnly = true)
    public List<InvoiceResponse> recent() {
        return invoices.findTop300ByOrderByIssuedAtDesc().stream()
                .map(i -> InvoiceMapper.toResponse(i, false, payment(i.getBooking().getId()))).toList();
    }

    private Payment payment(UUID bookingId) {
        return payments.findByBookingId(bookingId).orElse(null);
    }
}
