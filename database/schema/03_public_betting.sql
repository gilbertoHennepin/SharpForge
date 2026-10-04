-- =============================================================================
-- SharpForge :: 03_public_betting.sql
-- Ticket percentages, money percentages, sharp signal tracking
-- =============================================================================

-- ---------------------------------------------------------------------------
-- action.betting_splits  (ticket % vs. money % snapshots over time)
-- ---------------------------------------------------------------------------
CREATE TABLE action.betting_splits (
    split_id            BIGSERIAL           PRIMARY KEY,
    game_id             UUID                NOT NULL REFERENCES core.games(game_id) ON DELETE CASCADE,
    book_id             SMALLINT            REFERENCES market.sportsbooks(book_id),  -- NULL = consensus
    market_type         market.market_type  NOT NULL,
    bet_side            market.bet_side     NOT NULL,
    recorded_at         TIMESTAMPTZ         NOT NULL,
    minutes_to_game     INTEGER,            -- negative means after game started (live)
    -- Ticket (count) split
    ticket_pct          NUMERIC(5,2)        NOT NULL CHECK (ticket_pct BETWEEN 0 AND 100),
    -- Money (handle) split
    money_pct           NUMERIC(5,2)        NOT NULL CHECK (money_pct BETWEEN 0 AND 100),
    -- Computed divergence: large gap = possible sharp action
    ticket_money_gap    NUMERIC(5,2) GENERATED ALWAYS AS (money_pct - ticket_pct) STORED,
    -- Accompanying line at this snapshot
    current_spread      NUMERIC(5,1),
    current_total       NUMERIC(5,1),
    current_ml          INTEGER,
    created_at          TIMESTAMPTZ         NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_split UNIQUE (game_id, book_id, market_type, bet_side, recorded_at)
) PARTITION BY RANGE (recorded_at);

CREATE TABLE action.betting_splits_2020 PARTITION OF action.betting_splits
    FOR VALUES FROM ('2020-01-01') TO ('2021-01-01');
CREATE TABLE action.betting_splits_2021 PARTITION OF action.betting_splits
    FOR VALUES FROM ('2021-01-01') TO ('2022-01-01');
CREATE TABLE action.betting_splits_2022 PARTITION OF action.betting_splits
    FOR VALUES FROM ('2022-01-01') TO ('2023-01-01');
CREATE TABLE action.betting_splits_2023 PARTITION OF action.betting_splits
    FOR VALUES FROM ('2023-01-01') TO ('2024-01-01');
CREATE TABLE action.betting_splits_2024 PARTITION OF action.betting_splits
    FOR VALUES FROM ('2024-01-01') TO ('2025-01-01');
CREATE TABLE action.betting_splits_2025 PARTITION OF action.betting_splits
    FOR VALUES FROM ('2025-01-01') TO ('2026-01-01');
CREATE TABLE action.betting_splits_2026 PARTITION OF action.betting_splits
    FOR VALUES FROM ('2026-01-01') TO ('2027-01-01');

CREATE INDEX idx_bs_game_market       ON action.betting_splits (game_id, market_type, recorded_at DESC);
CREATE INDEX idx_bs_gap               ON action.betting_splits (ticket_money_gap DESC);
-- Composite: "closing splits" filter for backtesting (last 4 hrs before game)
CREATE INDEX idx_bs_closing_window    ON action.betting_splits (game_id, market_type, minutes_to_game)
    WHERE minutes_to_game BETWEEN 0 AND 240;

-- ---------------------------------------------------------------------------
-- action.sharp_signals  (per-game sharp money event log)
-- ---------------------------------------------------------------------------
CREATE TABLE action.sharp_signals (
    signal_id       BIGSERIAL           PRIMARY KEY,
    game_id         UUID                NOT NULL REFERENCES core.games(game_id) ON DELETE CASCADE,
    book_id         SMALLINT            REFERENCES market.sportsbooks(book_id),
    market_type     market.market_type  NOT NULL,
    bet_side        market.bet_side     NOT NULL,
    signal_type     action.sharp_signal NOT NULL,
    detected_at     TIMESTAMPTZ         NOT NULL,
    minutes_to_game INTEGER,
    -- Line context when signal fired
    spread_at_signal    NUMERIC(5,1),
    total_at_signal     NUMERIC(5,1),
    ml_at_signal        INTEGER,
    -- Confirmation metadata
    confirmed_by_move   BOOLEAN         NOT NULL DEFAULT FALSE,
    move_size_after     NUMERIC(4,1),           -- spread movement after signal
    notes               TEXT,
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_ss_game          ON action.sharp_signals (game_id, market_type);
CREATE INDEX idx_ss_signal_type   ON action.sharp_signals (signal_type, detected_at DESC);
CREATE INDEX idx_ss_confirmed     ON action.sharp_signals (game_id) WHERE confirmed_by_move;
-- Composite: backtesting filter "steam moves with line confirmation close to game"
CREATE INDEX idx_ss_steam_timing  ON action.sharp_signals
    (signal_type, minutes_to_game, confirmed_by_move)
    WHERE signal_type IN ('STEAM_MOVE', 'REVERSE_LINE_MOVE');

-- ---------------------------------------------------------------------------
-- action.sharp_report  (aggregated per-game sharp summary — one row per game × market)
-- Used for fast join in backtesting queries; updated by trigger or ETL
-- ---------------------------------------------------------------------------
CREATE TABLE action.sharp_report (
    report_id               BIGSERIAL           PRIMARY KEY,
    game_id                 UUID                NOT NULL REFERENCES core.games(game_id) ON DELETE CASCADE,
    market_type             market.market_type  NOT NULL,
    -- Closing splits (4 hours before game)
    closing_home_ticket_pct NUMERIC(5,2),
    closing_home_money_pct  NUMERIC(5,2),
    closing_over_ticket_pct NUMERIC(5,2),
    closing_over_money_pct  NUMERIC(5,2),
    -- Sharp signal counts
    steam_move_count        SMALLINT            NOT NULL DEFAULT 0,
    reverse_line_count      SMALLINT            NOT NULL DEFAULT 0,
    wiseguy_count           SMALLINT            NOT NULL DEFAULT 0,
    -- Sharp side
    sharp_side              market.bet_side,
    sharp_signal_strength   NUMERIC(4,2),       -- composite 0-10 score
    -- Square side (majority tickets)
    square_side             market.bet_side,
    is_sharp_vs_public      BOOLEAN             NOT NULL DEFAULT FALSE, -- sharp fades public
    -- Total handle estimate (USD millions)
    estimated_handle_usd_m  NUMERIC(10,3),
    computed_at             TIMESTAMPTZ         NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_sharp_report UNIQUE (game_id, market_type)
);

CREATE INDEX idx_sr_game          ON action.sharp_report (game_id);
CREATE INDEX idx_sr_sharp_side    ON action.sharp_report (sharp_side, market_type);
CREATE INDEX idx_sr_signal_str    ON action.sharp_report (sharp_signal_strength DESC);
CREATE INDEX idx_sr_is_sharp_pub  ON action.sharp_report (is_sharp_vs_public, market_type)
    WHERE is_sharp_vs_public;
-- Composite: filtering system (sharp + closing line, the 2 most common backtesting columns)
CREATE INDEX idx_sr_sharp_market  ON action.sharp_report
    (market_type, sharp_side, sharp_signal_strength DESC, is_sharp_vs_public);
