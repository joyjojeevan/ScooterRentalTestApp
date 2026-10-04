package lk.scooterrentkandy.support;

import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

/** Replaces the application clock in every test context. */
@Configuration
@Profile("test")
public class TestClockConfig {

    @Bean
    @Primary
    MutableClock testClock() {
        return new MutableClock(ZoneId.systemDefault());
    }
}
