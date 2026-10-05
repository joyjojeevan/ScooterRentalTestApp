package lk.scooterrentkandy.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.UUID;
import lk.scooterrentkandy.models.Role;

public final class UserDtos {

    private UserDtos() {
    }

    public record RegisterRequest(
            @NotBlank @Email String email,
            @NotBlank @Size(min = 8, message = "must be at least 8 characters") String password,
            @NotBlank String fullName,
            @NotBlank String phone,
            String idDocumentNumber,
            String drivingLicenseNo,
            String country) {
    }

    public record LoginRequest(@NotBlank @Email String email, @NotBlank String password) {
    }

    public record UpdateProfileRequest(
            @NotBlank String fullName,
            @NotBlank String phone,
            String idDocumentNumber,
            String drivingLicenseNo,
            String country) {
    }

    public record UserResponse(
            UUID id,
            String email,
            String fullName,
            String phone,
            Role role,
            String idDocumentNumber,
            String drivingLicenseNo,
            String country,
            boolean active,
            LocalDateTime createdAt) {
    }

    public record AuthResponse(String token, UserResponse user) {
    }
}
