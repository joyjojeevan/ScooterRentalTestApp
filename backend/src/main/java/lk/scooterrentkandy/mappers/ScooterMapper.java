package lk.scooterrentkandy.mappers;

import lk.scooterrentkandy.dto.ScooterDtos.ScooterResponse;
import lk.scooterrentkandy.models.Scooter;
import lk.scooterrentkandy.models.ScooterStatus;

public final class ScooterMapper {

    private ScooterMapper() {
    }

    /** {@code held}: a pending or active booking holds the scooter, so it is not bookable right now. */
    public static ScooterResponse toResponse(Scooter s, boolean held) {
        return new ScooterResponse(s.getId(), s.getCode(), s.getModel(), s.getPlateNumber(), s.getEngineCc(),
                s.getStatus(), s.getHourlyRate(), s.getPerKmRate(), s.getTotalMileage(), s.getLatitude(),
                s.getLongitude(), s.getImageUrl(), s.getDescription(), s.isDeleted(),
                !held && !s.isDeleted() && s.getStatus() == ScooterStatus.AVAILABLE);
    }
}
