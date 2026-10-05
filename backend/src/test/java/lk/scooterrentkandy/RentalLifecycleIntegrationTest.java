package lk.scooterrentkandy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.lang.reflect.RecordComponent;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import lk.scooterrentkandy.dto.BookingDtos;
import lk.scooterrentkandy.dto.PaymentDtos;
import lk.scooterrentkandy.models.Payment;
import lk.scooterrentkandy.services.BookingService;
import lk.scooterrentkandy.services.MockPaymentGateway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

/** Phase 2 (decisions E1, E2, E4, E6): open-ended rentals, time + distance billing, one payment per booking. */
class RentalLifecycleIntegrationTest extends ApiTestSupport {

    static final String PONCHO = "Rain poncho"; // 150.00 per day, 20 in stock

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MockPaymentGateway mockStripe;

    @Autowired
    BookingService bookingService;

    // ---------------------------------------------------------------- initial payment (E4, E6)

    @Test
    void initialPaymentActivatesTheBookingWithoutAnyAdminHandover() throws Exception {
        NewScooter s = newScooter("300.00", "20.00");
        String customer = register("Ivy Initial");
        String id = createBooking(customer, s.id(), now(), List.of(Map.of("gearItemId", gearIdByName(PONCHO), "quantity", 1)));

        JsonNode b = booking(customer, id);
        assertThat(b.get("status").asText()).isEqualTo("PENDING");
        assertThat(b.get("stage").asText()).isEqualTo("AWAITING_PAYMENT");
        assertThat(b.get("initialCharge").decimalValue()).isEqualByComparingTo("450.00"); // 1h x 300 + 1 day x 150
        assertThat(b.get("dropLocation").isNull()).isTrue();
        // SDS 2.2: pickupLocation is mandatory.
        call(post("/api/bookings"), register("No Pickup"), Map.of("scooterId", newScooter("300.00", "20.00").id(),
                "startTime", now().toString())).andExpect(status().isBadRequest());
        // Held while pending: not bookable by anyone else.
        call(get("/api/scooters/" + s.id()), null, null).andExpect(jsonPath("$.available").value(false));
        book(register("Other Person"), s.id(), now(), null).andExpect(status().isConflict());

        // Paying before signing is refused.
        call(post("/api/bookings/" + id + "/payment-intent"), customer, null).andExpect(status().isBadRequest());
        sign(customer, id, "Ivy Initial");
        JsonNode intent = initialIntent(customer, id);
        assertThat(intent.get("amount").decimalValue()).isEqualByComparingTo("450.00");
        assertThat(intent.get("clientSecret").asText()).startsWith(intent.get("intentId").asText() + "_secret_");
        assertThat(booking(customer, id).at("/payment/status").asText()).isEqualTo("PENDING");

        confirm(intent, "pm_card_visa").andExpect(jsonPath("$.status").value("succeeded"));

        b = booking(customer, id);
        assertThat(b.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(b.get("stage").asText()).isEqualTo("IN_PROGRESS");
        assertThat(b.at("/payment/status").asText()).isEqualTo("SUCCESS");
        assertThat(b.at("/payment/amount").decimalValue()).isEqualByComparingTo("450.00");
        assertThat(b.at("/payment/cardLast4").asText()).isEqualTo("4242");
        call(get("/api/scooters/" + s.id()), null, null).andExpect(jsonPath("$.status").value("RENTED"));

        // The old handover/return endpoints are gone.
        call(post("/api/admin/bookings/" + id + "/check-out"), adminToken, Map.of()).andExpect(status().isNotFound());
        call(post("/api/admin/bookings/" + id + "/return"), adminToken, Map.of("endOdometer", 1)).andExpect(status().isNotFound());
    }

    @Test
    void declinedPaymentLeavesTheBookingPendingAndCanBeRetriedOnTheSamePaymentRow() throws Exception {
        NewScooter s = newScooter("300.00", "20.00");
        String customer = register("Dee Decline");
        String id = createBooking(customer, s.id(), now(), null);
        sign(customer, id, "Dee Decline");

        JsonNode first = initialIntent(customer, id);
        confirm(first, "pm_card_chargeDeclined")
                .andExpect(jsonPath("$.status").value("requires_payment_method"))
                .andExpect(jsonPath("$.error").value("Your card was declined."));
        JsonNode b = booking(customer, id);
        assertThat(b.get("status").asText()).isEqualTo("PENDING");
        assertThat(b.get("stage").asText()).isEqualTo("PAYMENT_FAILED");
        assertThat(b.at("/payment/status").asText()).isEqualTo("FAILED");

        JsonNode second = initialIntent(customer, id);
        assertThat(second.get("paymentId").asText()).isEqualTo(first.get("paymentId").asText());
        confirm(second, "pm_card_visa").andExpect(status().isOk());
        assertThat(booking(customer, id).get("status").asText()).isEqualTo("ACTIVE");
        assertThat(paymentRows(id)).isEqualTo(1);
    }

    @Test
    void duplicatePaymentRowsArePrevented() throws Exception {
        NewScooter s = newScooter("300.00", "20.00");
        String customer = register("Dup Lee");
        String id = createBooking(customer, s.id(), now(), null);
        sign(customer, id, "Dup Lee");

        JsonNode a = initialIntent(customer, id);
        JsonNode b = initialIntent(customer, id);
        assertThat(b.get("paymentId").asText()).isEqualTo(a.get("paymentId").asText());
        assertThat(b.get("intentId").asText()).isNotEqualTo(a.get("intentId").asText());
        // The replaced intent was cancelled, so it can no longer be paid.
        confirm(a, "pm_card_visa").andExpect(status().isConflict());
        confirm(b, "pm_card_visa").andExpect(status().isOk());
        call(post("/api/bookings/" + id + "/payment-intent"), customer, null).andExpect(status().isConflict());
        assertThat(paymentRows(id)).isEqualTo(1);

        assertThatThrownBy(() -> jdbc.update("insert into payments (id, booking_id, amount, method, status, provider, "
                + "created_at) values (?, ?, 1, 'CARD', 'PENDING', 'mock', now())", java.util.UUID.randomUUID(),
                java.util.UUID.fromString(id)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ---------------------------------------------------------------- ending a rental (E1, E2)

    @Test
    void customerEndsTheRentalAndIsBilledForHoursDistanceAndGear() throws Exception {
        NewScooter s = newScooter("300.00", "20.00");
        String customer = register("Cara Complete");
        String id = activeRental(customer, "Cara Complete", s.id(),
                List.of(Map.of("gearItemId", gearIdByName(PONCHO), "quantity", 2)), "pm_card_visa");
        LocalDateTime t0 = now();

        // Two 0.01 degree hops north: 2 x 1.112 km = 2.22 km along the GPS logs.
        ping(s.code(), 7.2936, 80.6413, 20, t0.plusMinutes(5));
        ping(s.code(), 7.3036, 80.6413, 35, t0.plusMinutes(30));
        ping(s.code(), 7.3136, 80.6413, 30, t0.plusMinutes(60));
        JsonNode tracking = body(call(get("/api/bookings/" + id + "/tracking"), customer, null).andExpect(status().isOk()));
        assertThat(tracking.get("distanceKm").decimalValue()).isEqualByComparingTo("2.22");

        clock.advance(Duration.ofHours(2).plusMinutes(30));
        JsonNode done = body(complete(customer, id).andExpect(status().isOk()));

        // ceil(2.5 h) = 3 h x 300 = 900; 2.22 km x 20 = 44.40; ponchos 2 x 150 x ceil(3/24) = 300
        assertThat(done.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(done.get("billableHours").asLong()).isEqualTo(3);
        assertThat(done.get("distanceKm").decimalValue()).isEqualByComparingTo("2.22");
        assertThat(done.get("totalCost").decimalValue()).isEqualByComparingTo("1244.40");
        // Initial 600 (300 + 2 x 150) + remaining 644.40 charged to the saved card = one payment of 1244.40.
        assertThat(done.at("/payment/amount").decimalValue()).isEqualByComparingTo("1244.40");
        assertThat(done.at("/payment/status").asText()).isEqualTo("SUCCESS");
        assertThat(paymentRows(id)).isEqualTo(1);

        JsonNode invoice = body(call(get("/api/bookings/" + id + "/invoices"), customer, null)).get(0);
        assertThat(invoice.get("kind").asText()).isEqualTo("FINAL");
        assertThat(invoice.get("total").decimalValue()).isEqualByComparingTo("1244.40");
        assertThat(invoice.get("paymentStatus").asText()).isEqualTo("SUCCESS");
        assertThat(lines(invoice)).containsExactly("3.00 x 300.00 = 900.00", "2.22 x 20.00 = 44.40",
                "2.00 x 150.00 = 300.00");

        // SDS 2.2: drop location = the rental's last GPS position ("lat, lng" while reverse geocoding is mocked).
        assertThat(done.get("pickupLocation").asText()).isEqualTo("Kandy office, Dalada Veediya");
        assertThat(done.get("dropLocation").asText()).isEqualTo("7.313600, 80.641300");

        JsonNode scooter = body(call(get("/api/scooters/" + s.id()), null, null));
        assertThat(scooter.get("status").asText()).isEqualTo("AVAILABLE");
        // SDS 2.2 totalMileage is decimal: the exact GPS distance is added, not a rounded figure.
        assertThat(scooter.get("totalMileage").decimalValue()).isEqualByComparingTo("1002.22");
        complete(customer, id).andExpect(status().isConflict());
    }

    @Test
    void anotherCustomerCannotEndMyRentalButAnAdminCan() throws Exception {
        NewScooter s = newScooter("300.00", "20.00");
        String customer = register("Owen Owner");
        String id = activeRental(customer, "Owen Owner", s.id(), null, "pm_card_visa");
        clock.advance(Duration.ofMinutes(90));

        complete(register("Nosy Neighbour"), id).andExpect(status().isNotFound());
        assertThat(booking(customer, id).get("status").asText()).isEqualTo("ACTIVE");

        JsonNode done = body(complete(adminToken, id).andExpect(status().isOk()));
        assertThat(done.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(done.get("totalCost").decimalValue()).isEqualByComparingTo("600.00"); // ceil(1.5) = 2 h
    }

    @Test
    void shortRentalIsBilledTheOneHourMinimumAndNeedsNoSecondCharge() throws Exception {
        NewScooter s = newScooter("300.00", "20.00");
        String customer = register("Min Imum");
        String id = activeRental(customer, "Min Imum", s.id(), null, "pm_card_visa");
        clock.advance(Duration.ofMinutes(10));

        JsonNode done = body(complete(customer, id).andExpect(status().isOk()));
        assertThat(done.get("billableHours").asLong()).isEqualTo(1);
        assertThat(done.get("totalCost").decimalValue()).isEqualByComparingTo("300.00");
        // No GPS log: the drop location falls back to the scooter's last-known position (none for a new scooter).
        assertThat(done.get("dropLocation").asText()).isEqualTo("Not recorded");
        assertThat(done.at("/payment/amount").decimalValue()).isEqualByComparingTo("300.00");
    }

    @Test
    void gearIsBilledPerStartedDay() throws Exception {
        NewScooter s = newScooter("300.00", "0.00");
        String customer = register("Gus Gear");
        String id = activeRental(customer, "Gus Gear", s.id(),
                List.of(Map.of("gearItemId", gearIdByName(PONCHO), "quantity", 1)), "pm_card_visa");
        clock.advance(Duration.ofHours(24).plusMinutes(20));

        JsonNode done = body(complete(customer, id).andExpect(status().isOk()));
        // 25 h x 300 = 7500; poncho 150 x ceil(25/24) = 2 days = 300
        assertThat(done.get("billableHours").asLong()).isEqualTo(25);
        assertThat(done.get("totalCost").decimalValue()).isEqualByComparingTo("7800.00");
    }

    @Test
    void failedRemainingChargeKeepsTheRentalActiveUntilTheBalanceIsPaid() throws Exception {
        NewScooter s = newScooter("300.00", "20.00");
        String customer = register("Bal Ance");
        // This test card pays on-session but declines later off-session charges.
        String id = activeRental(customer, "Bal Ance", s.id(), null, "pm_card_chargeCustomerFail");
        clock.advance(Duration.ofHours(2));

        complete(customer, id).andExpect(status().isPaymentRequired());
        JsonNode b = booking(customer, id);
        assertThat(b.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(b.get("stage").asText()).isEqualTo("BALANCE_DUE");
        // SDS: endTime and totalCost stay NULL while the booking is ACTIVE; the frozen settlement is pending.
        assertThat(b.get("endTime").isNull()).isTrue();
        assertThat(b.get("totalCost").isNull()).isTrue();
        assertThat(b.get("pendingTotalCost").decimalValue()).isEqualByComparingTo("600.00");
        assertThat(b.get("billableHours").asLong()).isEqualTo(2);
        assertThat(b.at("/payment/amount").decimalValue()).isEqualByComparingTo("300.00");
        assertThat(b.at("/payment/balanceDue").decimalValue()).isEqualByComparingTo("300.00");
        assertThat(b.at("/payment/failureReason").asText()).isEqualTo("Your card was declined.");
        String endedAt = b.get("endedAt").asText();
        JsonNode invoice = body(call(get("/api/bookings/" + id + "/invoices"), customer, null)).get(0);
        assertThat(invoice.get("balanceDue").decimalValue()).isEqualByComparingTo("300.00");
        call(get("/api/scooters/" + s.id()), null, null).andExpect(jsonPath("$.status").value("RENTED"));

        // Retrying later charges exactly the same amount; nothing is recalculated.
        clock.advance(Duration.ofHours(3));
        complete(customer, id).andExpect(status().isPaymentRequired());
        b = booking(customer, id);
        assertThat(b.get("endedAt").asText()).isEqualTo(endedAt);
        assertThat(b.get("pendingTotalCost").decimalValue()).isEqualByComparingTo("600.00");
        assertThat(b.at("/payment/balanceDue").decimalValue()).isEqualByComparingTo("300.00");
        assertThat(paymentRows(id)).isEqualTo(1);

        // Pay the balance online with another card.
        JsonNode balance = body(call(post("/api/bookings/" + id + "/balance-intent"), customer, null)
                .andExpect(status().isOk()));
        assertThat(balance.get("amount").decimalValue()).isEqualByComparingTo("300.00");
        assertThat(balance.get("purpose").asText()).isEqualTo("BALANCE");
        confirm(balance, "pm_card_visa").andExpect(status().isOk());

        b = booking(customer, id);
        assertThat(b.get("status").asText()).isEqualTo("COMPLETED");
        // The settlement moved into the final fields.
        assertThat(b.get("endTime").asText()).isEqualTo(endedAt);
        assertThat(b.get("totalCost").decimalValue()).isEqualByComparingTo("600.00");
        assertThat(b.get("endedAt").isNull()).isTrue();
        assertThat(b.get("pendingTotalCost").isNull()).isTrue();
        assertThat(b.at("/payment/amount").decimalValue()).isEqualByComparingTo("600.00");
        assertThat(b.at("/payment/balanceDue").isNull()).isTrue();
        assertThat(paymentRows(id)).isEqualTo(1);
        call(get("/api/scooters/" + s.id()), null, null).andExpect(jsonPath("$.status").value("AVAILABLE"));
    }

    @Test
    void earlierPrepaidBookingsAreRefundedTheDifferenceWhenTheyEnd() throws Exception {
        NewScooter s = newScooter("300.00", "20.00");
        String customer = register("Leg Acy");
        String id = activeRental(customer, "Leg Acy", s.id(), null, "pm_card_visa");
        // As migrated from the deposit model: far more was collected up front than an hour.
        jdbc.update("update payments set amount = 20000 where booking_id = ?", java.util.UUID.fromString(id));
        clock.advance(Duration.ofHours(4));

        JsonNode done = body(complete(customer, id).andExpect(status().isOk()));
        assertThat(done.get("totalCost").decimalValue()).isEqualByComparingTo("1200.00");
        assertThat(done.at("/payment/amount").decimalValue()).isEqualByComparingTo("1200.00");
    }

    @Test
    void everyProviderCallIsRecordedUnderTheSinglePayment() throws Exception {
        NewScooter s = newScooter("300.00", "20.00");
        String customer = register("Audi Trail");
        String id = createBooking(customer, s.id(), now(), null);
        sign(customer, id, "Audi Trail");
        JsonNode declined = initialIntent(customer, id);
        confirm(declined, "pm_card_chargeDeclined").andExpect(status().isOk());
        JsonNode replaced = initialIntent(customer, id);                       // superseded without confirming
        JsonNode paid = initialIntent(customer, id);
        confirm(paid, "pm_card_chargeCustomerFail").andExpect(status().isOk());
        clock.advance(Duration.ofHours(2));
        complete(customer, id).andExpect(status().isPaymentRequired());       // off-session balance declined
        JsonNode balance = body(call(post("/api/bookings/" + id + "/balance-intent"), customer, null));
        confirm(balance, "pm_card_visa").andExpect(status().isOk());

        JsonNode payment = body(call(get("/api/bookings/" + id + "/payment"), customer, null));
        List<String> trail = StreamSupport.stream(payment.get("transactions").spliterator(), false)
                .map(t -> t.get("kind").asText() + ":" + t.get("status").asText() + ":" + t.get("amount").decimalValue()
                        .setScale(2) + ":" + (t.get("providerRef").isNull() ? "-" : t.get("providerRef").asText()))
                .toList();
        assertThat(trail).containsExactly(
                "INITIAL:FAILED:300.00:" + declined.get("intentId").asText(),
                "INITIAL:FAILED:300.00:" + replaced.get("intentId").asText(),
                "INITIAL:SUCCESS:300.00:" + paid.get("intentId").asText(),
                "BALANCE:FAILED:300.00:-",
                "BALANCE:SUCCESS:300.00:" + balance.get("intentId").asText());
        assertThat(payment.get("transactions").get(0).get("failureReason").asText()).isEqualTo("Replaced by a new payment attempt");
        assertThat(payment.get("transactions").get(3).get("failureReason").asText()).isEqualTo("Your card was declined.");
        assertThat(paymentRows(id)).isEqualTo(1);
        // Admin lists stay light: no transaction history there.
        JsonNode listed = StreamSupport.stream(body(call(get("/api/admin/payments"), adminToken, null)).spliterator(), false)
                .filter(p -> p.get("bookingId").asText().equals(id)).findFirst().orElseThrow();
        assertThat(listed.get("transactions").isNull()).isTrue();
    }

    @Test
    void refundsAndSuccessfulBalanceChargesKeepTheirProviderReference() throws Exception {
        NewScooter s = newScooter("300.00", "20.00");
        String customer = register("Ref Erence");
        String id = createBooking(customer, s.id(), now().plusDays(1), null);
        sign(customer, id, "Ref Erence");
        confirm(initialIntent(customer, id), "pm_card_visa").andExpect(status().isOk());
        call(post("/api/bookings/" + id + "/cancel"), customer, null).andExpect(status().isOk());
        JsonNode refund = body(call(get("/api/bookings/" + id + "/payment"), customer, null)).get("transactions").get(1);
        assertThat(refund.get("kind").asText()).isEqualTo("REFUND");
        assertThat(refund.get("status").asText()).isEqualTo("SUCCESS");
        assertThat(refund.get("providerRef").asText()).startsWith("re_mock_");

        NewScooter s2 = newScooter("300.00", "20.00");
        String rental = activeRental(customer, "Ref Erence", s2.id(), null, "pm_card_visa");
        clock.advance(Duration.ofHours(3));
        complete(customer, rental).andExpect(status().isOk());
        JsonNode charged = body(call(get("/api/bookings/" + rental + "/payment"), customer, null)).get("transactions").get(1);
        assertThat(charged.get("kind").asText()).isEqualTo("BALANCE");
        assertThat(charged.get("amount").decimalValue()).isEqualByComparingTo("600.00");
        assertThat(charged.get("providerRef").asText()).startsWith("pi_mock_");
    }

    // ---------------------------------------------------------------- cancellation (E3 rules)

    @Test
    void cancellingBeforePaymentClosesTheOpenIntent() throws Exception {
        NewScooter s = newScooter("300.00", "20.00");
        String customer = register("Pen Ding");
        String id = createBooking(customer, s.id(), now(), null);
        sign(customer, id, "Pen Ding");
        JsonNode intent = initialIntent(customer, id);

        call(post("/api/bookings/" + id + "/cancel"), customer, null).andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(booking(customer, id).at("/payment/status").asText()).isEqualTo("FAILED");
        confirm(intent, "pm_card_visa").andExpect(status().isConflict());
        call(get("/api/scooters/" + s.id()), null, null).andExpect(jsonPath("$.available").value(true));
    }

    @Test
    void paidBookingCancelledBeforeItStartsIsRefundedInFull() throws Exception {
        NewScooter s = newScooter("300.00", "20.00");
        String customer = register("Fu Ture");
        String id = createBooking(customer, s.id(), now().plusDays(2), null);
        sign(customer, id, "Fu Ture");
        confirm(initialIntent(customer, id), "pm_card_visa").andExpect(status().isOk());
        assertThat(booking(customer, id).get("stage").asText()).isEqualTo("UPCOMING");

        // Ending a rental that hasn't started is refused; cancel it instead.
        complete(customer, id).andExpect(status().isConflict());
        JsonNode cancelled = body(call(post("/api/bookings/" + id + "/cancel"), customer, null).andExpect(status().isOk()));
        assertThat(cancelled.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(cancelled.get("stage").asText()).isEqualTo("REFUNDED");
        assertThat(cancelled.at("/payment/status").asText()).isEqualTo("REFUNDED");
        assertThat(cancelled.at("/payment/amount").decimalValue()).isEqualByComparingTo("300.00");
        call(get("/api/scooters/" + s.id()), null, null).andExpect(jsonPath("$.status").value("AVAILABLE"));
    }

    @Test
    void startedRentalCannotBeCancelled() throws Exception {
        NewScooter s = newScooter("300.00", "20.00");
        String customer = register("Sta Rted");
        String id = activeRental(customer, "Sta Rted", s.id(), null, "pm_card_visa");
        call(post("/api/bookings/" + id + "/cancel"), customer, null).andExpect(status().isConflict());
        assertThat(booking(customer, id).at("/payment/status").asText()).isEqualTo("SUCCESS");
    }

    @Test
    void paymentArrivingAfterTheBookingExpiredIsRefunded() throws Exception {
        NewScooter s = newScooter("300.00", "20.00");
        String customer = register("Lay Te");
        String id = createBooking(customer, s.id(), now(), null);
        sign(customer, id, "Lay Te");
        JsonNode intent = initialIntent(customer, id);
        // The customer confirms, but the webhook is delayed past the 30-minute hold.
        MockPaymentGateway.Confirmation c = mockStripe.confirm(intent.get("intentId").asText(),
                intent.get("clientSecret").asText(), "pm_card_visa", "4242");
        bookingService.expireUnpaid(LocalDateTime.now(clock).plusMinutes(1));

        webhook(c.webhookPayload(), c.webhookSignature()).andExpect(status().isOk());
        JsonNode b = booking(customer, id);
        assertThat(b.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(b.at("/payment/status").asText()).isEqualTo("REFUNDED");
    }

    // ---------------------------------------------------------------- webhook and card-data rules (E6)

    @Test
    void webhooksMustBeSignedAndRepeatedDeliveriesChangeNothing() throws Exception {
        NewScooter s = newScooter("300.00", "20.00");
        String customer = register("Web Hook");
        String id = createBooking(customer, s.id(), now(), null);
        sign(customer, id, "Web Hook");
        JsonNode intent = initialIntent(customer, id);
        MockPaymentGateway.Confirmation c = mockStripe.confirm(intent.get("intentId").asText(),
                intent.get("clientSecret").asText(), "pm_card_visa", "4242");

        webhook(c.webhookPayload(), null).andExpect(status().isBadRequest());
        webhook(c.webhookPayload(), "t=1,v1=deadbeef").andExpect(status().isBadRequest());
        webhook(c.webhookPayload().replace("pm_card_visa", "pm_card_mastercard"), c.webhookSignature())
                .andExpect(status().isBadRequest());
        assertThat(booking(customer, id).get("status").asText()).isEqualTo("PENDING");

        webhook(c.webhookPayload(), c.webhookSignature()).andExpect(status().isOk());
        webhook(c.webhookPayload(), c.webhookSignature()).andExpect(status().isOk());
        JsonNode b = booking(customer, id);
        assertThat(b.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(b.at("/payment/amount").decimalValue()).isEqualByComparingTo("300.00");
        assertThat(paymentRows(id)).isEqualTo(1);
    }

    @Test
    void rawCardDataNeverReachesOurBackend() throws Exception {
        NewScooter s = newScooter("300.00", "20.00");
        String customer = register("Card Less");
        String id = createBooking(customer, s.id(), now(), null);
        sign(customer, id, "Card Less");
        JsonNode intent = initialIntent(customer, id);

        // The old card-taking endpoint no longer exists.
        call(post("/api/bookings/" + id + "/pay"), customer, Map.of("cardNumber", "4242424242424242",
                "expiry", "12/30", "cvc", "123", "cardholderName", "Card Less")).andExpect(status().isNotFound());
        // The (mock) provider endpoint accepts a token only; card fields are rejected.
        call(post("/mock-stripe/v1/payment_intents/" + intent.get("intentId").asText() + "/confirm"), null,
                Map.of("clientSecret", intent.get("clientSecret").asText(), "cardNumber", "4242424242424242",
                        "cvc", "123")).andExpect(status().isBadRequest());
        assertThat(booking(customer, id).get("status").asText()).isEqualTo("PENDING");

        // No request or response type in the payment or booking API has a field for card data.
        List<String> fields = Stream.concat(Arrays.stream(PaymentDtos.class.getDeclaredClasses()),
                        Arrays.stream(BookingDtos.class.getDeclaredClasses()))
                .filter(Class::isRecord)
                .flatMap(c -> Arrays.stream(c.getRecordComponents()).map(RecordComponent::getName))
                .map(String::toLowerCase).toList();
        assertThat(fields).noneMatch(f -> f.contains("cardnumber") || f.contains("cvc") || f.contains("cvv")
                || f.contains("expiry") || f.equals("number"));
        assertThat(jdbc.queryForList("select column_name from information_schema.columns where table_name = 'payments'",
                String.class)).noneMatch(c -> c.contains("card_number") || c.contains("cvc") || c.contains("expiry"));
    }

    @Test
    void noCashMethodAndNoDepositAnywhere() throws Exception {
        assertThat(Payment.Method.values()).containsExactly(Payment.Method.CARD, Payment.Method.ONLINE);
        assertThat(Payment.Status.values()).containsExactly(Payment.Status.PENDING, Payment.Status.SUCCESS,
                Payment.Status.FAILED, Payment.Status.REFUNDED);
        assertThatThrownBy(() -> jdbc.update("update payments set method = 'CASH' where id = (select min(id) from payments)"))
                .isInstanceOf(DataIntegrityViolationException.class);

        NewScooter s = newScooter("300.00", "20.00");
        String quote = call(post("/api/bookings/quote"), null, Map.of("scooterId", s.id()))
                .andReturn().getResponse().getContentAsString();
        String scooter = call(get("/api/scooters/" + s.id()), null, null).andReturn().getResponse().getContentAsString();
        String customer = register("Nora Plain");
        String id = createBooking(customer, s.id(), now(), null);
        String bookingJson = call(get("/api/bookings/" + id), customer, null).andReturn().getResponse().getContentAsString();
        assertThat(quote + scooter + bookingJson).doesNotContainIgnoringCase("deposit");
        assertThat(json.readTree(quote).get("initialCharge").decimalValue()).isEqualByComparingTo("300.00");
        assertThat(jdbc.queryForList("select column_name from information_schema.columns "
                + "where table_name in ('bookings', 'scooters', 'payments')", String.class))
                .noneMatch(c -> c.contains("deposit"));
    }

    // ---------------------------------------------------------------- helpers

    private int paymentRows(String bookingId) {
        return jdbc.queryForObject("select count(*) from payments where booking_id = ?", Integer.class,
                java.util.UUID.fromString(bookingId));
    }

    private org.springframework.test.web.servlet.ResultActions webhook(String payload, String signature) throws Exception {
        var req = post("/api/webhooks/stripe").contentType(MediaType.APPLICATION_JSON).content(payload);
        if (signature != null) {
            req.header("Stripe-Signature", signature);
        }
        return mvc.perform(req);
    }

    private static List<String> lines(JsonNode invoice) {
        return StreamSupport.stream(invoice.get("lines").spliterator(), false)
                .map(l -> l.get("quantity").decimalValue().setScale(2) + " x "
                        + l.get("unitPrice").decimalValue().setScale(2) + " = "
                        + l.get("amount").decimalValue().setScale(2))
                .toList();
    }
}
