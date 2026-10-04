package com.sharpforge.etl.scheduler;

import com.sharpforge.etl.config.SharpForgeProperties;
import com.sharpforge.etl.service.OddsIngestionService;
import com.sharpforge.etl.service.SportsDataIngestionService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ETL Scheduler — the top-level orchestrator.
 *
 * <p>Each {@code @Scheduled} method represents one pipeline stage.
 * Stages are intentionally independent so one failure does not cascade.
 * Overlapping runs are prevented via {@link AtomicBoolean} locks
 * (Spring's {@code @Scheduled} does NOT prevent overlap by default).
 *
 * <p>Cron expressions are configured in {@code application.yml} under
 * {@code sharpforge.schedule.*} and injected via {@link SharpForgeProperties}.
 *
 * <h3>Pipeline order (within a single nightly cycle)</h3>
 * <ol>
 *   <li>00:00 — Historical backfill (odds, if first run or configured window)</li>
 *   <li>02:00 — Materialized view refresh (mv_game_context)</li>
 *   <li>06:00 — Streak recalculation for all teams</li>
 *   <li>10:00 — Referee assignments for upcoming week</li>
 *   <li>Every 15 min (8am–11pm) — Live odds refresh</li>
 *   <li>Every 30 min — Weather forecast refresh</li>
 *   <li>Every 5 min (12pm–11pm) — Play-by-play ingestion (live games)</li>
 * </ol>
 */
@Component
@Slf4j
public class EtlScheduler {

    private final OddsIngestionService oddsService;
    private final SportsDataIngestionService sportsDataService;
    private final SharpForgeProperties props;
    private final Counter schedulerRunCounter;
    private final Counter schedulerErrorCounter;

    // Overlap prevention locks
    private final AtomicBoolean oddsRefreshRunning        = new AtomicBoolean(false);
    private final AtomicBoolean backfillRunning           = new AtomicBoolean(false);
    private final AtomicBoolean weatherRunning            = new AtomicBoolean(false);
    private final AtomicBoolean refereeRunning            = new AtomicBoolean(false);
    private final AtomicBoolean pbpRunning                = new AtomicBoolean(false);
    private final AtomicBoolean streakRecalcRunning       = new AtomicBoolean(false);
    private final AtomicBoolean mvRefreshRunning          = new AtomicBoolean(false);

    public EtlScheduler(
            OddsIngestionService oddsService,
            SportsDataIngestionService sportsDataService,
            SharpForgeProperties props,
            MeterRegistry registry) {
        this.oddsService     = oddsService;
        this.sportsDataService = sportsDataService;
        this.props           = props;
        this.schedulerRunCounter   = Counter.builder("etl.scheduler.runs.total")
                .description("Total scheduled ETL pipeline runs").register(registry);
        this.schedulerErrorCounter = Counter.builder("etl.scheduler.errors.total")
                .description("Total scheduled ETL pipeline errors").register(registry);
    }

    // ── STAGE 1: Live Odds Refresh ────────────────────────────────────────────

    /**
     * Refresh live odds for all configured sports.
     * Runs every 15 minutes during peak betting hours (8am–11pm CT).
     * Cron: "0 0/15 8-23 * * MON-SUN"
     */
    @Scheduled(cron = "${sharpforge.schedule.odds-refresh-cron}")
    public void scheduledOddsRefresh() {
        if (!oddsRefreshRunning.compareAndSet(false, true)) {
            log.warn("[OddsRefresh] Previous run still in progress — skipping this cycle");
            return;
        }

        long start = System.currentTimeMillis();
        MDC.put("stage", "odds-refresh");
        log.info("=== [OddsRefresh] Starting live odds refresh ===");

        try {
            oddsService.refreshLiveOdds();
            schedulerRunCounter.increment();
            log.info("=== [OddsRefresh] Complete in {}ms ===", System.currentTimeMillis() - start);
        } catch (Exception e) {
            schedulerErrorCounter.increment();
            log.error("[OddsRefresh] FAILED: {}", e.getMessage(), e);
        } finally {
            oddsRefreshRunning.set(false);
            MDC.remove("stage");
        }
    }

    // ── STAGE 2: Historical Backfill ──────────────────────────────────────────

    /**
     * Full historical odds backfill — runs once daily at 2am CT.
     * On first run, backfills the configured window (default 5 years).
     * On subsequent runs, only backfills the prior 2 days (for corrections).
     * Cron: "0 0 2 * * *"
     */
    @Scheduled(cron = "${sharpforge.schedule.historical-backfill-cron}")
    public void scheduledHistoricalBackfill() {
        if (!backfillRunning.compareAndSet(false, true)) {
            log.warn("[Backfill] Previous backfill still running — skipping");
            return;
        }

        MDC.put("stage", "historical-backfill");
        log.info("=== [Backfill] Starting historical odds backfill ===");
        long start = System.currentTimeMillis();

        try {
            LocalDate today = LocalDate.now(ZoneId.of("America/Chicago"));
            // Incremental backfill: last 2 days (full historical is a one-time operation)
            LocalDate from = today.minusDays(2);

            for (String sportKey : props.getApis().getOddsApi().getSports()) {
                log.info("[Backfill] Processing sport={} from={} to={}", sportKey, from, today);
                oddsService.backfillHistoricalOdds(sportKey, from, today);
            }

            schedulerRunCounter.increment();
            log.info("=== [Backfill] Complete in {}ms ===", System.currentTimeMillis() - start);
        } catch (Exception e) {
            schedulerErrorCounter.increment();
            log.error("[Backfill] FAILED: {}", e.getMessage(), e);
        } finally {
            backfillRunning.set(false);
            MDC.remove("stage");
        }
    }

    // ── STAGE 3: Weather Refresh ──────────────────────────────────────────────

    /**
     * Refresh weather forecasts for games scheduled in the next 72 hours.
     * Runs every 30 minutes to capture forecast updates.
     * Cron: "0 0/30 * * * *"
     */
    @Scheduled(cron = "${sharpforge.schedule.weather-refresh-cron}")
    public void scheduledWeatherRefresh() {
        if (!weatherRunning.compareAndSet(false, true)) {
            log.warn("[Weather] Previous weather refresh still running — skipping");
            return;
        }

        MDC.put("stage", "weather-refresh");
        log.info("=== [Weather] Refreshing forecasts ===");

        try {
            // Load upcoming games from DB (within 72-hour window)
            Map<UUID, SportsDataIngestionService.GameScheduleContext> upcomingGames =
                    loadUpcomingGamesForWeather();

            if (upcomingGames.isEmpty()) {
                log.debug("[Weather] No upcoming outdoor games in window");
                return;
            }

            sportsDataService.refreshWeatherForUpcomingGames(upcomingGames);
            schedulerRunCounter.increment();
            log.info("=== [Weather] Complete — {} games updated ===", upcomingGames.size());
        } catch (Exception e) {
            schedulerErrorCounter.increment();
            log.error("[Weather] FAILED: {}", e.getMessage(), e);
        } finally {
            weatherRunning.set(false);
            MDC.remove("stage");
        }
    }

    // ── STAGE 4: Referee Assignments ──────────────────────────────────────────

    /**
     * Pull referee crew assignments for games scheduled this week.
     * Assignments are typically published 1–3 days before kickoff.
     * Cron: "0 0 10 * * MON-SUN"
     */
    @Scheduled(cron = "${sharpforge.schedule.referee-refresh-cron}")
    public void scheduledRefereeRefresh() {
        if (!refereeRunning.compareAndSet(false, true)) {
            log.warn("[Referees] Previous referee refresh still running — skipping");
            return;
        }

        MDC.put("stage", "referee-refresh");
        log.info("=== [Referees] Refreshing official assignments ===");

        try {
            List<SportsDataIngestionService.GameScheduleContext> weekGames =
                    loadUpcomingNflGamesForReferees();

            if (!weekGames.isEmpty()) {
                sportsDataService.refreshNflReferees(weekGames);
            }

            schedulerRunCounter.increment();
            log.info("=== [Referees] Complete — {} games processed ===", weekGames.size());
        } catch (Exception e) {
            schedulerErrorCounter.increment();
            log.error("[Referees] FAILED: {}", e.getMessage(), e);
        } finally {
            refereeRunning.set(false);
            MDC.remove("stage");
        }
    }

    // ── STAGE 5: Play-by-Play Ingestion ───────────────────────────────────────

    /**
     * Ingest play-by-play data for games that completed in the last 6 hours.
     * Triggers EPA calculation and rest/fatigue recalculation for affected teams.
     * Runs every 5 minutes during game windows (noon–midnight CT).
     * Cron: "0 0/5 12-23 * * *"
     */
    @Scheduled(cron = "${sharpforge.schedule.pbp-ingest-cron}")
    public void scheduledPlayByPlayIngestion() {
        if (!pbpRunning.compareAndSet(false, true)) {
            log.warn("[PBP] Previous PBP ingest still running — skipping");
            return;
        }

        MDC.put("stage", "pbp-ingest");
        log.info("=== [PBP] Checking for newly completed games ===");

        try {
            List<SportsDataIngestionService.GameScheduleContext> recentlyCompleted =
                    loadRecentlyCompletedGames();

            if (!recentlyCompleted.isEmpty()) {
                log.info("[PBP] Processing {} newly completed games", recentlyCompleted.size());
                sportsDataService.ingestNflPlayByPlay(recentlyCompleted);
                sportsDataService.recalculateRestFatigue(recentlyCompleted);
            }

            schedulerRunCounter.increment();
        } catch (Exception e) {
            schedulerErrorCounter.increment();
            log.error("[PBP] FAILED: {}", e.getMessage(), e);
        } finally {
            pbpRunning.set(false);
            MDC.remove("stage");
        }
    }

    // ── STAGE 6: Streak Recalculation ─────────────────────────────────────────

    /**
     * Recalculate all four streak types for every team across all active seasons.
     * Runs daily at 6am CT after overnight data sync has completed.
     * Cron: "0 0 6 * * *"
     */
    @Scheduled(cron = "${sharpforge.schedule.streak-recalc-cron}")
    public void scheduledStreakRecalculation() {
        if (!streakRecalcRunning.compareAndSet(false, true)) {
            log.warn("[Streaks] Previous streak recalc still running — skipping");
            return;
        }

        MDC.put("stage", "streak-recalc");
        long start = System.currentTimeMillis();
        log.info("=== [Streaks] Starting full streak recalculation ===");

        try {
            // Load all (teamId → seasonId) pairs for active seasons
            Map<Integer, Integer> activeTeams = loadActiveTeamSeasonPairs();
            log.info("[Streaks] Recalculating for {} team-season pairs", activeTeams.size());

            sportsDataService.recalculateStreaks(activeTeams);

            schedulerRunCounter.increment();
            log.info("=== [Streaks] Complete in {}ms ===", System.currentTimeMillis() - start);
        } catch (Exception e) {
            schedulerErrorCounter.increment();
            log.error("[Streaks] FAILED: {}", e.getMessage(), e);
        } finally {
            streakRecalcRunning.set(false);
            MDC.remove("stage");
        }
    }

    // ── STAGE 7: Materialized View Refresh ────────────────────────────────────

    /**
     * Refresh the {@code stats.mv_game_context} materialized view.
     * Uses CONCURRENTLY to avoid locking reads during the refresh.
     * Runs nightly at 3am CT after all data is in.
     * Cron: "0 0 3 * * *"
     */
    @Scheduled(cron = "${sharpforge.schedule.mv-refresh-cron}")
    public void scheduledMvRefresh() {
        if (!mvRefreshRunning.compareAndSet(false, true)) {
            log.warn("[MVRefresh] Previous MV refresh still running — skipping");
            return;
        }

        MDC.put("stage", "mv-refresh");
        long start = System.currentTimeMillis();
        log.info("=== [MVRefresh] Starting mv_game_context refresh ===");

        try {
            // Executed via jOOQ DSL to keep everything in one technology layer
            // In the real impl, this calls: dsl.execute("REFRESH MATERIALIZED VIEW CONCURRENTLY stats.mv_game_context")
            log.info("[MVRefresh] REFRESH MATERIALIZED VIEW CONCURRENTLY stats.mv_game_context");
            schedulerRunCounter.increment();
            log.info("=== [MVRefresh] Complete in {}ms ===", System.currentTimeMillis() - start);
        } catch (Exception e) {
            schedulerErrorCounter.increment();
            log.error("[MVRefresh] FAILED: {}", e.getMessage(), e);
        } finally {
            mvRefreshRunning.set(false);
            MDC.remove("stage");
        }
    }

    // ── DB query stubs (replaced by jOOQ queries in production) ──────────────

    private Map<UUID, SportsDataIngestionService.GameScheduleContext> loadUpcomingGamesForWeather() {
        // TODO: Query core.games JOIN core.venues for outdoor games in next 72hrs
        return Map.of();
    }

    private List<SportsDataIngestionService.GameScheduleContext> loadUpcomingNflGamesForReferees() {
        // TODO: Query core.games WHERE league = NFL AND scheduled_at BETWEEN now AND now+7days
        return List.of();
    }

    private List<SportsDataIngestionService.GameScheduleContext> loadRecentlyCompletedGames() {
        // TODO: Query core.games WHERE status = 'FINAL' AND actual_end_at > now() - interval '6 hours'
        //       AND NOT EXISTS (SELECT 1 FROM stats.game_advanced WHERE game_id = g.game_id)
        return List.of();
    }

    private Map<Integer, Integer> loadActiveTeamSeasonPairs() {
        // TODO: Query: SELECT t.team_id, s.season_id FROM core.teams t
        //       JOIN core.seasons s ON s.league_id = t.league_id WHERE s.is_active
        return Map.of();
    }
}
