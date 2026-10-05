package lk.scooterrentkandy.security;

import java.util.UUID;
import lk.scooterrentkandy.models.Role;

/** The authenticated principal stored in the SecurityContext. */
public record AuthUser(UUID id, String email, Role role) {

    public boolean isAdmin() {
        return role.isAdmin();
    }
}
