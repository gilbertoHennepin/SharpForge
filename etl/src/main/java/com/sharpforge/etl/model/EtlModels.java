package com.sharpforge.etl.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Canonical internal domain models used within the ETL pipeline.
 * These decouple the API DTO layer from the jOOQ persistence layer
 * and carry all the derived/calculated fields produced by transformers.
 */
public final class EtlModels {

    private EtlModels() {}

    // ── Game ─────────────────────────────────────────────────────────────────

    @Data @Builder
    public static class GameRecord {
        private UUID gameId;
        private int leagueId;
        private int seasonId;
        private int homeTeamId;
        private int awayTeamId;
        private Integer venueId;
        private Instant scheduledAt;
        private String status;
        private Integer weekNumber;
        private Integer homeScore;
        private Integer awayScore;
        private boolean playoff;
        private boolean divisional;
        private boolean neutralSite;
        private Integer overtimePeriods;
        private Integer attendance;
        // External API ID for cross-referencing
        private String externalId;      // The Odds API event ID
        private String externalGameKey; // SportsData game key
    }

    // ── Odds ─────────────────────────────────────────────────────────────────

    @Data @Builder
    public static class ConsensusOddsRecord {
        private UUID gameId;
        private String marketType;
        // Opening
        private BigDecimal openHomeSpread;
        private BigDecimal openTotal;
        private Integer openHomeMl;
        private Integer openAwayMl;
        private BigDecimal openHomeJuice;
        private BigDecimal openAwayJuice;
        private BigDecimal openOverJuice;
        private BigDecimal openUnderJuice;
        private Instant openAt;
        // Closing
        private BigDecimal closeHomeSpread;
        private BigDecimal closeTotal;
        private Integer closeHomeMl;
        private Integer closeAwayMl;
        private BigDecimal closeHomeJuice;
        private BigDecimal closeAwayJuice;
        private Instant closeAt;
        // Previous game context
        private BigDecimal prevGameHomeSpread;
        private BigDecimal prevGameAwaySpread;
        // Results
        private BigDecimal actualHomeSpreadResult;
        private BigDecimal actualTotalResult;
        private String coveredSpread;   // 'HOME' | 'AWAY'
        private String totalResult;     // 'OVER' | 'UNDER'
    }

    @Data @Builder
    public static class BookOddsRecord {
        private UUID gameId;
        private int bookId;
        private String marketType;
        private Instant recordedAt;
        private BigDecimal homeSpread;
        private BigDecimal total;
        private Integer homeMl;
        private Integer awayMl;
        private BigDecimal homeJuice;
        private BigDecimal awayJuice;
        private BigDecimal overJuice;
        private BigDecimal underJuice;
        private boolean isOpening;
        private boolean isClosing;
    }

    @Data @Builder
    public static class LineMovementRecord {
        private UUID gameId;
        private int bookId;
        private String marketType;
        private Instant movedAt;
        private BigDecimal prevHomeSpread;
        private BigDecimal prevTotal;
        private Integer prevHomeMl;
        private BigDecimal newHomeSpread;
        private BigDecimal newTotal;
        private Integer newHomeMl;
        private String direction;
        private boolean steamMove;
        private boolean reverseLineMove;
        private Integer minutesToGame;
    }

    // ── Weather ───────────────────────────────────────────────────────────────

    @Data @Builder
    public static class WeatherRecord {
        private UUID gameId;
        private String dataSource;
        private Instant forecastAt;
        private boolean actual;
        private String condition;       // maps to environ.weather_condition enum
        private BigDecimal tempF;
        private BigDecimal windSpeedMph;
        private BigDecimal windGustMph;
        private String windDir;
        private BigDecimal crosswindMph;
        private BigDecimal precipProbPct;
        private BigDecimal precipInches;
        private BigDecimal snowInches;
        private BigDecimal humidityPct;
        private BigDecimal visibilityMiles;
        private BigDecimal pressureMb;
        // Derived impact scores (computed by WeatherTransformer)
        private BigDecimal passingImpactScore;
        private BigDecimal kickingImpactScore;
        private BigDecimal scoringSuppressionIdx;
    }

    // ── Personnel ─────────────────────────────────────────────────────────────

    @Data @Builder
    public static class GameStarterRecord {
        private UUID gameId;
        private int teamId;
        private UUID playerId;
        private String position;
        private boolean confirmed;
        private Instant confirmedAt;
        private String gameStatus;      // injury status enum value
        private BigDecimal stat1;       // QB rating / SP ERA / Goalie save%
        private BigDecimal stat2;
        private BigDecimal stat3;
    }

    @Data @Builder
    public static class GameOfficialRecord {
        private UUID gameId;
        private int officialId;
        private String role;
        private boolean crewChief;
    }

    // ── Streaks (the calculated output from StreakCalculator) ─────────────────

    @Data @Builder
    public static class TeamStreaks {
        private int teamId;
        private int seasonId;
        private LocalDate asOfDate;
        // Straight Up (W/L)
        private int suCurrentStreak;        // positive = win streak, negative = loss streak
        private int suLongestWinStreak;
        private int suLongestLossStreak;
        // Against the Spread
        private int atsCurrentStreak;       // positive = cover streak, negative = fail-to-cover
        private int atsLongestCoverStreak;
        private int atsLongestFailStreak;
        // Over / Under
        private int ouCurrentStreak;        // positive = over streak, negative = under
        private int ouLongestOverStreak;
        private int ouLongestUnderStreak;
        // Turnovers
        private int toCurrentStreak;        // positive = won TO battle, negative = lost
        private int toWonBattleStreak;
        private int toLostBattleStreak;
        // Additional situational streaks
        private int homeWinStreak;          // current home win streak
        private int awayWinStreak;          // current away win streak
        private int atsAfterWinStreak;      // ATS streak in games following a win
        private int atsAfterLossStreak;     // ATS streak in games following a loss
    }

    // ── Rest / Fatigue ────────────────────────────────────────────────────────

    @Data @Builder
    public static class RestFatigueRecord {
        private UUID gameId;
        private int teamId;
        private boolean homeTeam;
        private int daysRest;
        private Integer opponentDaysRest;
        private boolean backToBack;
        private boolean secondOfBackToBack;
        private boolean thirdIn4Days;
        private boolean shortWeek;
        private boolean longWeek;
        private int gamesLast7Days;
        private int gamesLast14Days;
        private int gamesLast30Days;
        private Integer travelMilesLast14d;
        private BigDecimal fatigueScore;
        private BigDecimal fatigueScoreOpp;
        private String prevGameResult;
        private boolean prevGameOt;
    }

    // ── Advanced Stats ────────────────────────────────────────────────────────

    @Data @Builder
    public static class GameAdvancedRecord {
        private UUID gameId;
        private int teamId;
        private boolean homeTeam;
        // xG (Hockey / Soccer)
        private BigDecimal xgFor;
        private BigDecimal xgAgainst;
        private BigDecimal actualGoals;
        // EPA (NFL)
        private BigDecimal epaPerPlay;
        private BigDecimal epaPerPass;
        private BigDecimal epaPerRush;
        // Hockey shot quality
        private BigDecimal corsiForPct;
        private BigDecimal fenwickForPct;
        // Turnovers
        private Integer turnoversCommitted;
        private Integer turnoversForced;
        // Possession / pace
        private BigDecimal playsPerGame;
        private BigDecimal possessionPct;
        // Penalties
        private Integer penaltyYards;
        private Integer penalties;
    }
}
