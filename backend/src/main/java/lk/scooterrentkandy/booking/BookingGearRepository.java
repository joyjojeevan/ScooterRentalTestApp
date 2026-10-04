package lk.scooterrentkandy.booking;

import java.util.Collection;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BookingGearRepository extends JpaRepository<BookingGear, UUID> {

    @Query("""
            select coalesce(sum(bg.quantity), 0) from BookingGear bg
            where bg.gearItem.id = :gearItemId and bg.booking.status in :statuses
            """)
    long sumReservedQuantity(@Param("gearItemId") UUID gearItemId,
            @Param("statuses") Collection<BookingStatus> statuses);
}
