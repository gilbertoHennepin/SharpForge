-- =============================================================================
-- SharpForge :: 06_advanced_stats.sql
-- Pythagorean expectations, expected goals (xG), fatigue / rest,
-- and sport-specific advanced metrics
-- =============================================================================

-- ---------------------------------------------------------------------------
-- stats.team_season_advanced  (season-level advanced stats, updated incrementally)
-- ---------------------------------------------------------------------------
CREATE TABLE stats.team_season_advanced (
    tsa_id                  BIGSERIAL   PRIMARY KEY,
    team_id                 INTEGER     NOT NULL REFERENCES core.teams(team_id),
    season_id               SMALLINT    NOT NULL REFERENCES core.seasons(season_id),
    as_of_game_number       SMALLINT    NOT NULL DEFAULT 0,  -- rolling window
    -- Record
    wins                    SMALLINT    NOT NULL DEFAULT 0,
    losses                  SMALLINT    NOT NULL DEFAULT 0,
    ties                    SMALLINT    NOT NULL DEFAULT 0,
    win_pct                 NUMERIC(6,4),
    -- Pythagorean expectation (applies across all sports, different exponents)
    points_scored           NUMERIC(10,2) NOT NULL DEFAULT 0,
    points_allowed          NUMERIC(10,2) NOT NULL DEFAULT 0,
    pythagorean_exponent    NUMERIC(4,2),               -- sport-specific (NFL≈2.37, MLB≈1.83, NBA≈13.91)
    pythagorean_win_pct     NUMERIC(6,4),               -- (PS^exp) / (PS^exp + PA^exp)
    pythag_luck             NUMERIC(6,4),               -- actual_win_pct - pythagorean_win_pct
    -- ATS performance
    ats_wins                SMALLINT    NOT NULL DEFAULT 0,
    ats_losses              SMALLINT    NOT NULL DEFAULT 0,
    ats_pushes              SMALLINT    NOT NULL DEFAULT 0,
    ats_win_pct             NUMERIC(6,4),
    -- Over/Under performance
    over_count              SMALLINT    NOT NULL DEFAULT 0,
    under_count             SMALLINT    NOT NULL DEFAULT 0,
    push_count              SMALLINT    NOT NULL DEFAULT 0,
    over_pct                NUMERIC(6,4),
    -- Home / Away splits
    home_win_pct            NUMERIC(6,4),
    away_win_pct            NUMERIC(6,4),
    home_ats_win_pct        NUMERIC(6,4),
    away_ats_win_pct        NUMERIC(6,4),
    -- Scoring
    avg_points_scored       NUMERIC(6,2),
    avg_points_allowed      NUMERIC(6,2),
    avg_margin              NUMERIC(6,2),
    avg_total               NUMERIC(6,2),
    -- Situational
    ats_after_win           NUMERIC(6,4),
    ats_after_loss          NUMERIC(6,4),
    ats_as_favorite         NUMERIC(6,4),
    ats_as_underdog         NUMERIC(6,4),
    ats_big_favorite        NUMERIC(6,4),   -- spread > 7 (NFL), > 10 (NBA)
    ats_big_underdog        NUMERIC(6,4),
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_tsa UNIQUE (team_id, season_id, as_of_game_number)
);

CREATE INDEX idx_tsa_team_season    ON stats.team_season_advanced (team_id, season_id, as_of_game_number DESC);
CREATE INDEX idx_tsa_pythag         ON stats.team_season_advanced (pythagorean_win_pct DESC);
CREATE INDEX idx_tsa_luck           ON stats.team_season_advanced (pythag_luck DESC);
CREATE INDEX idx_tsa_ats_pct        ON stats.team_season_advanced (ats_win_pct DESC);
CREATE INDEX idx_tsa_over_pct       ON stats.team_season_advanced (over_pct DESC);

