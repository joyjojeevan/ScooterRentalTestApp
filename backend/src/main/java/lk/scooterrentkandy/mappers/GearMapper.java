package lk.scooterrentkandy.mappers;

import lk.scooterrentkandy.dto.GearDtos.GearResponse;
import lk.scooterrentkandy.models.GearItem;

public final class GearMapper {

    private GearMapper() {
    }

    /** {@code available}: units free right now, or null where it is not shown (admin list). */
    public static GearResponse toResponse(GearItem g, Integer available) {
        return new GearResponse(g.getId(), g.getName(), g.getCategory(), g.getDescription(), g.getDailyRate(),
                g.getTotalQuantity(), available, g.isActive());
    }
}
