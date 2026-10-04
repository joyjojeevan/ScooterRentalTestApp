package lk.scooterrentkandy.gps;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lk.scooterrentkandy.scooter.ScooterStatus;

public final class GpsDtos {

    private GpsDtos() {
    }

    /** Payload a tracker device posts. Devices identify the scooter by its fleet code. */
    public record PingRequest(
            @NotBlank String scooterCode,
            @NotNull @DecimalMin("-90") @DecimalMax("90") Double latitude,
            @NotNull @DecimalMin("-180") @DecimalMax("180") Double longitude,
            /** SDS 2.2: required, 0 to 999.99 km/h. */
            @NotNull @DecimalMin("0.00") @DecimalMax("999.99") BigDecimal speedKmh,
            LocalDateTime recordedAt,
            /** Optional (SDS 4.3 payload). If sent, it must be the scooter's rental in progress. */
            UUID bookingId) {
    }

    public record Point(double latitude, double longitude, Double speedKmh, LocalDateTime recordedAt) {

        static Point from(GpsPing p) {
            return new Point(p.getLatitude().doubleValue(), p.getLongitude().doubleValue(),
                    p.getSpeedKmh().doubleValue(), p.getRecordedAt());
        }
    }

    public record LivePosition(
            UUID scooterId,
            String code,
            String model,
            String plateNumber,
            ScooterStatus status,
            Double latitude,
            Double longitude,
            LocalDateTime lastSeen,
            String activeBookingReference,
            String riderName,
            double distanceFromBaseKm) {
    }

    /** {@code distanceKm}: along the trail (for a booking, the rental so far). */
    /** SDS 5.2: a speed-violation alert for the admin dashboard. */
    public record SpeedViolationResponse(UUID id, String scooterCode, String scooterModel, String plateNumber,
            String bookingReference, String riderName, BigDecimal speedKmh, BigDecimal latitude, BigDecimal longitude,
            LocalDateTime recordedAt) {

        static SpeedViolationResponse from(SpeedViolation v) {
            var s = v.getScooter();
            var b = v.getBooking();
            return new SpeedViolationResponse(v.getId(), s.getCode(), s.getModel(), s.getPlateNumber(),
                    b == null ? null : b.getReference(), b == null ? null : b.getCustomer().getFullName(),
                    v.getSpeedKmh(), v.getLatitude(), v.getLongitude(), v.getRecordedAt());
        }
    }

    public record Tracking(LivePosition position, List<Point> trail, BigDecimal distanceKm) {
    }
}