-- ---------------------------------------------------------------------------
-- stats.game_advanced  (per-game advanced metrics, one row per team per game)
-- ---------------------------------------------------------------------------
CREATE TABLE stats.game_advanced (
    ga_id               BIGSERIAL   PRIMARY KEY,
    game_id             UUID        NOT NULL REFERENCES core.games(game_id) ON DELETE CASCADE,
    team_id             INTEGER     NOT NULL REFERENCES core.teams(team_id),
    is_home             BOOLEAN     NOT NULL,
    -- Expected Goals / xG (Soccer, Hockey)
    xg_for              NUMERIC(6,3),       -- expected goals scored
    xg_against          NUMERIC(6,3),       -- expected goals conceded
    xg_diff             NUMERIC(6,3) GENERATED ALWAYS AS (xg_for - xg_against) STORED,
    actual_goals        NUMERIC(6,3),
    xg_outperformance   NUMERIC(6,3) GENERATED ALWAYS AS (actual_goals - xg_for) STORED,
    -- Expected Points Added (EPA) — NFL / NCAAF
    epa_per_play        NUMERIC(7,4),
    epa_per_pass        NUMERIC(7,4),
    epa_per_rush        NUMERIC(7,4),
    -- Win Probability metrics
    wp_added            NUMERIC(7,4),       -- total win probability added
    wp_avg_start        NUMERIC(5,4),       -- avg WP at start of drive
    -- Efficiency ratings
    offensive_dvoa      NUMERIC(7,4),       -- Defense-adjusted Value Over Average
    defensive_dvoa      NUMERIC(7,4),
    special_teams_dvoa  NUMERIC(7,4),
    -- MLB: run-scoring environment
    fip                 NUMERIC(5,2),       -- Fielding Independent Pitching (pitching team)
    xfip                NUMERIC(5,2),
    woba                NUMERIC(6,4),       -- Weighted On-Base Average (offense)
    xwoba               NUMERIC(6,4),
    -- Shot quality (NHL / Soccer)
    corsi_for_pct       NUMERIC(5,2),       -- CF%
    fenwick_for_pct     NUMERIC(5,2),       -- FF%
    quality_chance_for  SMALLINT,
    quality_chance_against SMALLINT,
    -- Pace / tempo
    plays_per_game      NUMERIC(5,1),
    possession_pct      NUMERIC(5,2),
    -- Turnover differential
    turnovers_committed SMALLINT,
    turnovers_forced    SMALLINT,
    turnover_diff       SMALLINT GENERATED ALWAYS AS (turnovers_forced - turnovers_committed) STORED,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_ga UNIQUE (game_id, team_id)
);

CREATE INDEX idx_ga_game          ON stats.game_advanced (game_id, team_id);
CREATE INDEX idx_ga_team          ON stats.game_advanced (team_id, game_id);
CREATE INDEX idx_ga_xg            ON stats.game_advanced (xg_diff DESC);
CREATE INDEX idx_ga_epa           ON stats.game_advanced (epa_per_play DESC);
CREATE INDEX idx_ga_dvoa          ON stats.game_advanced (offensive_dvoa DESC, defensive_dvoa ASC);

-- ---------------------------------------------------------------------------
-- stats.rest_and_fatigue  (rest days, schedule density, back-to-back)
-- ---------------------------------------------------------------------------
CREATE TABLE stats.rest_and_fatigue (
    fatigue_id              BIGSERIAL   PRIMARY KEY,
    game_id                 UUID        NOT NULL REFERENCES core.games(game_id) ON DELETE CASCADE,
    team_id                 INTEGER     NOT NULL REFERENCES core.teams(team_id),
    is_home                 BOOLEAN     NOT NULL,
    -- Rest
    days_rest               SMALLINT    NOT NULL,   -- days since last game (0 = same day)
    opponent_days_rest      SMALLINT,               -- opponent's rest
    rest_advantage          SMALLINT    GENERATED ALWAYS AS (days_rest - opponent_days_rest) STORED,
    is_back_to_back         BOOLEAN     NOT NULL DEFAULT FALSE,   -- days_rest = 1
    is_second_of_back2back  BOOLEAN     NOT NULL DEFAULT FALSE,
    is_third_in_4_days      BOOLEAN     NOT NULL DEFAULT FALSE,
    is_short_week           BOOLEAN     NOT NULL DEFAULT FALSE,   -- NFL: < 6 days (Thursday game)
    is_long_week            BOOLEAN     NOT NULL DEFAULT FALSE,   -- NFL: bye week (≥ 14 days)
    -- Schedule density
    games_last_7_days       SMALLINT    NOT NULL DEFAULT 1,
    games_last_14_days      SMALLINT    NOT NULL DEFAULT 1,
    games_last_30_days      SMALLINT    NOT NULL DEFAULT 1,
    -- Miles traveled last 14 days (from environ.travel_factors)
    travel_miles_last_14d   INTEGER,
    -- Fatigue index (composite 0-10; higher = more fatigued)
    fatigue_score           NUMERIC(4,2) NOT NULL DEFAULT 0,
    fatigue_score_opp       NUMERIC(4,2),
    fatigue_advantage       NUMERIC(4,2) GENERATED ALWAYS AS (fatigue_score_opp - fatigue_score) STORED,
    -- Prior game outcome context
    prev_game_result        CHAR(1),    -- 'W', 'L', 'T'
    prev_game_was_overtime  BOOLEAN     NOT NULL DEFAULT FALSE,
    prev_game_was_emotional BOOLEAN     NOT NULL DEFAULT FALSE,  -- rivalry, playoff elimination, etc.
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_fatigue UNIQUE (game_id, team_id)
);

CREATE INDEX idx_raf_game             ON stats.rest_and_fatigue (game_id, team_id);
CREATE INDEX idx_raf_team             ON stats.rest_and_fatigue (team_id, game_id);
CREATE INDEX idx_raf_days_rest        ON stats.rest_and_fatigue (days_rest, is_home);
CREATE INDEX idx_raf_back2back        ON stats.rest_and_fatigue (is_back_to_back) WHERE is_back_to_back;
CREATE INDEX idx_raf_short_week       ON stats.rest_and_fatigue (is_short_week) WHERE is_short_week;
CREATE INDEX idx_raf_fatigue          ON stats.rest_and_fatigue (fatigue_score DESC, fatigue_advantage DESC);
-- Composite: "rest advantage + fatigue advantage" — a key backtesting filter pair
CREATE INDEX idx_raf_advantage_combo  ON stats.rest_and_fatigue
    (rest_advantage, fatigue_advantage, is_home, days_rest);
