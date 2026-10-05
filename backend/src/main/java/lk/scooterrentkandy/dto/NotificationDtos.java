package lk.scooterrentkandy.dto;

import jakarta.validation.constraints.NotBlank;
import java.time.LocalDateTime;
import java.util.UUID;
import lk.scooterrentkandy.models.Notification;

public final class NotificationDtos {

    private NotificationDtos() {
    }

    public record NotificationResponse(UUID id, String subject, String message, boolean read,
            LocalDateTime createdAt) {
    }

    public record NotificationLogResponse(UUID id, String recipient, Notification.Channel channel,
            String subject, Notification.Status status, LocalDateTime createdAt) {
    }

    public record BroadcastRequest(@NotBlank String subject, @NotBlank String message) {
    }
}
