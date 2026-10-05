package lk.scooterrentkandy.repository;

import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lk.scooterrentkandy.models.Booking;
import lk.scooterrentkandy.models.BookingStatus;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BookingRepository extends JpaRepository<Booking, UUID> {

    /** Serialises payment, completion and cancellation of the same booking. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Booking b where b.id = :id")
    Optional<Booking> findByIdForUpdate(@Param("id") UUID id);

    /** SDS 4.2 step 4: a scooter is taken while it has a pending or active booking. */
    boolean existsByScooterIdAndStatusIn(UUID scooterId, Collection<BookingStatus> statuses);

    @Query("select distinct b.scooter.id from Booking b where b.status in :statuses")
    List<UUID> findScooterIdsWithStatus(@Param("statuses") Collection<BookingStatus> statuses);

    @EntityGraph(attributePaths = {"scooter", "customer"})
    List<Booking> findByCustomerIdOrderByCreatedAtDesc(UUID customerId);

    @EntityGraph(attributePaths = {"scooter", "customer"})
    List<Booking> findAllByOrderByCreatedAtDesc();

    @EntityGraph(attributePaths = {"scooter", "customer"})
    List<Booking> findByStatusOrderByStartTimeAsc(BookingStatus status);

    @EntityGraph(attributePaths = {"scooter", "customer", "gear", "gear.gearItem"})
    Optional<Booking> findWithDetailsById(UUID id);

    Optional<Booking> findFirstByScooterIdAndStatus(UUID scooterId, BookingStatus status);

    /** Rentals under way: started and not yet ended. */
    @EntityGraph(attributePaths = {"scooter"})
    List<Booking> findByStatusAndStartTimeLessThanEqualAndEndedAtIsNull(BookingStatus status, LocalDateTime now);

    List<Booking> findByStatusAndCreatedAtBefore(BookingStatus status, LocalDateTime cutoff);

    @EntityGraph(attributePaths = {"scooter", "customer"})
    List<Booking> findByStatusAndStartTimeBetween(BookingStatus status, LocalDateTime from, LocalDateTime to);

    /** Rentals whose time overlaps the window (open rentals run until now). */
    @EntityGraph(attributePaths = {"scooter"})
    @Query("""
            select b from Booking b
            where b.status in :statuses and b.startTime < :to and (b.endTime is null or b.endTime > :from)
            """)
    List<Booking> findRentalsOverlapping(@Param("statuses") Collection<BookingStatus> statuses,
            @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @EntityGraph(attributePaths = {"scooter", "customer"})
    List<Booking> findByCreatedAtBetween(LocalDateTime from, LocalDateTime to);

    long countByStatus(BookingStatus status);
}
