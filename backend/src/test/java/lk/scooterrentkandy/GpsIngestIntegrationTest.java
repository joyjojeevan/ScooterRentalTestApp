package lk.scooterrentkandy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;

/** SDS 2.2 GPS Log (booking NOT NULL, DECIMAL precision, speed rules) and 4.3 speed-violation detection. */
class GpsIngestIntegrationTest extends ApiTestSupport {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void idlePingsUpdateThePositionButAreNotLogged() throws Exception {
        NewScooter s = newScooter("300.00", "20.00");
        device(Map.of("scooterCode", s.code(), "latitude", 7.30123456, "longitude", 80.64987654, "speedKmh", 12.345))
                .andExpect(status().isAccepted());

        assertThat(logs(s.id())).isZero();
        JsonNode live = StreamSupport.stream(body(call(get("/api/admin/gps/live"), adminToken, null)).spliterator(), false)
                .filter(p -> p.get("code").asText().equals(s.code())).findFirst().orElseThrow();
        assertThat(live.get("latitude").asDouble()).isEqualTo(7.301235);   // SDS DECIMAL(9,6)
        assertThat(live.get("lastSeen").isNull()).isFalse();
        assertThat(jdbc.queryForObject("select last_speed_kmh from scooters where id = ?", java.math.BigDecimal.class,
                java.util.UUID.fromString(s.id()))).isEqualByComparingTo("12.35");                    // SDS DECIMAL(5,2)
    }

    @Test
    void rentalPingsAlwaysCarryTheBookingAndTheDeviceMayNameIt() throws Exception {
        NewScooter s = newScooter("300.00", "20.00");
        String customer = register("Gee Pee");
        String id = activeRental(customer, "Gee Pee", s.id(), null, "pm_card_visa");

        device(ping(s.code(), 20, null)).andExpect(status().isAccepted());
        device(ping(s.code(), 21, id)).andExpect(status().isAccepted());
        device(ping(s.code(), 22, java.util.UUID.randomUUID().toString())).andExpect(status().isConflict());   // not this scooter's rental

        assertThat(jdbc.queryForList("select booking_id from gps_pings where scooter_id = ?", java.util.UUID.class,
                java.util.UUID.fromString(s.id()))).containsExactly(java.util.UUID.fromString(id), java.util.UUID.fromString(id));
        assertThat(body(call(get("/api/bookings/" + id + "/tracking"), customer, null)).get("trail").size()).isEqualTo(2);
        // A booking id sent for an idle scooter is rejected too.
        NewScooter idle = newScooter("300.00", "20.00");
        device(ping(idle.code(), 20, id)).andExpect(status().isConflict());
    }

    @Test
    void speedMustBePresentNonNegativeAndWithinTheSdsRange() throws Exception {
        NewScooter s = newScooter("300.00", "20.00");
        Map<String, Object> noSpeed = Map.of("scooterCode", s.code(), "latitude", 7.29, "longitude", 80.64);
        device(noSpeed).andExpect(status().isBadRequest());
        device(ping(s.code(), -1, null)).andExpect(status().isBadRequest());
        device(ping(s.code(), 1000, null)).andExpect(status().isBadRequest());
        device(ping(s.code(), 999.99, null)).andExpect(status().isAccepted());
        assertThatDbRejectsNegativeSpeed();
    }

