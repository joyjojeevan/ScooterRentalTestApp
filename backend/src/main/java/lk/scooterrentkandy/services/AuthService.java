package lk.scooterrentkandy.services;

import java.util.UUID;
import lk.scooterrentkandy.dto.UserDtos.AuthResponse;
import lk.scooterrentkandy.dto.UserDtos.LoginRequest;
import lk.scooterrentkandy.dto.UserDtos.RegisterRequest;
import lk.scooterrentkandy.dto.UserDtos.UpdateProfileRequest;
import lk.scooterrentkandy.dto.UserDtos.UserResponse;
import lk.scooterrentkandy.exception.ApiException;
import lk.scooterrentkandy.mappers.UserMapper;
import lk.scooterrentkandy.models.Role;
import lk.scooterrentkandy.models.User;
import lk.scooterrentkandy.repository.UserRepository;
import lk.scooterrentkandy.security.JwtService;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final NotificationService notifications;

    public AuthService(UserRepository users, PasswordEncoder passwordEncoder, JwtService jwtService,
            NotificationService notifications) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.notifications = notifications;
    }

    @Transactional
    public AuthResponse register(RegisterRequest req) {
        if (users.existsByEmailIgnoreCase(req.email().trim())) {
            throw ApiException.conflict("An account with this email already exists");
        }
        User user = new User();
        user.setEmail(req.email().trim().toLowerCase());
        user.setPasswordHash(passwordEncoder.encode(req.password()));
        user.setFullName(req.fullName().trim());
        user.setPhone(req.phone());
        user.setIdDocumentNumber(req.idDocumentNumber());
        user.setDrivingLicenseNo(req.drivingLicenseNo());
        user.setCountry(req.country());
        user.setRole(Role.USER);
        users.save(user);
        notifications.notify(user, "Welcome to Scooter Rent Kandy",
                "Hi " + user.getFullName() + ", your account is ready. Happy riding around Kandy!");
        return new AuthResponse(jwtService.issue(user), UserMapper.toResponse(user));
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest req) {
        User user = users.findByEmailIgnoreCase(req.email().trim())
                .filter(User::isActive)
                .filter(u -> passwordEncoder.matches(req.password(), u.getPasswordHash()))
                .orElseThrow(() -> new BadCredentialsException("bad credentials"));
        return new AuthResponse(jwtService.issue(user), UserMapper.toResponse(user));
    }

    @Transactional(readOnly = true)
    public UserResponse me(UUID userId) {
        return UserMapper.toResponse(users.findById(userId).orElseThrow(() -> ApiException.notFound("User")));
    }

    @Transactional
    public UserResponse updateProfile(UUID userId, UpdateProfileRequest req) {
        User user = users.findById(userId).orElseThrow(() -> ApiException.notFound("User"));
        user.setFullName(req.fullName().trim());
        user.setPhone(req.phone());
        user.setIdDocumentNumber(req.idDocumentNumber());
        user.setDrivingLicenseNo(req.drivingLicenseNo());
        user.setCountry(req.country());
        return UserMapper.toResponse(user);
    }
}
