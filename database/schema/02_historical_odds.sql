-- =============================================================================
-- SharpForge :: 02_historical_odds.sql
-- Opening lines, closing lines, juice/vig, line movement, and market books
-- =============================================================================

-- ---------------------------------------------------------------------------
-- market.sportsbooks  (book / exchange reference)
-- ---------------------------------------------------------------------------
CREATE TABLE market.sportsbooks (
    book_id         SMALLSERIAL PRIMARY KEY,
    book_name       VARCHAR(80)  NOT NULL UNIQUE,
    abbreviation    VARCHAR(15)  NOT NULL UNIQUE,
    country         CHAR(3)      NOT NULL DEFAULT 'USA',
    is_sharp        BOOLEAN      NOT NULL DEFAULT FALSE, -- sharp/sharp-facing book
    is_exchange     BOOLEAN      NOT NULL DEFAULT FALSE, -- betting exchange (no vig)
    is_active       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- ---------------------------------------------------------------------------
-- market.consensus_odds  (opening & closing consensus / composite lines)
-- One row per game × market_type.  Primary analytics join target.
-- ---------------------------------------------------------------------------
CREATE TABLE market.consensus_odds (
    odds_id             BIGSERIAL           PRIMARY KEY,
    game_id             UUID                NOT NULL REFERENCES core.games(game_id) ON DELETE CASCADE,
    market_type         market.market_type  NOT NULL,
    -- Opening line (first posted)
    open_home_spread    NUMERIC(5,1),       -- negative = home favored
    open_away_spread    NUMERIC(5,1),
    open_total          NUMERIC(5,1),
    open_home_ml        INTEGER,            -- American odds
    open_away_ml        INTEGER,
    open_draw_ml        INTEGER,
    open_home_juice     NUMERIC(6,4),       -- vig expressed as implied prob (0-1)
    open_away_juice     NUMERIC(6,4),
    open_over_juice     NUMERIC(6,4),
    open_under_juice    NUMERIC(6,4),
    open_at             TIMESTAMPTZ         NOT NULL,
    -- Closing line (last posted before game)
    close_home_spread   NUMERIC(5,1),
    close_away_spread   NUMERIC(5,1),
    close_total         NUMERIC(5,1),
    close_home_ml       INTEGER,
    close_away_ml       INTEGER,
    close_draw_ml       INTEGER,
    close_home_juice    NUMERIC(6,4),
    close_away_juice    NUMERIC(6,4),
    close_over_juice    NUMERIC(6,4),
    close_under_juice   NUMERIC(6,4),
    close_at            TIMESTAMPTZ,
    -- Derived movement stats (filled by trigger / ETL)
    spread_move         NUMERIC(4,1) GENERATED ALWAYS AS (close_home_spread - open_home_spread) STORED,
    total_move          NUMERIC(4,1) GENERATED ALWAYS AS (close_total - open_total) STORED,
    ml_home_move        INTEGER      GENERATED ALWAYS AS (close_home_ml - open_home_ml) STORED,
    -- Previous game spread (situational filter)
    prev_game_home_spread NUMERIC(5,1),    -- home team ATS result last game
    prev_game_away_spread NUMERIC(5,1),    -- away team ATS result last game
    prev_game_home_total  NUMERIC(5,1),
    prev_game_away_total  NUMERIC(5,1),
    -- Actual results vs. line
    actual_home_spread_result NUMERIC(5,1), -- (home_score - away_score) - home_spread
    actual_total_result       NUMERIC(5,1), -- total_score - open_total
    covered_spread      market.bet_side,   -- which side covered
    total_result        market.bet_side,   -- OVER / UNDER
    created_at          TIMESTAMPTZ         NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ         NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_consensus_game_market UNIQUE (game_id, market_type)
);

CREATE INDEX idx_co_game          ON market.consensus_odds (game_id);
CREATE INDEX idx_co_market        ON market.consensus_odds (market_type);
CREATE INDEX idx_co_open_spread   ON market.consensus_odds (open_home_spread);
CREATE INDEX idx_co_close_spread  ON market.consensus_odds (close_home_spread);
CREATE INDEX idx_co_spread_move   ON market.consensus_odds (spread_move);
CREATE INDEX idx_co_total_move    ON market.consensus_odds (total_move);
-- Composite: backtesting filter "game + market + movement direction"
CREATE INDEX idx_co_game_market_move ON market.consensus_odds
    (game_id, market_type, spread_move, total_move);
-- Composite: situational "previous game spread + current market"
CREATE INDEX idx_co_prev_current  ON market.consensus_odds
    (prev_game_home_spread, open_home_spread, close_home_spread);

-- ---------------------------------------------------------------------------
-- market.book_odds  (individual sportsbook lines — full history per book)
-- ---------------------------------------------------------------------------
CREATE TABLE market.book_odds (
    book_odds_id    BIGSERIAL           PRIMARY KEY,
    game_id         UUID                NOT NULL REFERENCES core.games(game_id) ON DELETE CASCADE,
    book_id         SMALLINT            NOT NULL REFERENCES market.sportsbooks(book_id),
    market_type     market.market_type  NOT NULL,
    recorded_at     TIMESTAMPTZ         NOT NULL,           -- snapshot timestamp
    home_spread     NUMERIC(5,1),
    away_spread     NUMERIC(5,1),
    total           NUMERIC(5,1),
    home_ml         INTEGER,
    away_ml         INTEGER,
    draw_ml         INTEGER,
    home_juice      NUMERIC(6,4),
    away_juice      NUMERIC(6,4),
    over_juice      NUMERIC(6,4),
    under_juice     NUMERIC(6,4),
    is_opening      BOOLEAN         NOT NULL DEFAULT FALSE,
    is_closing      BOOLEAN         NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW()
) PARTITION BY RANGE (recorded_at);

-- Yearly partitions (add more as needed)
CREATE TABLE market.book_odds_2020 PARTITION OF market.book_odds
    FOR VALUES FROM ('2020-01-01') TO ('2021-01-01');
CREATE TABLE market.book_odds_2021 PARTITION OF market.book_odds
    FOR VALUES FROM ('2021-01-01') TO ('2022-01-01');
CREATE TABLE market.book_odds_2022 PARTITION OF market.book_odds
    FOR VALUES FROM ('2022-01-01') TO ('2023-01-01');
CREATE TABLE market.book_odds_2023 PARTITION OF market.book_odds
    FOR VALUES FROM ('2023-01-01') TO ('2024-01-01');
CREATE TABLE market.book_odds_2024 PARTITION OF market.book_odds
    FOR VALUES FROM ('2024-01-01') TO ('2025-01-01');
CREATE TABLE market.book_odds_2025 PARTITION OF market.book_odds
    FOR VALUES FROM ('2025-01-01') TO ('2026-01-01');
CREATE TABLE market.book_odds_2026 PARTITION OF market.book_odds
    FOR VALUES FROM ('2026-01-01') TO ('2027-01-01');

-- Partition-local indexes (automatically apply to all partitions)
CREATE INDEX idx_bo_game_book     ON market.book_odds (game_id, book_id, recorded_at DESC);
CREATE INDEX idx_bo_book_market   ON market.book_odds (book_id, market_type, recorded_at DESC);
CREATE INDEX idx_bo_game_market   ON market.book_odds (game_id, market_type, recorded_at DESC);
CREATE INDEX idx_bo_opening       ON market.book_odds (game_id, market_type) WHERE is_opening;
CREATE INDEX idx_bo_closing       ON market.book_odds (game_id, market_type) WHERE is_closing;

-- ---------------------------------------------------------------------------
-- market.line_movements  (explicit timestamped line change log)
-- ---------------------------------------------------------------------------
CREATE TABLE market.line_movements (
    movement_id     BIGSERIAL           PRIMARY KEY,
    game_id         UUID                NOT NULL REFERENCES core.games(game_id) ON DELETE CASCADE,
    book_id         SMALLINT            NOT NULL REFERENCES market.sportsbooks(book_id),
    market_type     market.market_type  NOT NULL,
    moved_at        TIMESTAMPTZ         NOT NULL,
    -- Before
    prev_home_spread    NUMERIC(5,1),
    prev_total          NUMERIC(5,1),
    prev_home_ml        INTEGER,
    prev_home_juice     NUMERIC(6,4),
    prev_away_juice     NUMERIC(6,4),
    -- After
    new_home_spread     NUMERIC(5,1),
    new_total           NUMERIC(5,1),
    new_home_ml         INTEGER,
    new_home_juice      NUMERIC(6,4),
    new_away_juice      NUMERIC(6,4),
    -- Movement metadata
    spread_change       NUMERIC(4,1) GENERATED ALWAYS AS (new_home_spread - prev_home_spread) STORED,
    total_change        NUMERIC(4,1) GENERATED ALWAYS AS (new_total - prev_total) STORED,
    direction           market.line_move_direction,
    is_steam_move       BOOLEAN         NOT NULL DEFAULT FALSE,
    is_reverse_line     BOOLEAN         NOT NULL DEFAULT FALSE,  -- tickets vs. money disagree
    minutes_to_game     INTEGER,        -- how many minutes before kickoff
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW()
) PARTITION BY RANGE (moved_at);

CREATE TABLE market.line_movements_2020 PARTITION OF market.line_movements
    FOR VALUES FROM ('2020-01-01') TO ('2021-01-01');
CREATE TABLE market.line_movements_2021 PARTITION OF market.line_movements
    FOR VALUES FROM ('2021-01-01') TO ('2022-01-01');
CREATE TABLE market.line_movements_2022 PARTITION OF market.line_movements
    FOR VALUES FROM ('2022-01-01') TO ('2023-01-01');
CREATE TABLE market.line_movements_2023 PARTITION OF market.line_movements
    FOR VALUES FROM ('2023-01-01') TO ('2024-01-01');
CREATE TABLE market.line_movements_2024 PARTITION OF market.line_movements
    FOR VALUES FROM ('2024-01-01') TO ('2025-01-01');
CREATE TABLE market.line_movements_2025 PARTITION OF market.line_movements
    FOR VALUES FROM ('2025-01-01') TO ('2026-01-01');
CREATE TABLE market.line_movements_2026 PARTITION OF market.line_movements
    FOR VALUES FROM ('2026-01-01') TO ('2027-01-01');

CREATE INDEX idx_lm_game_market   ON market.line_movements (game_id, market_type, moved_at DESC);
CREATE INDEX idx_lm_steam         ON market.line_movements (game_id) WHERE is_steam_move;
CREATE INDEX idx_lm_reverse       ON market.line_movements (game_id) WHERE is_reverse_line;
CREATE INDEX idx_lm_book_time     ON market.line_movements (book_id, moved_at DESC);
-- Composite: "large spread move near game time" filter
CREATE INDEX idx_lm_spread_timing ON market.line_movements
    (ABS(spread_change), minutes_to_game, is_steam_move);
