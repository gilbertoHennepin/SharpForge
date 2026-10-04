package com.sharpforge.etl.api.backtest;

import org.jooq.Condition;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Component;

import static org.jooq.impl.DSL.field;

/**
 * Dynamically builds jOOQ Conditions from a nested JSON payload for the backtest engine.
 * Maps DTO filters directly to the `stats.mv_game_context` materialized view.
 */
@Component
public class BacktestQueryBuilder {

    public Condition buildCondition(BacktestRequestDto req) {
        Condition cond = DSL.noCondition();

        // ── Core / Context ──────────────────────────────────────────────
        if (req.getLeagueId() != null) {
            cond = cond.and(field("league_id").eq(req.getLeagueId()));
        }
        if (req.getStartDate() != null) {
            cond = cond.and(field("game_date").ge(req.getStartDate()));
        }
        if (req.getEndDate() != null) {
            cond = cond.and(field("game_date").le(req.getEndDate()));
        }

        // ── Odds Filters ────────────────────────────────────────────────
        if (req.getOdds() != null) {
            var odds = req.getOdds();
            if (odds.getMinSpread() != null) {
                // Absolute spread value (e.g., underdog > 3.0 or favorite < -3.0)
                cond = cond.and(DSL.abs(field("close_home_spread", Double.class)).ge(odds.getMinSpread()));
            }
            if (odds.getMaxSpread() != null) {
                cond = cond.and(DSL.abs(field("close_home_spread", Double.class)).le(odds.getMaxSpread()));
            }
            if (odds.getMinTotal() != null) {
                cond = cond.and(field("close_total").ge(odds.getMinTotal()));
            }
            if (odds.getMaxTotal() != null) {
                cond = cond.and(field("close_total").le(odds.getMaxTotal()));
            }
            if ("TOWARD_HOME".equalsIgnoreCase(odds.getSpreadMoveDirection())) {
                cond = cond.and(field("spread_move", Double.class).lt(0.0)); // line dropped, favoring home
            } else if ("TOWARD_AWAY".equalsIgnoreCase(odds.getSpreadMoveDirection())) {
                cond = cond.and(field("spread_move", Double.class).gt(0.0));
            }
            if (odds.getMinSteamMoves() != null) {
                cond = cond.and(field("steam_move_count").ge(odds.getMinSteamMoves()));
            }
        }

        // ── Public Betting / Sharp Filters ──────────────────────────────
        if (req.getPublicBetting() != null) {
            var pb = req.getPublicBetting();
            if (pb.getSharpSide() != null) {
                cond = cond.and(field("sharp_side").eq(pb.getSharpSide().toUpperCase()));
            }
            if (Boolean.TRUE.equals(pb.getIsSharpVsPublic())) {
                cond = cond.and(field("is_sharp_vs_public").eq(true));
            }
            if (pb.getMaxPublicTicketPct() != null) {
                // Determine which side the public is NOT on
                // This requires knowing the bet target, but for simplicity we check home_ticket_pct
                // In a production system, this dynamically aliases to target team
                cond = cond.and(field("closing_home_ticket_pct", Double.class).le(pb.getMaxPublicTicketPct()));
            }
        }

        // ── Weather Filters ─────────────────────────────────────────────
        if (req.getWeather() != null) {
            var w = req.getWeather();
            if (Boolean.TRUE.equals(w.getExcludeDomes())) {
                cond = cond.and(field("is_dome").eq(false));
            }
            if (w.getMinWindSpeedMph() != null) {
                cond = cond.and(field("wind_speed_mph").ge(w.getMinWindSpeedMph()));
            }
            if (w.getMaxTempF() != null) {
                cond = cond.and(field("temp_f").le(w.getMaxTempF()));
            }
            if (w.getMinPrecipProbPct() != null) {
                cond = cond.and(field("precip_prob_pct").ge(w.getMinPrecipProbPct()));
            }
            if (w.getMinScoringSuppressionIdx() != null) {
                cond = cond.and(field("scoring_suppression_idx").ge(w.getMinScoringSuppressionIdx()));
            }
        }

        // ── Rest & Fatigue Filters ──────────────────────────────────────
        if (req.getRest() != null) {
            var r = req.getRest();
            if (r.getMinRestAdvantage() != null) {
                cond = cond.and(field("rest_advantage").ge(r.getMinRestAdvantage()));
            }
            if (r.getMaxHomeRestDays() != null) {
                cond = cond.and(field("home_days_rest").le(r.getMaxHomeRestDays()));
            }
            if (r.getMaxAwayRestDays() != null) {
                cond = cond.and(field("away_days_rest").le(r.getMaxAwayRestDays()));
            }
            if (Boolean.TRUE.equals(r.getHomeShortWeek())) {
                cond = cond.and(field("home_short_week").eq(true));
            }
            if (Boolean.TRUE.equals(r.getAwayShortWeek())) {
                cond = cond.and(field("away_short_week").eq(true));
            }
        }

        // ── Personnel / Official Filters ────────────────────────────────
        if (req.getPersonnel() != null) {
            var p = req.getPersonnel();
            if (Boolean.TRUE.equals(p.getIsOverOfficial())) {
                cond = cond.and(field("is_over_official").eq(true));
            }
            if (Boolean.TRUE.equals(p.getIsUnderOfficial())) {
                cond = cond.and(field("is_under_official").eq(true));
            }
            if (p.getMinOfficialOverRate() != null) {
                cond = cond.and(field("official_over_rate").ge(p.getMinOfficialOverRate()));
            }
        }

        return cond;
    }
}
