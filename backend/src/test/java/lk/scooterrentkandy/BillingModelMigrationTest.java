package lk.scooterrentkandy;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** V3 must move the date/deposit model to the SDS billing model without losing data. */
class BillingModelMigrationTest {

    @Test
    void v3ConsolidatesPaymentsAndKeepsLegacyData() {
        var ds = new DriverManagerDataSource(
                "jdbc:h2:mem:v3" + System.nanoTime() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa", "");
        flyway(ds, "2").migrate();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        LocalDateTime t0 = LocalDateTime.of(2026, 9, 12, 9, 30);

        jdbc.update("insert into users (id, email, password_hash, full_name, role, active, created_at) values "
                + "(1, 'a@x.lk', 'h', 'Admin', 'ADMIN', true, now()), (2, 'c@x.lk', 'h', 'Cust', 'USER', true, now())");
        jdbc.update("insert into scooters (id, code, model, plate_number, status, daily_rate, deposit_amount, odometer_km, created_at) values "
                + "(1, 'S1', 'Dio', 'P1', 'AVAILABLE', 3600, 15000, 4520, now()),"
                + "(2, 'S2', 'Ntorq', 'P2', 'RENTED', 4800, 20000, 2950, now()),"
                + "(3, 'S3', 'Vespa', 'P3', 'AVAILABLE', 6500, 35000, 3300, now())");
        jdbc.update("insert into gear_items (id, name, category, daily_rate, total_quantity, active) values (1, 'Tent', 'CAMPING', 1500, 6, true)");
        String booking = "insert into bookings (id, reference, customer_id, scooter_id, start_date, end_date, rental_days, status, "
                + "scooter_cost, gear_cost, deposit_amount, total_amount, start_odometer, end_odometer, damage_charge, "
                + "checked_out_at, returned_at, contract_signed, created_at) values (?, ?, 2, ?, ?, ?, 3, ?, 10800, 4500, ?, 15300, ?, ?, ?, ?, ?, true, now())";
        // 1 completed with deposit refunded, 2 out on rental, 3 paid not handed over, 4 cancelled with full refund
        jdbc.update(booking, 1, "BK1", 1, "2026-09-12", "2026-09-14", "COMPLETED", 15000, 4200, 4520, 0,
                Timestamp.valueOf(t0), Timestamp.valueOf(t0.plusDays(2).plusHours(8)));
        jdbc.update(booking, 2, "BK2", 2, "2026-10-01", "2026-10-03", "ACTIVE", 20000, 2950, null, null,
                Timestamp.valueOf(t0.plusDays(19)), null);
        jdbc.update(booking, 3, "BK3", 3, "2026-10-05", "2026-10-07", "ACTIVE", 35000, null, null, null, null, null);
        jdbc.update(booking, 4, "BK4", 1, "2026-11-01", "2026-11-02", "CANCELLED", 15000, null, null, null, null, null);
        jdbc.update("insert into booking_gear (id, booking_id, gear_item_id, quantity, daily_rate, line_total) values (1, 1, 1, 1, 1500, 4500)");

        String payment = "insert into payments (id, booking_id, amount, type, method, status, provider, provider_ref, created_at) "
                + "values (?, ?, ?, ?, ?, ?, 'mock', ?, ?)";
        Timestamp paid = Timestamp.valueOf(t0.minusDays(2));
        jdbc.update(payment, 1, 1, 15300, "RENTAL", "CARD", "SUCCESS", "ch_rental_1", paid);
        jdbc.update(payment, 2, 1, 15000, "DEPOSIT", "CARD", "SUCCESS", "ch_rental_1", paid);
        jdbc.update(payment, 3, 1, 15000, "REFUND", "CARD", "SUCCESS", "re_1", Timestamp.valueOf(t0.plusDays(2)));
        jdbc.update(payment, 4, 2, 15300, "RENTAL", "CARD", "FAILED", null, paid);
        jdbc.update(payment, 5, 2, 15300, "RENTAL", "CARD", "SUCCESS", "ch_2", paid);
        jdbc.update(payment, 6, 2, 20000, "DEPOSIT", "CARD", "SUCCESS", "ch_2", paid);
        jdbc.update(payment, 7, 3, 15300, "RENTAL", "CARD", "SUCCESS", "ch_3", paid);
        jdbc.update(payment, 8, 3, 35000, "DEPOSIT", "CARD", "SUCCESS", "ch_3", paid);
        jdbc.update(payment, 9, 4, 15300, "RENTAL", "CARD", "REFUNDED", "ch_4", paid);
        jdbc.update(payment, 10, 4, 15000, "DEPOSIT", "CARD", "REFUNDED", "ch_4", paid);
        jdbc.update(payment, 11, 4, 30300, "REFUND", "CARD", "SUCCESS", "re_4", paid);
        String ping = "insert into gps_pings (scooter_id, latitude, longitude, speed_kmh, recorded_at) values (?, 7.29, 80.64, 20, ?)";
        jdbc.update(ping, 1, Timestamp.valueOf(t0.plusHours(1)));          // during booking 1
        jdbc.update(ping, 1, Timestamp.valueOf(t0.plusDays(10)));          // after it: no booking
        jdbc.update(ping, 2, Timestamp.valueOf(t0.plusDays(19).plusHours(2))); // during booking 2

        flyway(ds, "3").migrate();

        // Payments: one row per booking, net amount collected, archive keeps all 11 originals.
        assertThat(jdbc.queryForObject("select count(*) from legacy_payments", Integer.class)).isEqualTo(11);
        Map<Long, String> payments = rows(jdbc, "select booking_id, status || ':' || amount || ':' || method || ':' "
                + "|| coalesce(stripe_payment_id, '-') from payments");
        assertThat(payments).containsExactlyInAnyOrderEntriesOf(Map.of(
                1L, "SUCCESS:15300.00:CARD:ch_rental_1",
                2L, "SUCCESS:35300.00:CARD:ch_2",
                3L, "SUCCESS:50300.00:CARD:ch_3",
                4L, "REFUNDED:30300.00:CARD:ch_4"));
        assertThat(jdbc.queryForObject("select paid_at from payments where booking_id = 1", Timestamp.class))
                .isEqualTo(paid);

        // Bookings: open-ended times, final cost for the completed one, distance from the odometers.
        assertThat(jdbc.queryForObject("select start_time from bookings where id = 1", Timestamp.class))
                .isEqualTo(Timestamp.valueOf(t0));
        assertThat(jdbc.queryForObject("select end_time from bookings where id = 1", Timestamp.class))
                .isEqualTo(Timestamp.valueOf(t0.plusDays(2).plusHours(8)));
        assertThat(jdbc.queryForObject("select total_cost from bookings where id = 1", BigDecimal.class))
                .isEqualByComparingTo("15300");
        assertThat(jdbc.queryForObject("select distance_km from bookings where id = 1", BigDecimal.class))
                .isEqualByComparingTo("320");
        assertThat(jdbc.queryForObject("select start_time from bookings where id = 3", Timestamp.class))
                .isEqualTo(Timestamp.valueOf("2026-10-05 00:00:00"));
        assertThat(jdbc.queryForList("select id from bookings where end_time is null and total_cost is null order by id",
                Long.class)).containsExactly(2L, 3L, 4L);
        assertThat(jdbc.queryForObject("select count(*) from legacy_booking_terms", Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("select deposit_amount from legacy_booking_terms where booking_id = 3",
                BigDecimal.class)).isEqualByComparingTo("35000");

        // Scooters: hourly = daily / 24, per-km starts at 0, ACTIVE bookings make their scooter RENTED.
        assertThat(rows(jdbc, "select id, hourly_rate || '/' || per_km_rate || '/' || status from scooters"))
                .containsExactlyInAnyOrderEntriesOf(Map.of(1L, "150.00/0.00/AVAILABLE", 2L, "200.00/0.00/RENTED",
                        3L, "270.83/0.00/RENTED"));
        assertThat(jdbc.queryForObject("select daily_rate from legacy_scooter_rates where scooter_id = 3",
                BigDecimal.class)).isEqualByComparingTo("6500");

        // GPS pings linked to the booking they were recorded during.
        assertThat(jdbc.queryForList("select booking_id from gps_pings order by id", Long.class))
                .containsExactly(1L, null, 2L);
        assertThat(jdbc.queryForObject("select line_total from legacy_booking_gear_rates where booking_gear_id = 1",
                BigDecimal.class)).isEqualByComparingTo("4500");
        assertThat(jdbc.queryForList("select column_name from information_schema.columns where table_name = 'bookings'",
                String.class)).doesNotContain("deposit_amount", "start_date", "checked_out_at", "total_amount");
    }

    private static Flyway flyway(DriverManagerDataSource ds, String target) {
        return Flyway.configure().dataSource(ds).locations("classpath:db/migration").target(target).load();
    }

    private static Map<Long, String> rows(JdbcTemplate jdbc, String sql) {
        Map<Long, String> out = new java.util.HashMap<>();
        jdbc.query(sql, rs -> {
            out.put(rs.getLong(1), rs.getString(2));
        });
        return out;
    }

    @SuppressWarnings("unused")
    private static final List<String> NOTE = List.of("rows() keys are booking/scooter ids");
}
