-- =============================================================================
-- SharpForge :: 10_example_backtest_queries.sql
-- Canonical backtesting queries showing the full JOIN pattern
-- All queries target mv_game_context for sub-millisecond execution
-- =============================================================================

-- ---------------------------------------------------------------------------
-- Q1: Sharp Money + Closing Line Value system
-- "Games where sharp money is on the away team, closing line moved toward them,
--  and steam move was detected" — classic contrarian value bet
-- ---------------------------------------------------------------------------
EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT)
SELECT
    game_date,
    away_team_abbr,
    home_team_abbr,
    open_home_spread,
    close_home_spread,
    spread_move,
    closing_home_ticket_pct     AS home_ticket_pct,
    closing_home_money_pct      AS home_money_pct,
    steam_move_count,
    sharp_signal_strength,
    covered_spread
FROM stats.mv_game_context
WHERE league_id = 1                           -- NFL
  AND game_date BETWEEN '2018-09-01' AND '2024-02-15'
  AND sharp_side = 'AWAY'
  AND is_sharp_vs_public = TRUE
  AND steam_move_count >= 1
  AND spread_move < 0                          -- line moved toward away
  AND closing_home_ticket_pct >= 60            -- public is on home
ORDER BY game_date DESC;

-- ---------------------------------------------------------------------------
-- Q2: Extreme Weather Totals System
-- "Outdoor games where wind > 20 mph, temp < 35°F, precipitation likely"
-- ---------------------------------------------------------------------------
SELECT
    game_date,
    home_team_abbr,
    away_team_abbr,
    weather_condition,
    temp_f,
    wind_speed_mph,
    crosswind_mph,
    precip_prob_pct,
    scoring_suppression_idx,
    open_total,
    close_total,
    total_move,
    total_result
FROM stats.mv_game_context
WHERE league_id IN (1, 2)                     -- NFL + NCAAF
  AND is_dome = FALSE
  AND wind_speed_mph >= 20
  AND temp_f <= 35
  AND precip_prob_pct >= 40
  AND game_date BETWEEN '2015-09-01' AND '2024-02-15'
ORDER BY scoring_suppression_idx DESC, wind_speed_mph DESC;

-- ---------------------------------------------------------------------------
-- Q3: Rest Advantage System
-- "Home team with 10+ days rest vs. away team on short week (< 6 days)"
-- ---------------------------------------------------------------------------
SELECT
    game_date,
    home_team_abbr,
    away_team_abbr,
    home_days_rest,
    away_days_rest,
    rest_advantage,
    home_fatigue,
    away_fatigue,
    open_home_spread,
    close_home_spread,
    covered_spread
FROM stats.mv_game_context
WHERE league_id = 1                           -- NFL
  AND home_days_rest >= 10                    -- home had bye week
  AND away_short_week = TRUE
  AND game_date BETWEEN '2010-09-01' AND '2024-02-15'
ORDER BY rest_advantage DESC, game_date DESC;

-- ---------------------------------------------------------------------------
-- Q4: Referee Over Tendency System
-- "Games officiated by an over-prone crew chief with under-suppressed weather"
-- Combine official tendency with environmental suppression
-- ---------------------------------------------------------------------------
SELECT
    game_date,
    home_team_abbr,
    away_team_abbr,
    official_over_rate,
    official_avg_total,
    is_over_official,
    is_under_official,
    open_total,
    close_total,
    scoring_suppression_idx,
    total_result
FROM stats.mv_game_context
WHERE league_id IN (1, 4)                     -- NFL + NBA
  AND is_over_official = TRUE
  AND official_over_rate >= 0.58
  AND scoring_suppression_idx <= 2.0          -- minimal weather suppression
  AND game_date BETWEEN '2015-09-01' AND '2024-06-01'
ORDER BY official_over_rate DESC;

