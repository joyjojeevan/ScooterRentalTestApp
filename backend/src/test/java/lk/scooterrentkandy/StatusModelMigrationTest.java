package lk.scooterrentkandy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** V2 must convert pre-SDS rows in place without losing data. */
class StatusModelMigrationTest {

    @Test
    void v2ConvertsLegacyValuesInPlace() {
        var ds = new DriverManagerDataSource(
                "jdbc:h2:mem:migration" + System.nanoTime() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa", "");
        flyway(ds, "1").migrate();
        JdbcTemplate jdbc = new JdbcTemplate(ds);

        jdbc.update("insert into users (id, email, password_hash, full_name, role, active, created_at) values "
                + "(1, 'admin@x.lk', 'h', 'Admin', 'ADMIN', true, now()),"
                + "(2, 'cust@x.lk', 'h', 'Cust', 'CUSTOMER', true, now())");
        jdbc.update("insert into scooters (id, code, model, plate_number, status, daily_rate, deposit_amount, odometer_km, created_at) values "
                + "(1, 'S1', 'Dio', 'P1', 'AVAILABLE', 3500, 15000, 0, now()),"
                + "(2, 'S2', 'Dio', 'P2', 'RETIRED', 3500, 15000, 0, now()),"
                + "(3, 'S3', 'Dio', 'P3', 'RENTED', 3500, 15000, 0, now())");
        // 1 unpaid, 2 paid not handed over, 3 out on rental, 4 cancelled with full refund, 5 cancelled deposit-only.
        String booking = "insert into bookings (id, reference, customer_id, scooter_id, start_date, end_date, rental_days, "
                + "status, scooter_cost, gear_cost, deposit_amount, total_amount, checked_out_at, created_at) values "
                + "(?, ?, 2, ?, current_date, current_date, 1, ?, 3500, 0, 15000, 3500, ?, now())";
        jdbc.update(booking, 1, "BK1", 1, "PENDING_PAYMENT", null);
        jdbc.update(booking, 2, "BK2", 1, "CONFIRMED", null);
        jdbc.update(booking, 3, "BK3", 3, "ACTIVE", java.sql.Timestamp.valueOf(java.time.LocalDateTime.now()));
        jdbc.update(booking, 4, "BK4", 1, "CANCELLED", null);
        jdbc.update(booking, 5, "BK5", 1, "CANCELLED", null);
        String contract = "insert into contracts (booking_id, contract_number, terms_version, terms_text, signed_at, created_at) "
                + "values (?, ?, 'v', 'terms', ?, now())";
        jdbc.update(contract, 1, "CT1", null);
        for (int id = 2; id <= 5; id++) {
            jdbc.update(contract, id, "CT" + id, java.sql.Timestamp.valueOf(java.time.LocalDateTime.now()));
        }
        String payment = "insert into payments (id, booking_id, amount, type, method, status, provider, created_at) "
                + "values (?, ?, ?, ?, 'CARD', 'SUCCEEDED', 'mock', now())";
        jdbc.update(payment, 1, 4, 3500, "RENTAL");
        jdbc.update(payment, 2, 4, 15000, "DEPOSIT");
        jdbc.update(payment, 3, 4, 18500, "REFUND");
        jdbc.update(payment, 4, 5, 3500, "RENTAL");
        jdbc.update(payment, 5, 5, 15000, "DEPOSIT");
        jdbc.update(payment, 6, 5, 15000, "REFUND");

        flyway(ds, "2").migrate();

        assertThat(statuses(jdbc, "select id, status from bookings"))
                .containsExactlyInAnyOrderEntriesOf(Map.of(1L, "PENDING", 2L, "ACTIVE", 3L, "ACTIVE",
                        4L, "CANCELLED", 5L, "CANCELLED"));
        assertThat(jdbc.queryForList("select id from bookings where contract_signed order by id", Long.class))
                .containsExactly(2L, 3L, 4L, 5L);
        assertThat(jdbc.queryForObject("select checked_out_at is not null from bookings where id = 3", Boolean.class))
                .isTrue();
        assertThat(statuses(jdbc, "select id, status from payments"))
                .containsExactlyInAnyOrderEntriesOf(Map.of(1L, "REFUNDED", 2L, "REFUNDED", 3L, "SUCCESS",
                        4L, "SUCCESS", 5L, "SUCCESS", 6L, "SUCCESS"));
        assertThat(statuses(jdbc, "select id, role from users"))
                .containsExactlyInAnyOrderEntriesOf(Map.of(1L, "ADMIN", 2L, "USER"));
        assertThat(jdbc.queryForList("select id, status, deleted from scooters order by id"))
                .extracting(r -> r.get("status") + "/" + r.get("deleted"))
                .containsExactly("AVAILABLE/false", "MAINTENANCE/true", "RENTED/false");
        assertThat(jdbc.queryForObject("select count(*) from payments", Integer.class)).isEqualTo(6);
    }

    private static Flyway flyway(DriverManagerDataSource ds, String target) {
        return Flyway.configure().dataSource(ds).locations("classpath:db/migration").target(target).load();
    }

    private static Map<Long, String> statuses(JdbcTemplate jdbc, String sql) {
        Map<Long, String> out = new java.util.HashMap<>();
        jdbc.query(sql, rs -> {
            out.put(rs.getLong(1), rs.getString(2));
        });
        return out;
    }
}
