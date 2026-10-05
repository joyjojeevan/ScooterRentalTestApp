package lk.scooterrentkandy.mappers;

import lk.scooterrentkandy.dto.UserDtos.UserResponse;
import lk.scooterrentkandy.models.User;

public final class UserMapper {

    private UserMapper() {
    }

    public static UserResponse toResponse(User u) {
        return new UserResponse(u.getId(), u.getEmail(), u.getFullName(), u.getPhone(), u.getRole(),
                u.getIdDocumentNumber(), u.getDrivingLicenseNo(), u.getCountry(), u.isActive(), u.getCreatedAt());
    }
}
