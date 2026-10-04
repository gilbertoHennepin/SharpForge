package com.sharpforge.etl.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Strongly-typed configuration properties for all external APIs, ETL settings,
 * and scheduling expressions — bound from application.yml.
 */
@ConfigurationProperties(prefix = "sharpforge")
@Data
public class SharpForgeProperties {

    private Apis apis = new Apis();
    private Schedule schedule = new Schedule();
    private Etl etl = new Etl();

    // ── External API settings ─────────────────────────────────────────────

    @Data
    public static class Apis {
        private OddsApi oddsApi = new OddsApi();
        private SportsDataApi sportsDataApi = new SportsDataApi();
        private WeatherApi weatherApi = new WeatherApi();
    }

    @Data
    public static class OddsApi {
        private String baseUrl;
        private String apiKey;
        private List<String> sports;
        private List<String> markets;
        private List<String> bookmakers;
        private String regions;
        private String oddsFormat;
        private int connectTimeoutMs = 5000;
        private int readTimeoutMs = 10000;
        private int quotaWarnThreshold = 450;
    }

    @Data
    public static class SportsDataApi {
        private String baseUrl;
        private String apiKey;
        private int connectTimeoutMs = 5000;
        private int readTimeoutMs = 15000;
    }

    @Data
    public static class WeatherApi {
        private String baseUrl;
        private String apiKey;
        private int forecastLeadHours = 72;
        private int connectTimeoutMs = 3000;
        private int readTimeoutMs = 8000;
    }

    // ── Scheduling ────────────────────────────────────────────────────────

    @Data
    public static class Schedule {
        private String oddsRefreshCron;
        private String historicalBackfillCron;
        private String weatherRefreshCron;
        private String refereeRefreshCron;
        private String pbpIngestCron;
        private String streakRecalcCron;
        private String mvRefreshCron;
    }

    // ── ETL processing settings ───────────────────────────────────────────

    @Data
    public static class Etl {
        private int historicalBackfillDays = 1825;
        private int batchSize = 500;
        private int gameParallelism = 8;
        private int minGamesForStreak = 3;
        private Rest rest = new Rest();

        @Data
        public static class Rest {
            private int shortWeekThresholdDays = 6;
            private int longWeekThresholdDays = 14;
        }
    }
}
