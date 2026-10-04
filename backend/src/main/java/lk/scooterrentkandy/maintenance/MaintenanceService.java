package lk.scooterrentkandy.maintenance;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lk.scooterrentkandy.common.ApiException;
import lk.scooterrentkandy.maintenance.MaintenanceDtos.CompleteMaintenanceRequest;
import lk.scooterrentkandy.maintenance.MaintenanceDtos.CreateMaintenanceRequest;
import lk.scooterrentkandy.maintenance.MaintenanceDtos.MaintenanceResponse;
import lk.scooterrentkandy.maintenance.MaintenanceRecord.Status;
import lk.scooterrentkandy.notification.NotificationService;
import lk.scooterrentkandy.scooter.Scooter;
import lk.scooterrentkandy.scooter.ScooterRepository;
import lk.scooterrentkandy.scooter.ScooterStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MaintenanceService {

    /** Kilometres between routine services. */
    public static final int SERVICE_INTERVAL_KM = 3000;

    private final MaintenanceRepository records;
    private final ScooterRepository scooters;
    private final NotificationService notifications;

    public MaintenanceService(MaintenanceRepository records, ScooterRepository scooters,
            NotificationService notifications) {
        this.records = records;
        this.scooters = scooters;
        this.notifications = notifications;
    }

    @Transactional(readOnly = true)
    public List<MaintenanceResponse> list(UUID scooterId) {
        var list = scooterId == null ? records.findAllByOrderByCreatedAtDesc()
                : records.findByScooterIdOrderByCreatedAtDesc(scooterId);
        return list.stream().map(MaintenanceResponse::from).toList();
    }

    @Transactional
    public MaintenanceResponse create(CreateMaintenanceRequest req) {
        Scooter scooter = scooters.findById(req.scooterId()).orElseThrow(() -> ApiException.notFound("Scooter"));
        MaintenanceRecord m = new MaintenanceRecord();
        m.setScooter(scooter);
        m.setType(req.type());
        m.setDescription(req.description());
        m.setScheduledDate(req.scheduledDate() == null ? LocalDate.now() : req.scheduledDate());
        m.setStatus(Status.SCHEDULED);
        records.save(m);
        if (req.startNow()) {
            start(m);
        }
        return MaintenanceResponse.from(m);
    }

    @Transactional
    public MaintenanceResponse start(UUID id) {
        MaintenanceRecord m = find(id);
        if (m.getStatus() != Status.SCHEDULED) {
            throw ApiException.conflict("Only scheduled work can be started");
        }
        start(m);
        return MaintenanceResponse.from(m);
    }

    @Transactional
    public MaintenanceResponse complete(UUID id, CompleteMaintenanceRequest req) {
        MaintenanceRecord m = find(id);
        if (m.getStatus() == Status.DONE || m.getStatus() == Status.CANCELLED) {
            throw ApiException.conflict("Maintenance record is already closed");
        }
        Scooter s = m.getScooter();
        m.setStatus(Status.DONE);
        m.setCompletedDate(LocalDate.now());
        m.setCost(req.cost());
        if (req.notes() != null && !req.notes().isBlank()) {
            m.setDescription(m.getDescription() + "\n\nCompletion notes: " + req.notes());
        }
        // The record keeps a whole-km reading; the scooter's total mileage (SDS 2.2) is decimal.
        int current = s.getTotalMileage().intValue();
        int odo = req.odometerKm() != null ? req.odometerKm() : current;
        if (odo < current) {
            throw ApiException.badRequest("Odometer cannot go below the current reading of " + current);
        }
        m.setOdometerKm(odo);
        if (BigDecimal.valueOf(odo).compareTo(s.getTotalMileage()) > 0) {
            s.setTotalMileage(BigDecimal.valueOf(odo).setScale(2));
        }
        releaseScooterIfIdle(s, m.getId());
        return MaintenanceResponse.from(m);
    }

    @Transactional
    public MaintenanceResponse cancel(UUID id) {
        MaintenanceRecord m = find(id);
        if (m.getStatus() == Status.DONE) {
            throw ApiException.conflict("Completed work cannot be cancelled");
        }
        m.setStatus(Status.CANCELLED);
        releaseScooterIfIdle(m.getScooter(), m.getId());
        return MaintenanceResponse.from(m);
    }

    /**
     * Called after a rental return. Schedules a routine service once the scooter has done
     * {@link #SERVICE_INTERVAL_KM} since its last one, unless one is already pending.
     */
    @Transactional
    public void scheduleServiceIfDue(Scooter scooter) {
        int lastServiceOdo = records.findFirstByScooterIdAndTypeAndStatusOrderByCompletedDateDesc(
                        scooter.getId(), MaintenanceRecord.Type.SERVICE, Status.DONE)
                .map(MaintenanceRecord::getOdometerKm)
                .orElse(0);
        boolean pending = records.existsByScooterIdAndTypeAndStatusIn(scooter.getId(),
                MaintenanceRecord.Type.SERVICE, List.of(Status.SCHEDULED, Status.IN_PROGRESS));
        if (pending || scooter.getTotalMileage().intValue() - lastServiceOdo < SERVICE_INTERVAL_KM) {
            return;
        }
        MaintenanceRecord m = new MaintenanceRecord();
        m.setScooter(scooter);
        m.setType(MaintenanceRecord.Type.SERVICE);
        m.setStatus(Status.SCHEDULED);
        m.setScheduledDate(LocalDate.now());
        m.setDescription("Routine " + SERVICE_INTERVAL_KM + " km service (auto-scheduled at "
                + scooter.getTotalMileage() + " km)");
        records.save(m);
        notifications.notifyAdmins("Service due: " + scooter.getCode(),
                scooter.getModel() + " " + scooter.getPlateNumber() + " is at " + scooter.getTotalMileage()
                        + " km and is due for a routine service.");
    }

    private void start(MaintenanceRecord m) {
        Scooter s = m.getScooter();
        if (s.getStatus() == ScooterStatus.RENTED) {
            throw ApiException.conflict("Scooter is out on a rental; start work after it is returned");
        }
        m.setStatus(Status.IN_PROGRESS);
        if (!s.isDeleted()) {
            s.setStatus(ScooterStatus.MAINTENANCE);
        }
    }

    private void releaseScooterIfIdle(Scooter s, UUID closedRecordId) {
        boolean otherWork = records.findByScooterIdOrderByCreatedAtDesc(s.getId()).stream()
                .anyMatch(r -> !r.getId().equals(closedRecordId) && r.getStatus() == Status.IN_PROGRESS);
        if (!otherWork && s.getStatus() == ScooterStatus.MAINTENANCE) {
            s.setStatus(ScooterStatus.AVAILABLE);
        }
    }

    private MaintenanceRecord find(UUID id) {
        return records.findById(id).orElseThrow(() -> ApiException.notFound("Maintenance record"));
    }
}
