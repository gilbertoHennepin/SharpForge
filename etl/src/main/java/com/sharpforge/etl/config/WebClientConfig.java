package com.sharpforge.etl.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import lombok.extern.slf4j.Slf4j;

/**
 * WebClient beans for each external API, each with:
 * - Dedicated Netty connection pool (avoids resource contention)
 * - Configured connect/read timeouts
 * - Request/response logging filter
 * - API key header injection
 */
@Configuration
@Slf4j
public class WebClientConfig {

    private final SharpForgeProperties props;
    /** Rolling counter for quota monitoring on The Odds API. */
    private final AtomicInteger oddsApiRequestCount = new AtomicInteger(0);

    public WebClientConfig(SharpForgeProperties props) {
        this.props = props;
    }

    // ── The Odds API ─────────────────────────────────────────────────────────

    @Bean("oddsApiWebClient")
    public WebClient oddsApiWebClient() {
        var cfg = props.getApis().getOddsApi();
        HttpClient httpClient = buildHttpClient(
                "odds-api-pool", cfg.getConnectTimeoutMs(), cfg.getReadTimeoutMs());

        return WebClient.builder()
                .baseUrl(cfg.getBaseUrl())
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .filter(quotaTrackingFilter())
                .filter(loggingFilter("OddsAPI"))
                .build();
    }

    // ── SportsData API ────────────────────────────────────────────────────────

    @Bean("sportsDataApiWebClient")
    public WebClient sportsDataApiWebClient() {
        var cfg = props.getApis().getSportsDataApi();
        HttpClient httpClient = buildHttpClient(
                "sportsdata-pool", cfg.getConnectTimeoutMs(), cfg.getReadTimeoutMs());

        return WebClient.builder()
                .baseUrl(cfg.getBaseUrl())
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader("Ocp-Apim-Subscription-Key", cfg.getApiKey())
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .filter(loggingFilter("SportsDataAPI"))
                .build();
    }

    // ── OpenWeatherMap API ────────────────────────────────────────────────────

    @Bean("weatherApiWebClient")
    public WebClient weatherApiWebClient() {
        var cfg = props.getApis().getWeatherApi();
        HttpClient httpClient = buildHttpClient(
                "weather-pool", cfg.getConnectTimeoutMs(), cfg.getReadTimeoutMs());

        return WebClient.builder()
                .baseUrl(cfg.getBaseUrl())
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .filter(loggingFilter("WeatherAPI"))
                .build();
    }

    // ── Shared helpers ────────────────────────────────────────────────────────

    private HttpClient buildHttpClient(String poolName, int connectMs, int readMs) {
        ConnectionProvider provider = ConnectionProvider.builder(poolName)
                .maxConnections(50)
                .maxIdleTime(Duration.ofSeconds(20))
                .maxLifeTime(Duration.ofSeconds(60))
                .pendingAcquireTimeout(Duration.ofSeconds(10))
                .evictInBackground(Duration.ofSeconds(120))
                .build();

        return HttpClient.create(provider)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectMs)
                .responseTimeout(Duration.ofMillis(readMs))
                .doOnConnected(conn -> conn
                        .addHandlerLast(new ReadTimeoutHandler(readMs, TimeUnit.MILLISECONDS))
                        .addHandlerLast(new WriteTimeoutHandler(connectMs, TimeUnit.MILLISECONDS)));
    }

    /** Logs request method + URI and response status at DEBUG level. */
    private ExchangeFilterFunction loggingFilter(String label) {
        return ExchangeFilterFunction.ofRequestProcessor(req -> {
            log.debug("[{}] → {} {}", label, req.method(), req.url());
            return Mono.just(req);
        });
    }

    /** Tracks rolling request count and warns when approaching quota. */
    private ExchangeFilterFunction quotaTrackingFilter() {
        int warnThreshold = props.getApis().getOddsApi().getQuotaWarnThreshold();
        return ExchangeFilterFunction.ofRequestProcessor(req -> {
            int count = oddsApiRequestCount.incrementAndGet();
            if (count >= warnThreshold) {
                log.warn("[OddsAPI] Quota warning: {} requests used this month (threshold: {})",
                        count, warnThreshold);
            }
            return Mono.just(req);
        });
    }
}
