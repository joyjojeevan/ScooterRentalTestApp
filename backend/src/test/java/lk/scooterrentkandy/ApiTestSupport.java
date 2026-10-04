package lk.scooterrentkandy;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lk.scooterrentkandy.config.DataSeeder;
import lk.scooterrentkandy.support.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Shared helpers for API tests: accounts, scooters and the booking → payment → rental flow. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
abstract class ApiTestSupport {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    MutableClock clock;

    String adminToken;

    record NewScooter(String id, String code) {
    }

    @BeforeEach
    void loginAdmin() throws Exception {
        clock.reset();
        adminToken = token(DataSeeder.ADMIN_EMAIL, DataSeeder.ADMIN_PASSWORD);
    }

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    LocalDateTime now() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
    }

    // ---------------------------------------------------------------- accounts

    ResultActions login(String email, String password) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", email, "password", password))));
    }

    String token(String email, String password) throws Exception {
        return body(login(email, password).andExpect(status().isOk())).get("token").asText();
    }

    String register(String name) throws Exception {
        String email = name.toLowerCase().replace(' ', '.') + System.nanoTime() + "@example.com";
        return body(mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email, "password", "Secret123!",
                                "fullName", name, "phone", "+94 77 000 0000"))))
                .andExpect(status().isCreated())).get("token").asText();
    }

    // ---------------------------------------------------------------- fleet

    /** A scooter of its own for each test, so tests never compete for the seeded fleet. */
    NewScooter newScooter(String hourlyRate, String perKmRate) throws Exception {
        String code = "T" + (System.nanoTime() % 1_000_000_000L);
        JsonNode s = body(call(post("/api/admin/scooters"), adminToken, Map.of("code", code, "model", "Test Dio",
                "plateNumber", "CP " + code, "hourlyRate", new BigDecimal(hourlyRate),
                "perKmRate", new BigDecimal(perKmRate), "totalMileage", 1000)).andExpect(status().isCreated()));
        return new NewScooter(s.get("id").asText(), s.get("code").asText());
    }

    String gearIdByName(String name) throws Exception {
        for (JsonNode g : body(mvc.perform(get("/api/gear")))) {
            if (g.get("name").asText().equals(name)) {
                return g.get("id").asText();
            }
        }
        throw new AssertionError("No gear " + name);
    }

    // ---------------------------------------------------------------- booking flow

    ResultActions book(String token, String scooterId, LocalDateTime start, List<Map<String, Object>> gear)
            throws Exception {
        Map<String, Object> req = new HashMap<>(Map.of("scooterId", scooterId, "startTime", start.toString(),
                "pickupLocation", "Kandy office, Dalada Veediya"));
        if (gear != null) {
            req.put("gear", gear);
        }
        return call(post("/api/bookings"), token, req);
    }

    String createBooking(String token, String scooterId, LocalDateTime start, List<Map<String, Object>> gear)
            throws Exception {
        return body(book(token, scooterId, start, gear).andExpect(status().isCreated())).get("id").asText();
    }

    void sign(String token, String bookingId, String name) throws Exception {
        call(post("/api/bookings/" + bookingId + "/contract/sign"), token, Map.of("signedName", name, "agree", true))
                .andExpect(status().isOk());
    }

    JsonNode initialIntent(String token, String bookingId) throws Exception {
        return body(call(post("/api/bookings/" + bookingId + "/payment-intent"), token, null)
                .andExpect(status().isOk()));
    }

    /** What the browser's payment library does: confirm the intent with the provider using a token, not a card. */
    ResultActions confirm(JsonNode intent, String paymentMethod) throws Exception {
        return call(post("/mock-stripe/v1/payment_intents/" + intent.get("intentId").asText() + "/confirm"), null,
                Map.of("clientSecret", intent.get("clientSecret").asText(), "paymentMethod", paymentMethod,
                        "cardLast4", "4242"));
    }

    /** Registers a customer, books {@code scooter} from now, signs and pays the initial charge. */
    String activeRental(String token, String name, String scooterId, List<Map<String, Object>> gear,
            String paymentMethod) throws Exception {
        String id = createBooking(token, scooterId, now(), gear);
        sign(token, id, name);
        confirm(initialIntent(token, id), paymentMethod).andExpect(status().isOk());
        return id;
    }

    void ping(String scooterCode, double lat, double lng, double speed, LocalDateTime at) throws Exception {
        mvc.perform(post("/api/gps/pings").header("X-Device-Key", "local-device-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("scooterCode", scooterCode, "latitude", lat,
                                "longitude", lng, "speedKmh", speed, "recordedAt", at.toString()))))
                .andExpect(status().isAccepted());
    }

    ResultActions complete(String token, String bookingId) throws Exception {
        return call(put("/api/bookings/" + bookingId + "/complete"), token, null);
    }

    JsonNode booking(String token, String bookingId) throws Exception {
        return body(call(get("/api/bookings/" + bookingId), token, null).andExpect(status().isOk()));
    }

    // ---------------------------------------------------------------- plumbing

    ResultActions call(MockHttpServletRequestBuilder req, String token, Object payload) throws Exception {
        if (token != null) {
            req.header("Authorization", "Bearer " + token);
        }
        if (payload != null) {
            req.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(payload));
        }
        return mvc.perform(req);
    }

    JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }
}
