package com.sharpforge.etl.api.backtest;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.table;

@Service
@Slf4j
@RequiredArgsConstructor
public class BacktestService {

    private final DSLContext dsl;
    private final BacktestQueryBuilder queryBuilder;
    private final StatisticalSignificanceUtil statUtil;

    private static final BigDecimal JUICE_STD = BigDecimal.valueOf(1.10); // standard -110 juice = risk 1.1 to win 1.0

    @Transactional(readOnly = true)
    public BacktestResponseDto runBacktest(BacktestRequestDto request) {
        Condition condition = queryBuilder.buildCondition(request);

        log.info("Executing deep-filter backtest against mv_game_context...");

        // Note: For extreme performance on millions of rows, we could push the aggregation down to SQL
        // (e.g., COUNT(*) FILTER). For backtesting, where result sets typically match a few thousand games,
        // we stream the targeted fields to evaluate complex bet logic in-memory.
        var records = dsl.select(
                        field("game_id", UUID.class),
                        field("game_date", LocalDate.class),
                        field("home_team_abbr", String.class),
                        field("away_team_abbr", String.class),
                        field("home_score", Integer.class),
                        field("away_score", Integer.class),
                        field("close_home_spread", BigDecimal.class),
                        field("close_total", BigDecimal.class),
                        field("covered_spread", String.class),
                        field("total_result", String.class))
                .from(table("stats.mv_game_context"))
                .where(condition)
                .orderBy(field("game_date").desc())
                .fetch();

        int wins = 0;
        int losses = 0;
        int pushes = 0;
        BigDecimal totalUnits = BigDecimal.ZERO;
        List<BacktestResponseDto.GameLog> logs = new ArrayList<>();

        for (Record r : records) {
            String betPlaced = resolveBetPlaced(request.getBetTarget(), r);
            String betResult = resolveBetResult(request.getBetTarget(), r);

            BigDecimal units = BigDecimal.ZERO;
            if ("WIN".equals(betResult)) {
                wins++;
                units = BigDecimal.ONE; // win 1 unit
            } else if ("LOSS".equals(betResult)) {
                losses++;
                units = JUICE_STD.negate(); // lose 1.1 units
            } else if ("PUSH".equals(betResult)) {
                pushes++;
            }

            totalUnits = totalUnits.add(units);

            logs.add(BacktestResponseDto.GameLog.builder()
                    .gameId(r.get("game_id", UUID.class))
                    .gameDate(r.get("game_date", LocalDate.class))
                    .homeTeam(r.get("home_team_abbr", String.class))
                    .awayTeam(r.get("away_team_abbr", String.class))
                    .homeScore(r.get("home_score", Integer.class))
                    .awayScore(r.get("away_score", Integer.class))
                    .closeHomeSpread(r.get("close_home_spread", BigDecimal.class))
                    .closeTotal(r.get("close_total", BigDecimal.class))
                    .betPlaced(betPlaced)
                    .betResult(betResult)
                    .unitsWon(units.setScale(2, RoundingMode.HALF_UP))
                    .build());
        }

        int totalGames = wins + losses + pushes;
        BigDecimal winRate = (wins + losses) > 0
                ? BigDecimal.valueOf(wins).multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(wins + losses), 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        // ROI = (Units Won / Total Units Risked) * 100
        BigDecimal totalRisked = BigDecimal.valueOf(wins + losses).multiply(JUICE_STD);
        BigDecimal roi = totalRisked.compareTo(BigDecimal.ZERO) > 0
                ? totalUnits.multiply(BigDecimal.valueOf(100))
                    .divide(totalRisked, 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        double pValue = statUtil.calculatePValue(wins, losses);
        String confidence = statUtil.determineConfidence(pValue, wins + losses);

        BacktestResponseDto.Summary summary = BacktestResponseDto.Summary.builder()
                .totalGames(totalGames)
                .wins(wins)
                .losses(losses)
                .pushes(pushes)
                .winRatePct(winRate)
                .roiPct(roi)
                .unitsWon(totalUnits.setScale(2, RoundingMode.HALF_UP))
                .pValue(pValue)
                .confidenceLevel(confidence)
                .build();

        // Pagination
        int fromIndex = Math.min(request.getPage() * request.getSize(), logs.size());
        int toIndex = Math.min(fromIndex + request.getSize(), logs.size());
        List<BacktestResponseDto.GameLog> paginatedLogs = logs.subList(fromIndex, toIndex);

        return BacktestResponseDto.builder()
                .summary(summary)
                .gameLogs(paginatedLogs)
                .totalRecords(logs.size())
                .build();
    }

    private String resolveBetPlaced(BacktestRequestDto.BetTarget target, Record r) {
        BigDecimal homeSpread = r.get("close_home_spread", BigDecimal.class);
        if (target == null) return "UNKNOWN";

        return switch (target) {
            case HOME_SPREAD -> "HOME " + formatSpread(homeSpread);
            case AWAY_SPREAD -> "AWAY " + formatSpread(homeSpread != null ? homeSpread.negate() : null);
            case OVER -> "OVER " + r.get("close_total", BigDecimal.class);
            case UNDER -> "UNDER " + r.get("close_total", BigDecimal.class);
            case FAVORITE -> {
                if (homeSpread == null) yield "PK";
                yield homeSpread.compareTo(BigDecimal.ZERO) < 0 
                        ? "HOME " + formatSpread(homeSpread) 
                        : "AWAY " + formatSpread(homeSpread.negate());
            }
            case UNDERDOG -> {
                if (homeSpread == null) yield "PK";
                yield homeSpread.compareTo(BigDecimal.ZERO) > 0 
                        ? "HOME " + formatSpread(homeSpread) 
                        : "AWAY " + formatSpread(homeSpread.negate());
            }
        };
    }

    private String resolveBetResult(BacktestRequestDto.BetTarget target, Record r) {
        String coveredSpread = r.get("covered_spread", String.class);
        String totalResult = r.get("total_result", String.class);
        BigDecimal homeSpread = r.get("close_home_spread", BigDecimal.class);

        if (target == null) return "NONE";

        return switch (target) {
            case HOME_SPREAD -> "HOME".equals(coveredSpread) ? "WIN" : "PUSH".equals(coveredSpread) ? "PUSH" : "LOSS";
            case AWAY_SPREAD -> "AWAY".equals(coveredSpread) ? "WIN" : "PUSH".equals(coveredSpread) ? "PUSH" : "LOSS";
            case OVER -> "OVER".equals(totalResult) ? "WIN" : "PUSH".equals(totalResult) ? "PUSH" : "LOSS";
            case UNDER -> "UNDER".equals(totalResult) ? "WIN" : "PUSH".equals(totalResult) ? "PUSH" : "LOSS";
            case FAVORITE -> {
                if (homeSpread == null || homeSpread.compareTo(BigDecimal.ZERO) == 0 || "PUSH".equals(coveredSpread)) yield "PUSH";
                if (homeSpread.compareTo(BigDecimal.ZERO) < 0) { // Home favorite
                    yield "HOME".equals(coveredSpread) ? "WIN" : "LOSS";
                } else { // Away favorite
                    yield "AWAY".equals(coveredSpread) ? "WIN" : "LOSS";
                }
            }
            case UNDERDOG -> {
                if (homeSpread == null || homeSpread.compareTo(BigDecimal.ZERO) == 0 || "PUSH".equals(coveredSpread)) yield "PUSH";
                if (homeSpread.compareTo(BigDecimal.ZERO) > 0) { // Home underdog
                    yield "HOME".equals(coveredSpread) ? "WIN" : "LOSS";
                } else { // Away underdog
                    yield "AWAY".equals(coveredSpread) ? "WIN" : "LOSS";
                }
            }
        };
    }

    private String formatSpread(BigDecimal spread) {
        if (spread == null) return "PK";
        return spread.compareTo(BigDecimal.ZERO) > 0 ? "+" + spread : spread.toString();
    }
}
