package com.sharpforge.etl.client;

import com.sharpforge.etl.client.dto.SportsDataDto;
import com.sharpforge.etl.config.SharpForgeProperties;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Reactive client for SportsData.io (or Sportradar-compatible) API.
 * Covers weather, referee assignments, play-by-play, team stats, and schedules.
 */
@Component
@Slf4j
public class SportsDataApiClient {

    private final WebClient webClient;
    private final SharpForgeProperties props;

    public SportsDataApiClient(
            @Qualifier("sportsDataApiWebClient") WebClient webClient,
            SharpForgeProperties props) {
        this.webClient = webClient;
        this.props = props;
    }

    // ── Schedule ──────────────────────────────────────────────────────────────

    /**
     * Fetch weekly schedule for a given NFL season and week.
     * Adapts to season type: 1=Preseason, 2=Regular, 3=Postseason.
     */
    @Retry(name = "sports-data-api")
    @CircuitBreaker(name = "sports-data-api")
    public Flux<SportsDataDto.ScheduleEntry> fetchNflSchedule(int season, int week, int seasonType) {
        log.debug("Fetching NFL schedule season={} week={} type={}", season, week, seasonType);
        return webClient.get()
                .uri("/nfl/scores/json/ScoresByWeek/{season}/{week}?SeasonType={type}",
                        season, week, seasonType)
                .retrieve()
                .bodyToFlux(SportsDataDto.ScheduleEntry.class);
    }

    /** Fetch the full season schedule for MLB (daily games). */
    @Retry(name = "sports-data-api")
    @CircuitBreaker(name = "sports-data-api")
    public Flux<SportsDataDto.ScheduleEntry> fetchMlbScheduleByDate(String date) {
        return webClient.get()
                .uri("/mlb/scores/json/GamesByDate/{date}", date)
                .retrieve()
                .bodyToFlux(SportsDataDto.ScheduleEntry.class);
    }

    // ── Weather ───────────────────────────────────────────────────────────────

    /**
     * Fetch weather forecast for a specific game key.
     * Returns null (empty Mono) if the game is played indoors.
     */
    @Retry(name = "sports-data-api")
    @CircuitBreaker(name = "sports-data-api")
    public Mono<SportsDataDto.WeatherForecast> fetchGameWeather(String sport, String gameKey) {
        log.debug("Fetching weather for gameKey={}", gameKey);
        return webClient.get()
                .uri("/{sport}/scores/json/GameWeather/{gameKey}", sport, gameKey)
                .retrieve()
                .bodyToMono(SportsDataDto.WeatherForecast.class)
                .doOnSuccess(w -> {
                    if (w != null && Boolean.TRUE.equals(w.getIsInDome())) {
                        log.debug("Game {} is in a dome — weather factors neutral", gameKey);
                    }
                })
                .onErrorResume(e -> {
                    log.warn("Could not fetch weather for gameKey={}: {}", gameKey, e.getMessage());
                    return Mono.empty();
                });
    }

    // ── Referee Assignments ───────────────────────────────────────────────────

    /**
     * Fetch referee crew assignment for an NFL game.
     */
    @Retry(name = "sports-data-api")
    @CircuitBreaker(name = "sports-data-api")
    public Mono<SportsDataDto.RefereeAssignment> fetchNflReferees(String gameKey) {
        log.debug("Fetching referee assignment for gameKey={}", gameKey);
        return webClient.get()
                .uri("/nfl/scores/json/Referees/{gameKey}", gameKey)
                .retrieve()
                .bodyToMono(SportsDataDto.RefereeAssignment.class)
                .onErrorResume(e -> {
                    log.warn("Could not fetch referees for gameKey={}: {}", gameKey, e.getMessage());
                    return Mono.empty();
                });
    }

    /** NBA officials for a game. */
    @Retry(name = "sports-data-api")
    @CircuitBreaker(name = "sports-data-api")
    public Mono<SportsDataDto.RefereeAssignment> fetchNbaReferees(String gameKey) {
        return webClient.get()
                .uri("/nba/scores/json/Officials/{gameKey}", gameKey)
                .retrieve()
                .bodyToMono(SportsDataDto.RefereeAssignment.class)
                .onErrorResume(e -> Mono.empty());
    }

    // ── Play-by-Play ──────────────────────────────────────────────────────────

    /**
     * Fetch NFL play-by-play for a completed game.
     * Used to calculate EPA, turnovers, drive efficiency.
     */
    @Retry(name = "sports-data-api")
    @CircuitBreaker(name = "sports-data-api")
    public Mono<SportsDataDto.PlayByPlay> fetchNflPlayByPlay(String gameKey) {
        log.debug("Fetching NFL play-by-play for gameKey={}", gameKey);
        return webClient.get()
                .uri("/nfl/stats/json/PlayByPlay/{gameKey}", gameKey)
                .retrieve()
                .bodyToMono(SportsDataDto.PlayByPlay.class);
    }

    // ── Advanced Team Stats ───────────────────────────────────────────────────

    /**
     * Fetch team box-score stats for all games in a given week.
     * Feeds into turnover differential, Pythagorean inputs, rest calculations.
     */
    @Retry(name = "sports-data-api")
    @CircuitBreaker(name = "sports-data-api")
    public Flux<SportsDataDto.TeamGameStats> fetchNflTeamGameStats(int season, int week) {
        return webClient.get()
                .uri("/nfl/stats/json/TeamGameStats/{season}/{week}", season, week)
                .retrieve()
                .bodyToFlux(SportsDataDto.TeamGameStats.class);
    }

    @Retry(name = "sports-data-api")
    @CircuitBreaker(name = "sports-data-api")
    public Flux<SportsDataDto.HockeyAdvancedStats> fetchNhlAdvancedStats(String date) {
        return webClient.get()
                .uri("/nhl/stats/json/TeamGameStatsByDate/{date}", date)
                .retrieve()
                .bodyToFlux(SportsDataDto.HockeyAdvancedStats.class);
    }
}
