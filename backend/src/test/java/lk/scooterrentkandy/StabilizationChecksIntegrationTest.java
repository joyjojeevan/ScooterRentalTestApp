package lk.scooterrentkandy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;
import lk.scooterrentkandy.config.DataSeeder;
import lk.scooterrentkandy.contract.ContractRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/** Auth edge cases, contract rules, gear stock, GPS speed violations, maintenance, notifications and reports. */
class StabilizationChecksIntegrationTest extends ApiTestSupport {

    @Autowired
    ContractRepository contracts;

    // ---------------------------------------------------------------- authentication

    @Test
    void loginRejectsBadCredentialsAndTokens() throws Exception {
        login(DataSeeder.DEMO_EMAIL, "wrong").andExpect(status().isUnauthorized());
        login("nobody@example.com", "whatever").andExpect(status().isUnauthorized());
        assertThat(body(login(DataSeeder.DEMO_EMAIL, DataSeeder.DEMO_PASSWORD)).at("/user/role").asText())
                .isEqualTo("USER");
        assertThat(body(login(DataSeeder.ADMIN_EMAIL, DataSeeder.ADMIN_PASSWORD)).at("/user/role").asText())
                .isEqualTo("ADMIN");

        mvc.perform(get("/api/me").header("Authorization", "Bearer not-a-jwt")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/me").header("Authorization", "Bearer " + adminToken.substring(0, adminToken.length() - 4)
                + "AAAA")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/dashboard")).andExpect(status().isUnauthorized());
        call(get("/api/admin/dashboard"), adminToken, null).andExpect(status().isOk());
    }

