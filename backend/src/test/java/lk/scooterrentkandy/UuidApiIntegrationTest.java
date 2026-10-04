package lk.scooterrentkandy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import lk.scooterrentkandy.config.AppProperties;
import lk.scooterrentkandy.config.DataSeeder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** E5 API contract: UUID ids are JSON strings, paths take UUIDs, the JWT subject is the user's UUID. */
class UuidApiIntegrationTest extends ApiTestSupport {

    @Autowired
    AppProperties props;

    @Test
    void idsAreUuidStringsEverywhereAndPathsAcceptThem() throws Exception {
        NewScooter s = newScooter("300.00", "20.00");
        String customer = register("Uu Id");
        String id = activeRental(customer, "Uu Id", s.id(), null, "pm_card_visa");

        JsonNode b = booking(customer, id);
        assertThat(b.get("id").isTextual()).isTrue();
        assertThat(UUID.fromString(b.get("id").asText())).isNotNull();
        assertThat(UUID.fromString(b.at("/customer/id").asText())).isNotNull();
        assertThat(b.at("/scooter/id").asText()).isEqualTo(s.id());
        assertThat(b.get("reference").asText()).startsWith("BK-");

        JsonNode payment = body(call(get("/api/bookings/" + id + "/payment"), customer, null).andExpect(status().isOk()));
        assertThat(UUID.fromString(payment.get("id").asText())).isNotNull();
        assertThat(payment.get("bookingId").asText()).isEqualTo(id);
        assertThat(UUID.fromString(payment.at("/transactions/0/id").asText())).isNotNull();
        call(get("/api/scooters/" + s.id()), null, null).andExpect(status().isOk());
        call(get("/api/bookings/" + id + "/tracking"), customer, null).andExpect(status().isOk());
        call(get("/api/admin/gps/scooters/" + s.id() + "/trail"), adminToken, null).andExpect(status().isOk());
        JsonNode gear = body(mvc.perform(get("/api/gear"))).get(0);
        assertThat(UUID.fromString(gear.get("id").asText())).isNotNull();

        // Old numeric ids and malformed ids are client errors, not server errors; unknown UUIDs are 404.
        call(get("/api/bookings/7"), customer, null).andExpect(status().isBadRequest());
        call(get("/api/scooters/not-a-uuid"), null, null).andExpect(status().isBadRequest());
        call(get("/api/bookings/" + UUID.randomUUID()), customer, null).andExpect(status().isNotFound());
    }

    @Test
    void jwtSubjectIsTheUserUuidAndNumericSubjectsAreRejected() throws Exception {
        String token = token(DataSeeder.DEMO_EMAIL, DataSeeder.DEMO_PASSWORD);
        String subject = Jwts.parser().verifyWith(key()).build().parseSignedClaims(token).getPayload().getSubject();
        JsonNode me = body(call(get("/api/me"), token, null).andExpect(status().isOk()));
        assertThat(subject).isEqualTo(me.get("id").asText());
        assertThat(UUID.fromString(subject)).isNotNull();

        // A token issued before E5 carried the numeric user id: it no longer authenticates.
        String numeric = Jwts.builder().subject("2").claim("email", DataSeeder.DEMO_EMAIL).claim("role", "USER")
                .issuedAt(new Date()).expiration(Date.from(Instant.now().plusSeconds(600))).signWith(key()).compact();
        call(get("/api/bookings"), numeric, null).andExpect(status().isUnauthorized());
    }

    private javax.crypto.SecretKey key() {
        return Keys.hmacShaKeyFor(props.jwt().secret().getBytes(StandardCharsets.UTF_8));
    }
}
