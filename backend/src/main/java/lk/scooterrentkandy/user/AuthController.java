package lk.scooterrentkandy.user;

import jakarta.validation.Valid;
import lk.scooterrentkandy.security.AuthUser;
import lk.scooterrentkandy.security.CurrentUser;
import lk.scooterrentkandy.user.UserDtos.AuthResponse;
import lk.scooterrentkandy.user.UserDtos.LoginRequest;
import lk.scooterrentkandy.user.UserDtos.RegisterRequest;
import lk.scooterrentkandy.user.UserDtos.UpdateProfileRequest;
import lk.scooterrentkandy.user.UserDtos.UserResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/api/auth/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse register(@Valid @RequestBody RegisterRequest req) {
        return authService.register(req);
    }

    @PostMapping("/api/auth/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest req) {
        return authService.login(req);
    }

    @GetMapping("/api/me")
    public UserResponse me(@CurrentUser AuthUser user) {
        return authService.me(user.id());
    }

    @PutMapping("/api/me")
    public UserResponse updateMe(@CurrentUser AuthUser user, @Valid @RequestBody UpdateProfileRequest req) {
        return authService.updateProfile(user.id(), req);
    }
}
