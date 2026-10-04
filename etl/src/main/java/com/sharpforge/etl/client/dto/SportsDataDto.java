package com.sharpforge.etl.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * DTOs for the secondary sports data API (SportsData.io / Sportradar schema).
 * Covers weather, referee assignments, and play-by-play stats.
 */
public final class SportsDataDto {

    private SportsDataDto() {}

    // ── Weather ─────────────────────────────────────────────────────────────

    @Data
    public static class WeatherForecast {
        @JsonProperty("GameKey")         private String gameKey;
        @JsonProperty("Date")            private String date;          // ISO-8601
        @JsonProperty("StadiumID")       private Integer stadiumId;
        @JsonProperty("IsInDome")        private Boolean isInDome;
        @JsonProperty("Temperature")     private Double temperatureF;
        @JsonProperty("Humidity")        private Double humidityPct;
        @JsonProperty("WindSpeed")       private Double windSpeedMph;
        @JsonProperty("WindDirection")   private String windDirection;
        @JsonProperty("WindGust")        private Double windGustMph;
        @JsonProperty("Weather")         private String weatherDescription; // "Clear", "Rainy", etc.
        @JsonProperty("ForecastHigh")    private Double forecastHighF;
        @JsonProperty("ForecastLow")     private Double forecastLowF;
        @JsonProperty("ForecastWindChill") private Double windChillF;
        @JsonProperty("ForecastPrecipitation") private Double precipitationInches;
        @JsonProperty("ForecastSnowfall") private Double snowfallInches;
    }

    // ── Referee / Officials ──────────────────────────────────────────────────

    @Data
    public static class RefereeAssignment {
        @JsonProperty("GameKey")         private String gameKey;
        @JsonProperty("Date")            private String date;
        @JsonProperty("HomeTeam")        private String homeTeam;
        @JsonProperty("AwayTeam")        private String awayTeam;
        private List<Official> officials;
    }

    @Data
    public static class Official {
        @JsonProperty("RefereeID")       private Integer refereeId;
        @JsonProperty("Name")            private String name;
        @JsonProperty("Number")          private Integer jerseyNumber;
        @JsonProperty("Position")        private String position;     // "Referee", "Umpire", etc.
        @JsonProperty("College")         private String college;
        @JsonProperty("Experience")      private Integer yearsExperience;
    }

    // ── Play-by-Play (NFL) ───────────────────────────────────────────────────

    @Data
    public static class PlayByPlay {
        @JsonProperty("Score")           private GameScore score;
        @JsonProperty("Plays")           private List<Play> plays;
    }

    @Data
    public static class GameScore {
        @JsonProperty("GameKey")         private String gameKey;
        @JsonProperty("ScoreID")         private Integer scoreId;
        @JsonProperty("Status")          private String status;
        @JsonProperty("HomeScore")       private Integer homeScore;
        @JsonProperty("AwayScore")       private Integer awayScore;
        @JsonProperty("Quarter")         private Integer quarter;
        @JsonProperty("TimeRemaining")   private String timeRemaining;
        @JsonProperty("OverTime")        private Boolean overTime;
    }

    @Data
    public static class Play {
        @JsonProperty("PlayID")          private Long playId;
        @JsonProperty("QuarterName")     private String quarterName;
        @JsonProperty("Sequence")        private Integer sequence;
        @JsonProperty("TimeRemaining")   private String timeRemaining;
        @JsonProperty("PlayTime")        private String playTime;
        @JsonProperty("Team")            private String team;
        @JsonProperty("Opponent")        private String opponent;
        @JsonProperty("Down")            private Integer down;
        @JsonProperty("Distance")        private Integer distance;
        @JsonProperty("YardLine")        private Integer yardLine;
        @JsonProperty("YardLineTerritory") private String yardLineTerritory;
        @JsonProperty("YardsGained")     private Integer yardsGained;
        @JsonProperty("PlayDescription") private String playDescription;
        @JsonProperty("Category")        private String category;     // "Pass", "Rush", "Turnover", etc.
        @JsonProperty("Type")            private String type;         // "Fumble", "Interception", etc.
        @JsonProperty("IsTouchdown")     private Boolean isTouchdown;
        @JsonProperty("IsTurnover")      private Boolean isTurnover;
        @JsonProperty("HomeScore")       private Integer homeScore;
        @JsonProperty("AwayScore")       private Integer awayScore;
    }

