package lk.scooterrentkandy.mappers;

import lk.scooterrentkandy.dto.MaintenanceDtos.MaintenanceResponse;
import lk.scooterrentkandy.models.MaintenanceRecord;

public final class MaintenanceMapper {

    private MaintenanceMapper() {
    }

    public static MaintenanceResponse toResponse(MaintenanceRecord m) {
        var s = m.getScooter();
        return new MaintenanceResponse(m.getId(), s.getId(), s.getCode(), s.getModel(), m.getType(), m.getStatus(),
                m.getDescription(), m.getCost(), m.getScheduledDate(), m.getCompletedDate(), m.getOdometerKm(),
                m.getCreatedAt());
    }
}
