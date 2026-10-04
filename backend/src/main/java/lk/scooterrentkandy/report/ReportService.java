package lk.scooterrentkandy.report;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import lk.scooterrentkandy.booking.Booking;
import lk.scooterrentkandy.booking.BookingRepository;
import lk.scooterrentkandy.booking.BookingStatus;
import lk.scooterrentkandy.booking.PricingService;
import lk.scooterrentkandy.common.ApiException;
import lk.scooterrentkandy.config.AppProperties;
import lk.scooterrentkandy.maintenance.MaintenanceRecord;
import lk.scooterrentkandy.maintenance.MaintenanceRepository;
import lk.scooterrentkandy.payment.Payment;
import lk.scooterrentkandy.payment.PaymentRepository;
import lk.scooterrentkandy.report.ReportDtos.DailyRevenue;
import lk.scooterrentkandy.report.ReportDtos.Dashboard;
import lk.scooterrentkandy.report.ReportDtos.GearUsage;
import lk.scooterrentkandy.report.ReportDtos.ScooterPerformance;
import lk.scooterrentkandy.report.ReportDtos.Summary;
import lk.scooterrentkandy.scooter.Scooter;
import lk.scooterrentkandy.scooter.ScooterRepository;
import lk.scooterrentkandy.scooter.ScooterStatus;
import lk.scooterrentkandy.user.Role;
import lk.scooterrentkandy.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReportService {

    /** Bookings that are or were rentals. */
    private static final List<BookingStatus> RENTALS = List.of(BookingStatus.ACTIVE, BookingStatus.COMPLETED);

    private final BookingRepository bookings;
    private final PaymentRepository payments;
    private final ScooterRepository scooters;
    private final MaintenanceRepository maintenance;
    private final UserRepository users;
    private final Clock clock;
    private final String currency;

    public ReportService(BookingRepository bookings, PaymentRepository payments, ScooterRepository scooters,
            MaintenanceRepository maintenance, UserRepository users, Clock clock, AppProperties props) {
        this.bookings = bookings;
        this.payments = payments;
        this.scooters = scooters;
        this.maintenance = maintenance;
        this.users = users;
        this.clock = clock;
        this.currency = props.billing().currency();
    }

    @Transactional(readOnly = true)
    public Summary summary(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            throw ApiException.badRequest("'to' must be on or after 'from'");
        }
        if (ChronoUnit.DAYS.between(from, to) > 366) {
            throw ApiException.badRequest("Report range is limited to one year");
        }
        LocalDateTime fromTs = from.atStartOfDay();
        LocalDateTime toTs = to.plusDays(1).atStartOfDay();
        LocalDateTime now = LocalDateTime.now(clock);

        List<Payment> paid = payments.findByStatusInAndPaidAtBetween(List.of(Payment.Status.SUCCESS), fromTs, toTs);
        BigDecimal revenue = sum(paid);
        BigDecimal refunds = sum(payments.findByStatusAndBookingCancelledAtBetween(Payment.Status.REFUNDED, fromTs,
                toTs));
        BigDecimal outstanding = payments.findByBalanceDueIsNotNull().stream().map(Payment::getBalanceDue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Revenue per day, zero-filled so charts have no gaps.
        Map<LocalDate, BigDecimal> perDay = new TreeMap<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            perDay.put(d, BigDecimal.ZERO);
        }
        paid.forEach(p -> perDay.merge(p.getPaidAt().toLocalDate(), p.getAmount(), BigDecimal::add));
        List<DailyRevenue> revenueByDay = perDay.entrySet().stream()
                .map(e -> new DailyRevenue(e.getKey(), e.getValue())).toList();

        List<Booking> created = bookings.findByCreatedAtBetween(fromTs, toTs);
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (BookingStatus s : BookingStatus.values()) {
            byStatus.put(s.name(), 0L);
        }
        created.forEach(b -> byStatus.merge(b.getStatus().name(), 1L, Long::sum));

        // Utilisation: rented scooter-hours inside the window / available scooter-hours (up to now).
        LocalDateTime windowEnd = toTs.isAfter(now) ? now : toTs;
        List<Scooter> fleet = scooters.findByDeletedFalseOrderByCodeAsc();
        Map<UUID, Long> rentedMinutes = new HashMap<>();
        Map<UUID, Long> rentalsByScooter = new HashMap<>();
        for (Booking b : bookings.findRentalsOverlapping(RENTALS, fromTs, toTs)) {
            LocalDateTime s = b.getStartTime().isBefore(fromTs) ? fromTs : b.getStartTime();
            LocalDateTime end = b.getEndTime() != null ? b.getEndTime() : b.getEndedAt() != null ? b.getEndedAt() : now;
            LocalDateTime e = end.isAfter(toTs) ? toTs : end;
            long minutes = Math.max(0, Duration.between(s, e).toMinutes());
            rentedMinutes.merge(b.getScooter().getId(), minutes, Long::sum);
            rentalsByScooter.merge(b.getScooter().getId(), 1L, Long::sum);
        }
        long windowMinutes = Math.max(1, Duration.between(fromTs, windowEnd).toMinutes());
        long totalRented = rentedMinutes.values().stream().mapToLong(Long::longValue).sum();
        double utilization = fleet.isEmpty() ? 0
                : Math.min(100, Math.round(1000.0 * totalRented / (fleet.size() * windowMinutes)) / 10.0);

        Map<UUID, BigDecimal> revenueByScooter = paid.stream()
                .collect(Collectors.groupingBy(p -> p.getBooking().getScooter().getId(),
                        Collectors.reducing(BigDecimal.ZERO, Payment::getAmount, BigDecimal::add)));
        List<ScooterPerformance> perf = new ArrayList<>();
        for (Scooter s : fleet) {
            perf.add(new ScooterPerformance(s.getId(), s.getCode(), s.getModel(),
                    rentalsByScooter.getOrDefault(s.getId(), 0L),
                    Math.round(rentedMinutes.getOrDefault(s.getId(), 0L) / 60.0),
                    revenueByScooter.getOrDefault(s.getId(), BigDecimal.ZERO)));
        }
        perf.sort(Comparator.comparing(ScooterPerformance::revenue).reversed());

        Map<String, Long> gearUnits = new TreeMap<>();
        created.stream()
                .filter(b -> b.getStatus() != BookingStatus.CANCELLED)
                .flatMap(b -> b.getGear().stream())
                .forEach(g -> gearUnits.merge(g.getGearItem().getName(), (long) g.getQuantity(), Long::sum));
        List<GearUsage> gear = gearUnits.entrySet().stream()
                .map(e -> new GearUsage(e.getKey(), e.getValue()))
                .sorted(Comparator.comparingLong(GearUsage::units).reversed())
                .toList();

        return new Summary(from, to, currency, revenue, refunds, outstanding,
                maintenance.sumCostCompletedBetween(from, to), created.size(), byStatus,
                users.countByRoleAndCreatedAtBetween(Role.USER, fromTs, toTs), utilization, revenueByDay, perf, gear);
    }

    @Transactional(readOnly = true)
    public Dashboard dashboard() {
        LocalDateTime now = LocalDateTime.now(clock);
        Map<String, Long> fleet = new LinkedHashMap<>();
        for (ScooterStatus s : ScooterStatus.values()) {
            fleet.put(s.name(), scooters.countByStatusAndDeletedFalse(s));
        }
        List<Booking> active = bookings.findByStatusOrderByStartTimeAsc(BookingStatus.ACTIVE);
        long balanceDue = active.stream().filter(b -> b.getEndedAt() != null).count();
        long upcoming = active.stream().filter(b -> b.getEndedAt() == null && now.isBefore(b.getStartTime())).count();
        long underWay = active.size() - balanceDue - upcoming;

        LocalDate today = now.toLocalDate();
        BigDecimal revenue30 = sum(payments.findByStatusInAndPaidAtBetween(List.of(Payment.Status.SUCCESS),
                today.minusDays(29).atStartOfDay(), today.plusDays(1).atStartOfDay()));

        return new Dashboard(fleet, underWay, upcoming, balanceDue, bookings.countByStatus(BookingStatus.PENDING),
                maintenance.countByStatusIn(List.of(MaintenanceRecord.Status.SCHEDULED,
                        MaintenanceRecord.Status.IN_PROGRESS)),
                users.countByRole(Role.USER), revenue30, currency);
    }

    /** All bookings created in the window as CSV (for spreadsheets/accounting). */
    @Transactional(readOnly = true)
    public String bookingsCsv(LocalDate from, LocalDate to) {
        StringBuilder sb = new StringBuilder("reference,status,customer,email,scooter,plate,start_time,end_time,"
                + "billable_hours,distance_km,total_cost,amount_paid,payment_status,balance_due,created_at\n");
        for (Booking b : bookings.findByCreatedAtBetween(from.atStartOfDay(), to.plusDays(1).atStartOfDay())) {
            Payment p = payments.findByBookingId(b.getId()).orElse(null);
            sb.append(String.join(",",
                    csv(b.getReference()), b.getStatus().name(), csv(b.getCustomer().getFullName()),
                    csv(b.getCustomer().getEmail()), csv(b.getScooter().getCode()),
                    csv(b.getScooter().getPlateNumber()), b.getStartTime().toString(),
                    b.getEndTime() == null ? "" : b.getEndTime().toString(),
                    b.getEndTime() == null ? ""
                            : String.valueOf(PricingService.billableHours(b.getStartTime(), b.getEndTime())),
                    plain(b.getDistanceKm()), plain(b.getTotalCost()),
                    p == null ? "" : plain(p.getAmount()), p == null ? "" : p.getStatus().name(),
                    p == null ? "" : plain(p.getBalanceDue()), b.getCreatedAt().toString()))
                    .append('\n');
        }
        return sb.toString();
    }

    private static BigDecimal sum(List<Payment> list) {
        return list.stream().map(Payment::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static String plain(BigDecimal v) {
        return v == null ? "" : v.toPlainString();
    }

    private static String csv(String v) {
        if (v == null) {
            return "";
        }
        // Neutralise spreadsheet formula injection and quote embedded commas/quotes.
        String s = v.matches("^[=+\\-@].*") ? "'" + v : v;
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }
}