-- ---------------------------------------------------------------------------
-- Q5: Multi-factor Fade the Public (Contrarian) System
-- "Combine: public >65% on one side, reverse line move, away dog, divisional"
-- ---------------------------------------------------------------------------
SELECT
    game_date,
    home_team_abbr,
    away_team_abbr,
    open_home_spread,
    close_home_spread,
    spread_move,
    closing_home_ticket_pct,
    closing_home_money_pct,
    100 - closing_home_ticket_pct               AS away_ticket_pct,
    steam_move_count,
    reverse_line_count,
    sharp_signal_strength,
    is_divisional,
    covered_spread,
    -- Running ATS record
    SUM(CASE WHEN covered_spread = 'AWAY' THEN 1 ELSE 0 END)
        OVER (ORDER BY game_date ROWS UNBOUNDED PRECEDING) AS away_ats_wins_running,
    COUNT(*) OVER (ORDER BY game_date ROWS UNBOUNDED PRECEDING) AS total_games_running
FROM stats.mv_game_context
WHERE league_id = 1                           -- NFL
  AND closing_home_ticket_pct >= 65           -- public strongly on home
  AND spread_move > 0                         -- yet line moved AGAINST public (reverse)
  AND reverse_line_count >= 1
  AND open_home_spread > 0                    -- home is favored (away is dog)
  AND game_date BETWEEN '2010-09-01' AND '2024-02-15'
ORDER BY game_date;

-- ---------------------------------------------------------------------------
-- Q6: Pythagorean Regression System
-- "Teams that are massively outplaying their record (lucky) vs. unlucky teams"
-- ---------------------------------------------------------------------------
SELECT
    mvc.game_date,
    mvc.home_team_abbr,
    mvc.away_team_abbr,
    mvc.home_elo,
    mvc.away_elo,
    mvc.home_power_rating,
    mvc.away_power_rating,
    mvc.home_pythag_luck,           -- positive = overperforming, ripe for regression
    mvc.away_pythag_luck,
    mvc.open_home_spread,
    mvc.close_home_spread,
    mvc.covered_spread
FROM stats.mv_game_context mvc
WHERE mvc.league_id = 3                       -- NBA
  AND mvc.home_pythag_luck >= 0.05            -- home team is lucky (+5 pts above expectation)
  AND mvc.away_pythag_luck <= -0.04           -- away team is unlucky
  AND mvc.game_date BETWEEN '2015-10-01' AND '2024-06-15'
ORDER BY (mvc.home_pythag_luck - mvc.away_pythag_luck) DESC;

-- ---------------------------------------------------------------------------
-- Q7: Full Strategy Backtest with ROI Summary
-- Combines ALL major filters: sharp money + weather + rest + official + Pythag
-- ---------------------------------------------------------------------------
WITH filtered_games AS (
    SELECT
        game_id,
        game_date,
        home_team_abbr,
        away_team_abbr,
        close_home_spread,
        total_result,
        covered_spread,
        sharp_signal_strength,
        scoring_suppression_idx
    FROM stats.mv_game_context
    WHERE league_id = 1
      AND game_date BETWEEN '2015-09-01' AND '2024-02-15'
      AND is_sharp_vs_public = TRUE
      AND steam_move_count >= 1
      AND home_days_rest >= 7
      AND wind_speed_mph <= 15
      AND is_over_official = TRUE
      AND scoring_suppression_idx <= 1.5
),
results AS (
    SELECT
        COUNT(*)                                                AS total_games,
        COUNT(*) FILTER (WHERE covered_spread = sharp_side)    AS theoretical_wins,
        ROUND(AVG(sharp_signal_strength), 2)                   AS avg_signal_strength,
        ROUND(AVG(scoring_suppression_idx), 2)                 AS avg_weather_suppress,
        COUNT(*) FILTER (WHERE total_result = 'OVER')          AS overs,
        COUNT(*) FILTER (WHERE total_result = 'UNDER')         AS unders
    FROM filtered_games
    JOIN action.sharp_report USING (game_id)
    WHERE market_type = 'SPREAD'
)
SELECT
    total_games,
    theoretical_wins,
    ROUND(theoretical_wins::NUMERIC / NULLIF(total_games, 0) * 100, 1) AS win_pct,
    avg_signal_strength,
    avg_weather_suppress,
    overs,
    unders,
    -- ROI at standard -110 juice
    ROUND(
        (theoretical_wins * 100.0 - (total_games - theoretical_wins) * 110.0)
        / NULLIF(total_games * 110.0, 0) * 100
    , 2) AS roi_pct
FROM results;
