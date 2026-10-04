-- =============================================================================
-- SharpForge :: 08_materialized_views.sql
-- Pre-aggregated views for backtesting JOIN performance
-- =============================================================================

-- ---------------------------------------------------------------------------
-- mv_game_context
-- Master denormalized view — every game with its key context in a single row.
-- This is the primary target for backtesting filter queries, replacing
-- complex 8-table JOINs at query time with a single materialized scan.
-- ---------------------------------------------------------------------------
CREATE MATERIALIZED VIEW stats.mv_game_context AS
SELECT
    g.game_id,
    g.league_id,
    g.season_id,
    g.scheduled_at,
    g.scheduled_at::DATE                        AS game_date,
    g.status,
    g.is_playoff,
    g.is_divisional,
    g.is_neutral_site,
    -- Teams
    g.home_team_id,
    ht.abbreviation                             AS home_team_abbr,
    ht.team_name                                AS home_team_name,
    g.away_team_id,
    at_.abbreviation                            AS away_team_abbr,
    at_.team_name                               AS away_team_name,
    -- Venue / environment
    g.venue_id,
    v.is_dome,
    v.altitude_ft                               AS venue_altitude_ft,
    -- Scores
    g.home_score,
    g.away_score,
    g.total_score,
    g.score_diff,
    g.home_win,
    g.overtime_periods,
    -- Consensus odds (SPREAD)
    co.open_home_spread,
    co.close_home_spread,
    co.spread_move,
    co.open_total,
    co.close_total,
    co.total_move,
    co.open_home_ml,
    co.close_home_ml,
    co.ml_home_move,
    co.covered_spread,
    co.total_result,
    co.prev_game_home_spread,
    co.prev_game_away_spread,
    -- Sharp report
    sr.closing_home_ticket_pct,
    sr.closing_home_money_pct,
    sr.closing_over_ticket_pct,
    sr.closing_over_money_pct,
    sr.steam_move_count,
    sr.reverse_line_count,
    sr.sharp_side,
    sr.sharp_signal_strength,
    sr.is_sharp_vs_public,
    -- Weather (forecast)
    gw.condition                                AS weather_condition,
    gw.temp_f,
    gw.wind_speed_mph,
    gw.wind_gust_mph,
    gw.crosswind_mph,
    gw.precip_prob_pct,
    gw.humidity_pct,
    gw.scoring_suppression_idx,
    -- Home team rest / fatigue
    raf_h.days_rest                             AS home_days_rest,
    raf_h.is_back_to_back                       AS home_b2b,
    raf_h.is_short_week                         AS home_short_week,
    raf_h.games_last_7_days                     AS home_games_l7,
    raf_h.fatigue_score                         AS home_fatigue,
    raf_h.rest_advantage,
    -- Away team rest / fatigue
    raf_a.days_rest                             AS away_days_rest,
    raf_a.is_back_to_back                       AS away_b2b,
    raf_a.is_short_week                         AS away_short_week,
    raf_a.games_last_7_days                     AS away_games_l7,
    raf_a.fatigue_score                         AS away_fatigue,
    -- Home starting QB / SP / G
    gs_hqb.player_id                            AS home_starter_id,
    gs_hqb.position                             AS home_starter_pos,
    gs_hqb.game_status                          AS home_starter_status,
    gs_hqb.stat_1                               AS home_starter_stat1,
    -- Away starting QB / SP / G
    gs_aqb.player_id                            AS away_starter_id,
    gs_aqb.position                             AS away_starter_pos,
    gs_aqb.game_status                          AS away_starter_status,
    gs_aqb.stat_1                               AS away_starter_stat1,
    -- Referee tendencies (crew chief)
    ot.is_over_official,
    ot.is_under_official,
    ot.over_rate                                AS official_over_rate,
    ot.avg_total_points                         AS official_avg_total,
    -- Home team power rating on game date
    pr_h.elo_rating                             AS home_elo,
    pr_h.power_rating                           AS home_power_rating,
    pr_h.pythag_luck                            AS home_pythag_luck,
    -- Away team power rating on game date
    pr_a.elo_rating                             AS away_elo,
    pr_a.power_rating                           AS away_power_rating,
    pr_a.pythag_luck                            AS away_pythag_luck,
    -- xG / EPA (game-level)
    ga_h.xg_for                                AS home_xg_for,
    ga_h.xg_against                            AS home_xg_against,
    ga_h.epa_per_play                          AS home_epa,
    ga_a.xg_for                                AS away_xg_for,
    ga_a.xg_against                            AS away_xg_against,
    ga_a.epa_per_play                          AS away_epa