    @Test
    void violationsFireOnTheCrossingUsingTheStoredPreviousSpeed() throws Exception {
        NewScooter s = newScooter("300.00", "20.00");
        String customer = register("Spee Dy");
        String id = activeRental(customer, "Spee Dy", s.id(), null, "pm_card_visa");
        int smsBefore = speedSms(s.code());

        device(ping(s.code(), 55, null)).andExpect(status().isAccepted());
        device(ping(s.code(), 61, null)).andExpect(status().isAccepted());    // crossing: violation
        device(ping(s.code(), 75, null)).andExpect(status().isAccepted());    // still above: nothing new
        assertThat(violations(s.code())).hasSize(1);

        // The previous speed is persisted, not held in memory: it survives a restart.
        jdbc.update("update scooters set last_speed_kmh = 80 where id = ?", java.util.UUID.fromString(s.id()));
        device(ping(s.code(), 90, null)).andExpect(status().isAccepted());    // previous 80: no crossing
        assertThat(violations(s.code())).hasSize(1);
        jdbc.update("update scooters set last_speed_kmh = 40 where id = ?", java.util.UUID.fromString(s.id()));
        device(ping(s.code(), 62, null)).andExpect(status().isAccepted());    // previous 40: crossing
        List<JsonNode> v = violations(s.code());
        assertThat(v).hasSize(2);
        assertThat(v.get(1).get("bookingReference").asText()).isEqualTo(booking(customer, id).get("reference").asText());
        assertThat(v.get(1).get("riderName").asText()).isEqualTo("Spee Dy");
        // Routed through the outbound sender as SMS to admins (mock SNS), as well as in-app.
        assertThat(speedSms(s.code())).isEqualTo(smsBefore + 2);
    }

    @Test
    void outOfOrderReportsAreLoggedButDoNotMoveTheScooter() throws Exception {
        NewScooter s = newScooter("300.00", "20.00");
        String customer = register("Late Ping");
        String id = activeRental(customer, "Late Ping", s.id(), null, "pm_card_visa");
        device(Map.of("scooterCode", s.code(), "latitude", 7.30, "longitude", 80.64, "speedKmh", 30,
                "recordedAt", now().plusMinutes(10).toString())).andExpect(status().isAccepted());
        device(Map.of("scooterCode", s.code(), "latitude", 7.40, "longitude", 80.70, "speedKmh", 70,
                "recordedAt", now().plusMinutes(5).toString())).andExpect(status().isAccepted());

        assertThat(logs(s.id())).isEqualTo(2);
        assertThat(jdbc.queryForObject("select latitude from scooters where id = ?", Double.class, java.util.UUID.fromString(s.id()))).isEqualTo(7.30);
        assertThat(violations(s.code())).isEmpty();
        assertThat(body(call(get("/api/bookings/" + id + "/tracking"), customer, null)).get("trail").size()).isEqualTo(2);
    }

    // ---------------------------------------------------------------- helpers

    private void assertThatDbRejectsNegativeSpeed() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> jdbc.update("update gps_pings set speed_kmh = -1 where id = (select min(id) from gps_pings)"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    private Map<String, Object> ping(String code, double speed, String bookingId) {
        Map<String, Object> m = new HashMap<>(Map.of("scooterCode", code, "latitude", 7.2936, "longitude", 80.6413,
                "speedKmh", speed));
        if (bookingId != null) {
            m.put("bookingId", bookingId);
        }
        return m;
    }

    private ResultActions device(Map<String, Object> payload) throws Exception {
        return mvc.perform(post("/api/gps/pings").header("X-Device-Key", "local-device-key")
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(payload)));
    }

    private int logs(String scooterId) {
        return jdbc.queryForObject("select count(*) from gps_pings where scooter_id = ?", Integer.class,
                java.util.UUID.fromString(scooterId));
    }

    private List<JsonNode> violations(String code) throws Exception {
        List<JsonNode> all = StreamSupport.stream(body(call(get("/api/admin/gps/speed-violations?limit=100"),
                adminToken, null)).spliterator(), false).filter(v -> v.get("scooterCode").asText().equals(code)).toList();
        List<JsonNode> oldestFirst = new java.util.ArrayList<>(all);
        java.util.Collections.reverse(oldestFirst);
        return oldestFirst;
    }

    private int speedSms(String code) {
        return jdbc.queryForObject("select count(*) from notifications where channel = 'SMS' and subject = ?",
                Integer.class, "Speed violation: " + code);
    }
}
