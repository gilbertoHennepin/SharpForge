package com.sharpforge.etl.transform;

import com.sharpforge.etl.config.SharpForgeProperties;
import com.sharpforge.etl.model.EtlModels.RestFatigueRecord;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;
import java.util.UUID;

/**
 * Rest and Fatigue Calculator.
 *
 * <p>For every (team, game) pair computes:
 * <ul>
 *   <li><b>Days rest</b> — days since last game (0 = same day)</li>
 *   <li><b>Rest advantage</b> — vs. opponent days rest</li>
 *   <li><b>Schedule density</b> — games in last 7 / 14 / 30 days</li>
 *   <li><b>Back-to-back / short week / long week flags</b></li>
 *   <li><b>Fatigue score</b> — composite 0–10 index</li>
 *   <li><b>Travel miles</b> — aggregated over last 14 days from venue coordinates</li>
 * </ul>
 *
 * <p>The fatigue score mirrors the PostgreSQL {@code stats.fn_fatigue_score()} function
 * so that the ETL and the DB stay in sync. Any formula changes here should be
 * reflected in the SQL function (and vice versa).
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class RestDaysCalculator {

    private final SharpForgeProperties props;

    // Earth radius in miles for Haversine
    private static final double EARTH_RADIUS_MILES = 3958.8;

    /**
     * Input context: a scheduled fixture for a specific team, with their full
     * historical fixture list and venue coordinates.
     */
    public record FixtureContext(
            UUID gameId,
            int teamId,
            boolean homeTeam,
            LocalDate gameDate,
            /** Venue of the current game. */
            VenueCoords currentVenueCoords,
            /** Team's home venue coordinates (for travel back calculation). */
            VenueCoords homeVenueCoords,
            /** All historical fixtures for this team, sorted newest→oldest. */
            List<HistoricalFixture> history,
            /** Opponent's fixture history for rest comparison. */
            List<HistoricalFixture> opponentHistory,
            /** Whether the previous game went to overtime. */
            boolean prevGameOvertimeFlag,
            /** Whether the team won the previous game. */
            String prevGameResult           // "W" | "L" | "T"
    ) {}

    public record VenueCoords(double latitudeDeg, double longitudeDeg, boolean isDome) {}

    public record HistoricalFixture(
            LocalDate gameDate,
            VenueCoords venueCoords,
            boolean homeGame
    ) {}

    /**
     * Calculate rest and fatigue metrics for a single (team, game) pair.
     */
    public RestFatigueRecord calculate(FixtureContext ctx) {
        int shortWeekDays = props.getEtl().getRest().getShortWeekThresholdDays();
        int longWeekDays  = props.getEtl().getRest().getLongWeekThresholdDays();

        // ── Days rest ─────────────────────────────────────────────────────────
        int daysRest = computeDaysRest(ctx.gameDate(), ctx.history());
        int oppDaysRest = computeDaysRest(ctx.gameDate(), ctx.opponentHistory());

        // ── Schedule density ──────────────────────────────────────────────────
        int gamesL7  = countGamesInWindow(ctx.gameDate(), ctx.history(), 7);
        int gamesL14 = countGamesInWindow(ctx.gameDate(), ctx.history(), 14);
        int gamesL30 = countGamesInWindow(ctx.gameDate(), ctx.history(), 30);

        // ── Flags ─────────────────────────────────────────────────────────────
        boolean backToBack  = daysRest == 1;
        boolean secondOfB2B = isSecondOfBackToBack(ctx.gameDate(), ctx.history());
        boolean thirdIn4    = gamesL7 >= 3 && daysRest <= 2;
        boolean shortWeek   = daysRest > 0 && daysRest < shortWeekDays;
        boolean longWeek    = daysRest >= longWeekDays;

        // ── Travel miles (last 14 days, cumulative) ───────────────────────────
        int travelMilesL14 = computeTravelMilesLast14Days(
                ctx.gameDate(), ctx.history(), ctx.currentVenueCoords(), ctx.homeVenueCoords());

        // ── Timezone shifts (approximate from longitude difference) ───────────
        int timezoneShifts = estimateTimezoneShift(
                ctx.homeVenueCoords(), ctx.currentVenueCoords());

        // ── Fatigue score ─────────────────────────────────────────────────────
        BigDecimal fatigueScore = computeFatigueScore(
                daysRest, gamesL7, gamesL14, travelMilesL14,
                timezoneShifts, ctx.prevGameOvertimeFlag());

        BigDecimal oppFatigueScore = computeFatigueScore(
                oppDaysRest, gamesL7, gamesL14, 0, 0, false);

        log.debug("RestCalc team={} game={} daysRest={} fatigue={} gamesL7={}",
                ctx.teamId(), ctx.gameId(), daysRest, fatigueScore, gamesL7);

        return RestFatigueRecord.builder()
                .gameId(ctx.gameId())
                .teamId(ctx.teamId())
                .homeTeam(ctx.homeTeam())
                .daysRest(daysRest)
                .opponentDaysRest(oppDaysRest)
                .backToBack(backToBack)
                .secondOfBackToBack(secondOfB2B)
                .thirdIn4Days(thirdIn4)
                .shortWeek(shortWeek)
                .longWeek(longWeek)
                .gamesLast7Days(gamesL7)
                .gamesLast14Days(gamesL14)
                .gamesLast30Days(gamesL30)
                .travelMilesLast14d(travelMilesL14)
                .fatigueScore(fatigueScore)
                .fatigueScoreOpp(oppFatigueScore)
                .prevGameResult(ctx.prevGameResult())
                .prevGameOt(ctx.prevGameOvertimeFlag())
                .build();
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    /** Days since the most recent game before gameDate. Returns 999 if no prior game. */
    private int computeDaysRest(LocalDate gameDate, List<HistoricalFixture> history) {
        return history.stream()
                .map(HistoricalFixture::gameDate)
                .filter(d -> d.isBefore(gameDate))
                .max(Comparator.naturalOrder())
                .map(lastGame -> (int) ChronoUnit.DAYS.between(lastGame, gameDate))
                .orElse(999);   // 999 = no prior game (season opener)
    }

    /** Count games played within the last {@code windowDays} days before gameDate. */
    private int countGamesInWindow(LocalDate gameDate, List<HistoricalFixture> history, int windowDays) {
        LocalDate cutoff = gameDate.minusDays(windowDays);
        return (int) history.stream()
                .map(HistoricalFixture::gameDate)
                .filter(d -> !d.isBefore(cutoff) && d.isBefore(gameDate))
                .count();
    }

    /**
     * True if the team played yesterday AND the game before that was also consecutive
     * (i.e., they are playing the 2nd game of a true B2B sequence).
     */
    private boolean isSecondOfBackToBack(LocalDate gameDate, List<HistoricalFixture> history) {
        List<LocalDate> recent = history.stream()
                .map(HistoricalFixture::gameDate)
                .filter(d -> d.isBefore(gameDate))
                .sorted(Comparator.reverseOrder())
                .limit(2)
                .toList();

        if (recent.size() < 2) return false;
        long gap1 = ChronoUnit.DAYS.between(recent.get(0), gameDate);
        long gap2 = ChronoUnit.DAYS.between(recent.get(1), recent.get(0));
        return gap1 == 1 && gap2 == 1;
    }

    /**
     * Compute cumulative travel miles over the last 14 days using the Haversine formula.
     * Route: home → venue1 → venue2 → ... → currentVenue.
     */
    private int computeTravelMilesLast14Days(
            LocalDate gameDate,
            List<HistoricalFixture> history,
            VenueCoords currentVenue,
            VenueCoords homeVenue) {

        LocalDate cutoff = gameDate.minusDays(14);
        List<HistoricalFixture> recentGames = history.stream()
                .filter(f -> !f.gameDate().isBefore(cutoff) && f.gameDate().isBefore(gameDate))
                .sorted(Comparator.comparing(HistoricalFixture::gameDate))
                .toList();

        if (recentGames.isEmpty()) return 0;

        int totalMiles = 0;
        VenueCoords prev = homeVenue;

        for (HistoricalFixture fixture : recentGames) {
            if (fixture.venueCoords() != null && !fixture.homeGame()) {
                totalMiles += haversineDistanceMiles(prev, fixture.venueCoords());
                prev = fixture.venueCoords();
            }
        }

        // Add miles to reach current game's venue
        if (!isHomeVenue(currentVenue, homeVenue)) {
            totalMiles += haversineDistanceMiles(prev, currentVenue);
        }

        return totalMiles;
    }

    /** Haversine great-circle distance between two lat/lon points. */
    private int haversineDistanceMiles(VenueCoords a, VenueCoords b) {
        double lat1 = Math.toRadians(a.latitudeDeg());
        double lat2 = Math.toRadians(b.latitudeDeg());
        double dLat = lat2 - lat1;
        double dLon = Math.toRadians(b.longitudeDeg() - a.longitudeDeg());

        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(lat1) * Math.cos(lat2)
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);

        return (int) (2 * EARTH_RADIUS_MILES * Math.asin(Math.sqrt(h)));
    }

    /** Approximate timezone shift using 15° longitude per hour. */
    private int estimateTimezoneShift(VenueCoords home, VenueCoords current) {
        double lonDiff = current.longitudeDeg() - home.longitudeDeg();
        return (int) Math.round(lonDiff / 15.0);
    }

    private boolean isHomeVenue(VenueCoords venue, VenueCoords homeVenue) {
        return Math.abs(venue.latitudeDeg()  - homeVenue.latitudeDeg())  < 0.01
            && Math.abs(venue.longitudeDeg() - homeVenue.longitudeDeg()) < 0.01;
    }

    /**
     * Fatigue score formula — mirrors PostgreSQL {@code stats.fn_fatigue_score()}.
     * Returns a value in [0, 10] where 10 = maximum fatigue.
     */
    public BigDecimal computeFatigueScore(
            int daysRest,
            int gamesLast7,
            int gamesLast14,
            int travelMiles,
            int timezoneShifts,
            boolean prevOvertime) {

        double restComponent = switch (daysRest) {
            case 0     -> 4.0;
            case 1     -> 3.0;
            case 2     -> 1.5;
            case 3     -> 0.8;
            default    -> 0.0;
        };

        double densityComponent = Math.max(0, (gamesLast7 - 1) * 0.6)
                                + Math.max(0, (gamesLast14 - 2) * 0.2);

        double travelComponent  = Math.min(2.0, travelMiles / 1500.0)
                                + Math.abs(timezoneShifts) * 0.3;

        double otBonus          = prevOvertime ? 0.5 : 0.0;

        double raw = restComponent + densityComponent + travelComponent + otBonus;
        double clamped = Math.min(10.0, Math.max(0.0, raw));

        return BigDecimal.valueOf(clamped).setScale(2, RoundingMode.HALF_UP);
    }
}
