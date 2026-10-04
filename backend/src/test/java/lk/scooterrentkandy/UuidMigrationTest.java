package lk.scooterrentkandy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** E5 (V10): UUID keys for all 14 live tables, every relationship and row preserved, old ids mapped. */
class UuidMigrationTest {

    static final List<String> TABLES = List.of("users", "scooters", "gear_items", "bookings", "booking_gear",
            "contracts", "payments", "payment_transactions", "invoices", "invoice_lines", "notifications",
            "maintenance_records", "gps_pings", "speed_violations");
    static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 1, 9, 15);

    DriverManagerDataSource ds;
    JdbcTemplate jdbc;

    @BeforeEach
    void liveShapedDataAtV9() {
        ds = new DriverManagerDataSource(
                "jdbc:h2:mem:e5" + System.nanoTime() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa", "");
        flyway("9").migrate();
        jdbc = new JdbcTemplate(ds);
        jdbc.update("insert into users (id, email, password_hash, full_name, phone, role, active, created_at) values "
                + "(1, 'admin@x.lk', 'h', 'Admin', '+94', 'ADMIN', true, now()), (2, 'alex@x.lk', 'h', 'Alex', '+94', 'USER', true, now())");
        jdbc.update("insert into scooters (id, code, model, plate_number, status, hourly_rate, per_km_rate, total_mileage, "
                + "deleted, created_at) values (1, 'SRK-01', 'Dio', 'P1', 'AVAILABLE', 145.83, 0, 4203, false, now()),"
                + "(3, 'SRK-03', 'Ray', 'P3', 'RENTED', 166.67, 0, 7800, false, now())");
        jdbc.update("insert into gear_items (id, name, category, daily_rate, total_quantity, active) values "
                + "(5, 'Extra helmet', 'SAFETY', 300, 12, true)");
        jdbc.update("insert into bookings (id, reference, customer_id, scooter_id, status, start_time, end_time, total_cost, "
                + "distance_km, pickup_location, contract_signed, created_at) values "
                + "(1, 'BK-1', 2, 1, 'COMPLETED', ?, ?, 14000, 320, 'Kandy office', true, now()),"
                + "(3, 'BK-3', 2, 3, 'ACTIVE', ?, null, null, null, 'Kandy office', true, now())",
                ts(T0.minusDays(20)), ts(T0.minusDays(17)), ts(T0));
        jdbc.update("insert into booking_gear (id, booking_id, gear_item_id, quantity) values (9, 3, 5, 1)");
        jdbc.update("insert into contracts (id, booking_id, contract_number, terms_version, terms_text, signed_name, "
                + "signed_at, created_at) values (1, 1, 'CT-1', '2026.1', 'terms', 'Alex', now(), now()),"
                + "(3, 3, 'CT-3', '2026.1', 'terms', 'Alex', now(), now())");
        jdbc.update("insert into payments (id, booking_id, amount, method, status, provider, stripe_payment_id, paid_at, "
                + "created_at) values (1, 1, 14000, 'CARD', 'SUCCESS', 'mock', 'ref-1', now(), now()),"
                + "(7, 3, 37200, 'CARD', 'SUCCESS', 'mock', 'ref-3', now(), now())");
        jdbc.update("insert into payment_transactions (id, payment_id, kind, provider_ref, amount, status, created_at) values "
                + "(1, 1, 'INITIAL', 'ref-1', 14000, 'SUCCESS', now()), (2, 7, 'INITIAL', 'ref-3', 37200, 'SUCCESS', now())");
        jdbc.update("insert into invoices (id, invoice_number, booking_id, kind, status, currency, subtotal, tax, total, issued_at) "
                + "values (4, 'INV-4', 1, 'RENTAL', 'PAID', 'LKR', 14000, 0, 14000, now())");
        jdbc.update("insert into invoice_lines (id, invoice_id, description, quantity, unit_price, amount) values "
                + "(11, 4, 'Scooter', 4, 3500, 14000), (12, 4, 'Helmet', 1, 0, 0)");
        jdbc.update("insert into maintenance_records (id, scooter_id, type, status, description, created_at) values "
                + "(2, 1, 'SERVICE', 'SCHEDULED', 'Routine', now())");
        jdbc.update("insert into notifications (id, user_id, channel, subject, message, status, created_at) values "
                + "(30, 2, 'IN_APP', 'Hi', 'Welcome', 'SENT', now()), (31, 1, 'IN_APP', 'New booking', 'BK-3', 'SENT', now())");
        for (int i = 0; i < 5; i++) {
            jdbc.update("insert into gps_pings (id, scooter_id, booking_id, latitude, longitude, speed_kmh, recorded_at) "
                    + "values (?, 3, 3, 7.291158, 80.643293, 30, ?)", 100 + i, ts(T0.plusMinutes(i)));
        }
        jdbc.update("insert into speed_violations (id, scooter_id, booking_id, speed_kmh, latitude, longitude, recorded_at, "
                + "created_at) values (1, 3, 3, 72.5, 7.3, 80.64, now(), now()), (2, 1, null, 65, 7.3, 80.64, now(), now())");
        // An archive row that refers to an old booking id.
        jdbc.update("insert into legacy_booking_terms (booking_id, start_date, end_date, rental_days, scooter_cost, gear_cost, "
                + "deposit_amount, total_amount) values (3, '2026-10-01', '2026-10-04', 4, 16000, 1200, 20000, 17200)");
    }

    @Test
    void everyRowAndRelationshipSurvivesWithUuidKeys() {
        Map<String, Integer> before = counts();

        flyway("10").migrate();

        assertThat(counts()).isEqualTo(before);
        // Every primary key and every foreign key column is UUID.
        for (String t : TABLES) {
            assertThat(type(t, "id")).as(t + ".id").isEqualTo("uuid");
            assertThat(jdbc.queryForObject("select count(*) from legacy_id_map where table_name = ?", Integer.class, t))
                    .as(t + " mapping").isEqualTo(before.get(t));
        }
        for (String[] fk : new String[][] {{"bookings", "customer_id"}, {"bookings", "scooter_id"},
                {"booking_gear", "booking_id"}, {"booking_gear", "gear_item_id"}, {"contracts", "booking_id"},
                {"payments", "booking_id"}, {"payment_transactions", "payment_id"}, {"invoices", "booking_id"},
                {"invoice_lines", "invoice_id"}, {"maintenance_records", "scooter_id"}, {"notifications", "user_id"},
                {"gps_pings", "scooter_id"}, {"gps_pings", "booking_id"}, {"speed_violations", "scooter_id"},
                {"speed_violations", "booking_id"}}) {
            assertThat(type(fk[0], fk[1])).as(fk[0] + "." + fk[1]).isEqualTo("uuid");
        }
        assertThat(jdbc.queryForObject("select count(*) from information_schema.table_constraints where "
                + "constraint_type = 'FOREIGN KEY' and lower(constraint_name) like 'fk\\_%' escape '\\'", Integer.class))
                .isEqualTo(15);

        // Relationships, checked by natural values.
        assertThat(jdbc.queryForMap("select u.email, s.code, p.stripe_payment_id, p.amount, c.contract_number, "
                + "(select count(*) from gps_pings g where g.booking_id = b.id) as pings, "
                + "(select g.name from booking_gear bg join gear_items g on g.id = bg.gear_item_id where bg.booking_id = b.id) as gear, "
                + "(select t.provider_ref from payment_transactions t where t.payment_id = p.id) as tx "
                + "from bookings b join users u on u.id = b.customer_id join scooters s on s.id = b.scooter_id "
                + "join payments p on p.booking_id = b.id join contracts c on c.booking_id = b.id where b.reference = 'BK-3'"))
                .containsEntry("email", "alex@x.lk").containsEntry("code", "SRK-03")
                .containsEntry("stripe_payment_id", "ref-3").containsEntry("contract_number", "CT-3")
                .containsEntry("pings", 5L).containsEntry("gear", "Extra helmet").containsEntry("tx", "ref-3");
        assertThat(jdbc.queryForObject("select count(*) from invoice_lines l join invoices i on i.id = l.invoice_id "
                + "join bookings b on b.id = i.booking_id where b.reference = 'BK-1'", Integer.class)).isEqualTo(2);
        // Line order (formerly the BIGINT id order) is kept in line_no.
        assertThat(jdbc.queryForList("select description from invoice_lines order by line_no", String.class))
                .containsExactly("Scooter", "Helmet");
        assertThat(jdbc.queryForList("select s.code from speed_violations v join scooters s on s.id = v.scooter_id "
                + "left join bookings b on b.id = v.booking_id order by v.speed_kmh", String.class))
                .containsExactly("SRK-01", "SRK-03");
        assertThat(jdbc.queryForObject("select count(*) from speed_violations where booking_id is null", Integer.class)).isEqualTo(1);

        // Archives keep their BIGINT ids and join through the map.
        assertThat(type("legacy_booking_terms", "booking_id")).isEqualTo("bigint");
        assertThat(jdbc.queryForObject("select b.reference from legacy_booking_terms t join legacy_id_map m "
                + "on m.table_name = 'bookings' and m.old_id = t.booking_id join bookings b on b.id = m.new_id", String.class))
                .isEqualTo("BK-3");
        // Audit of the migration.
        assertThat(jdbc.queryForObject("select count(*) from e5_migration_audit where rows_after = rows_before",
                Integer.class)).isEqualTo(21);
    }

    @Test
    void constraintsAndCascadesAreBack() {
        flyway("10").migrate();
        Object bk3 = jdbc.queryForObject("select id from bookings where reference = 'BK-3'", Object.class);
        Object scooter3 = jdbc.queryForObject("select id from scooters where code = 'SRK-03'", Object.class);
        Object alex = jdbc.queryForObject("select id from users where email = 'alex@x.lk'", Object.class);

        // One ACTIVE booking per scooter.
        assertThatThrownBy(() -> jdbc.update("insert into bookings (id, reference, customer_id, scooter_id, status, "
                + "start_time, pickup_location, contract_signed, created_at) values (?, 'BK-X', ?, ?, 'ACTIVE', now(), "
                + "'Kandy', true, now())", java.util.UUID.randomUUID(), alex, scooter3))
                .isInstanceOf(DataIntegrityViolationException.class);
        // One payment and one contract per booking.
        assertThatThrownBy(() -> jdbc.update("insert into payments (id, booking_id, amount, method, status, provider, "
                + "created_at) values (?, ?, 1, 'CARD', 'PENDING', 'mock', now())", java.util.UUID.randomUUID(), bk3))
                .isInstanceOf(DataIntegrityViolationException.class);
        // Foreign keys reject unknown parents.
        assertThatThrownBy(() -> jdbc.update("insert into notifications (id, user_id, channel, subject, message, status, "
                + "created_at) values (?, ?, 'IN_APP', 's', 'm', 'SENT', now())", java.util.UUID.randomUUID(),
                java.util.UUID.randomUUID())).isInstanceOf(DataIntegrityViolationException.class);
        // ON DELETE CASCADE kept for invoice lines.
        jdbc.update("delete from invoices where invoice_number = 'INV-4'");
        assertThat(jdbc.queryForObject("select count(*) from invoice_lines", Integer.class)).isZero();
    }

    private Map<String, Integer> counts() {
        Map<String, Integer> out = new java.util.TreeMap<>();
        for (String t : TABLES) {
            out.put(t, jdbc.queryForObject("select count(*) from " + t, Integer.class));
        }
        out.put("legacy_booking_terms", jdbc.queryForObject("select count(*) from legacy_booking_terms", Integer.class));
        return out;
    }

    private String type(String table, String column) {
        return jdbc.queryForObject("select lower(data_type) from information_schema.columns where table_name = ? "
                + "and column_name = ?", String.class, table, column);
    }

    private Flyway flyway(String target) {
        return Flyway.configure().dataSource(ds).locations("classpath:db/migration", "classpath:db/vendor/h2")
                .target(target).load();
    }

    private static Timestamp ts(LocalDateTime t) {
        return t == null ? null : Timestamp.valueOf(t);
    }
}
