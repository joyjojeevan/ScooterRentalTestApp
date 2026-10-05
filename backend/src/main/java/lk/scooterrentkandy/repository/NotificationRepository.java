package lk.scooterrentkandy.repository;

import java.util.List;
import java.util.UUID;
import lk.scooterrentkandy.models.Notification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    List<Notification> findByUserIdAndChannelOrderByCreatedAtDesc(UUID userId, Notification.Channel channel);

    long countByUserIdAndChannelAndReadAtIsNull(UUID userId, Notification.Channel channel);

    @EntityGraph(attributePaths = "user")
    List<Notification> findTop200ByOrderByCreatedAtDesc();
}
