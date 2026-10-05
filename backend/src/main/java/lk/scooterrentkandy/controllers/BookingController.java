package lk.scooterrentkandy.controllers;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lk.scooterrentkandy.dto.BillingDtos.InvoiceResponse;
import lk.scooterrentkandy.dto.BookingDtos.BookingResponse;
import lk.scooterrentkandy.dto.BookingDtos.ContractResponse;
import lk.scooterrentkandy.dto.BookingDtos.CreateBookingRequest;
import lk.scooterrentkandy.dto.BookingDtos.Quote;
import lk.scooterrentkandy.dto.BookingDtos.QuoteRequest;
import lk.scooterrentkandy.dto.BookingDtos.SignContractRequest;
import lk.scooterrentkandy.dto.PaymentDtos.PaymentResponse;
import lk.scooterrentkandy.exception.ApiException;
import lk.scooterrentkandy.models.BookingStatus;
import lk.scooterrentkandy.security.AuthUser;
import lk.scooterrentkandy.security.CurrentUser;
import lk.scooterrentkandy.services.BillingService;
import lk.scooterrentkandy.services.BookingService;
import lk.scooterrentkandy.services.PaymentService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BookingController {

    private final BookingService bookings;
    private final PaymentService payments;
    private final BillingService billing;

    public BookingController(BookingService bookings, PaymentService payments, BillingService billing) {
        this.bookings = bookings;
        this.payments = payments;
        this.billing = billing;
    }

    @PostMapping("/api/bookings/quote")
    public Quote quote(@Valid @RequestBody QuoteRequest req) {
        return bookings.quote(req);
    }

    @PostMapping("/api/bookings")
    @ResponseStatus(HttpStatus.CREATED)
    public BookingResponse create(@CurrentUser AuthUser user, @Valid @RequestBody CreateBookingRequest req) {
        return bookings.create(user.id(), req);
    }

    @GetMapping("/api/bookings")
    public List<BookingResponse> mine(@CurrentUser AuthUser user) {
        return bookings.listForCustomer(user.id());
    }

    @GetMapping("/api/bookings/{id}")
    public BookingResponse get(@PathVariable UUID id, @CurrentUser AuthUser user) {
        return bookings.get(id, user);
    }

    @GetMapping("/api/bookings/{id}/contract")
    public ContractResponse contract(@PathVariable UUID id, @CurrentUser AuthUser user) {
        return bookings.contract(id, user);
    }

    @PostMapping("/api/bookings/{id}/contract/sign")
    public ContractResponse sign(@PathVariable UUID id, @CurrentUser AuthUser user,
            @Valid @RequestBody SignContractRequest req, HttpServletRequest http) {
        return bookings.signContract(id, user, req, http.getRemoteAddr());
    }

    /** SDS 4.3 steps 5-14: the customer ends the rental (an admin may do it for them). */
    @PutMapping("/api/bookings/{id}/complete")
    public BookingResponse complete(@PathVariable UUID id, @CurrentUser AuthUser user) {
        return bookings.complete(id, user);
    }

    @PostMapping("/api/bookings/{id}/cancel")
    public BookingResponse cancel(@PathVariable UUID id, @CurrentUser AuthUser user) {
        return bookings.cancel(id, user);
    }

    /** The booking's single payment (SDS 2.3: one payment per booking). */
    @GetMapping("/api/bookings/{id}/payment")
    public PaymentResponse payment(@PathVariable UUID id, @CurrentUser AuthUser user) {
        bookings.get(id, user); // ownership check
        return payments.forBooking(id).orElseThrow(() -> ApiException.notFound("Payment"));
    }

    @GetMapping("/api/bookings/{id}/invoices")
    public List<InvoiceResponse> invoices(@PathVariable UUID id, @CurrentUser AuthUser user) {
        bookings.get(id, user); // ownership check
        return billing.forBooking(id);
    }

    @GetMapping("/api/admin/bookings")
    public List<BookingResponse> all(@RequestParam(required = false) BookingStatus status) {
        return bookings.listAll(status);
    }
}
