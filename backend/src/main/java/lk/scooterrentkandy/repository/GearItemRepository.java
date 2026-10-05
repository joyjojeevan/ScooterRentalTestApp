package lk.scooterrentkandy.repository;

import java.util.List;
import java.util.UUID;
import lk.scooterrentkandy.models.GearItem;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GearItemRepository extends JpaRepository<GearItem, UUID> {

    List<GearItem> findByActiveTrueOrderByCategoryAscNameAsc();

    List<GearItem> findAllByOrderByCategoryAscNameAsc();
}
