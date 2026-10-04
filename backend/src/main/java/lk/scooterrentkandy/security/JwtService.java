package lk.scooterrentkandy.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Optional;
import javax.crypto.SecretKey;
import lk.scooterrentkandy.config.AppProperties;
import lk.scooterrentkandy.user.User;
import org.springframework.stereotype.Service;

@Service
public class JwtService {

    private final SecretKey key;
    private final long expirationMinutes;

    public JwtService(AppProperties props) {
        this.key = Keys.hmacShaKeyFor(props.jwt().secret().getBytes(StandardCharsets.UTF_8));
        this.expirationMinutes = props.jwt().expirationMinutes();
    }

    public String issue(User user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(user.getId()))
                .claim("email", user.getEmail())
                .claim("role", user.getRole().name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(expirationMinutes, ChronoUnit.MINUTES)))
                .signWith(key)
                .compact();
    }

    public Optional<Claims> parse(String token) {
        try {
            return Optional.of(Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload());
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
