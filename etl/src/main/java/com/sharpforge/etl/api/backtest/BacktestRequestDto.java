package com.sharpforge.etl.api.backtest;

import lombok.Data;

import java.time.LocalDate;

/**
 * DTO representing a complex, nested backtest query payload from the frontend.
 * Evaluates deep situational filters across odds, public betting, weather, and rest.
 */
@Data
public class BacktestRequestDto {

    private Integer leagueId;
    private LocalDate startDate;
    private LocalDate endDate;
    
    /** The target to evaluate ROI against. E.g. HOME_SPREAD, AWAY_SPREAD, OVER, UNDER, FAVORITE, UNDERDOG */
    private BetTarget betTarget;

    // ── Nested Filters ────────────────────────────────────────────────────────

    private OddsFilters odds;
    private PublicBettingFilters publicBetting;
    private WeatherFilters weather;
    private RestFilters rest;
    private PersonnelFilters personnel;

    // Pagination
    private int page = 0;
    private int size = 100;

    public enum BetTarget {
        HOME_SPREAD, AWAY_SPREAD, OVER, UNDER, FAVORITE, UNDERDOG
    }

    @Data
    public static class OddsFilters {
        private Double minSpread;       // absolute value of spread (e.g. 3.0)
        private Double maxSpread;
        private Double minTotal;
        private Double maxTotal;
        private String spreadMoveDirection; // "TOWARD_HOME", "TOWARD_AWAY"
        private Integer minSteamMoves;
    }

    @Data
    public static class PublicBettingFilters {
        private String sharpSide;           // "HOME", "AWAY", "OVER", "UNDER"
        private Boolean isSharpVsPublic;    // Requires sharp_signal_strength > threshold
        private Double maxPublicTicketPct;  // e.g., 30.0 (fade the public)
        private Double minSharpMoneyPct;    // e.g., 70.0
    }

    @Data
    public static class WeatherFilters {
        private Boolean excludeDomes;
        private Double minWindSpeedMph;
        private Double maxTempF;
        private Double minPrecipProbPct;
        private Double minScoringSuppressionIdx;
    }

    @Data
    public static class RestFilters {
        private Integer minRestAdvantage;   // Difference in days rest
        private Integer maxHomeRestDays;
        private Integer maxAwayRestDays;
        private Boolean homeShortWeek;
        private Boolean awayShortWeek;
    }

    @Data
    public static class PersonnelFilters {
        private Boolean isOverOfficial;
        private Boolean isUnderOfficial;
        private Double minOfficialOverRate;
    }
}
