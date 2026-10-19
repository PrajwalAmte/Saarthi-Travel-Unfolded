package io.destinationintel;

import io.destinationintel.config.AppProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableConfigurationProperties(AppProperties.class)
@EnableScheduling
@EnableCaching
public class SaarthiTravelUnfoldedApplication {

    public static void main(String[] args) {
        SpringApplication.run(SaarthiTravelUnfoldedApplication.class, args);
    }
}
