package dev.accessai;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.SpringApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/** {@code @EnableScheduling} existe pelo publicador do outbox, que roda em ciclo. */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class AccessAiApplication {

    public static void main(String[] args) {
        SpringApplication.run(AccessAiApplication.class, args);
    }
}
