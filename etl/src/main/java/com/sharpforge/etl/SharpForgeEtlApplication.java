package com.sharpforge.etl;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * SharpForge ETL Pipeline
 *
 * <p>Ingests sports betting data from The Odds API and secondary sports data providers,
 * transforms raw records into advanced analytics (streaks, rest days, Pythagorean stats),
 * and persists everything to PostgreSQL using jOOQ batch inserts.
 */
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan("com.sharpforge.etl.config")
public class SharpForgeEtlApplication {

    public static void main(String[] args) {
        SpringApplication.run(SharpForgeEtlApplication.class, args);
    }
}
