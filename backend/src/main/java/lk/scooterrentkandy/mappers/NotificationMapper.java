package lk.scooterrentkandy.mappers;

import lk.scooterrentkandy.dto.NotificationDtos.NotificationLogResponse;
import lk.scooterrentkandy.dto.NotificationDtos.NotificationResponse;
import lk.scooterrentkandy.models.Notification;

public final class NotificationMapper {

    private NotificationMapper() {
    }

    public static NotificationResponse toResponse(Notification n) {
        return new NotificationResponse(n.getId(), n.getSubject(), n.getMessage(), n.getReadAt() != null,
                n.getCreatedAt());
    }

    public static NotificationLogResponse toLogResponse(Notification n) {
        return new NotificationLogResponse(n.getId(), n.getUser().getEmail(), n.getChannel(), n.getSubject(),
                n.getStatus(), n.getCreatedAt());
    }
}
