package com.sharpforge.etl.repository;

import com.sharpforge.etl.config.SharpForgeProperties;
import com.sharpforge.etl.model.EtlModels.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.*;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.jooq.impl.DSL.*;

/**
 * jOOQ batch repository for all ETL persistence operations.
 *
 * <p>All insert methods use jOOQ's {@link DSLContext#batchInsert} or the
 * {@code INSERT INTO ... VALUES (...), (...), ...} multi-row syntax for
 * high-throughput ingestion. Every write is idempotent via {@code ON CONFLICT DO UPDATE}
 * (upsert semantics) so the scheduler can safely re-run without duplicating data.
 *
 * <p>Chunk sizes are controlled by {@code sharpforge.etl.batch-size} in application.yml.
 */
@Repository
@Slf4j
@RequiredArgsConstructor
public class EtlBatchRepository {

    private final DSLContext dsl;
    private final SharpForgeProperties props;

    // ── Table name constants ───────────────────────────────────────────────────
    // We use DSL.table() / DSL.field() to keep the code compatible
    // whether or not jOOQ code generation has run.

    private static final Table<?> CONSENSUS_ODDS  = table("market.consensus_odds");
    private static final Table<?> BOOK_ODDS        = table("market.book_odds");
    private static final Table<?> LINE_MOVEMENTS   = table("market.line_movements");
    private static final Table<?> GAME_WEATHER     = table("environ.game_weather");
    private static final Table<?> GAME_STARTERS    = table("personnel.game_starters");
    private static final Table<?> GAME_OFFICIALS   = table("personnel.game_officials");
    private static final Table<?> REST_FATIGUE     = table("stats.rest_and_fatigue");
    private static final Table<?> GAME_ADVANCED    = table("stats.game_advanced");
    private static final Table<?> STREAK_TABLE     = table("stats.team_streak_snapshots");

    // ── Consensus Odds ────────────────────────────────────────────────────────

    /**
     * Upsert consensus odds for a batch of games.
     * Conflict target: (game_id, market_type).
     */
    @Transactional
    public int[] batchUpsertConsensusOdds(List<ConsensusOddsRecord> records) {
        int chunkSize = props.getEtl().getBatchSize();
        log.info("Upserting {} consensus odds records (chunk={})", records.size(), chunkSize);

        int[] results = new int[records.size()];
        int inserted = 0;

        for (int i = 0; i < records.size(); i += chunkSize) {
            List<ConsensusOddsRecord> chunk = records.subList(i, Math.min(i + chunkSize, records.size()));

            var inserts = chunk.stream()
                    .map(r -> dsl.insertInto(CONSENSUS_ODDS,
                            field("game_id"),
                            field("market_type"),
                            field("open_home_spread"),
                            field("open_total"),
                            field("open_home_ml"),
                            field("open_away_ml"),
                            field("open_home_juice"),
                            field("open_away_juice"),
                            field("open_over_juice"),
                            field("open_under_juice"),
                            field("open_at"),
                            field("close_home_spread"),
                            field("close_total"),
                            field("close_home_ml"),
                            field("close_away_ml"),
                            field("close_home_juice"),
                            field("close_away_juice"),
                            field("close_at"),
                            field("prev_game_home_spread"),
                            field("prev_game_away_spread"),
                            field("actual_home_spread_result"),
                            field("actual_total_result"),
                            field("covered_spread"),
                            field("total_result"),
                            field("updated_at"))
                        .values(
                            r.getGameId(),
                            r.getMarketType(),
                            r.getOpenHomeSpread(),
                            r.getOpenTotal(),
                            r.getOpenHomeMl(),
                            r.getOpenAwayMl(),
                            r.getOpenHomeJuice(),
                            r.getOpenAwayJuice(),
                            r.getOpenOverJuice(),
                            r.getOpenUnderJuice(),
                            toOffset(r.getOpenAt()),
                            r.getCloseHomeSpread(),
                            r.getCloseTotal(),
                            r.getCloseHomeMl(),
                            r.getCloseAwayMl(),
                            r.getCloseHomeJuice(),
                            r.getCloseAwayJuice(),
                            toOffset(r.getCloseAt()),
                            r.getPrevGameHomeSpread(),
                            r.getPrevGameAwaySpread(),
                            r.getActualHomeSpreadResult(),
                            r.getActualTotalResult(),
                            r.getCoveredSpread(),
                            r.getTotalResult(),
                            OffsetDateTime.now())
                        .onConflict(field("game_id"), field("market_type"))
                        .doUpdate()
                            .set(field("close_home_spread"),         r.getCloseHomeSpread())
                            .set(field("close_total"),               r.getCloseTotal())
                            .set(field("close_home_ml"),             r.getCloseHomeMl())
                            .set(field("close_away_ml"),             r.getCloseAwayMl())
                            .set(field("actual_home_spread_result"), r.getActualHomeSpreadResult())
                            .set(field("actual_total_result"),       r.getActualTotalResult())
                            .set(field("covered_spread"),            r.getCoveredSpread())
                            .set(field("total_result"),              r.getTotalResult())
                            .set(field("updated_at"),                OffsetDateTime.now()))
                    .toList();

            int[] chunkResults = dsl.batch(inserts).execute();
            System.arraycopy(chunkResults, 0, results, inserted, chunkResults.length);
            inserted += chunk.size();
            log.debug("Upserted consensus odds chunk {}/{}", inserted, records.size());
        }

        return results;
    }

