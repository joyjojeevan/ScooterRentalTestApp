package lk.scooterrentkandy.billing;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lk.scooterrentkandy.payment.Payment;

public final class BillingDtos {

    private BillingDtos() {
    }

    public record InvoiceLineResponse(String description, BigDecimal quantity, BigDecimal unitPrice, BigDecimal amount) {
    }

    public record InvoiceResponse(
            UUID id,
            String invoiceNumber,
            UUID bookingId,
            String bookingReference,
            String customerName,
            String customerEmail,
            Invoice.Kind kind,
            /** Earlier invoices only (PAID/VOID/ISSUED); null for FINAL invoices. */
            Invoice.Status status,
            /** Status of record for the booking's money (SDS 3.2 PaymentStatus). */
            Payment.Status paymentStatus,
            /** Unpaid remainder of this invoice, if the remaining-balance charge failed. */
            BigDecimal balanceDue,
            String currency,
            BigDecimal subtotal,
            BigDecimal tax,
            BigDecimal total,
            LocalDateTime issuedAt,
            List<InvoiceLineResponse> lines) {

        public static InvoiceResponse from(Invoice i, boolean withLines, Payment payment) {
            var b = i.getBooking();
            List<InvoiceLineResponse> lines = withLines
                    ? i.getLines().stream()
                            .map(l -> new InvoiceLineResponse(l.getDescription(), l.getQuantity(), l.getUnitPrice(),
                                    l.getAmount()))
                            .toList()
                    : List.of();
            return new InvoiceResponse(i.getId(), i.getInvoiceNumber(), b.getId(), b.getReference(),
                    b.getCustomer().getFullName(), b.getCustomer().getEmail(), i.getKind(), i.getStatus(),
                    payment == null ? null : payment.getStatus(),
                    payment != null && i.getKind() == Invoice.Kind.FINAL ? payment.getBalanceDue() : null,
                    i.getCurrency(), i.getSubtotal(), i.getTax(), i.getTotal(), i.getIssuedAt(), lines);
        }
    }
}
