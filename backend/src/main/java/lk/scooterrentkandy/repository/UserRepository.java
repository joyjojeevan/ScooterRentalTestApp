package lk.scooterrentkandy.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lk.scooterrentkandy.models.Role;
import lk.scooterrentkandy.models.User;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);

    List<User> findByRoleOrderByCreatedAtDesc(Role role);

    List<User> findByRoleAndActiveTrue(Role role);

    List<User> findByRoleInAndActiveTrue(java.util.Collection<Role> roles);

    long countByRole(Role role);

    long countByRoleAndCreatedAtBetween(Role role, java.time.LocalDateTime from, java.time.LocalDateTime to);
}