    // ── Team Game Stats (for xG / EPA / Pythagorean inputs) ─────────────────

    @Data
    public static class TeamGameStats {
        @JsonProperty("GameKey")         private String gameKey;
        @JsonProperty("Team")            private String team;
        @JsonProperty("Opponent")        private String opponent;
        @JsonProperty("HomeOrAway")      private String homeOrAway;
        @JsonProperty("Score")           private Integer score;
        @JsonProperty("OpponentScore")   private Integer opponentScore;
        // Passing
        @JsonProperty("PassingYards")    private Double passingYards;
        @JsonProperty("PassingTouchdowns") private Integer passingTds;
        @JsonProperty("PassingInterceptions") private Integer interceptions;
        @JsonProperty("QuarterbackRating") private Double qbRating;
        // Rushing
        @JsonProperty("RushingYards")    private Double rushingYards;
        @JsonProperty("RushingTouchdowns") private Integer rushingTds;
        // Defense
        @JsonProperty("Sacks")           private Double sacks;
        @JsonProperty("FumbleRecoveries") private Integer fumbleRecoveries;
        @JsonProperty("DefensiveTouchdowns") private Integer defensiveTds;
        // Turnovers
        @JsonProperty("Turnovers")       private Integer turnovers;
        @JsonProperty("TurnoverDifferential") private Integer turnoverDiff;
        // Special Teams
        @JsonProperty("FieldGoals")      private Integer fieldGoals;
        @JsonProperty("FieldGoalAttempts") private Integer fieldGoalAttempts;
        // Penalties
        @JsonProperty("PenaltyYards")    private Integer penaltyYards;
        @JsonProperty("Penalties")       private Integer penalties;
    }

    // ── NHL xG / Corsi (Hockey Reference schema) ─────────────────────────────

    @Data
    public static class HockeyAdvancedStats {
        @JsonProperty("GameID")          private Integer gameId;
        @JsonProperty("Team")            private String team;
        @JsonProperty("CorsiFor")        private Double corsiFor;
        @JsonProperty("CorsiAgainst")    private Double corsiAgainst;
        @JsonProperty("FenwickFor")      private Double fenwickFor;
        @JsonProperty("FenwickAgainst")  private Double fenwickAgainst;
        @JsonProperty("xGoalsFor")       private Double xGoalsFor;
        @JsonProperty("xGoalsAgainst")   private Double xGoalsAgainst;
        @JsonProperty("GoalsFor")        private Integer goalsFor;
        @JsonProperty("GoalsAgainst")    private Integer goalsAgainst;
        @JsonProperty("Shots")           private Integer shots;
        @JsonProperty("ShotsAgainst")    private Integer shotsAgainst;
        @JsonProperty("PossessionPct")   private Double possessionPct;
    }

    // ── Travel / Schedule ────────────────────────────────────────────────────

    @Data
    public static class ScheduleEntry {
        @JsonProperty("GameKey")         private String gameKey;
        @JsonProperty("SeasonType")      private Integer seasonType;
        @JsonProperty("Week")            private Integer week;
        @JsonProperty("Date")            private String date;
        @JsonProperty("HomeTeam")        private String homeTeam;
        @JsonProperty("AwayTeam")        private String awayTeam;
        @JsonProperty("StadiumID")       private Integer stadiumId;
        @JsonProperty("Status")          private String status;
        @JsonProperty("HomeScore")       private Integer homeScore;
        @JsonProperty("AwayScore")       private Integer awayScore;
        @JsonProperty("Channel")         private String channel;
        @JsonProperty("Attendance")      private Integer attendance;
    }
}
