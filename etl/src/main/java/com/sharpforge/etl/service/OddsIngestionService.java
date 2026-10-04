package com.sharpforge.etl.service;

import com.sharpforge.etl.client.OddsApiClient;
import com.sharpforge.etl.client.dto.OddsApiDto;
import com.sharpforge.etl.config.SharpForgeProperties;
import com.sharpforge.etl.model.EtlModels.*;
import com.sharpforge.etl.repository.EtlBatchRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Odds Ingestion Service.
 *
 * <p>Orchestrates the full odds data pipeline for a single sport:
 * <ol>
 *   <li>Fetch live or historical events from The Odds API</li>
 *   <li>For each event, fetch per-book line snapshots</li>
 *   <li>Compute consensus lines (average across sharp books)</li>
 *   <li>Detect line movements and steam/reverse signals</li>
 *   <li>Batch-upsert everything via {@link EtlBatchRepository}</li>
 * </ol>
 */
@Service
@Slf4j
public class OddsIngestionService {

    private final OddsApiClient oddsClient;
    private final EtlBatchRepository repo;
    private final SharpForgeProperties props;
    private final Counter oddsProcessedCounter;
    private final Counter lineMovementCounter;
    private final Timer oddsIngestionTimer;

    // Book IDs for the configured bookmakers (loaded from DB on startup)
    private final Map<String, Integer> bookIdsByKey = new HashMap<>();

    // Sharp books used for consensus line calculation
    private static final Set<String> SHARP_BOOKS = Set.of(
            "pinnacle", "betonlineag", "bookmaker", "circasports");

    public OddsIngestionService(
            OddsApiClient oddsClient,
            EtlBatchRepository repo,
            SharpForgeProperties props,
            MeterRegistry meterRegistry) {
        this.oddsClient = oddsClient;
        this.repo = repo;
        this.props = props;
        this.oddsProcessedCounter = Counter.builder("etl.odds.processed")
                .description("Total odds records ingested").register(meterRegistry);
        this.lineMovementCounter = Counter.builder("etl.line.movements.detected")
                .description("Line movement events detected").register(meterRegistry);
        this.oddsIngestionTimer = Timer.builder("etl.odds.ingestion.duration")
                .description("Time to complete odds ingestion for one sport").register(meterRegistry);
    }

    // ── Live Odds Refresh ─────────────────────────────────────────────────────

    /**
     * Refresh current odds for all configured sports.
     * Called by {@link com.sharpforge.etl.scheduler.EtlScheduler} on the 15-min cron.
     */
    public void refreshLiveOdds() {
        List<String> sports = props.getApis().getOddsApi().getSports();
        log.info("Starting live odds refresh for {} sports", sports.size());

        Flux.fromIterable(sports)
                .flatMap(sport -> refreshLiveOddsForSport(sport)
                        .doOnError(e -> log.error("Error refreshing odds for {}: {}", sport, e.getMessage()))
                        .onErrorResume(e -> Mono.empty()),
                        props.getEtl().getGameParallelism())
                .blockLast();

        log.info("Live odds refresh complete. Remaining API quota: {}",
                oddsClient.getRemainingQuota());
    }

    private Mono<Void> refreshLiveOddsForSport(String sportKey) {
        return oddsIngestionTimer.record(() ->
                oddsClient.fetchLiveOdds(sportKey)
                        .collectList()
                        .flatMap(events -> {
                            MDC.put("sport", sportKey);
                            log.debug("Processing {} events for sport={}", events.size(), sportKey);
                            return Mono.fromCallable(() -> processEvents(events, sportKey, false))
                                    .subscribeOn(Schedulers.boundedElastic());
                        })
                        .doOnError(e -> log.error("Failed sport={}: {}", sportKey, e.getMessage()))
                        .then());
    }

    // ── Historical Backfill ───────────────────────────────────────────────────

    /**
     * Backfill historical odds for a given sport over a date range.
     * Walks backwards through time fetching one snapshot per hour.
     * Designed to be idempotent — safe to re-run.
     *
     * @param sportKey  Odds API sport key
     * @param from      Start date (inclusive)
     * @param to        End date (inclusive)
     */
    public void backfillHistoricalOdds(String sportKey, LocalDate from, LocalDate to) {
        long totalHours = ChronoUnit.HOURS.between(
                from.atStartOfDay().toInstant(ZoneOffset.UTC),
                to.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC));

        log.info("Starting historical backfill for sport={} from={} to={} ({} hours)",
                sportKey, from, to, totalHours);

