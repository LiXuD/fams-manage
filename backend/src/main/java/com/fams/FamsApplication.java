package com.fams;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point of the FAMS fixed-asset product application.
 *
 * <p>The class sits in the root package so that Spring Boot's default component
 * scanning covers every {@code com.fams} product module without an explicit base
 * package declaration. Delivered feature sets are selected by Spring profiles, for
 * example {@code fams-product} for the product endpoints and
 * {@code local-observability} for the observability checks.
 */
@SpringBootApplication
public class FamsApplication {

    public static void main(String[] args) {
        SpringApplication.run(FamsApplication.class, args);
    }
}
