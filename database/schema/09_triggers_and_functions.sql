-- =============================================================================
-- SharpForge :: 09_triggers_and_functions.sql
-- Utility functions, update triggers, and automatic denormalization helpers
-- =============================================================================

-- ---------------------------------------------------------------------------
-- Utility: update updated_at timestamp automatically
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION core.fn_set_updated_at()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$;

-- Apply to all tables with updated_at
DO $$
DECLARE
    t RECORD;
BEGIN
    FOR t IN
        SELECT table_schema, table_name
        FROM information_schema.columns
        WHERE column_name = 'updated_at'
          AND table_schema IN ('core','market','action','environ','personnel','stats','backtest')
    LOOP
        EXECUTE format(
            'CREATE OR REPLACE TRIGGER trg_set_updated_at
             BEFORE UPDATE ON %I.%I
             FOR EACH ROW EXECUTE FUNCTION core.fn_set_updated_at()',
            t.table_schema, t.table_name
        );
    END LOOP;
END;
$$;

-- ---------------------------------------------------------------------------
-- Function: compute Pythagorean win percentage
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION stats.fn_pythagorean_win_pct(
    p_scored    NUMERIC,
    p_allowed   NUMERIC,
    p_exp       NUMERIC DEFAULT 2.37  -- NFL default
)
RETURNS NUMERIC LANGUAGE sql IMMUTABLE STRICT AS $$
    SELECT ROUND(
        POWER(p_scored, p_exp) /
        NULLIF(POWER(p_scored, p_exp) + POWER(p_allowed, p_exp), 0),
        4
    );
$$;

-- ---------------------------------------------------------------------------
-- Function: American odds → implied probability
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION market.fn_implied_prob(p_american_odds INTEGER)
RETURNS NUMERIC LANGUAGE sql IMMUTABLE STRICT AS $$
    SELECT CASE
        WHEN p_american_odds < 0 THEN ROUND(ABS(p_american_odds)::NUMERIC / (ABS(p_american_odds) + 100), 4)
        WHEN p_american_odds > 0 THEN ROUND(100.0 / (p_american_odds + 100), 4)
        ELSE NULL
    END;
$$;

-- ---------------------------------------------------------------------------
-- Function: implied probability → American odds
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION market.fn_american_odds(p_implied_prob NUMERIC)
RETURNS INTEGER LANGUAGE sql IMMUTABLE STRICT AS $$
    SELECT CASE
        WHEN p_implied_prob >= 0.5 THEN ROUND(-(p_implied_prob / (1 - p_implied_prob)) * 100)::INTEGER
        WHEN p_implied_prob > 0    THEN ROUND(((1 - p_implied_prob) / p_implied_prob) * 100)::INTEGER
        ELSE NULL
    END;
$$;

-- ---------------------------------------------------------------------------
-- Function: compute no-vig (true) probability from a two-way market
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION market.fn_no_vig_prob(
    p_ml_a INTEGER,   -- side A American odds
    p_ml_b INTEGER    -- side B American odds
)
RETURNS TABLE (prob_a NUMERIC, prob_b NUMERIC, vig NUMERIC)
LANGUAGE sql IMMUTABLE STRICT AS $$
    WITH implied AS (
        SELECT
            market.fn_implied_prob(p_ml_a) AS ip_a,
            market.fn_implied_prob(p_ml_b) AS ip_b
    )
    SELECT
        ROUND(ip_a / (ip_a + ip_b), 4),
        ROUND(ip_b / (ip_a + ip_b), 4),
        ROUND(ip_a + ip_b - 1, 4)   -- overround / juice
    FROM implied;
$$;

-- ---------------------------------------------------------------------------
-- Function: compute Closing Line Value (CLV)
-- Returns how many implied probability points better the bet was than close
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION market.fn_clv(
    p_bet_spread    NUMERIC,    -- spread taken
    p_close_spread  NUMERIC,    -- closing spread on same side
    p_bet_juice     NUMERIC DEFAULT -0.1   -- as implied prob delta (e.g. -110 = -0.0909)
)
RETURNS NUMERIC LANGUAGE sql IMMUTABLE AS $$
    SELECT ROUND(p_close_spread - p_bet_spread + p_bet_juice, 3);
