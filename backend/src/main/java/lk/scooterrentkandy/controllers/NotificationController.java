package lk.scooterrentkandy.controllers;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.scooterrentkandy.dto.NotificationDtos.BroadcastRequest;
import lk.scooterrentkandy.dto.NotificationDtos.NotificationLogResponse;
import lk.scooterrentkandy.dto.NotificationDtos.NotificationResponse;
import lk.scooterrentkandy.security.AuthUser;
import lk.scooterrentkandy.security.CurrentUser;
import lk.scooterrentkandy.services.NotificationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class NotificationController {

    private final NotificationService service;

    public NotificationController(NotificationService service) {
        this.service = service;
    }

    @GetMapping("/api/notifications")
    public List<NotificationResponse> inbox(@CurrentUser AuthUser user) {
        return service.inbox(user.id());
    }

    @GetMapping("/api/notifications/unread-count")
    public Map<String, Long> unread(@CurrentUser AuthUser user) {
        return Map.of("count", service.unreadCount(user.id()));
    }

    @PostMapping("/api/notifications/{id}/read")
    public void markRead(@CurrentUser AuthUser user, @PathVariable UUID id) {
        service.markRead(user.id(), id);
    }

    @PostMapping("/api/notifications/read-all")
    public void markAllRead(@CurrentUser AuthUser user) {
        service.markAllRead(user.id());
    }

    @GetMapping("/api/admin/notifications")
    public List<NotificationLogResponse> log() {
        return service.recentLog();
    }

    @PostMapping("/api/admin/notifications/broadcast")
    public Map<String, Integer> broadcast(@Valid @RequestBody BroadcastRequest req) {
        return Map.of("recipients", service.broadcastToCustomers(req.subject(), req.message()));
    }
}