-- Composite: schedule density filter
CREATE INDEX idx_raf_density          ON stats.rest_and_fatigue
    (games_last_7_days, games_last_14_days, fatigue_score DESC);

-- ---------------------------------------------------------------------------
-- stats.power_ratings  (Elo / proprietary power ratings per team per week)
-- ---------------------------------------------------------------------------
CREATE TABLE stats.power_ratings (
    rating_id           BIGSERIAL   PRIMARY KEY,
    team_id             INTEGER     NOT NULL REFERENCES core.teams(team_id),
    season_id           SMALLINT    NOT NULL REFERENCES core.seasons(season_id),
    as_of_date          DATE        NOT NULL,
    -- Elo
    elo_rating          NUMERIC(7,2) NOT NULL DEFAULT 1500,
    elo_change          NUMERIC(6,2),
    -- Composite power rating (proprietary)
    power_rating        NUMERIC(7,3),
    power_rating_off    NUMERIC(7,3),
    power_rating_def    NUMERIC(7,3),
    -- Ranking within league
    overall_rank        SMALLINT,
    offensive_rank      SMALLINT,
    defensive_rank      SMALLINT,
    -- Predictive quality metrics
    srs                 NUMERIC(7,3),   -- Simple Rating System (margin adjusted for strength of schedule)
    sos                 NUMERIC(7,3),   -- Strength of Schedule
    sov                 NUMERIC(7,3),   -- Strength of Victory
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_power_rating UNIQUE (team_id, season_id, as_of_date)
);

CREATE INDEX idx_pr_team_date     ON stats.power_ratings (team_id, as_of_date DESC);
CREATE INDEX idx_pr_elo           ON stats.power_ratings (elo_rating DESC, as_of_date DESC);
CREATE INDEX idx_pr_power         ON stats.power_ratings (power_rating DESC, as_of_date DESC);
CREATE INDEX idx_pr_season_rank   ON stats.power_ratings (season_id, overall_rank, as_of_date DESC);

-- ---------------------------------------------------------------------------
-- stats.situational_splits  (pre-computed ATS/O-U records for 400+ situation filters)
-- Used to quickly answer "when team X is home dog after a loss vs. division rival"
-- ---------------------------------------------------------------------------
CREATE TABLE stats.situational_splits (
    split_id            BIGSERIAL       PRIMARY KEY,
    team_id             INTEGER         NOT NULL REFERENCES core.teams(team_id),
    league_id           SMALLINT        NOT NULL REFERENCES core.leagues(league_id),
    season_id           SMALLINT        REFERENCES core.seasons(season_id), -- NULL = all-time
    -- Filter dimensions (denormalized for performance)
    split_category      VARCHAR(60)     NOT NULL,   -- 'Home Underdog', 'After Loss vs. Division', etc.
    split_key           VARCHAR(200)    NOT NULL,   -- stringified composite key for the filter
    -- Results
    sample_size         INTEGER         NOT NULL DEFAULT 0,
    ats_wins            INTEGER         NOT NULL DEFAULT 0,
    ats_losses          INTEGER         NOT NULL DEFAULT 0,
    ats_pushes          INTEGER         NOT NULL DEFAULT 0,
    ats_win_pct         NUMERIC(6,4),
    over_count          INTEGER         NOT NULL DEFAULT 0,
    under_count         INTEGER         NOT NULL DEFAULT 0,
    over_pct            NUMERIC(6,4),
    avg_margin_vs_spread NUMERIC(6,2),
    roi_pct             NUMERIC(7,4),           -- Return on investment at -110
    -- Statistical significance
    z_score             NUMERIC(6,4),
    is_significant      BOOLEAN         NOT NULL DEFAULT FALSE, -- |z| > 1.96 (95% CI)
    computed_at         TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_situational_split UNIQUE (team_id, season_id, split_key)
);

CREATE INDEX idx_ss_team_cat      ON stats.situational_splits (team_id, split_category);
CREATE INDEX idx_ss_league_cat    ON stats.situational_splits (league_id, split_category, ats_win_pct DESC);
CREATE INDEX idx_ss_roi           ON stats.situational_splits (roi_pct DESC) WHERE is_significant;
CREATE INDEX idx_ss_significant   ON stats.situational_splits (is_significant, sample_size DESC, ats_win_pct DESC)
    WHERE is_significant;
CREATE INDEX idx_ss_key           ON stats.situational_splits (split_key, team_id);
-- GIN index for keyword search in split_key
CREATE INDEX idx_ss_key_gin       ON stats.situational_splits USING GIN (split_key gin_trgm_ops);