FROM core.games g
JOIN core.teams  ht  ON ht.team_id  = g.home_team_id
JOIN core.teams  at_ ON at_.team_id = g.away_team_id
LEFT JOIN core.venues v              ON v.venue_id       = g.venue_id
LEFT JOIN market.consensus_odds co   ON co.game_id       = g.game_id AND co.market_type = 'SPREAD'
LEFT JOIN action.sharp_report sr     ON sr.game_id       = g.game_id AND sr.market_type = 'SPREAD'
LEFT JOIN environ.game_weather gw    ON gw.game_id       = g.game_id AND NOT gw.is_actual
LEFT JOIN stats.rest_and_fatigue raf_h ON raf_h.game_id  = g.game_id AND raf_h.team_id = g.home_team_id
LEFT JOIN stats.rest_and_fatigue raf_a ON raf_a.game_id  = g.game_id AND raf_a.team_id = g.away_team_id
LEFT JOIN personnel.game_starters gs_hqb
    ON gs_hqb.game_id = g.game_id AND gs_hqb.team_id = g.home_team_id
   AND gs_hqb.position IN ('QB', 'SP', 'G', 'P')
LEFT JOIN personnel.game_starters gs_aqb
    ON gs_aqb.game_id = g.game_id AND gs_aqb.team_id = g.away_team_id
   AND gs_aqb.position IN ('QB', 'SP', 'G', 'P')
LEFT JOIN (
    SELECT go.game_id, ot_inner.*
    FROM personnel.game_officials go
    JOIN personnel.official_tendencies ot_inner ON ot_inner.official_id = go.official_id
    WHERE go.is_crew_chief
) ot ON ot.game_id = g.game_id
LEFT JOIN LATERAL (
    SELECT pr.elo_rating, pr.power_rating, tsa.pythag_luck
    FROM stats.power_ratings pr
    LEFT JOIN stats.team_season_advanced tsa
        ON tsa.team_id = pr.team_id AND tsa.season_id = g.season_id
    WHERE pr.team_id = g.home_team_id AND pr.as_of_date <= g.scheduled_at::DATE
    ORDER BY pr.as_of_date DESC LIMIT 1
) pr_h ON TRUE
LEFT JOIN LATERAL (
    SELECT pr.elo_rating, pr.power_rating, tsa.pythag_luck
    FROM stats.power_ratings pr
    LEFT JOIN stats.team_season_advanced tsa
        ON tsa.team_id = pr.team_id AND tsa.season_id = g.season_id
    WHERE pr.team_id = g.away_team_id AND pr.as_of_date <= g.scheduled_at::DATE
    ORDER BY pr.as_of_date DESC LIMIT 1
) pr_a ON TRUE
LEFT JOIN stats.game_advanced ga_h ON ga_h.game_id = g.game_id AND ga_h.team_id = g.home_team_id
LEFT JOIN stats.game_advanced ga_a ON ga_a.game_id = g.game_id AND ga_a.team_id = g.away_team_id
WHERE g.status = 'FINAL'
WITH DATA;

-- ---------------------------------------------------------------------------
-- Indexes on the materialized view (the heart of fast backtesting)
-- ---------------------------------------------------------------------------

