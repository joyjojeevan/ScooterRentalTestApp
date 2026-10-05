package lk.scooterrentkandy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;
import lk.scooterrentkandy.services.GpsRetentionJob;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** SDS 8.3: GPS logs are retained for 6 months; logs of a rental under way are never purged. */
class GpsRetentionIntegrationTest extends ApiTestSupport {

    @Autowired
    GpsRetentionJob retention;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void completedRentalLogsAreKeptForSixMonthsAndActiveRentalLogsAlways() throws Exception {
        LocalDateTime cutoff = retention.cutoff();
        LocalDateTime sixMonthsAgo = LocalDateTime.now(clock).minusMonths(6);
        assertThat(cutoff).isBetween(sixMonthsAgo.minusSeconds(2), sixMonthsAgo);
        assertThat(cutoff.getNano()).isZero();

        // A completed rental and one still under way.
        NewScooter done = newScooter("300.00", "20.00");
        String customer = register("Reten Tion");
        String completed = activeRental(customer, "Reten Tion", done.id(), null, "pm_card_visa");
        clock.advance(Duration.ofMinutes(30));
        complete(customer, completed).andExpect(status().isOk());
        clock.reset();
        NewScooter riding = newScooter("300.00", "20.00");
        String active = activeRental(register("Still Riding"), "Still Riding", riding.id(), null, "pm_card_visa");

        UUID justTooOld = ping(done.id(), completed, cutoff.minusSeconds(1));
        UUID monthTooOld = ping(done.id(), completed, cutoff.minusDays(30));
        UUID atCutoff = ping(done.id(), completed, cutoff);
        UUID dayInside = ping(done.id(), completed, cutoff.plusDays(1));
        UUID eightDaysOld = ping(done.id(), completed, LocalDateTime.now(clock).minusDays(8)); // old 7-day rule
        UUID activeVeryOld = ping(riding.id(), active, cutoff.minusDays(60));
        long archivedOld = archivedPing(cutoff.minusDays(90));

        retention.purgeBefore(cutoff);

        assertThat(exists(justTooOld)).as("1 s past 6 months").isFalse();
        assertThat(exists(monthTooOld)).as("a month past 6 months").isFalse();
        assertThat(exists(atCutoff)).as("exactly 6 months").isTrue();
        assertThat(exists(dayInside)).as("inside 6 months").isTrue();
        assertThat(exists(eightDaysOld)).as("8 days: no longer purged").isTrue();
        assertThat(exists(activeVeryOld)).as("rental under way").isTrue();
        assertThat(jdbc.queryForObject("select count(*) from legacy_gps_pings_unlinked where id = ?", Integer.class,
                archivedOld)).as("archive untouched").isEqualTo(1);

        // The scheduled run (cutoff = now - 6 months) also keeps everything that remains inside the window.
        assertThat(retention.purge()).isLessThanOrEqualTo(1);
        assertThat(exists(dayInside) && exists(eightDaysOld) && exists(activeVeryOld)).isTrue();
    }

    private UUID ping(String scooterId, String bookingId, LocalDateTime at) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into gps_pings (id, scooter_id, booking_id, latitude, longitude, speed_kmh, recorded_at) "
                + "values (?, ?, ?, 7.2936, 80.6413, 20, ?)", id, UUID.fromString(scooterId), UUID.fromString(bookingId),
                Timestamp.valueOf(at));
        return id;
    }

    /** Archives keep their historical BIGINT ids. */
    private long archivedPing(LocalDateTime at) {
        long id = 900_000 + System.nanoTime() % 100_000;
        jdbc.update("insert into legacy_gps_pings_unlinked (id, scooter_id, latitude, longitude, speed_kmh, recorded_at) "
                + "values (?, 1, 7.2936, 80.6413, 20, ?)", id, Timestamp.valueOf(at));
        return id;
    }

    private boolean exists(UUID pingId) {
        return jdbc.queryForObject("select count(*) from gps_pings where id = ?", Integer.class, pingId) == 1;
    }
}