    // ── Book Odds (per-snapshot) ──────────────────────────────────────────────

    /**
     * Batch-insert per-book odds snapshots.
     * Does NOT upsert — each snapshot is a unique timestamped record.
     * Uses {@code ON CONFLICT DO NOTHING} to skip true duplicates on re-run.
     */
    @Transactional
    public int batchInsertBookOdds(List<BookOddsRecord> records) {
        int chunkSize = props.getEtl().getBatchSize();
        log.info("Inserting {} book odds snapshots", records.size());

        int total = 0;
        for (int i = 0; i < records.size(); i += chunkSize) {
            List<BookOddsRecord> chunk = records.subList(i, Math.min(i + chunkSize, records.size()));

            var inserts = chunk.stream()
                    .map(r -> dsl.insertInto(BOOK_ODDS,
                            field("game_id"), field("book_id"), field("market_type"),
                            field("recorded_at"), field("home_spread"), field("total"),
                            field("home_ml"), field("away_ml"), field("home_juice"),
                            field("away_juice"), field("over_juice"), field("under_juice"),
                            field("is_opening"), field("is_closing"))
                        .values(
                            r.getGameId(), r.getBookId(), r.getMarketType(),
                            toOffset(r.getRecordedAt()), r.getHomeSpread(), r.getTotal(),
                            r.getHomeMl(), r.getAwayMl(), r.getHomeJuice(),
                            r.getAwayJuice(), r.getOverJuice(), r.getUnderJuice(),
                            r.isOpening(), r.isClosing())
                        .onConflictDoNothing())
                    .toList();

            total += dsl.batch(inserts).execute().length;
        }

        log.info("Completed book odds insert: {} chunks processed", total);
        return total;
    }

    // ── Line Movements ────────────────────────────────────────────────────────

    @Transactional
    public int batchInsertLineMovements(List<LineMovementRecord> records) {
        int chunkSize = props.getEtl().getBatchSize();
        log.info("Inserting {} line movement records", records.size());

        int total = 0;
        for (int i = 0; i < records.size(); i += chunkSize) {
            List<LineMovementRecord> chunk = records.subList(i, Math.min(i + chunkSize, records.size()));

            var inserts = chunk.stream()
                    .map(r -> dsl.insertInto(LINE_MOVEMENTS,
                            field("game_id"), field("book_id"), field("market_type"),
                            field("moved_at"), field("prev_home_spread"), field("prev_total"),
                            field("prev_home_ml"), field("new_home_spread"), field("new_total"),
                            field("new_home_ml"), field("direction"),
                            field("is_steam_move"), field("is_reverse_line"), field("minutes_to_game"))
                        .values(
                            r.getGameId(), r.getBookId(), r.getMarketType(),
                            toOffset(r.getMovedAt()), r.getPrevHomeSpread(), r.getPrevTotal(),
                            r.getPrevHomeMl(), r.getNewHomeSpread(), r.getNewTotal(),
                            r.getNewHomeMl(), r.getDirection(),
                            r.isSteamMove(), r.isReverseLineMove(), r.getMinutesToGame())
                        .onConflictDoNothing())
                    .toList();

            total += dsl.batch(inserts).execute().length;
        }
        return total;
    }

    // ── Weather ───────────────────────────────────────────────────────────────

