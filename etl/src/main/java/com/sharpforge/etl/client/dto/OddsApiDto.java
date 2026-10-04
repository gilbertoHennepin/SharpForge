package com.sharpforge.etl.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * DTOs mapping the JSON response from The Odds API v4.
 *
 * <p>Example endpoint: GET /v4/sports/{sport}/odds
 * Documentation: https://the-odds-api.com/lob-odds-api/
 */
public final class OddsApiDto {

    private OddsApiDto() {}

    /** Top-level game event from the odds API. */
    @Data
    public static class Event {
        private String id;
        @JsonProperty("sport_key")   private String sportKey;
        @JsonProperty("sport_title") private String sportTitle;
        @JsonProperty("commence_time") private String commenceTime;     // ISO-8601
        @JsonProperty("home_team")   private String homeTeam;
        @JsonProperty("away_team")   private String awayTeam;
        private List<Bookmaker> bookmakers;
    }

    /** One sportsbook's offering for this event. */
    @Data
    public static class Bookmaker {
        private String key;
        private String title;
        @JsonProperty("last_update") private String lastUpdate;
        private List<Market> markets;
    }

    /** One market (h2h / spreads / totals) within a bookmaker offering. */
    @Data
    public static class Market {
        private String key;                 // "h2h", "spreads", "totals"
        @JsonProperty("last_update") private String lastUpdate;
        private List<Outcome> outcomes;
    }

    /** Individual outcome within a market (home spread, away ML, over, etc.). */
    @Data
    public static class Outcome {
        private String name;                // team name OR "Over"/"Under"
        private Double price;               // American odds
        private Double point;              // spread or total value
        private String description;        // e.g. "Chicago Bears"
    }

    /** Response wrapper for historical odds endpoint. */
    @Data
    public static class HistoricalResponse {
        private String timestamp;
        @JsonProperty("previous_timestamp") private String previousTimestamp;
        @JsonProperty("next_timestamp")     private String nextTimestamp;
        private List<Event> data;
    }

    /** Remaining API quota returned in response headers (parsed separately). */
    @Data
    public static class QuotaInfo {
        private int requestsUsed;
        private int requestsRemaining;
        private int requestsLastMonth;
    }
}
