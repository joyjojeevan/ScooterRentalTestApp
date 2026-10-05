package lk.scooterrentkandy.services;

import java.util.List;
import java.util.UUID;
import lk.scooterrentkandy.dto.GearDtos.GearRequest;
import lk.scooterrentkandy.dto.GearDtos.GearResponse;
import lk.scooterrentkandy.exception.ApiException;
import lk.scooterrentkandy.mappers.GearMapper;
import lk.scooterrentkandy.models.BookingStatus;
import lk.scooterrentkandy.models.GearItem;
import lk.scooterrentkandy.repository.BookingGearRepository;
import lk.scooterrentkandy.repository.GearItemRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GearService {

    private final GearItemRepository gear;
    private final BookingGearRepository bookingGear;

    public GearService(GearItemRepository gear, BookingGearRepository bookingGear) {
        this.gear = gear;
        this.bookingGear = bookingGear;
    }

    @Transactional(readOnly = true)
    public List<GearResponse> listActive() {
        return gear.findByActiveTrueOrderByCategoryAscNameAsc().stream()
                .map(g -> GearMapper.toResponse(g, available(g)))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<GearResponse> listAll() {
        return gear.findAllByOrderByCategoryAscNameAsc().stream().map(g -> GearMapper.toResponse(g, null)).toList();
    }

    /** Units available now (SDS 2.2 stock): owned units minus those on pending or active rentals. */
    public int available(GearItem item) {
        long reserved = bookingGear.sumReservedQuantity(item.getId(), BookingStatus.BLOCKING);
        return (int) Math.max(0, item.getTotalQuantity() - reserved);
    }

    public GearItem find(UUID id) {
        return gear.findById(id).orElseThrow(() -> ApiException.notFound("Gear item"));
    }

    @Transactional
    public GearResponse create(GearRequest req) {
        GearItem g = new GearItem();
        apply(g, req);
        return GearMapper.toResponse(gear.save(g), null);
    }

    @Transactional
    public GearResponse update(UUID id, GearRequest req) {
        GearItem g = find(id);
        apply(g, req);
        return GearMapper.toResponse(g, null);
    }

    private void apply(GearItem g, GearRequest req) {
        g.setName(req.name().trim());
        g.setCategory(req.category());
        g.setDescription(req.description());
        g.setDailyRate(req.dailyRate());
        g.setTotalQuantity(req.totalQuantity());
        g.setActive(req.active());
    }
}
