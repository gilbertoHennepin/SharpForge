package com.sharpforge.etl.client;

import com.sharpforge.etl.client.dto.OddsApiDto;
import com.sharpforge.etl.config.SharpForgeProperties;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Reactive client for The Odds API v4.
 *
 * <p>All methods are decorated with Resilience4j annotations for retry,
 * circuit-breaking, and rate limiting. Returns reactive types (Mono / Flux)
 * for non-blocking ingestion.
 */
@Component
@Slf4j
public class OddsApiClient {

    private final WebClient webClient;
    private final SharpForgeProperties props;
    private final AtomicInteger remainingQuota = new AtomicInteger(Integer.MAX_VALUE);

    public OddsApiClient(
            @Qualifier("oddsApiWebClient") WebClient webClient,
            SharpForgeProperties props) {
        this.webClient = webClient;
        this.props = props;
    }

    // ── Live / Upcoming Odds ─────────────────────────────────────────────────

    /**
     * Fetch current odds for a specific sport key across configured bookmakers.
     *
     * @param sportKey e.g. "americanfootball_nfl"
     * @return Flux of event odds objects
     */
    @Retry(name = "odds-api")
    @CircuitBreaker(name = "odds-api")
    @RateLimiter(name = "odds-api")
    public Flux<OddsApiDto.Event> fetchLiveOdds(String sportKey) {
        String marketsParam = String.join(",", props.getApis().getOddsApi().getMarkets());
        String bookmakersParam = String.join(",", props.getApis().getOddsApi().getBookmakers());

        log.debug("Fetching live odds for sport={} markets={}", sportKey, marketsParam);

        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/sports/{sport}/odds")
                        .queryParam("apiKey", props.getApis().getOddsApi().getApiKey())
                        .queryParam("regions", props.getApis().getOddsApi().getRegions())
                        .queryParam("markets", marketsParam)
                        .queryParam("bookmakers", bookmakersParam)
                        .queryParam("oddsFormat", props.getApis().getOddsApi().getOddsFormat())
                        .build(sportKey))
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, resp ->
                        resp.bodyToMono(String.class).flatMap(body -> {
                            log.error("OddsAPI 4xx error for sport={}: {}", sportKey, body);
                            return Mono.error(new OddsApiException("Client error: " + body, resp.statusCode().value()));
                        }))
                .onStatus(HttpStatusCode::is5xxServerError, resp ->
                        Mono.error(new OddsApiException("Server error", resp.statusCode().value())))
                .bodyToFlux(OddsApiDto.Event.class)
                .doOnNext(event -> log.debug("Received event id={} {} @ {}",
                        event.getId(), event.getSportKey(), event.getCommenceTime()))
                .doOnError(e -> log.error("Error fetching live odds for {}: {}", sportKey, e.getMessage()));
    }

    // ── Historical Odds ───────────────────────────────────────────────────────

    /**
     * Fetch historical odds snapshot at a specific timestamp.
     * Used for backfilling opening and closing lines.
     *
     * @param sportKey   The Odds API sport key
     * @param timestamp  UTC instant of the desired snapshot
     * @return Historical response with event list and pagination cursors
     */
    @Retry(name = "odds-api")
    @CircuitBreaker(name = "odds-api")
    @RateLimiter(name = "odds-api")
    public Mono<OddsApiDto.HistoricalResponse> fetchHistoricalOdds(String sportKey, Instant timestamp) {
        String marketsParam = String.join(",", props.getApis().getOddsApi().getMarkets());

        log.debug("Fetching historical odds for sport={} at timestamp={}", sportKey, timestamp);

        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/historical/sports/{sport}/odds")
                        .queryParam("apiKey", props.getApis().getOddsApi().getApiKey())
                        .queryParam("regions", props.getApis().getOddsApi().getRegions())
                        .queryParam("markets", marketsParam)
                        .queryParam("oddsFormat", props.getApis().getOddsApi().getOddsFormat())
                        .queryParam("date", timestamp.toString())
                        .build(sportKey))
                .retrieve()
                .toEntity(OddsApiDto.HistoricalResponse.class)
                .map(entity -> {
                    // Parse remaining quota from response headers
                    String remaining = entity.getHeaders().getFirst("x-requests-remaining");
                    if (remaining != null) {
                        remainingQuota.set(Integer.parseInt(remaining));
                        log.debug("OddsAPI quota remaining: {}", remaining);
                    }
                    return entity.getBody();
                })
                .doOnError(WebClientResponseException.TooManyRequests.class, e ->
                        log.warn("OddsAPI rate limit hit, Resilience4j will retry"));
    }

    /**
     * Fetch historical odds for a specific game event ID and timestamp window.
     * Used to capture the full line-movement timeline for a single game.
     */
    @Retry(name = "odds-api")
    @CircuitBreaker(name = "odds-api")
    @RateLimiter(name = "odds-api")
    public Mono<OddsApiDto.HistoricalResponse> fetchHistoricalEventOdds(
            String sportKey, String eventId, Instant timestamp) {

        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/historical/sports/{sport}/events/{eventId}/odds")
                        .queryParam("apiKey", props.getApis().getOddsApi().getApiKey())
                        .queryParam("regions", props.getApis().getOddsApi().getRegions())
                        .queryParam("markets", "spreads,h2h,totals")
                        .queryParam("oddsFormat", props.getApis().getOddsApi().getOddsFormat())
                        .queryParam("date", timestamp.toString())
                        .build(sportKey, eventId))
                .retrieve()
                .bodyToMono(OddsApiDto.HistoricalResponse.class);
    }

    /** Available sports from The Odds API. */
    @Retry(name = "odds-api")
    public Flux<String> fetchAvailableSports() {
        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/sports")
                        .queryParam("apiKey", props.getApis().getOddsApi().getApiKey())
                        .queryParam("all", true)
                        .build())
                .retrieve()
                .bodyToFlux(String.class);
    }

    public int getRemainingQuota() {
        return remainingQuota.get();
    }

    // ── Exception type ────────────────────────────────────────────────────────

    public static class OddsApiException extends RuntimeException {
        private final int statusCode;
        public OddsApiException(String message, int statusCode) {
            super(message);
            this.statusCode = statusCode;
        }
        public int getStatusCode() { return statusCode; }
    }
}
