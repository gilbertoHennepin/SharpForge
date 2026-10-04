package com.sharpforge.etl.transform;

import com.sharpforge.etl.model.EtlModels.TeamStreaks;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Streak Calculator — the heart of the SharpForge ETL transformation layer.
 *
 * <p>Given a chronologically-ordered list of {@link GameResult} records for a single team,
 * computes all four streak types required for situational betting analysis:
 *
 * <ol>
 *   <li><b>SU (Straight Up)</b> — Win/Loss streaks regardless of spread</li>
 *   <li><b>ATS (Against the Spread)</b> — Cover/fail-to-cover streaks</li>
 *   <li><b>O/U (Over/Under)</b> — Whether the game total went over or under</li>
 *   <li><b>Turnover</b> — Winning/losing the turnover battle</li>
 * </ol>
 *
 * <p>Streak encoding: positive integers = current favorable streak,
 * negative integers = current adverse streak, 0 = push/no result.
 *
 * <p>Example: suCurrentStreak = +5 means team won their last 5 games.
 *             atsCurrentStreak = -3 means team failed to cover 3 straight.
 */
@Component
@Slf4j
public class StreakCalculator {

    /**
     * Lightweight projection of a completed game result as seen from one team's perspective.
     * Passed in from the ingestion service after scores and odds are persisted.
     */
    public record GameResult(
            LocalDate gameDate,
            boolean homeGame,
            boolean won,                 // SU result
            boolean coveredSpread,       // ATS result (true = covered)
            boolean pushed,              // ATS push (neither win nor loss for ATS)
            boolean wentOver,            // O/U over result
            boolean wentUnder,           // O/U under result
            boolean ouPush,              // total landed exactly on the number
            int turnoversCommitted,      // our turnovers
            int turnoversForced,         // opponent turnovers
            boolean prevGameWon          // needed for "ATS after win/loss" situational splits
    ) {}

    /**
     * Compute all streaks for a team given their full game history up to today.
     *
     * @param teamId  Database team ID
     * @param seasonId Database season ID
     * @param history Chronologically-ordered game results, OLDEST → NEWEST
     * @param minGames Minimum games before streaks are reported
     * @return Fully-populated {@link TeamStreaks} record
     */
    public TeamStreaks calculate(int teamId, int seasonId, List<GameResult> history, int minGames) {
        if (history.size() < minGames) {
            log.debug("Team {} has only {} games — below minGames threshold {}, returning empty streaks",
                    teamId, history.size(), minGames);
            return emptyStreaks(teamId, seasonId);
        }

        // ── SU analysis ──────────────────────────────────────────────────────
        StreakState su         = computeStreaks(history, r -> r.won() ? 1 : -1);

        // ── ATS analysis (skip pushes) ────────────────────────────────────────
        StreakState ats        = computeStreaks(history, r -> {
            if (r.pushed()) return 0;
            return r.coveredSpread() ? 1 : -1;
        });

        // ── O/U analysis (skip pushes) ────────────────────────────────────────
        StreakState ou         = computeStreaks(history, r -> {
            if (r.ouPush()) return 0;
            return r.wentOver() ? 1 : -1;
        });

        // ── Turnover battle analysis ──────────────────────────────────────────
        StreakState to         = computeStreaks(history, r -> {
            int diff = r.turnoversForced() - r.turnoversCommitted();
            if (diff > 0) return 1;    // won turnover battle
            if (diff < 0) return -1;   // lost turnover battle
            return 0;                  // broke even
        });

        // ── Home/Away splits ──────────────────────────────────────────────────
        List<GameResult> homeGames = history.stream().filter(GameResult::homeGame).toList();
        List<GameResult> awayGames = history.stream().filter(r -> !r.homeGame()).toList();
        StreakState homeWin  = computeStreaks(homeGames, r -> r.won() ? 1 : -1);
        StreakState awayWin  = computeStreaks(awayGames, r -> r.won() ? 1 : -1);

        // ── ATS after win / after loss ────────────────────────────────────────
        List<GameResult> afterWin  = filterAfterResult(history, true);
        List<GameResult> afterLoss = filterAfterResult(history, false);
        StreakState atsAfterWin  = computeStreaks(afterWin,  r -> r.pushed() ? 0 : (r.coveredSpread() ? 1 : -1));
        StreakState atsAfterLoss = computeStreaks(afterLoss, r -> r.pushed() ? 0 : (r.coveredSpread() ? 1 : -1));

        LocalDate asOf = history.isEmpty() ? LocalDate.now()
                : history.get(history.size() - 1).gameDate();

        return TeamStreaks.builder()
                .teamId(teamId)
                .seasonId(seasonId)
                .asOfDate(asOf)
                // SU
                .suCurrentStreak(su.current())
                .suLongestWinStreak(su.longestPositive())
                .suLongestLossStreak(su.longestNegative())
                // ATS
                .atsCurrentStreak(ats.current())
                .atsLongestCoverStreak(ats.longestPositive())
                .atsLongestFailStreak(ats.longestNegative())
                // O/U
                .ouCurrentStreak(ou.current())
                .ouLongestOverStreak(ou.longestPositive())
                .ouLongestUnderStreak(ou.longestNegative())
                // Turnovers
                .toCurrentStreak(to.current())
                .toWonBattleStreak(to.longestPositive())
                .toLostBattleStreak(to.longestNegative())
                // Home/Away
                .homeWinStreak(homeWin.current())
                .awayWinStreak(awayWin.current())
                // Situational ATS
                .atsAfterWinStreak(atsAfterWin.current())
                .atsAfterLossStreak(atsAfterLoss.current())
                .build();
    }

