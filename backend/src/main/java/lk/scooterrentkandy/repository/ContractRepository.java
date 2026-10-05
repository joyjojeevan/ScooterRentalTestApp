package lk.scooterrentkandy.repository;

import java.util.Optional;
import java.util.UUID;
import lk.scooterrentkandy.models.Contract;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ContractRepository extends JpaRepository<Contract, UUID> {

    Optional<Contract> findByBookingId(UUID bookingId);
}
