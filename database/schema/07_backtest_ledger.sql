-- =============================================================================
-- SharpForge :: 07_backtest_ledger.sql
-- Strategy definitions, simulation runs, individual bet results, and P&L ledger
-- =============================================================================

-- ---------------------------------------------------------------------------
-- backtest.strategies  (strategy / system definitions)
-- ---------------------------------------------------------------------------
CREATE TABLE backtest.strategies (
    strategy_id     UUID            PRIMARY KEY DEFAULT uuid_generate_v4(),
    strategy_name   VARCHAR(120)    NOT NULL,
    description     TEXT,
    sport           core.sport_type NOT NULL,
    league_id       SMALLINT        REFERENCES core.leagues(league_id),
    -- Filter JSON (stores the full 400+ filter specification)
    filter_spec     JSONB           NOT NULL DEFAULT '{}',
    -- Bet parameters
    market_type     market.market_type NOT NULL DEFAULT 'SPREAD',
    bet_side_rule   VARCHAR(40),        -- 'HOME', 'AWAY', 'UNDERDOG', 'FAVORITE', 'DYNAMIC'
    unit_size       NUMERIC(10,2)   NOT NULL DEFAULT 100,  -- base bet in USD
    is_active       BOOLEAN         NOT NULL DEFAULT TRUE,
    created_by      VARCHAR(60),
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_strat_sport      ON backtest.strategies (sport, league_id);
CREATE INDEX idx_strat_market     ON backtest.strategies (market_type, is_active);
CREATE INDEX idx_strat_filter     ON backtest.strategies USING GIN (filter_spec);

-- ---------------------------------------------------------------------------
-- backtest.simulation_runs  (one row per strategy × date range execution)
-- ---------------------------------------------------------------------------
CREATE TABLE backtest.simulation_runs (
    run_id          UUID            PRIMARY KEY DEFAULT uuid_generate_v4(),
    strategy_id     UUID            NOT NULL REFERENCES backtest.strategies(strategy_id),
    run_label       VARCHAR(120),
    date_from       DATE            NOT NULL,
    date_to         DATE            NOT NULL,
    -- Snapshot of filter spec used (in case strategy is edited)
    filter_spec_snapshot JSONB      NOT NULL,
    -- Execution metadata
    executed_at     TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    execution_ms    INTEGER,                    -- query execution time
    -- Aggregate results (denormalized for dashboard queries)
    total_bets      INTEGER         NOT NULL DEFAULT 0,
    wins            INTEGER         NOT NULL DEFAULT 0,
    losses          INTEGER         NOT NULL DEFAULT 0,
    pushes          INTEGER         NOT NULL DEFAULT 0,
    no_action       INTEGER         NOT NULL DEFAULT 0,
    win_pct         NUMERIC(6,4),
    ats_win_pct     NUMERIC(6,4),
    total_units_bet NUMERIC(12,2),
    total_return    NUMERIC(12,2),
    net_profit      NUMERIC(12,2),
    roi_pct         NUMERIC(8,4),
    max_drawdown    NUMERIC(12,2),
    max_win_streak  SMALLINT,
    max_loss_streak SMALLINT,
    sharpe_ratio    NUMERIC(6,4),
    kelly_criterion NUMERIC(6,4),
    clv_avg         NUMERIC(6,4),   -- Avg Closing Line Value across all bets
    notes           TEXT,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_runs_strategy    ON backtest.simulation_runs (strategy_id, executed_at DESC);
CREATE INDEX idx_runs_roi         ON backtest.simulation_runs (roi_pct DESC);
CREATE INDEX idx_runs_date        ON backtest.simulation_runs (date_from, date_to);
CREATE INDEX idx_runs_filter      ON backtest.simulation_runs USING GIN (filter_spec_snapshot);

-- ---------------------------------------------------------------------------
-- backtest.bet_results  (individual bet records — the granular ledger)
-- ---------------------------------------------------------------------------
CREATE TABLE backtest.bet_results (
    bet_id          BIGSERIAL               PRIMARY KEY,
    run_id          UUID                    NOT NULL REFERENCES backtest.simulation_runs(run_id) ON DELETE CASCADE,
    strategy_id     UUID                    NOT NULL REFERENCES backtest.strategies(strategy_id),
    game_id         UUID                    NOT NULL REFERENCES core.games(game_id),
    market_type     market.market_type      NOT NULL,
    bet_side        market.bet_side         NOT NULL,
    -- Bet details
    bet_line        NUMERIC(5,1)            NOT NULL,   -- spread or total bet
    bet_ml          INTEGER,                            -- moneyline taken
    bet_juice       NUMERIC(6,4),                       -- vig paid
    unit_size       NUMERIC(10,2)           NOT NULL,
    -- Timing
    bet_type        VARCHAR(20)             NOT NULL DEFAULT 'OPENING',  -- OPENING, CLOSING, CUSTOM
    simulated_at    TIMESTAMPTZ,
    minutes_to_game INTEGER,
    -- Result
    status          backtest.result_status  NOT NULL,
    actual_line     NUMERIC(5,1),           -- actual spread/total result
    profit_loss     NUMERIC(12,2),          -- in units
    -- Closing Line Value
    closing_line    NUMERIC(5,1),
    clv             NUMERIC(5,2),           -- bet_line - closing_line (ATS; positive = better than closing)
    clv_pct         NUMERIC(6,4),           -- CLV as implied prob difference
    -- Context snapshot (denormalized for fast per-bet analysis)
    home_team_abbr  VARCHAR(10),
    away_team_abbr  VARCHAR(10),
    game_date       DATE,
    sharp_signal    action.sharp_signal,
    weather_flag    BOOLEAN,
    rest_advantage  SMALLINT,
    created_at      TIMESTAMPTZ             NOT NULL DEFAULT NOW()
) PARTITION BY RANGE (game_date);

CREATE TABLE backtest.bet_results_2020 PARTITION OF backtest.bet_results
    FOR VALUES FROM ('2020-01-01') TO ('2021-01-01');
CREATE TABLE backtest.bet_results_2021 PARTITION OF backtest.bet_results
    FOR VALUES FROM ('2021-01-01') TO ('2022-01-01');
CREATE TABLE backtest.bet_results_2022 PARTITION OF backtest.bet_results
    FOR VALUES FROM ('2022-01-01') TO ('2023-01-01');
CREATE TABLE backtest.bet_results_2023 PARTITION OF backtest.bet_results
    FOR VALUES FROM ('2023-01-01') TO ('2024-01-01');
CREATE TABLE backtest.bet_results_2024 PARTITION OF backtest.bet_results
    FOR VALUES FROM ('2024-01-01') TO ('2025-01-01');
CREATE TABLE backtest.bet_results_2025 PARTITION OF backtest.bet_results
    FOR VALUES FROM ('2025-01-01') TO ('2026-01-01');
CREATE TABLE backtest.bet_results_2026 PARTITION OF backtest.bet_results
    FOR VALUES FROM ('2026-01-01') TO ('2027-01-01');

CREATE INDEX idx_br_run           ON backtest.bet_results (run_id, status);
CREATE INDEX idx_br_strategy      ON backtest.bet_results (strategy_id, game_date DESC);
CREATE INDEX idx_br_game          ON backtest.bet_results (game_id, market_type);
CREATE INDEX idx_br_clv           ON backtest.bet_results (clv DESC);
CREATE INDEX idx_br_pnl           ON backtest.bet_results (profit_loss DESC);
CREATE INDEX idx_br_status        ON backtest.bet_results (status, market_type, game_date DESC);
-- Composite: run-level P&L aggregation query
CREATE INDEX idx_br_run_pnl       ON backtest.bet_results (run_id, status, profit_loss);
-- Composite: CLV analysis by strategy + market
CREATE INDEX idx_br_strat_clv     ON backtest.bet_results (strategy_id, market_type, clv DESC);
