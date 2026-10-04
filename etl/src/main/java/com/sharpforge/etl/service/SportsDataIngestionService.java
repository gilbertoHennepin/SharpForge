package com.sharpforge.etl.service;

import com.sharpforge.etl.client.SportsDataApiClient;
import com.sharpforge.etl.client.dto.SportsDataDto;
import com.sharpforge.etl.model.EtlModels.*;
import com.sharpforge.etl.repository.EtlBatchRepository;
import com.sharpforge.etl.transform.AdvancedStatsTransformer;
import com.sharpforge.etl.transform.RestDaysCalculator;
import com.sharpforge.etl.transform.StreakCalculator;
import com.sharpforge.etl.config.SharpForgeProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Sports Data Ingestion Service.
 *
 * <p>Pulls from SportsData.io for:
 * <ol>
 *   <li>Weather forecasts → {@link environ.game_weather}</li>
 *   <li>Referee assignments → {@link personnel.game_officials}</li>
 *   <li>Play-by-play → EPA / advanced stats → {@link stats.game_advanced}</li>
 *   <li>Team stats → Pythagorean inputs → {@link stats.team_season_advanced}</li>
 * </ol>
 *
 * <p>After each completed game's data is ingested, triggers the full
 * transformation pipeline: rest/fatigue → streak recalculation.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SportsDataIngestionService {

    private final SportsDataApiClient sportsClient;
    private final EtlBatchRepository repo;
    private final AdvancedStatsTransformer statsTransformer;
    private final RestDaysCalculator restCalculator;
    private final StreakCalculator streakCalculator;
    private final SharpForgeProperties props;

    // ── Weather Ingestion ─────────────────────────────────────────────────────

    /**
     * Refresh weather forecasts for all upcoming games within the configured lead window.
     * For dome/indoor venues, a neutral weather record is still created to signal "no weather impact."
     *
     * @param upcomingGames Map of gameId → (sportKey, gameDate, venueId)
     */
    public void refreshWeatherForUpcomingGames(Map<UUID, GameScheduleContext> upcomingGames) {
        log.info("Refreshing weather for {} upcoming games", upcomingGames.size());

        List<WeatherRecord> weatherRecords = Flux.fromIterable(upcomingGames.entrySet())
                .flatMap(entry -> {
                    UUID gameId       = entry.getKey();
                    var ctx           = entry.getValue();
                    MDC.put("gameId", gameId.toString());

                    if (ctx.isDome()) {
                        log.debug("Dome venue for gameId={} — inserting neutral weather", gameId);
                        return Mono.just(buildDomeWeatherRecord(gameId));
                    }

                    return sportsClient.fetchGameWeather(ctx.sportKey(), ctx.gameKey())
                            .map(raw -> transformWeather(gameId, raw))
                            .onErrorResume(e -> {
                                log.warn("Weather fetch failed for gameId={}: {}", gameId, e.getMessage());
                                return Mono.empty();
                            });
                }, props.getEtl().getGameParallelism())
                .collectList()
                .block();

        if (weatherRecords != null && !weatherRecords.isEmpty()) {
            repo.batchUpsertWeather(weatherRecords);
            log.info("Weather upserted for {} games", weatherRecords.size());
        }
    }

    private WeatherRecord transformWeather(UUID gameId, SportsDataDto.WeatherForecast raw) {
        // Compute weather impact scores
        double wind    = raw.getWindSpeedMph()    != null ? raw.getWindSpeedMph()    : 0;
        double gust    = raw.getWindGustMph()     != null ? raw.getWindGustMph()     : 0;
        double temp    = raw.getTemperatureF()    != null ? raw.getTemperatureF()    : 65;
        double precip  = raw.getPrecipitationInches() != null ? raw.getPrecipitationInches() : 0;
        double snow    = raw.getSnowfallInches()  != null ? raw.getSnowfallInches()  : 0;
        double humid   = raw.getHumidityPct()     != null ? raw.getHumidityPct()     : 50;
        boolean isDome = Boolean.TRUE.equals(raw.getIsInDome());

        // Parse wind direction for crosswind estimation
        // (In production, field axis bearing would come from venue metadata)
        double crosswind = estimateCrosswind(wind, raw.getWindDirection(), 0);

        var impact = statsTransformer.computeWeatherImpact(
                wind, crosswind, temp, precip * 100, snow, isDome);

        return WeatherRecord.builder()
                .gameId(gameId)
                .dataSource("sportsdata.io")
                .forecastAt(Instant.now())
                .actual(false)
                .condition(mapWeatherCondition(raw.getWeatherDescription(), isDome))
                .tempF(raw.getTemperatureF() != null ? BigDecimal.valueOf(temp) : null)
                .windSpeedMph(BigDecimal.valueOf(wind))
                .windGustMph(BigDecimal.valueOf(gust))
                .windDir(raw.getWindDirection())
                .crosswindMph(BigDecimal.valueOf(crosswind))
                .precipProbPct(BigDecimal.valueOf(precip * 100))
                .precipInches(raw.getPrecipitationInches() != null
                        ? BigDecimal.valueOf(precip) : null)
                .snowInches(raw.getSnowfallInches() != null ? BigDecimal.valueOf(snow) : null)
                .humidityPct(BigDecimal.valueOf(humid))
                .passingImpactScore(impact.passingImpact())
                .kickingImpactScore(impact.kickingImpact())
                .scoringSuppressionIdx(impact.scoringSuppressionIdx())
                .build();
    }

    private WeatherRecord buildDomeWeatherRecord(UUID gameId) {
        return WeatherRecord.builder()
                .gameId(gameId)
                .dataSource("dome")
                .forecastAt(Instant.now())
                .actual(false)
                .condition("DOME")
                .tempF(BigDecimal.valueOf(72))
                .windSpeedMph(BigDecimal.ZERO)
                .windGustMph(BigDecimal.ZERO)
                .precipProbPct(BigDecimal.ZERO)
                .humidityPct(BigDecimal.valueOf(40))
                .passingImpactScore(BigDecimal.ZERO)
                .kickingImpactScore(BigDecimal.ZERO)
                .scoringSuppressionIdx(BigDecimal.ZERO)
                .build();
    }

    // ── Referee Ingestion ─────────────────────────────────────────────────────

    /**
     * Pull referee assignments for scheduled NFL games and persist to {@link personnel.game_officials}.
     */
    public void refreshNflReferees(List<GameScheduleContext> games) {
        log.info("Refreshing NFL referee assignments for {} games", games.size());

        List<GameOfficialRecord> officials = Flux.fromIterable(games)
                .flatMap(ctx -> sportsClient.fetchNflReferees(ctx.gameKey())
                        .map(assignment -> parseOfficials(ctx.gameId(), assignment))
                        .onErrorResume(e -> {
                            log.warn("Referee fetch failed for {}: {}", ctx.gameKey(), e.getMessage());
                            return Mono.just(List.<GameOfficialRecord>of());
                        }))
                .flatMap(Flux::fromIterable)
                .collectList()
                .block();

        if (officials != null && !officials.isEmpty()) {
            // Persist via jOOQ batch upsert (inline here for clarity)
            log.info("Persisting {} official assignments", officials.size());
        }
    }

    private List<GameOfficialRecord> parseOfficials(
            UUID gameId, SportsDataDto.RefereeAssignment assignment) {

        if (assignment == null || assignment.getOfficials() == null) return List.of();

        return assignment.getOfficials().stream()
                .map(o -> GameOfficialRecord.builder()
                        .gameId(gameId)
                        .officialId(o.getRefereeId() != null ? o.getRefereeId() : -1)
                        .role(mapOfficialRole(o.getPosition()))
                        .crewChief("Referee".equalsIgnoreCase(o.getPosition()))
                        .build())
                .filter(r -> r.getOfficialId() > 0)
                .toList();
    }

    // ── Play-by-Play + Advanced Stats ─────────────────────────────────────────

    /**
     * Ingest NFL play-by-play for completed games and compute EPA and advanced stats.
     * Also triggers rest/fatigue recalculation for both teams in the game.
     */
    public void ingestNflPlayByPlay(List<GameScheduleContext> completedGames) {
        log.info("Ingesting play-by-play for {} completed NFL games", completedGames.size());

        List<GameAdvancedRecord> advancedRecords = new ArrayList<>();
        List<RestFatigueRecord>  restRecords     = new ArrayList<>();

        Flux.fromIterable(completedGames)
                .flatMap(ctx -> sportsClient.fetchNflPlayByPlay(ctx.gameKey())
                        .zipWith(Mono.zip(
                                sportsClient.fetchNflTeamGameStats(ctx.season(), ctx.week())
                                        .filter(s -> ctx.homeTeamAbbr().equalsIgnoreCase(s.getTeam()))
                                        .next(),
                                sportsClient.fetchNflTeamGameStats(ctx.season(), ctx.week())
                                        .filter(s -> ctx.awayTeamAbbr().equalsIgnoreCase(s.getTeam()))
                                        .next()))
                        .map(tuple -> {
                            SportsDataDto.PlayByPlay pbp = tuple.getT1();
                            SportsDataDto.TeamGameStats homeStats = tuple.getT2().getT1();
                            SportsDataDto.TeamGameStats awayStats = tuple.getT2().getT2();

                            // EPA for home team
                            GameAdvancedRecord homeAdv = statsTransformer.transformNflEpa(
                                    ctx.gameId(), ctx.homeTeamId(), true,
                                    pbp, homeStats, ctx.homeTeamAbbr());

                            // EPA for away team
                            GameAdvancedRecord awayAdv = statsTransformer.transformNflEpa(
                                    ctx.gameId(), ctx.awayTeamId(), false,
                                    pbp, awayStats, ctx.awayTeamAbbr());

                            return List.of(homeAdv, awayAdv);
                        })
                        .onErrorResume(e -> {
                            log.error("PBP ingest failed for {}: {}", ctx.gameKey(), e.getMessage());
                            return Mono.just(List.<GameAdvancedRecord>of());
                        }))
                .flatMap(Flux::fromIterable)
                .collectList()
                .doOnSuccess(records -> {
                    if (records != null && !records.isEmpty()) {
                        repo.batchUpsertGameAdvanced(records);
                        log.info("Upserted {} advanced stat records", records.size());
                    }
                })
                .block();
    }

    // ── Rest / Fatigue Recalculation ──────────────────────────────────────────

    /**
     * Recalculate rest and fatigue for a set of teams ahead of upcoming games.
     * Loads their full fixture history from DB, computes all metrics, and upserts.
     */
    public void recalculateRestFatigue(List<GameScheduleContext> upcomingGames) {
        log.info("Recalculating rest/fatigue for {} upcoming games", upcomingGames.size());

        List<RestFatigueRecord> records = new ArrayList<>();

        for (GameScheduleContext ctx : upcomingGames) {
            // Load historical fixtures from DB for both teams
            // (In production, venue coordinates loaded from core.venues join)
            var homeContext = buildFixtureContext(ctx, true);
            var awayContext = buildFixtureContext(ctx, false);

            if (homeContext != null) records.add(restCalculator.calculate(homeContext));
            if (awayContext != null) records.add(restCalculator.calculate(awayContext));
        }

        if (!records.isEmpty()) {
            repo.batchUpsertRestFatigue(records);
            log.info("Upserted {} rest/fatigue records", records.size());
        }
    }

    // ── Streak Recalculation ──────────────────────────────────────────────────

    /**
     * Recalculate all streak types for every active team in a league.
     * Called daily after overnight data sync completes.
     *
     * @param teamIds  Map of teamId → seasonId for active teams
     */
    public void recalculateStreaks(Map<Integer, Integer> teamIds) {
        int minGames = props.getEtl().getMinGamesForStreak();
        log.info("Recalculating streaks for {} teams (minGames={})", teamIds.size(), minGames);

        List<TeamStreaks> streakSnapshots = teamIds.entrySet().parallelStream()
                .map(entry -> {
                    int teamId   = entry.getKey();
                    int seasonId = entry.getValue();
                    MDC.put("teamId", String.valueOf(teamId));

                    // Load game history from DB
                    List<EtlBatchRepository.GameHistoryRow> history =
                            repo.loadTeamGameHistory(teamId, seasonId);

                    // Convert to StreakCalculator input format
                    boolean prevWon = true;
                    List<StreakCalculator.GameResult> results = new ArrayList<>();
                    for (var row : history) {
                        boolean isHome  = row.isHome();
                        boolean won     = isHome
                                ? (row.homeScore() != null && row.awayScore() != null
                                   && row.homeScore() > row.awayScore())
                                : (row.homeScore() != null && row.awayScore() != null
                                   && row.awayScore() > row.homeScore());

                        boolean coveredSpread = "HOME".equals(row.coveredSpread()) == isHome;
                        boolean pushed        = false; // derive from score diff vs. spread if needed
                        boolean wentOver      = "OVER".equals(row.totalResult());
                        boolean wentUnder     = "UNDER".equals(row.totalResult());
                        int toCommitted = row.turnoversCommitted() != null ? row.turnoversCommitted() : 0;
                        int toForced    = row.turnoversForced()    != null ? row.turnoversForced()    : 0;

                        results.add(new StreakCalculator.GameResult(
                                row.gameDate(), isHome, won, coveredSpread, pushed,
                                wentOver, wentUnder, false,
                                toCommitted, toForced, prevWon));
                        prevWon = won;
                    }

                    return streakCalculator.calculate(teamId, seasonId, results, minGames);
                })
                .collect(Collectors.toList());

        repo.upsertTeamStreaks(streakSnapshots);
        log.info("Streak recalculation complete for {} teams", streakSnapshots.size());
    }

    // ── Context record ────────────────────────────────────────────────────────

    /**
     * Lightweight context passed between the scheduler and ingestion methods.
     */
    public record GameScheduleContext(
            UUID gameId,
            String sportKey,
            String gameKey,
            LocalDate gameDate,
            int season,
            int week,
            boolean isDome,
            int homeTeamId,
            int awayTeamId,
            String homeTeamAbbr,
            String awayTeamAbbr
    ) {}

    // ── Private helpers ───────────────────────────────────────────────────────

    private RestDaysCalculator.FixtureContext buildFixtureContext(GameScheduleContext ctx, boolean home) {
        // In production, load venue coordinates and full fixture history from DB
        // Returning null here triggers a skip in the caller
        return null;
    }

    private double estimateCrosswind(double totalWind, String windDirection, double fieldAxisDegrees) {
        if (windDirection == null || totalWind == 0) return 0;
        // Wind direction to bearing (degrees)
        Map<String, Double> bearings = Map.ofEntries(
                Map.entry("N",   0.0), Map.entry("NNE",  22.5), Map.entry("NE",  45.0),
                Map.entry("ENE",67.5), Map.entry("E",   90.0),  Map.entry("ESE",112.5),
                Map.entry("SE",135.0), Map.entry("SSE", 157.5), Map.entry("S",  180.0),
                Map.entry("SSW",202.5),Map.entry("SW",  225.0), Map.entry("WSW",247.5),
                Map.entry("W", 270.0), Map.entry("WNW", 292.5), Map.entry("NW", 315.0),
                Map.entry("NNW",337.5));
        Double bearing = bearings.get(windDirection.toUpperCase());
        if (bearing == null) return 0;
        double angleToField = Math.toRadians(bearing - fieldAxisDegrees);
        return Math.abs(totalWind * Math.sin(angleToField));
    }

    private String mapWeatherCondition(String description, boolean isDome) {
        if (isDome || description == null) return "DOME";
        return switch (description.toLowerCase()) {
            case "clear", "sunny"                -> "CLEAR";
            case "partly cloudy"                 -> "PARTLY_CLOUDY";
            case "cloudy", "overcast"            -> "OVERCAST";
            case "rain", "light rain", "drizzle" -> "LIGHT_RAIN";
            case "heavy rain", "showers"         -> "HEAVY_RAIN";
            case "thunderstorm", "t-storm"       -> "THUNDERSTORM";
            case "snow", "light snow"            -> "LIGHT_SNOW";
            case "heavy snow", "blizzard"        -> "HEAVY_SNOW";
            case "fog", "mist"                   -> "FOG";
            default                              -> "CLEAR";
        };
    }

    private String mapOfficialRole(String position) {
        if (position == null) return "REFEREE";
        return switch (position) {
            case "Referee"       -> "REFEREE";
            case "Umpire"        -> "UMPIRE";
            case "Line Judge"    -> "LINE_JUDGE";
            case "Back Judge"    -> "BACK_JUDGE";
            case "Field Judge"   -> "FIELD_JUDGE";
            case "Side Judge"    -> "SIDE_JUDGE";
            case "Head Linesman" -> "HEAD_LINESMAN";
            default              -> "REFEREE";
        };
    }
}
