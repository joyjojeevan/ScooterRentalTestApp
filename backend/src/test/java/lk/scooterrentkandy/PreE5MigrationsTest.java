package lk.scooterrentkandy;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * The pre-E5 SDS fixes (V4-V9), each run against data shaped like the live database at V3: completed and active
 * bookings with consolidated payments, a cancelled unpaid booking, linked and unlinked GPS pings.
 */
class PreE5MigrationsTest {

    static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 1, 9, 15);

    DriverManagerDataSource ds;
    JdbcTemplate jdbc;

    @BeforeEach
    void atV3() {
        ds = new DriverManagerDataSource(
                "jdbc:h2:mem:pre" + System.nanoTime() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa", "");
        flyway("3").migrate();
        jdbc = new JdbcTemplate(ds);
        jdbc.update("insert into users (id, email, password_hash, full_name, role, active, created_at) values "
                + "(1, 'a@x.lk', 'h', 'Admin', 'ADMIN', true, now()), (2, 'c@x.lk', 'h', 'Cust', 'USER', true, now())");
        jdbc.update("insert into scooters (id, code, model, plate_number, status, hourly_rate, per_km_rate, odometer_km, "
                + "latitude, longitude, created_at) values "
                + "(1, 'S1', 'Dio', 'P1', 'AVAILABLE', 145.83, 0, 4203, 7.3128175105, 80.6413, now()),"
                + "(2, 'S2', 'Ray', 'P2', 'RENTED', 166.67, 0, 7800, 7.4040141091, 80.65, now())");
        String booking = "insert into bookings (id, reference, customer_id, scooter_id, status, start_time, end_time, "
                + "distance_km, total_cost, pickup_location, contract_signed, created_at) values (?, ?, 2, ?, ?, ?, ?, ?, ?, ?, ?, now())";
        jdbc.update(booking, 1, "BK1", 1, "COMPLETED", ts(T0.minusDays(20)), ts(T0.minusDays(17)), 320, 14000, "Kandy office", true);
        jdbc.update(booking, 3, "BK3", 2, "ACTIVE", ts(T0), null, null, null, "Kandy office", true);
        jdbc.update(booking, 5, "BK5", 1, "CANCELLED", ts(T0.plusDays(1)), null, null, null, "Kandy office", false);
        String payment = "insert into payments (id, booking_id, amount, method, status, provider, stripe_payment_id, "
                + "paid_at, created_at) values (?, ?, ?, 'CARD', 'SUCCESS', 'mock', ?, ?, now())";
        jdbc.update(payment, 1, 1, 14000, "mock_seed_BK1_rental", ts(T0.minusDays(22)));
        jdbc.update(payment, 3, 3, 37200, "mock_seed_BK3_rental", ts(T0.minusDays(2)));
        String ping = "insert into gps_pings (scooter_id, booking_id, latitude, longitude, speed_kmh, recorded_at) "
                + "values (?, ?, ?, ?, ?, ?)";
        jdbc.update(ping, 2, 3, 7.2911581583722445, 80.64329327343441, 39.9, ts(T0.plusMinutes(1)));
        jdbc.update(ping, 2, 3, 7.3011581583722445, 80.64329327343441, 72.5, ts(T0.plusMinutes(2)));
        jdbc.update(ping, 1, null, 7.3128175105, 80.6413, 72.5, ts(T0.plusDays(2)));   // idle: no booking
    }

    @Test
    void v4BackfillsOneInitialTransactionPerPayment() {
        flyway("4").migrate();
        assertThat(rows("select payment_id, kind || ':' || status || ':' || amount || ':' || provider_ref "
                + "from payment_transactions")).containsExactlyInAnyOrderEntriesOf(Map.of(
                1L, "INITIAL:SUCCESS:14000.00:mock_seed_BK1_rental",
                3L, "INITIAL:SUCCESS:37200.00:mock_seed_BK3_rental"));
        assertThat(jdbc.queryForObject("select count(*) from payments", Integer.class)).isEqualTo(2);
    }

    @Test
    void v5MovesAFrozenSettlementOffTheSdsFieldsOfAnActiveBooking() {
        // A balance-due booking in the V3 shape: ACTIVE but with end_time/total_cost already set.
        jdbc.update("update bookings set end_time = ?, distance_km = 12.5, total_cost = 9000 where id = 3",
                ts(T0.plusHours(30)));
        flyway("5").migrate();

        assertThat(jdbc.queryForMap("select end_time, distance_km, total_cost, ended_at, pending_distance_km, "
                + "pending_total_cost from bookings where id = 3"))
                .containsEntry("end_time", null).containsEntry("total_cost", null).containsEntry("distance_km", null)
                .containsEntry("ended_at", ts(T0.plusHours(30)))
                .containsEntry("pending_distance_km", new java.math.BigDecimal("12.50"))
                .containsEntry("pending_total_cost", new java.math.BigDecimal("9000.00"));
        // Completed bookings keep their final fields.
        assertThat(jdbc.queryForObject("select total_cost from bookings where id = 1", java.math.BigDecimal.class))
                .isEqualByComparingTo("14000");
        // The SDS rule is now enforced by the database.
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> jdbc.update("update bookings set total_cost = 1 where id = 5"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void v6MakesPickupMandatoryAndAddsDropLocation() {
        jdbc.update("update bookings set pickup_location = null where id = 5");
        flyway("6").migrate();

        assertThat(strings("select pickup_location from bookings order by id"))
                .containsExactly("Kandy office", "Kandy office", "Not recorded");
        assertThat(jdbc.queryForList("select booking_id from legacy_booking_pickup_missing", Long.class))
                .containsExactly(5L);
        assertThat(jdbc.queryForObject("select count(*) from bookings where drop_location is not null", Integer.class))
                .isZero();
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> jdbc.update("update bookings set pickup_location = null where id = 1"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void v7TurnsTheIntegerOdometerIntoDecimalTotalMileage() {
        flyway("7").migrate();

        assertThat(rows("select id, total_mileage from scooters"))
                .containsExactlyInAnyOrderEntriesOf(Map.of(1L, "4203.00", 2L, "7800.00"));
        jdbc.update("update scooters set total_mileage = total_mileage + 2.22 where id = 1");
        assertThat(jdbc.queryForObject("select total_mileage from scooters where id = 1", java.math.BigDecimal.class))
                .isEqualByComparingTo("4205.22");
        assertThat(strings("select column_name from information_schema.columns where table_name = 'scooters'"))
                .doesNotContain("odometer_km");
    }

    @Test
    void v8ArchivesIdlePingsAndAppliesSdsPrecision() {
        flyway("8").migrate();

        // The idle ping is archived and gone from the GPS log; logged pings all have a booking.
        assertThat(jdbc.queryForObject("select count(*) from legacy_gps_pings_unlinked", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForList("select booking_id from gps_pings order by id", Long.class)).containsExactly(3L, 3L);
        // Original precision archived; the log holds DECIMAL(9,6) / DECIMAL(5,2).
        assertThat(jdbc.queryForObject("select latitude from legacy_gps_ping_precision order by id fetch first 1 rows only",
                Double.class)).isEqualTo(7.2911581583722445);
        assertThat(jdbc.queryForMap("select latitude, longitude, speed_kmh from gps_pings order by id fetch first 1 rows only"))
                .containsEntry("latitude", new java.math.BigDecimal("7.291158"))
                .containsEntry("longitude", new java.math.BigDecimal("80.643293"))
                .containsEntry("speed_kmh", new java.math.BigDecimal("39.90"));
        // Scooter last-known state from the latest ping, including the archived idle one.
        assertThat(rows("select id, last_speed_kmh from scooters"))
                .containsExactlyInAnyOrderEntriesOf(Map.of(1L, "72.50", 2L, "72.50"));
        assertThat(jdbc.queryForObject("select last_seen_at from scooters where id = 1", Timestamp.class))
                .isEqualTo(ts(T0.plusDays(2)));
        assertThat(jdbc.queryForObject("select count(*) from speed_violations", Integer.class)).isZero();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(
                        "insert into gps_pings (scooter_id, latitude, longitude, speed_kmh, recorded_at) values (1, 7, 80, 10, now())"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void v8RefusesToInventSpeedsForLoggedPingsWithoutOne() {
        jdbc.update("update gps_pings set speed_kmh = null where booking_id = 3 and speed_kmh = 39.9");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> flyway("8").migrate())
                .isInstanceOf(org.flywaydb.core.api.FlywayException.class);
    }

    @Test
    void v9AllowsOnlyOneActiveBookingPerScooter() {
        flyway("9").migrate();

        String insert = "insert into bookings (id, reference, customer_id, scooter_id, status, start_time, pickup_location, "
                + "contract_signed, created_at) values (?, ?, 2, 2, ?, now(), 'Kandy office', true, now())";
        // Scooter 2 already has ACTIVE booking 3: a second ACTIVE one is rejected...
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(insert, 100, "BK-X", "ACTIVE"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        // ...while other statuses on the same scooter are unaffected.
        jdbc.update(insert, 101, "BK-P", "PENDING");
        jdbc.update(insert, 102, "BK-C", "CANCELLED");
        // A different scooter may have its own ACTIVE booking.
        jdbc.update(insert.replace("2, 2, ?", "2, 1, ?"), 103, "BK-A1", "ACTIVE");
        assertThat(jdbc.queryForObject("select count(*) from bookings where status = 'ACTIVE'", Integer.class)).isEqualTo(2);
    }

    // ---------------------------------------------------------------- helpers

    Flyway flyway(String target) {
        return Flyway.configure().dataSource(ds).locations("classpath:db/migration", "classpath:db/vendor/h2")
                .target(target).load();
    }

    Map<Long, String> rows(String sql) {
        Map<Long, String> out = new java.util.HashMap<>();
        jdbc.query(sql, rs -> {
            out.put(rs.getLong(1), rs.getString(2));
        });
        return out;
    }

    List<String> strings(String sql) {
        return jdbc.queryForList(sql, String.class);
    }

    static Timestamp ts(LocalDateTime t) {
        return t == null ? null : Timestamp.valueOf(t);
    }
}
