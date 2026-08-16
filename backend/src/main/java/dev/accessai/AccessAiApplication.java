package dev.accessai;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.SpringApplication;

@SpringBootApplication
@ConfigurationPropertiesScan
public class AccessAiApplication {

    public static void main(String[] args) {
        SpringApplication.run(AccessAiApplication.class, args);
    }
}
