package lk.scooterrentkandy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import lk.scooterrentkandy.services.DataSeeder;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class BookingFlowIntegrationTest extends ApiTestSupport {

    @Test
    void fullRentalLifecycle() throws Exception {
        NewScooter s = newScooter("320.00", "20.00");
        String customer = register("Sam Rider");
        String tent = gearIdByName("2-person tent");

        JsonNode booking = body(book(customer, s.id(), now(), List.of(Map.of("gearItemId", tent, "quantity", 1)))
                .andExpect(status().isCreated()));
        String id = booking.get("id").asText();
        assertThat(booking.get("status").asText()).isEqualTo("PENDING");
        assertThat(booking.get("endTime").isNull()).isTrue();
        // 1 h x 320 + 1 day x 1500
        assertThat(booking.get("initialCharge").decimalValue()).isEqualByComparingTo("1820.00");

        // The same scooter can't be taken by someone else while this booking holds it.
        String other = register("Other Person");
        book(other, s.id(), now(), null).andExpect(status().isConflict());

        sign(customer, id, "sam rider");
        confirm(initialIntent(customer, id), "pm_card_visa").andExpect(status().isOk());
        call(get("/api/bookings/" + id), customer, null).andExpect(jsonPath("$.status").value("ACTIVE"));

        // Another customer cannot see it.
        call(get("/api/bookings/" + id), other, null).andExpect(status().isNotFound());
        call(get("/api/bookings/" + id + "/tracking"), customer, null).andExpect(status().isOk());

        clock.advance(Duration.ofHours(5).plusMinutes(1));
        JsonNode done = body(complete(customer, id).andExpect(status().isOk()));
        assertThat(done.get("status").asText()).isEqualTo("COMPLETED");
        // 6 h x 320 + 0 km + tent 1 day x 1500
        assertThat(done.get("totalCost").decimalValue()).isEqualByComparingTo("3420.00");

        JsonNode payment = body(call(get("/api/bookings/" + id + "/payment"), customer, null).andExpect(status().isOk()));
        assertThat(payment.get("amount").decimalValue()).isEqualByComparingTo("3420.00");
        assertThat(payment.get("status").asText()).isEqualTo("SUCCESS");
        call(get("/api/bookings/" + id + "/invoices"), customer, null)
                .andExpect(jsonPath("$[0].kind").value("FINAL"))
                .andExpect(jsonPath("$[0].paymentStatus").value("SUCCESS"));

        call(get("/api/admin/reports/summary"), adminToken, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currency").value("LKR"));
        call(get("/api/admin/dashboard"), adminToken, null).andExpect(status().isOk());
    }

    @Test
    void customersCannotUseAdminEndpoints() throws Exception {
        String customer = token(DataSeeder.DEMO_EMAIL, DataSeeder.DEMO_PASSWORD);
        call(get("/api/admin/bookings"), customer, null).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
        mvc.perform(get("/api/bookings")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/scooters")).andExpect(status().isOk());
    }

    @Test
    void wrongHttpMethodIsMethodNotAllowed() throws Exception {
        call(get("/api/admin/notifications/broadcast"), adminToken, null)
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", "POST"))
                .andExpect(jsonPath("$.status").value(405));
    }

    @Test
    void gpsDeviceIngestRequiresKey() throws Exception {
        String ping = json.writeValueAsString(Map.of("scooterCode", "SRK-02", "latitude", 7.29, "longitude", 80.63,
                "speedKmh", 20));
        mvc.perform(post("/api/gps/pings").contentType(MediaType.APPLICATION_JSON).content(ping))
                .andExpect(status().isUnauthorized());
        // SDS 2.2: speed is required.
        mvc.perform(post("/api/gps/pings").header("X-Device-Key", "local-device-key").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("scooterCode", "SRK-02", "latitude", 7.29, "longitude", 80.63))))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/gps/pings").header("X-Device-Key", "local-device-key")
                        .contentType(MediaType.APPLICATION_JSON).content(ping))
                .andExpect(status().isAccepted());
    }
}
