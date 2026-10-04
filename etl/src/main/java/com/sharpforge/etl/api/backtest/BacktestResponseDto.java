package com.sharpforge.etl.api.backtest;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Output payload for a backtest query.
 * Contains the aggregated performance metrics (ROI, Units, Win Rate)
 * and a paginated list of historical games that matched the criteria.
 */
@Data
@Builder
public class BacktestResponseDto {

    private Summary summary;
    private List<GameLog> gameLogs;
    private long totalRecords;

    @Data
    @Builder
    public static class Summary {
        private int totalGames;
        private int wins;
        private int losses;
        private int pushes;
        private BigDecimal winRatePct;
        private BigDecimal roiPct;
        private BigDecimal unitsWon;
        private Double pValue;
        private String confidenceLevel;
    }

    @Data
    @Builder
    public static class GameLog {
        private UUID gameId;
        private LocalDate gameDate;
        private String homeTeam;
        private String awayTeam;
        private Integer homeScore;
        private Integer awayScore;
        private BigDecimal closeHomeSpread;
        private BigDecimal closeTotal;
        private String betPlaced;   // e.g. "HOME +3.5" or "UNDER 44"
        private String betResult;   // "WIN", "LOSS", "PUSH"
        private BigDecimal unitsWon; // +1.0 for win, -1.1 for loss at -110 juice
    }
}