-- Primary access pattern: league + date range
CREATE UNIQUE INDEX idx_mvc_game       ON stats.mv_game_context (game_id);
CREATE INDEX idx_mvc_league_date       ON stats.mv_game_context (league_id, game_date DESC);
CREATE INDEX idx_mvc_home_team         ON stats.mv_game_context (home_team_id, game_date DESC);
CREATE INDEX idx_mvc_away_team         ON stats.mv_game_context (away_team_id, game_date DESC);

-- Odds filters
CREATE INDEX idx_mvc_spread            ON stats.mv_game_context (open_home_spread, close_home_spread);
CREATE INDEX idx_mvc_spread_move       ON stats.mv_game_context (spread_move, total_move);
CREATE INDEX idx_mvc_total             ON stats.mv_game_context (open_total, close_total);

-- Sharp money filters
CREATE INDEX idx_mvc_sharp             ON stats.mv_game_context (is_sharp_vs_public, sharp_side, sharp_signal_strength DESC);
CREATE INDEX idx_mvc_steam             ON stats.mv_game_context (steam_move_count DESC, reverse_line_count DESC);
CREATE INDEX idx_mvc_ticket_money      ON stats.mv_game_context (closing_home_ticket_pct, closing_home_money_pct);

-- Weather filters
CREATE INDEX idx_mvc_weather           ON stats.mv_game_context (weather_condition, wind_speed_mph DESC, temp_f);
CREATE INDEX idx_mvc_scoring_suppress  ON stats.mv_game_context (scoring_suppression_idx DESC) WHERE NOT is_dome;

-- Rest / fatigue filters
CREATE INDEX idx_mvc_rest              ON stats.mv_game_context (home_days_rest, away_days_rest, rest_advantage);
CREATE INDEX idx_mvc_b2b               ON stats.mv_game_context (home_b2b, away_b2b);
CREATE INDEX idx_mvc_fatigue           ON stats.mv_game_context (home_fatigue DESC, away_fatigue DESC);

-- Official filters
CREATE INDEX idx_mvc_over_official     ON stats.mv_game_context (is_over_official, official_over_rate DESC);

-- Power rating / Elo filters
CREATE INDEX idx_mvc_elo               ON stats.mv_game_context (home_elo DESC, away_elo DESC);
CREATE INDEX idx_mvc_power             ON stats.mv_game_context (home_power_rating DESC, away_power_rating DESC);

-- Result filters (for computing ATS and O/U results)
CREATE INDEX idx_mvc_covered           ON stats.mv_game_context (covered_spread, league_id, game_date DESC);
CREATE INDEX idx_mvc_ou_result         ON stats.mv_game_context (total_result, league_id, game_date DESC);

-- MEGA-COMPOSITE: The most common backtesting WHERE clause pattern:
-- league + date range + sharp signal + spread move + rest + weather
CREATE INDEX idx_mvc_backtest_core ON stats.mv_game_context (
    league_id,
    game_date DESC,
    is_sharp_vs_public,
    spread_move,
    home_days_rest,
    away_days_rest,
    scoring_suppression_idx
);

-- MEGA-COMPOSITE: Totals backtesting pattern
-- league + is_dome + weather + official + rest + total move
CREATE INDEX idx_mvc_totals_bt ON stats.mv_game_context (
    league_id,
    is_dome,
    total_move,
    is_over_official,
    scoring_suppression_idx,
    official_over_rate
);

-- MEGA-COMPOSITE: Sharp money system pattern
-- league + sharp signal strength + steam moves + ticket/money gap
CREATE INDEX idx_mvc_sharp_system ON stats.mv_game_context (
    league_id,
    sharp_signal_strength DESC,
    steam_move_count DESC,
    closing_home_ticket_pct,
    closing_home_money_pct,
    is_sharp_vs_public
);

-- Refresh strategy (run after data loads)
-- REFRESH MATERIALIZED VIEW CONCURRENTLY stats.mv_game_context;