    @Transactional
    public int[] batchUpsertWeather(List<WeatherRecord> records) {
        log.info("Upserting {} weather records", records.size());

        var inserts = records.stream()
                .map(r -> dsl.insertInto(GAME_WEATHER,
                        field("game_id"), field("data_source"), field("forecast_at"),
                        field("is_actual"), field("condition"), field("temp_f"),
                        field("wind_speed_mph"), field("wind_gust_mph"), field("wind_dir"),
                        field("crosswind_mph"), field("precip_prob_pct"), field("precip_inches"),
                        field("snow_inches"), field("humidity_pct"), field("visibility_miles"),
                        field("pressure_mb"), field("passing_impact_score"),
                        field("kicking_impact_score"), field("scoring_suppression_idx"))
                    .values(
                        r.getGameId(), r.getDataSource(), toOffset(r.getForecastAt()),
                        r.isActual(), r.getCondition(), r.getTempF(),
                        r.getWindSpeedMph(), r.getWindGustMph(), r.getWindDir(),
                        r.getCrosswindMph(), r.getPrecipProbPct(), r.getPrecipInches(),
                        r.getSnowInches(), r.getHumidityPct(), r.getVisibilityMiles(),
                        r.getPressureMb(), r.getPassingImpactScore(),
                        r.getKickingImpactScore(), r.getScoringSuppressionIdx())
                    .onConflict(field("game_id"), field("is_actual"))
                    .doUpdate()
                        .set(field("condition"),               r.getCondition())
                        .set(field("temp_f"),                  r.getTempF())
                        .set(field("wind_speed_mph"),          r.getWindSpeedMph())
                        .set(field("scoring_suppression_idx"), r.getScoringSuppressionIdx())
                        .set(field("passing_impact_score"),    r.getPassingImpactScore())
                        .set(field("kicking_impact_score"),    r.getKickingImpactScore()))
                .toList();

        return dsl.batch(inserts).execute();
    }

    // ── Rest & Fatigue ────────────────────────────────────────────────────────

    @Transactional
    public int[] batchUpsertRestFatigue(List<RestFatigueRecord> records) {
        log.info("Upserting {} rest/fatigue records", records.size());

        var inserts = records.stream()
                .map(r -> dsl.insertInto(REST_FATIGUE,
                        field("game_id"), field("team_id"), field("is_home"),
                        field("days_rest"), field("opponent_days_rest"),
                        field("is_back_to_back"), field("is_second_of_back2back"),
                        field("is_third_in_4_days"), field("is_short_week"), field("is_long_week"),
                        field("games_last_7_days"), field("games_last_14_days"), field("games_last_30_days"),
                        field("travel_miles_last_14d"), field("fatigue_score"), field("fatigue_score_opp"),
                        field("prev_game_result"), field("prev_game_was_overtime"))
                    .values(
                        r.getGameId(), r.getTeamId(), r.isHomeTeam(),
                        r.getDaysRest(), r.getOpponentDaysRest(),
                        r.isBackToBack(), r.isSecondOfBackToBack(),
                        r.isThirdIn4Days(), r.isShortWeek(), r.isLongWeek(),
                        r.getGamesLast7Days(), r.getGamesLast14Days(), r.getGamesLast30Days(),
                        r.getTravelMilesLast14d(), r.getFatigueScore(), r.getFatigueScoreOpp(),
                        r.getPrevGameResult(), r.isPrevGameOt())
                    .onConflict(field("game_id"), field("team_id"))
                    .doUpdate()
                        .set(field("days_rest"),               r.getDaysRest())
                        .set(field("fatigue_score"),           r.getFatigueScore())
                        .set(field("fatigue_score_opp"),       r.getFatigueScoreOpp())
                        .set(field("games_last_7_days"),       r.getGamesLast7Days())
                        .set(field("travel_miles_last_14d"),   r.getTravelMilesLast14d()))
                .toList();

        return dsl.batch(inserts).execute();
    }

    // ── Advanced Stats ────────────────────────────────────────────────────────

    @Transactional
    public int[] batchUpsertGameAdvanced(List<GameAdvancedRecord> records) {
        log.info("Upserting {} game advanced stat records", records.size());

        var inserts = records.stream()
                .map(r -> dsl.insertInto(GAME_ADVANCED,
                        field("game_id"), field("team_id"), field("is_home"),
                        field("xg_for"), field("xg_against"), field("actual_goals"),
                        field("epa_per_play"), field("epa_per_pass"), field("epa_per_rush"),
                        field("corsi_for_pct"), field("fenwick_for_pct"),
                        field("turnovers_committed"), field("turnovers_forced"),
                        field("plays_per_game"), field("possession_pct"),
                        field("penalty_yards"), field("penalties"))
                    .values(
                        r.getGameId(), r.getTeamId(), r.isHomeTeam(),
                        r.getXgFor(), r.getXgAgainst(), r.getActualGoals(),
                        r.getEpaPerPlay(), r.getEpaPerPass(), r.getEpaPerRush(),
                        r.getCorsiForPct(), r.getFenwickForPct(),
                        r.getTurnoversCommitted(), r.getTurnoversForced(),
                        r.getPlaysPerGame(), r.getPossessionPct(),
                        r.getPenaltyYards(), r.getPenalties())
                    .onConflict(field("game_id"), field("team_id"))
                    .doUpdate()
                        .set(field("epa_per_play"),          r.getEpaPerPlay())
                        .set(field("xg_for"),                r.getXgFor())
                        .set(field("xg_against"),            r.getXgAgainst())
                        .set(field("turnovers_committed"),   r.getTurnoversCommitted())
                        .set(field("turnovers_forced"),      r.getTurnoversForced()))
                .toList();

        return dsl.batch(inserts).execute();
    }