    // ── Core streak engine ────────────────────────────────────────────────────

    /**
     * Iterates a game history list applying a {@link SignalExtractor} to each game
     * to classify it as +1 (favorable), -1 (adverse), or 0 (push/neutral).
     * Tracks current streak, longest positive streak, and longest adverse streak.
     */
    private StreakState computeStreaks(List<GameResult> history, SignalExtractor extractor) {
        int currentStreak   = 0;
        int runningStreak   = 0;
        int longestPositive = 0;
        int longestNegative = 0;

        for (GameResult result : history) {
            int signal = extractor.signal(result);

            if (signal == 0) {
                // Push / neutral: the current streak is broken but not flipped
                currentStreak = 0;
                runningStreak = 0;
                continue;
            }

            if (runningStreak == 0) {
                // Starting a new streak from a push or the very beginning
                runningStreak = signal;
            } else if (Integer.signum(runningStreak) == Integer.signum(signal)) {
                // Same direction: extend streak
                runningStreak += signal;
            } else {
                // Direction flip: snapshot longest and reset
                longestPositive = Math.max(longestPositive, runningStreak);
                longestNegative = Math.min(longestNegative, runningStreak);
                runningStreak = signal;
            }

            currentStreak = runningStreak;
        }

        // Final snapshot after loop
        longestPositive = Math.max(longestPositive, runningStreak);
        longestNegative = Math.min(longestNegative, runningStreak);

        return new StreakState(currentStreak, longestPositive, Math.abs(longestNegative));
    }

    /**
     * Filter history to games that followed a win (or loss) — for "ATS after win/loss" splits.
     * A game at index i is included only when history[i-1].prevGameWon() matches the filter.
     */
    private List<GameResult> filterAfterResult(List<GameResult> history, boolean afterWin) {
        List<GameResult> filtered = new ArrayList<>();
        for (int i = 1; i < history.size(); i++) {
            // Use prevGameWon flag from the current game record
            if (history.get(i).prevGameWon() == afterWin) {
                filtered.add(history.get(i));
            }
        }
        return filtered;
    }

    private TeamStreaks emptyStreaks(int teamId, int seasonId) {
        return TeamStreaks.builder()
                .teamId(teamId)
                .seasonId(seasonId)
                .asOfDate(LocalDate.now())
                .build();
    }

    @FunctionalInterface
    private interface SignalExtractor {
        /** Returns +1 (favorable), -1 (adverse), 0 (push/neutral). */
        int signal(GameResult result);
    }

    /** Immutable result of a streak computation pass. */
    private record StreakState(int current, int longestPositive, int longestNegative) {}
}
