package lk.scooterrentkandy.gps;

import java.math.BigDecimal;
import java.util.List;

/**
 * Total distance travelled along a booking's GPS logs (SDS 3.1, 4.3 computeTotalDistance). Production uses the
 * Google Maps Distance Matrix API (SDS 1.1); {@link HaversineDistanceService} stands in for it locally.
 */
public interface DistanceService {

    /** Points in the order they were recorded. */
    BigDecimal totalKm(List<GpsDtos.Point> points);
}
