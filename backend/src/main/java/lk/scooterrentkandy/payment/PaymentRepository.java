package lk.scooterrentkandy.payment;

import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    Optional<Payment> findByBookingId(UUID bookingId);

    /** Serialises webhook deliveries and completion for the same booking. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.booking.id = :bookingId")
    Optional<Payment> findByBookingIdForUpdate(@Param("bookingId") UUID bookingId);

    @EntityGraph(attributePaths = {"booking", "booking.customer"})
    List<Payment> findTop300ByOrderByCreatedAtDesc();

    @EntityGraph(attributePaths = {"booking", "booking.scooter"})
    List<Payment> findByStatusInAndPaidAtBetween(Collection<Payment.Status> statuses, LocalDateTime from,
            LocalDateTime to);

    List<Payment> findByBalanceDueIsNotNull();

    @EntityGraph(attributePaths = {"booking", "booking.scooter"})
    List<Payment> findByStatusAndBookingCancelledAtBetween(Payment.Status status, LocalDateTime from,
            LocalDateTime to);
}