    // ── Streaks ───────────────────────────────────────────────────────────────

    /**
     * Upsert the latest streak snapshot for a team.
     * The {@code stats.team_streak_snapshots} table stores one row per (team, season, date).
     */
    @Transactional
    public void upsertTeamStreaks(List<TeamStreaks> streaks) {
        log.info("Upserting {} team streak snapshots", streaks.size());

        var inserts = streaks.stream()
                .map(s -> dsl.insertInto(STREAK_TABLE,
                        field("team_id"), field("season_id"), field("as_of_date"),
                        field("su_current_streak"),   field("su_longest_win_streak"),   field("su_longest_loss_streak"),
                        field("ats_current_streak"),  field("ats_longest_cover_streak"), field("ats_longest_fail_streak"),
                        field("ou_current_streak"),   field("ou_longest_over_streak"),   field("ou_longest_under_streak"),
                        field("to_current_streak"),   field("to_won_battle_streak"),     field("to_lost_battle_streak"),
                        field("home_win_streak"),     field("away_win_streak"),
                        field("ats_after_win_streak"), field("ats_after_loss_streak"))
                    .values(
                        s.getTeamId(), s.getSeasonId(), s.getAsOfDate(),
                        s.getSuCurrentStreak(),  s.getSuLongestWinStreak(),   s.getSuLongestLossStreak(),
                        s.getAtsCurrentStreak(), s.getAtsLongestCoverStreak(), s.getAtsLongestFailStreak(),
                        s.getOuCurrentStreak(),  s.getOuLongestOverStreak(),   s.getOuLongestUnderStreak(),
                        s.getToCurrentStreak(),  s.getToWonBattleStreak(),     s.getToLostBattleStreak(),
                        s.getHomeWinStreak(),    s.getAwayWinStreak(),
                        s.getAtsAfterWinStreak(), s.getAtsAfterLossStreak())
                    .onConflict(field("team_id"), field("season_id"), field("as_of_date"))
                    .doUpdate()
                        .set(field("su_current_streak"),    s.getSuCurrentStreak())
                        .set(field("ats_current_streak"),   s.getAtsCurrentStreak())
                        .set(field("ou_current_streak"),    s.getOuCurrentStreak())
                        .set(field("to_current_streak"),    s.getToCurrentStreak())
                        .set(field("home_win_streak"),      s.getHomeWinStreak())
                        .set(field("away_win_streak"),      s.getAwayWinStreak())
                        .set(field("ats_after_win_streak"), s.getAtsAfterWinStreak())
                        .set(field("ats_after_loss_streak"),s.getAtsAfterLossStreak()))
                .toList();

        dsl.batch(inserts).execute();
    }

    // ── Query helpers (used by ingestion services to load context) ────────────

    /** Load game history for a team to build streak and rest context. */
    public List<GameHistoryRow> loadTeamGameHistory(int teamId, int seasonId) {
        return dsl.resultQuery("""
                SELECT g.game_id,
                       g.scheduled_at::DATE          AS game_date,
                       g.home_team_id = {0}          AS is_home,
                       g.home_score,
                       g.away_score,
                       co.open_home_spread,
                       co.covered_spread,
                       co.total_result,
                       ga.turnovers_committed,
                       ga.turnovers_forced,
                       g.overtime_periods > 0        AS went_overtime
                FROM core.games g
                LEFT JOIN market.consensus_odds co
                    ON co.game_id = g.game_id AND co.market_type = 'SPREAD'
                LEFT JOIN stats.game_advanced ga
                    ON ga.game_id = g.game_id AND ga.team_id = {0}
                WHERE (g.home_team_id = {0} OR g.away_team_id = {0})
                  AND g.season_id = {1}
                  AND g.status = 'FINAL'
                ORDER BY g.scheduled_at ASC
                """, teamId, seasonId)
                .fetchInto(GameHistoryRow.class);
    }

    public record GameHistoryRow(
            UUID gameId,
            java.time.LocalDate gameDate,
            boolean isHome,
            Integer homeScore,
            Integer awayScore,
            java.math.BigDecimal openHomeSpread,
            String coveredSpread,
            String totalResult,
            Integer turnoversCommitted,
            Integer turnoversForced,
            boolean wentOvertime
    ) {}

    // ── Utility ───────────────────────────────────────────────────────────────

    private OffsetDateTime toOffset(java.time.Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
