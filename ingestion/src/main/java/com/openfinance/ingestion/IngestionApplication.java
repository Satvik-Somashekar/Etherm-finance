package com.openfinance.ingestion;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.time.Clock;

@SpringBootApplication
public class IngestionApplication {

    public static void main(String[] args) {
        SpringApplication.run(IngestionApplication.class, args);
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
