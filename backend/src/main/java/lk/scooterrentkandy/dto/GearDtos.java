package lk.scooterrentkandy.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;
import lk.scooterrentkandy.models.GearItem;

public final class GearDtos {

    private GearDtos() {
    }

    public record GearRequest(
            @NotBlank String name,
            @NotNull GearItem.Category category,
            String description,
            @NotNull @DecimalMin("0.00") BigDecimal dailyRate,
            @Min(0) int totalQuantity,
            boolean active) {
    }

    /** {@code available}: units free right now (public list only; null in the admin list). */
    public record GearResponse(
            UUID id,
            String name,
            GearItem.Category category,
            String description,
            BigDecimal dailyRate,
            int totalQuantity,
            Integer available,
            boolean active) {
    }
}
