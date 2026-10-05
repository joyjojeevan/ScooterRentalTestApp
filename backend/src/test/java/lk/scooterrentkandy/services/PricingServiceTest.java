package lk.scooterrentkandy.services;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import lk.scooterrentkandy.models.GearItem;
import lk.scooterrentkandy.models.Scooter;
import lk.scooterrentkandy.services.PricingService.FinalBill;
import lk.scooterrentkandy.services.PricingService.GearSelection;
import lk.scooterrentkandy.services.PricingService.InitialCharge;
import org.junit.jupiter.api.Test;

/** Decision E1: totalCost = ceil(hours) x hourlyRate + km x perKmRate + qty x dailyRate x ceil(hours / 24). */
class PricingServiceTest {

    static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 3, 9, 0);
    final PricingService pricing = new PricingService();

    @Test
    void hoursAreRoundedUpWithAOneHourMinimum() {
        assertThat(PricingService.billableHours(T0, T0)).isEqualTo(1);
        assertThat(PricingService.billableHours(T0, T0.plusMinutes(1))).isEqualTo(1);
        assertThat(PricingService.billableHours(T0, T0.plusMinutes(59))).isEqualTo(1);
        assertThat(PricingService.billableHours(T0, T0.plusHours(1))).isEqualTo(1);
        assertThat(PricingService.billableHours(T0, T0.plusHours(1).plusSeconds(1))).isEqualTo(2);
        assertThat(PricingService.billableHours(T0, T0.plusHours(2).plusMinutes(30))).isEqualTo(3);
        assertThat(PricingService.billableHours(T0, T0.plusHours(3))).isEqualTo(3);
        assertThat(PricingService.billableHours(T0, T0.minusHours(2))).isEqualTo(1);
    }

    @Test
    void gearDaysAreCeilOfHoursOver24() {
        assertThat(PricingService.gearDays(1)).isEqualTo(1);
        assertThat(PricingService.gearDays(24)).isEqualTo(1);
        assertThat(PricingService.gearDays(25)).isEqualTo(2);
        assertThat(PricingService.gearDays(48)).isEqualTo(2);
        assertThat(PricingService.gearDays(49)).isEqualTo(3);
    }

    @Test
    void finalTotalFollowsTheFormula() {
        Scooter s = scooter("300.00", "20.00");
        List<GearSelection> gear = List.of(new GearSelection(gear("Tent", "1500.00"), 1),
                new GearSelection(gear("Sleeping bag", "600.00"), 2));

        // 25h 10m -> 26 hours, 2 gear days; 12.345 km -> 12.35 km
        FinalBill bill = pricing.finalBill(s, gear, T0, T0.plusHours(25).plusMinutes(10), new BigDecimal("12.345"));

        assertThat(bill.hours()).isEqualTo(26);
        assertThat(bill.timeCost()).isEqualByComparingTo("7800.00");          // 26 x 300
        assertThat(bill.distanceKm()).isEqualByComparingTo("12.35");
        assertThat(bill.distanceCost()).isEqualByComparingTo("247.00");       // 12.35 x 20
        assertThat(bill.gearDays()).isEqualTo(2);
        assertThat(bill.gearCost()).isEqualByComparingTo("5400.00");          // 1x1500x2 + 2x600x2
        assertThat(bill.total()).isEqualByComparingTo("13447.00");
    }

    @Test
    void minimumBillIsOneHourAndOneGearDayWhichIsTheInitialCharge() {
        Scooter s = scooter("250.00", "15.00");
        List<GearSelection> gear = List.of(new GearSelection(gear("Helmet", "300.00"), 2));

        InitialCharge initial = pricing.initialCharge(s, gear);
        FinalBill shortest = pricing.finalBill(s, gear, T0, T0.plusMinutes(5), BigDecimal.ZERO);

        assertThat(initial.total()).isEqualByComparingTo("850.00");           // 250 + 2 x 300
        assertThat(shortest.total()).isEqualByComparingTo(initial.total());
    }

    @Test
    void distanceIsBilledPerKilometreAndNothingElseIsAdded() {
        Scooter s = scooter("100.00", "10.00");
        FinalBill noKm = pricing.finalBill(s, List.of(), T0, T0.plusHours(2), BigDecimal.ZERO);
        FinalBill withKm = pricing.finalBill(s, List.of(), T0, T0.plusHours(2), new BigDecimal("40"));

        assertThat(noKm.total()).isEqualByComparingTo("200.00");
        assertThat(withKm.total()).isEqualByComparingTo("600.00");             // + 40 x 10, no other fee or tax
    }

    @Test
    void ratesAreReadFromTheScooterAtCalculationTime() {
        Scooter s = scooter("300.00", "20.00");
        BigDecimal before = pricing.finalBill(s, List.of(), T0, T0.plusHours(1), BigDecimal.ONE).total();
        s.setHourlyRate(new BigDecimal("400.00"));
        BigDecimal after = pricing.finalBill(s, List.of(), T0, T0.plusHours(1), BigDecimal.ONE).total();

        assertThat(before).isEqualByComparingTo("320.00");
        assertThat(after).isEqualByComparingTo("420.00");
    }

    private static Scooter scooter(String hourly, String perKm) {
        Scooter s = new Scooter();
        s.setHourlyRate(new BigDecimal(hourly));
        s.setPerKmRate(new BigDecimal(perKm));
        return s;
    }

    private static GearItem gear(String name, String dailyRate) {
        GearItem g = new GearItem();
        g.setName(name);
        g.setDailyRate(new BigDecimal(dailyRate));
        return g;
    }
}
