package lk.scooterrentkandy.gear;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GearItemRepository extends JpaRepository<GearItem, UUID> {

    List<GearItem> findByActiveTrueOrderByCategoryAscNameAsc();

    List<GearItem> findAllByOrderByCategoryAscNameAsc();
}
