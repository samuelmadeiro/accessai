package dev.accessai.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Relogio injetado em vez de {@code Instant.now()} espalhado pelo codigo:
 * tempo vira dependencia e o teste consegue fixa-lo.
 */
@Configuration
public class RelogioConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
