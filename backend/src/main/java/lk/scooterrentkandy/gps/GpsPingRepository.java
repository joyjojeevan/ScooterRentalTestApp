package lk.scooterrentkandy.gps;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GpsPingRepository extends JpaRepository<GpsPing, UUID> {

    List<GpsPing> findByScooterIdAndRecordedAtAfterOrderByRecordedAtAsc(UUID scooterId, LocalDateTime after);

    Optional<GpsPing> findFirstByScooterIdOrderByRecordedAtDesc(UUID scooterId);

    List<GpsPing> findByBookingIdOrderByRecordedAtAsc(UUID bookingId);

    List<GpsPing> findByBookingIdAndRecordedAtBetweenOrderByRecordedAtAsc(UUID bookingId, LocalDateTime from,
            LocalDateTime to);

    Optional<GpsPing> findFirstByBookingIdOrderByRecordedAtDesc(UUID bookingId);

    boolean existsByBookingId(UUID bookingId);

    /**
     * SDS 8.3 retention: deletes logs recorded before the cutoff, except those of a rental still under way
     * (its distance is not billed yet). Logs of completed or cancelled bookings go only once they pass the cutoff.
     */
    @Modifying
    @Query("""
            delete from GpsPing p where p.recordedAt < :cutoff
              and p.booking.id not in (
                  select b.id from Booking b where b.status = lk.scooterrentkandy.booking.BookingStatus.ACTIVE)
            """)
    int deleteOlderThanOutsideActiveRentals(@Param("cutoff") LocalDateTime cutoff);
}