$$;

-- ---------------------------------------------------------------------------
-- Function: fatigue score computation
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION stats.fn_fatigue_score(
    p_days_rest         SMALLINT,
    p_games_last_7      SMALLINT,
    p_games_last_14     SMALLINT,
    p_travel_miles      INTEGER DEFAULT 0,
    p_timezone_shifts   SMALLINT DEFAULT 0,
    p_prev_overtime     BOOLEAN DEFAULT FALSE
)
RETURNS NUMERIC LANGUAGE sql IMMUTABLE AS $$
    SELECT ROUND(
        LEAST(10,
            -- Rest component (fewer rest days = higher fatigue)
            CASE WHEN p_days_rest = 0 THEN 4.0
                 WHEN p_days_rest = 1 THEN 3.0
                 WHEN p_days_rest = 2 THEN 1.5
                 WHEN p_days_rest = 3 THEN 0.8
                 ELSE 0.0 END
            -- Schedule density component
            + (p_games_last_7 - 1) * 0.6
            + (p_games_last_14 - 2) * 0.2
            -- Travel component
            + LEAST(2.0, p_travel_miles::NUMERIC / 1500)
            + ABS(p_timezone_shifts) * 0.3
            -- Overtime bonus
            + CASE WHEN p_prev_overtime THEN 0.5 ELSE 0 END
        ), 2
    );
$$;

-- ---------------------------------------------------------------------------
-- Trigger: after game result, refresh mv_game_context for that game
-- (In production, use CONCURRENTLY refresh on a schedule instead)
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION stats.fn_invalidate_game_context()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    -- Mark the MV as needing refresh (lightweight table approach)
    INSERT INTO stats.mv_refresh_queue (game_id, queued_at)
    VALUES (NEW.game_id, NOW())
    ON CONFLICT (game_id) DO UPDATE SET queued_at = NOW();
    RETURN NEW;
END;
$$;

CREATE TABLE IF NOT EXISTS stats.mv_refresh_queue (
    game_id     UUID        PRIMARY KEY,
    queued_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Apply to relevant tables
CREATE OR REPLACE TRIGGER trg_invalidate_ctx_games
    AFTER INSERT OR UPDATE ON core.games
    FOR EACH ROW EXECUTE FUNCTION stats.fn_invalidate_game_context();

CREATE OR REPLACE TRIGGER trg_invalidate_ctx_odds
    AFTER INSERT OR UPDATE ON market.consensus_odds
    FOR EACH ROW EXECUTE FUNCTION stats.fn_invalidate_game_context();

-- ---------------------------------------------------------------------------
-- Function: backtest aggregate rollup after inserting bet_results
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION backtest.fn_rollup_run_stats(p_run_id UUID)
RETURNS VOID LANGUAGE plpgsql AS $$
BEGIN
    UPDATE backtest.simulation_runs sr
    SET
        total_bets    = agg.total_bets,
        wins          = agg.wins,
        losses        = agg.losses,
        pushes        = agg.pushes,
        no_action     = agg.no_action,
        win_pct       = ROUND(agg.wins::NUMERIC / NULLIF(agg.wins + agg.losses, 0), 4),
        total_return  = agg.total_return,
        net_profit    = agg.net_profit,
        roi_pct       = ROUND(agg.net_profit / NULLIF(agg.total_units, 0), 4),
        clv_avg       = agg.clv_avg
    FROM (
        SELECT
            COUNT(*)                                        AS total_bets,
            COUNT(*) FILTER (WHERE status = 'WIN')         AS wins,
            COUNT(*) FILTER (WHERE status = 'LOSS')        AS losses,
            COUNT(*) FILTER (WHERE status = 'PUSH')        AS pushes,
            COUNT(*) FILTER (WHERE status = 'NO_ACTION')   AS no_action,
            SUM(unit_size)                                  AS total_units,
            SUM(profit_loss + unit_size) FILTER (WHERE status = 'WIN') AS total_return,
            SUM(profit_loss)                               AS net_profit,
            ROUND(AVG(clv), 3)                             AS clv_avg
        FROM backtest.bet_results
        WHERE run_id = p_run_id
    ) agg
    WHERE sr.run_id = p_run_id;
END;
$$;