    @Test
    void clientErrorsAreBadRequestNotServerErrors() throws Exception {
        call(get("/api/admin/reports/bookings.csv"), adminToken, null).andExpect(status().isBadRequest());
        call(get("/api/admin/gps/scooters/abc/trail"), adminToken, null).andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------- contracts

    @Test
    void contractMustBeSignedWithAccountNameAndIsStored() throws Exception {
        NewScooter s = newScooter("300.00", "20.00");
        String customer = register("Sita Signer");
        String other = register("Pat Payer");
        String id = createBooking(customer, s.id(), now().plusDays(10), null);

        call(get("/api/bookings/" + id + "/contract"), customer, null)
                .andExpect(jsonPath("$.signed").value(false))
                .andExpect(jsonPath("$.termsText").value(org.hamcrest.Matchers.containsString("Sita Signer")))
                .andExpect(jsonPath("$.termsText").value(org.hamcrest.Matchers.containsString("LKR 300.00 per hour")));
        call(post("/api/bookings/" + id + "/contract/sign"), customer, Map.of("signedName", "Someone Else", "agree", true))
                .andExpect(status().isBadRequest());
        call(post("/api/bookings/" + id + "/contract/sign"), customer, Map.of("signedName", "Sita Signer", "agree", false))
                .andExpect(status().isBadRequest());
        call(post("/api/bookings/" + id + "/contract/sign"), customer, Map.of("signedName", "Sita Signer", "agree", true))
                .andExpect(jsonPath("$.signed").value(true))
                .andExpect(jsonPath("$.signedName").value("Sita Signer"));
        var stored = contracts.findByBookingId(java.util.UUID.fromString(id)).orElseThrow();
        assertThat(stored.getSignedAt()).isNotNull();
        assertThat(stored.getSignedIp()).isNotBlank();

        // Only the owner can start payment; a stranger can't even see the booking.
        call(post("/api/bookings/" + id + "/payment-intent"), other, null).andExpect(status().isNotFound());
        confirm(initialIntent(customer, id), "pm_card_chargeDeclinedInsufficientFunds")
                .andExpect(jsonPath("$.error").value("Your card has insufficient funds."));
        confirm(initialIntent(customer, id), "pm_card_visa").andExpect(status().isOk());

        // Contract is frozen once paid.
        call(post("/api/bookings/" + id + "/contract/sign"), customer, Map.of("signedName", "Sita Signer", "agree", true))
                .andExpect(status().isConflict());
    }

    // ---------------------------------------------------------------- camping gear

    @Test
    void gearStockIsEnforcedAcrossBookings() throws Exception {
        String a = register("Cam Pa");
        String b = register("Cam Pb");
        NewScooter s1 = newScooter("300.00", "20.00");
        NewScooter s2 = newScooter("300.00", "20.00");
        String stove = gearIdByName("Camping stove + gas"); // 5 in stock

        call(post("/api/bookings/quote"), null, Map.of("scooterId", s1.id(),
                "gear", List.of(Map.of("gearItemId", stove, "quantity", 6))))
                .andExpect(status().isConflict());

        String first = createBooking(a, s1.id(), now(), List.of(Map.of("gearItemId", stove, "quantity", 5)));
        assertThat(booking(a, first).at("/gear/0/quantity").asInt()).isEqualTo(5);
        call(get("/api/gear"), null, null)
                .andExpect(jsonPath("$[?(@.name == 'Camping stove + gas')].available").value(0));

        book(b, s2.id(), now(), List.of(Map.of("gearItemId", stove, "quantity", 1))).andExpect(status().isConflict());

        // Releasing the first booking frees the stock again.
        call(post("/api/bookings/" + first + "/cancel"), a, null).andExpect(jsonPath("$.status").value("CANCELLED"));
        book(b, s2.id(), now(), List.of(Map.of("gearItemId", stove, "quantity", 1))).andExpect(status().isCreated());
    }

    // ---------------------------------------------------------------- GPS

    @Test
    void gpsStoresPingsAndAlertsAdminsOncePerSpeedViolation() throws Exception {
        NewScooter s = newScooter("300.00", "20.00");
        String code = s.code();
        int before = speedAlerts(code);

        ping(code, 7.2936, 80.6413, 60.0, now());   // at the limit: fine
        ping(code, 7.80, 81.20, 40.0, now());       // ~80 km from Kandy but slow: no alert (no geofence rule)
        assertThat(speedAlerts(code)).isEqualTo(before);

        ping(code, 7.30, 80.65, 72.5, now());
        ping(code, 7.31, 80.66, 80.0, now());       // still speeding: same violation, no second alert
        assertThat(speedAlerts(code)).isEqualTo(before + 1);

        ping(code, 7.32, 80.67, 35.0, now());
        ping(code, 7.33, 80.68, 65.0, now());       // new violation
        assertThat(speedAlerts(code)).isEqualTo(before + 2);

        // Idle scooter: no GPS logs are stored (SDS 2.2: logs belong to a rental), but its position is kept.
        JsonNode trail = body(call(get("/api/admin/gps/scooters/" + s.id() + "/trail?hours=1"), adminToken, null));
        assertThat(trail.get("trail").size()).isZero();
        assertThat(trail.at("/position/latitude").asDouble()).isEqualTo(7.33);
        assertThat(trail.at("/position/lastSeen").isNull()).isFalse();
        // Each crossing is a recorded violation for the dashboard, newest first.
        List<JsonNode> recorded = StreamSupport.stream(body(call(get("/api/admin/gps/speed-violations?limit=100"),
                adminToken, null)).spliterator(), false).filter(v -> v.get("scooterCode").asText().equals(code)).toList();
        assertThat(recorded).extracting(v -> v.get("speedKmh").decimalValue().setScale(2).toPlainString())
                .containsExactly("65.00", "72.50");
        assertThat(recorded.get(0).get("bookingReference").isNull()).isTrue();

        mvc.perform(post("/api/gps/pings").header("X-Device-Key", "wrong-key").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("scooterCode", code, "latitude", 7.29, "longitude", 80.64))))
                .andExpect(status().isUnauthorized());
    }

    // ---------------------------------------------------------------- maintenance

    @Test
    void maintenanceTakesScooterOutOfServiceUntilDone() throws Exception {
        NewScooter s = newScooter("300.00", "20.00");
        JsonNode rec = body(call(post("/api/admin/maintenance"), adminToken, Map.of("scooterId", s.id(),
                "type", "REPAIR", "description", "Replace brake cable", "startNow", true))
                .andExpect(status().isCreated()));
        assertThat(rec.get("status").asText()).isEqualTo("IN_PROGRESS");
        call(get("/api/scooters/" + s.id()), null, null).andExpect(jsonPath("$.status").value("MAINTENANCE"));

        // Can't be booked while it's in the workshop.
        book(register("Will Wait"), s.id(), now(), null).andExpect(status().isConflict());

        call(post("/api/admin/maintenance/" + rec.get("id").asText() + "/complete"), adminToken,
                Map.of("cost", 2500, "notes", "Done"))
                .andExpect(jsonPath("$.status").value("DONE"));
        call(get("/api/scooters/" + s.id()), null, null).andExpect(jsonPath("$.status").value("AVAILABLE"));
        call(get("/api/admin/maintenance?scooterId=" + s.id()), adminToken, null).andExpect(status().isOk());
    }

    // ---------------------------------------------------------------- notifications and reports

    @Test
    void adminBroadcastReachesCustomersOnly() throws Exception {
        String customer = register("Nina News");
        String subject = "Road closure " + System.nanoTime();
        JsonNode res = body(call(post("/api/admin/notifications/broadcast"), adminToken,
                Map.of("subject", subject, "message", "Peradeniya Road closed today")).andExpect(status().isOk()));
        assertThat(res.get("recipients").asInt()).isGreaterThan(0);
        assertThat(body(call(get("/api/notifications"), customer, null)).toString()).contains(subject);
        assertThat(body(call(get("/api/notifications"), adminToken, null)).toString()).doesNotContain(subject);
        call(get("/api/notifications/unread-count"), customer, null).andExpect(status().isOk());
        call(post("/api/notifications/read-all"), customer, null).andExpect(status().is2xxSuccessful());
        call(post("/api/admin/notifications/broadcast"), adminToken, Map.of("subject", "", "message", ""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void reportsAndCsvExport() throws Exception {
        LocalDate today = LocalDate.now();
        call(get("/api/admin/reports/summary?from=" + today.minusDays(30) + "&to=" + today), adminToken, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currency").value("LKR"))
                .andExpect(jsonPath("$.fleetUtilizationPct").isNumber())
                .andExpect(jsonPath("$.revenue").isNumber())
                .andExpect(jsonPath("$.revenueByDay").isArray());
        JsonNode dash = body(call(get("/api/admin/dashboard"), adminToken, null));
        assertThat(dash.get("activeRentals").asInt()).isGreaterThanOrEqualTo(1);

        String csv = call(get("/api/admin/reports/bookings.csv?from=" + today.minusDays(30) + "&to=" + today),
                adminToken, null)
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("attachment")))
                .andReturn().getResponse().getContentAsString();
        assertThat(csv).startsWith("reference,status,customer,email,scooter,plate,start_time,end_time,billable_hours,"
                + "distance_km,total_cost,amount_paid,payment_status");
        assertThat(csv.lines().count()).isGreaterThan(1);
    }

    private int speedAlerts(String code) throws Exception {
        int n = 0;
        for (JsonNode note : body(call(get("/api/notifications"), adminToken, null))) {
            if (note.get("subject").asText().equals("Speed violation: " + code)) {
                n++;
            }
        }
        return n;
    }
}
