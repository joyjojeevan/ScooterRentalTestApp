package lk.scooterrentkandy.notification;

import jakarta.validation.constraints.NotBlank;
import java.time.LocalDateTime;
import java.util.UUID;

public final class NotificationDtos {

    private NotificationDtos() {
    }

    public record NotificationResponse(UUID id, String subject, String message, boolean read,
            LocalDateTime createdAt) {

        static NotificationResponse from(Notification n) {
            return new NotificationResponse(n.getId(), n.getSubject(), n.getMessage(), n.getReadAt() != null,
                    n.getCreatedAt());
        }
    }

    public record NotificationLogResponse(UUID id, String recipient, Notification.Channel channel,
            String subject, Notification.Status status, LocalDateTime createdAt) {

        static NotificationLogResponse from(Notification n) {
            return new NotificationLogResponse(n.getId(), n.getUser().getEmail(), n.getChannel(), n.getSubject(),
                    n.getStatus(), n.getCreatedAt());
        }
    }

    public record BroadcastRequest(@NotBlank String subject, @NotBlank String message) {
    }
}