        // Walk hour by hour (The Odds API historical endpoint is snapshot-based)
        Instant current = from.atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant end     = to.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);

        int processed = 0;
        while (current.isBefore(end)) {
            Instant snapshot = current;
            try {
                OddsApiDto.HistoricalResponse response =
                        oddsClient.fetchHistoricalOdds(sportKey, snapshot).block();

                if (response != null && response.getData() != null) {
                    processEvents(response.getData(), sportKey, true);
                    processed += response.getData().size();
                }

                // Advance to next snapshot (The Odds API paginates via next_timestamp)
                if (response != null && response.getNextTimestamp() != null) {
                    current = Instant.parse(response.getNextTimestamp());
                } else {
                    current = current.plus(1, ChronoUnit.HOURS);
                }

                // Rate limit protection between historical calls
                Thread.sleep(200);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Historical backfill interrupted at {}", snapshot);
                break;
            } catch (Exception e) {
                log.error("Error during historical backfill at {}: {}", snapshot, e.getMessage());
                current = current.plus(1, ChronoUnit.HOURS); // skip broken snapshot
            }
        }

        log.info("Historical backfill complete for sport={}: {} events processed", sportKey, processed);
    }

    // ── Core Processing Logic ─────────────────────────────────────────────────

    private Void processEvents(List<OddsApiDto.Event> events, String sportKey, boolean historical) {
        if (events == null || events.isEmpty()) return null;

        List<BookOddsRecord> bookOddsRecords    = new ArrayList<>();
        List<ConsensusOddsRecord> consensusRecs = new ArrayList<>();
        List<LineMovementRecord> movementRecs   = new ArrayList<>();

        for (OddsApiDto.Event event : events) {
            UUID gameId = resolveGameId(event, sportKey);
            if (gameId == null) {
                log.debug("Could not resolve gameId for event {}", event.getId());
                continue;
            }

            Instant recordedAt = Instant.now();

            // ── Process per-book lines ──────────────────────────────────────
            Map<String, BookOddsRecord> spreadByBook  = new HashMap<>();
            Map<String, BookOddsRecord> totalByBook   = new HashMap<>();
            Map<String, BookOddsRecord> mlByBook      = new HashMap<>();

            for (OddsApiDto.Bookmaker bm : event.getBookmakers()) {
                int bookId = bookIdsByKey.getOrDefault(bm.getKey(), -1);
                if (bookId < 0) continue;

                for (OddsApiDto.Market market : bm.getMarkets()) {
                    BookOddsRecord record = parseBookOdds(gameId, bookId, market, recordedAt, historical);
                    if (record != null) {
                        bookOddsRecords.add(record);
                        // Store for consensus calculation
                        switch (market.getKey()) {
                            case "spreads" -> spreadByBook.put(bm.getKey(), record);
                            case "totals"  -> totalByBook.put(bm.getKey(), record);
                            case "h2h"     -> mlByBook.put(bm.getKey(), record);
                        }
                    }
                }
            }

            // ── Build consensus (average across all, weighted toward sharp books) ──
            ConsensusOddsRecord consensus = buildConsensusRecord(
                    gameId, spreadByBook, totalByBook, mlByBook, recordedAt, event);
            if (consensus != null) consensusRecs.add(consensus);

            // ── Detect line movements ────────────────────────────────────────
            List<LineMovementRecord> moves = detectLineMovements(gameId, spreadByBook, totalByBook, recordedAt, event);
            movementRecs.addAll(moves);
        }

        // ── Batch persist ─────────────────────────────────────────────────────
        if (!bookOddsRecords.isEmpty()) {
            repo.batchInsertBookOdds(bookOddsRecords);
            oddsProcessedCounter.increment(bookOddsRecords.size());
        }
        if (!consensusRecs.isEmpty()) {
            repo.batchUpsertConsensusOdds(consensusRecs);
        }
        if (!movementRecs.isEmpty()) {
            repo.batchInsertLineMovements(movementRecs);
            lineMovementCounter.increment(movementRecs.size());
        }

        return null;
    }

    // ── Parsing helpers ───────────────────────────────────────────────────────

    private BookOddsRecord parseBookOdds(
            UUID gameId, int bookId, OddsApiDto.Market market,
            Instant recordedAt, boolean isOpening) {

        List<OddsApiDto.Outcome> outcomes = market.getOutcomes();
        if (outcomes == null || outcomes.isEmpty()) return null;

        var builder = BookOddsRecord.builder()
                .gameId(gameId)
                .bookId(bookId)
                .marketType(mapMarketType(market.getKey()))
                .recordedAt(recordedAt)
                .isOpening(isOpening)
                .isClosing(false);

        return switch (market.getKey()) {
            case "spreads" -> {
                for (OddsApiDto.Outcome o : outcomes) {
                    BigDecimal spread = o.getPoint() != null ? BigDecimal.valueOf(o.getPoint()) : null;
                    BigDecimal juice  = o.getPrice() != null ? impliedProb(o.getPrice().intValue()) : null;
                    // Home spread is negative for the favorite
                    if (isHomeTeam(o, outcomes)) {
                        builder.homeSpread(spread).homeJuice(juice);
                    } else {
                        builder.awayJuice(juice);
                    }
                }
                yield builder.build();
            }
            case "totals" -> {
                for (OddsApiDto.Outcome o : outcomes) {
                    BigDecimal total = o.getPoint() != null ? BigDecimal.valueOf(o.getPoint()) : null;
                    BigDecimal juice = o.getPrice() != null ? impliedProb(o.getPrice().intValue()) : null;
                    if ("Over".equalsIgnoreCase(o.getName())) {
                        builder.total(total).overJuice(juice);
                    } else {
                        builder.underJuice(juice);
                    }
                }
                yield builder.build();
            }
            case "h2h" -> {
                for (OddsApiDto.Outcome o : outcomes) {
                    if (isHomeTeam(o, outcomes)) {
                        builder.homeMl(o.getPrice() != null ? o.getPrice().intValue() : null);
                    } else {
                        builder.awayMl(o.getPrice() != null ? o.getPrice().intValue() : null);
                    }
                }
                yield builder.build();
            }
            default -> null;
        };
    }

    private ConsensusOddsRecord buildConsensusRecord(
            UUID gameId,
            Map<String, BookOddsRecord> spreadByBook,
            Map<String, BookOddsRecord> totalByBook,
            Map<String, BookOddsRecord> mlByBook,
            Instant recordedAt,
            OddsApiDto.Event event) {

        // Weight sharp books 2x when computing consensus
        OptionalDouble avgSpread = spreadByBook.entrySet().stream()
                .filter(e -> e.getValue().getHomeSpread() != null)
                .flatMapToDouble(e -> {
                    double spread = e.getValue().getHomeSpread().doubleValue();
                    return SHARP_BOOKS.contains(e.getKey())
                            ? java.util.stream.DoubleStream.of(spread, spread)  // 2x weight
                            : java.util.stream.DoubleStream.of(spread);
                })
                .average();

        OptionalDouble avgTotal = totalByBook.entrySet().stream()
                .filter(e -> e.getValue().getTotal() != null)
                .flatMapToDouble(e -> {
                    double total = e.getValue().getTotal().doubleValue();
                    return SHARP_BOOKS.contains(e.getKey())
                            ? java.util.stream.DoubleStream.of(total, total)
                            : java.util.stream.DoubleStream.of(total);
                })
                .average();

        if (avgSpread.isEmpty() && avgTotal.isEmpty()) return null;

        OptionalDouble avgHomeMl = mlByBook.values().stream()
                .filter(r -> r.getHomeMl() != null)
                .mapToInt(BookOddsRecord::getHomeMl)
                .average();
        OptionalDouble avgAwayMl = mlByBook.values().stream()
                .filter(r -> r.getAwayMl() != null)
                .mapToInt(BookOddsRecord::getAwayMl)
                .average();

        return ConsensusOddsRecord.builder()
                .gameId(gameId)
                .marketType("SPREAD")
                .openHomeSpread(avgSpread.isPresent() ? bd(avgSpread.getAsDouble(), 1) : null)
                .openTotal(avgTotal.isPresent()       ? bd(avgTotal.getAsDouble(),   1) : null)
                .openHomeMl(avgHomeMl.isPresent()     ? (int) avgHomeMl.getAsDouble()   : null)
                .openAwayMl(avgAwayMl.isPresent()     ? (int) avgAwayMl.getAsDouble()   : null)
                .openAt(recordedAt)
                .build();
    }

    /** Detects line movements by comparing current snapshot vs. prior values loaded from DB. */
    private List<LineMovementRecord> detectLineMovements(
            UUID gameId,
            Map<String, BookOddsRecord> spreadByBook,
            Map<String, BookOddsRecord> totalByBook,
            Instant now,
            OddsApiDto.Event event) {

        // In a production implementation this would load the previous snapshot from DB,
        // compare, and emit movements. Stub returns empty — see StreakCalculationService
        // for how prior data is loaded via repo.loadTeamGameHistory().
        return List.of();
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    /** Resolve internal UUID game_id from an Odds API event. Queries the DB. */
    private UUID resolveGameId(OddsApiDto.Event event, String sportKey) {
        // In production: lookup by external_id or (home_team, away_team, scheduled_at)
        // Stub returns a deterministic UUID from the event ID for wiring purposes
        try {
            return UUID.nameUUIDFromBytes(event.getId().getBytes());
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isHomeTeam(OddsApiDto.Outcome outcome, List<OddsApiDto.Outcome> outcomes) {
        // The Odds API lists home team first in its outcomes array
        return outcomes.indexOf(outcome) == 0;
    }

    private String mapMarketType(String oddsApiKey) {
        return switch (oddsApiKey) {
            case "spreads" -> "SPREAD";
            case "totals"  -> "TOTAL";
            case "h2h"     -> "MONEYLINE";
            default        -> oddsApiKey.toUpperCase();
        };
    }

    private BigDecimal impliedProb(int americanOdds) {
        double prob = americanOdds < 0
                ? (double) Math.abs(americanOdds) / (Math.abs(americanOdds) + 100)
                : 100.0 / (americanOdds + 100);
        return BigDecimal.valueOf(prob).setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal bd(double value, int scale) {
        return BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP);
    }

    public void registerBookIds(Map<String, Integer> ids) {
        bookIdsByKey.putAll(ids);
    }
}
