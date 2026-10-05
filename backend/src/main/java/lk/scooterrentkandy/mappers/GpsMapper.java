package lk.scooterrentkandy.mappers;

import lk.scooterrentkandy.dto.GpsDtos.LivePosition;
import lk.scooterrentkandy.dto.GpsDtos.Point;
import lk.scooterrentkandy.dto.GpsDtos.SpeedViolationResponse;
import lk.scooterrentkandy.models.Booking;
import lk.scooterrentkandy.models.GpsPing;
import lk.scooterrentkandy.models.Scooter;
import lk.scooterrentkandy.models.SpeedViolation;

public final class GpsMapper {

    private GpsMapper() {
    }

    public static Point toPoint(GpsPing p) {
        return new Point(p.getLatitude().doubleValue(), p.getLongitude().doubleValue(), p.getSpeedKmh().doubleValue(),
                p.getRecordedAt());
    }

    public static SpeedViolationResponse toResponse(SpeedViolation v) {
        var s = v.getScooter();
        var b = v.getBooking();
        return new SpeedViolationResponse(v.getId(), s.getCode(), s.getModel(), s.getPlateNumber(),
                b == null ? null : b.getReference(), b == null ? null : b.getCustomer().getFullName(),
                v.getSpeedKmh(), v.getLatitude(), v.getLongitude(), v.getRecordedAt());
    }

    /** {@code activeRental}: the scooter's rental under way, or null. */
    public static LivePosition toLivePosition(Scooter s, Booking activeRental, double distanceFromBaseKm) {
        return new LivePosition(s.getId(), s.getCode(), s.getModel(), s.getPlateNumber(), s.getStatus(),
                s.getLatitude(), s.getLongitude(), s.getLastSeenAt(),
                activeRental == null ? null : activeRental.getReference(),
                activeRental == null ? null : activeRental.getCustomer().getFullName(), distanceFromBaseKm);
    }
}
