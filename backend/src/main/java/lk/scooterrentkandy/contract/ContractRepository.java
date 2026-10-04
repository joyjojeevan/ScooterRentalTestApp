package lk.scooterrentkandy.contract;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ContractRepository extends JpaRepository<Contract, UUID> {

    Optional<Contract> findByBookingId(UUID bookingId);
}
