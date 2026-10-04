package lk.scooterrentkandy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.Map;
import java.util.stream.StreamSupport;
import lk.scooterrentkandy.config.AppProperties;
import lk.scooterrentkandy.config.DataSeeder;
import lk.scooterrentkandy.user.Role;
import lk.scooterrentkandy.user.User;
import lk.scooterrentkandy.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Phase 1 (decision E3): SDS status values, contractSigned on the booking, soft-deleted scooters, roles. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SdsStatusModelIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    UserRepository users;

    @Autowired
    PasswordEncoder encoder;

    @Autowired
    AppProperties props;

    @Autowired
    JdbcTemplate jdbc;

    String adminToken;

    @BeforeEach
    void login() throws Exception {
        adminToken = token(DataSeeder.ADMIN_EMAIL, DataSeeder.ADMIN_PASSWORD);
    }

    @Test
    void bookingMovesPendingToActiveWithContractSignedOnTheBooking() throws Exception {
        JsonNode reg = register("sds" + System.nanoTime() + "@example.com", "Sam Status");
        assertThat(reg.at("/user/role").asText()).isEqualTo("USER");
        String customer = reg.get("token").asText();
        LocalDateTime start = LocalDateTime.now().plusDays(150);

        JsonNode b = body(call(post("/api/bookings"), customer, Map.of("scooterId", newScooterId(),
                "startTime", start.toString(), "pickupLocation", "Kandy office")).andExpect(status().isCreated()));
        String id = b.get("id").asText();
        assertThat(b.get("status").asText()).isEqualTo("PENDING");
        assertThat(b.get("contractSigned").asBoolean()).isFalse();

        call(post("/api/bookings/" + id + "/contract/sign"), customer, Map.of("signedName", "Sam Status", "agree", true))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select contract_signed from bookings where id = ?", Boolean.class, id))
                .isTrue();
        call(get("/api/bookings/" + id), customer, null).andExpect(jsonPath("$.contractSigned").value(true));

        JsonNode intent = body(call(post("/api/bookings/" + id + "/payment-intent"), customer, null)
                .andExpect(status().isOk()));
        call(post("/mock-stripe/v1/payment_intents/" + intent.get("intentId").asText() + "/confirm"), null,
                Map.of("clientSecret", intent.get("clientSecret").asText(), "paymentMethod", "pm_card_visa"))
                .andExpect(status().isOk());
        call(get("/api/bookings/" + id), customer, null).andExpect(jsonPath("$.status").value("ACTIVE"));
        JsonNode p = body(call(get("/api/bookings/" + id + "/payment"), customer, null));
        assertThat(p.get("status").asText()).isEqualTo("SUCCESS");
        assertThat(p.get("method").asText()).isEqualTo("CARD");

        // Before the start time: cancellable with a full refund.
        call(post("/api/bookings/" + id + "/cancel"), customer, null).andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(body(call(get("/api/bookings/" + id + "/payment"), customer, null)).get("status").asText())
                .isEqualTo("REFUNDED");
    }

    @Test
    void scootersLeaveTheFleetBySoftDelete() throws Exception {
        String code = "TST-" + (System.nanoTime() % 100000);
        JsonNode s = body(call(post("/api/admin/scooters"), adminToken, Map.of("code", code, "model", "Test Dio",
                "plateNumber", "CP " + code, "hourlyRate", 250, "perKmRate", 15, "totalMileage", 0))
                .andExpect(status().isCreated()));
        String id = s.get("id").asText();
        assertThat(s.get("deleted").asBoolean()).isFalse();

        call(patch("/api/admin/scooters/" + id + "/status?value=RETIRED"), adminToken, null)
                .andExpect(status().isBadRequest());

        call(delete("/api/admin/scooters/" + id), adminToken, null).andExpect(jsonPath("$.deleted").value(true));
        assertThat(codes(body(mvc.perform(get("/api/scooters"))))).doesNotContain(code);
        assertThat(codes(body(call(get("/api/admin/scooters"), adminToken, null)))).contains(code);
        String customer = register("gone" + System.nanoTime() + "@example.com", "Gina Gone").get("token").asText();
        call(post("/api/bookings"), customer, Map.of("scooterId", id, "pickupLocation", "Kandy office",
                "startTime", LocalDateTime.now().plusDays(10).toString())).andExpect(status().isBadRequest());

        call(post("/api/admin/scooters/" + id + "/restore"), adminToken, null)
                .andExpect(jsonPath("$.deleted").value(false));
        assertThat(codes(body(mvc.perform(get("/api/scooters"))))).contains(code);
    }

    @Test
    void superAdminHasAdminRightsAndLegacyCustomerTokensAreRejected() throws Exception {
        String email = "super" + System.nanoTime() + "@example.com";
        User u = new User();
        u.setEmail(email);
        u.setPasswordHash(encoder.encode("Super@12345"));
        u.setFullName("Sue Super");
        u.setPhone("+94 77 111 2222");
        u.setRole(Role.SUPER_ADMIN);
        u.setActive(true);
        users.save(u);

        JsonNode login = body(login(email, "Super@12345").andExpect(status().isOk()));
        assertThat(login.at("/user/role").asText()).isEqualTo("SUPER_ADMIN");
        String superToken = login.get("token").asText();
        call(get("/api/admin/dashboard"), superToken, null).andExpect(status().isOk());
        call(get("/api/admin/bookings"), superToken, null).andExpect(status().isOk());

        String customer = token(DataSeeder.DEMO_EMAIL, DataSeeder.DEMO_PASSWORD);
        call(get("/api/admin/dashboard"), customer, null).andExpect(status().isForbidden());

        // A token issued before the migration, carrying the old CUSTOMER role, must not authenticate.
        String legacy = Jwts.builder().subject("2").claim("email", DataSeeder.DEMO_EMAIL).claim("role", "CUSTOMER")
                .issuedAt(new Date()).expiration(Date.from(Instant.now().plusSeconds(600)))
                .signWith(Keys.hmacShaKeyFor(props.jwt().secret().getBytes(StandardCharsets.UTF_8))).compact();
        call(get("/api/bookings"), legacy, null).andExpect(status().isUnauthorized());
    }

    @Test
    void databaseAllowsOnlyOneActiveBookingPerScooter() {
        // Seeded SRK-03 has an ACTIVE rental; making another of its bookings ACTIVE violates SDS 2.3.
        Object scooter = jdbc.queryForObject("select id from scooters where code = 'SRK-03'", Object.class);
        Object other = jdbc.queryForObject("select id from users where email = ?", Object.class, DataSeeder.DEMO_EMAIL);
        jdbc.update("insert into bookings (id, reference, customer_id, scooter_id, status, start_time, pickup_location, "
                + "contract_signed, created_at) values (?, 'BK-IDX-TEST', ?, ?, 'PENDING', now(), 'Kandy office', false, now())",
                java.util.UUID.randomUUID(), other, scooter);
        assertThatThrownBy(() -> jdbc.update("update bookings set status = 'ACTIVE' where reference = 'BK-IDX-TEST'"))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("update bookings set status = 'CANCELLED' where reference = 'BK-IDX-TEST'");
    }

    @Test
    void databaseRejectsNonSdsStatusValues() {
        assertThatThrownBy(() -> jdbc.update("update bookings set status = 'CONFIRMED' where id = (select min(id) from bookings)"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update payments set status = 'SUCCEEDED' where id = (select min(id) from payments)"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update scooters set status = 'RETIRED' where id = (select min(id) from scooters)"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update users set role = 'CUSTOMER' where id = (select min(id) from users)"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ---------------------------------------------------------------- helpers

    private String newScooterId() throws Exception {
        String code = "SM" + (System.nanoTime() % 1_000_000_000L);
        return body(call(post("/api/admin/scooters"), adminToken, Map.of("code", code, "model", "Test Dio",
                "plateNumber", "CP " + code, "hourlyRate", 300, "perKmRate", 20, "totalMileage", 0))
                .andExpect(status().isCreated())).get("id").asText();
    }

    private static java.util.List<String> codes(JsonNode scooters) {
        return StreamSupport.stream(scooters.spliterator(), false).map(s -> s.get("code").asText()).toList();
    }

    private ResultActions login(String email, String password) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", email, "password", password))));
    }

    private String token(String email, String password) throws Exception {
        return body(login(email, password).andExpect(status().isOk())).get("token").asText();
    }

    private JsonNode register(String email, String name) throws Exception {
        return body(mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email, "password", "Secret123!",
                                "fullName", name, "phone", "+94 77 000 0000"))))
                .andExpect(status().isCreated()));
    }

    private String scooterIdByCode(String code) throws Exception {
        for (JsonNode s : body(mvc.perform(get("/api/scooters")))) {
            if (s.get("code").asText().equals(code)) {
                return s.get("id").asText();
            }
        }
        throw new AssertionError("No scooter " + code);
    }

    private ResultActions call(MockHttpServletRequestBuilder req, String token, Object payload) throws Exception {
        if (token != null) {
            req.header("Authorization", "Bearer " + token);
        }
        if (payload != null) {
            req.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(payload));
        }
        return mvc.perform(req);
    }

    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }
}
