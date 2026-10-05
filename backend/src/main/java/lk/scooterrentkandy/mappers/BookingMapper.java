package lk.scooterrentkandy.mappers;

import java.math.BigDecimal;
import java.util.List;
import lk.scooterrentkandy.dto.BookingDtos.BookingResponse;
import lk.scooterrentkandy.dto.BookingDtos.ContractResponse;
import lk.scooterrentkandy.dto.BookingDtos.CustomerSummary;
import lk.scooterrentkandy.dto.BookingDtos.GearLineResponse;
import lk.scooterrentkandy.dto.BookingDtos.PaymentSummary;
import lk.scooterrentkandy.dto.BookingDtos.ScooterSummary;
import lk.scooterrentkandy.dto.BookingDtos.Stage;
import lk.scooterrentkandy.models.Booking;
import lk.scooterrentkandy.models.Contract;
import lk.scooterrentkandy.models.Payment;

/** The booking service works out the stage, billable hours and initial charge; this only assembles the response. */
public final class BookingMapper {

    private BookingMapper() {
    }

    public static BookingResponse toResponse(Booking b, Payment p, Stage stage, Long billableHours,
            BigDecimal initialCharge) {
        var c = b.getCustomer();
        var s = b.getScooter();
        List<GearLineResponse> gear = b.getGear().stream()
                .map(g -> new GearLineResponse(g.getGearItem().getId(), g.getGearItem().getName(), g.getQuantity(),
                        g.getGearItem().getDailyRate()))
                .toList();
        return new BookingResponse(b.getId(), b.getReference(), b.getStatus(), stage,
                new CustomerSummary(c.getId(), c.getFullName(), c.getEmail(), c.getPhone()),
                new ScooterSummary(s.getId(), s.getCode(), s.getModel(), s.getPlateNumber(), s.getImageUrl(),
                        s.getHourlyRate(), s.getPerKmRate()),
                b.getStartTime(), b.getEndTime(), billableHours, b.getDistanceKm(), b.getTotalCost(), b.getEndedAt(),
                b.getPendingDistanceKm(), b.getPendingTotalCost(), initialCharge, gear,
                p == null ? null : new PaymentSummary(p.getStatus(), p.getAmount(), p.getBalanceDue(),
                        p.getCardLast4(), p.getFailureReason(), p.getPaidAt()),
                b.getPickupLocation(), b.getDropLocation(), b.getNotes(), b.isContractSigned(), b.getCancelledAt(),
                b.getCreatedAt());
    }

    public static ContractResponse toResponse(Contract c) {
        return new ContractResponse(c.getId(), c.getContractNumber(), c.getTermsVersion(), c.getTermsText(),
                c.isSigned(), c.getSignedName(), c.getSignedAt());
    }
}
