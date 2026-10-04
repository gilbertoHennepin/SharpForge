-- =============================================================================
-- SharpForge :: 01_core_reference_tables.sql
-- Reference/lookup tables: leagues, teams, venues, seasons
-- =============================================================================

-- ---------------------------------------------------------------------------
-- core.leagues
-- ---------------------------------------------------------------------------
CREATE TABLE core.leagues (
    league_id       SMALLSERIAL     PRIMARY KEY,
    sport           core.sport_type NOT NULL,
    league_name     VARCHAR(60)     NOT NULL,
    abbreviation    VARCHAR(10)     NOT NULL UNIQUE,
    country         VARCHAR(50)     NOT NULL DEFAULT 'USA',
    founded_year    SMALLINT,
    active          BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

-- ---------------------------------------------------------------------------
-- core.seasons
-- ---------------------------------------------------------------------------
CREATE TABLE core.seasons (
    season_id       SMALLSERIAL PRIMARY KEY,
    league_id       SMALLINT    NOT NULL REFERENCES core.leagues(league_id),
    season_label    VARCHAR(20) NOT NULL,        -- e.g. '2023-24', '2024'
    season_year     SMALLINT    NOT NULL,
    start_date      DATE        NOT NULL,
    end_date        DATE        NOT NULL,
    playoff_start   DATE,
    is_active       BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_season UNIQUE (league_id, season_year, season_label)
);

-- ---------------------------------------------------------------------------
-- core.conferences
-- ---------------------------------------------------------------------------
CREATE TABLE core.conferences (
    conference_id   SMALLSERIAL PRIMARY KEY,
    league_id       SMALLINT    NOT NULL REFERENCES core.leagues(league_id),
    conference_name VARCHAR(60) NOT NULL,
    abbreviation    VARCHAR(10),
    division        VARCHAR(40),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ---------------------------------------------------------------------------
-- core.venues
-- ---------------------------------------------------------------------------
CREATE TABLE core.venues (
    venue_id        SERIAL      PRIMARY KEY,
    venue_name      VARCHAR(120) NOT NULL,
    city            VARCHAR(80)  NOT NULL,
    state_province  VARCHAR(60),
    country         CHAR(3)      NOT NULL DEFAULT 'USA',
    latitude        NUMERIC(9,6),
    longitude       NUMERIC(9,6),
    altitude_ft     SMALLINT,                    -- elevation above sea level
    capacity        INTEGER,
    surface         core.surface_type,
    is_dome         BOOLEAN      NOT NULL DEFAULT FALSE,
    is_retractable  BOOLEAN      NOT NULL DEFAULT FALSE,
    opened_year     SMALLINT,
    timezone        VARCHAR(50)  NOT NULL DEFAULT 'America/Chicago',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_venues_geo ON core.venues (latitude, longitude);
CREATE INDEX idx_venues_dome ON core.venues (is_dome, is_retractable);

-- ---------------------------------------------------------------------------
-- core.teams
-- ---------------------------------------------------------------------------
CREATE TABLE core.teams (
    team_id         SERIAL          PRIMARY KEY,
    league_id       SMALLINT        NOT NULL REFERENCES core.leagues(league_id),
    conference_id   SMALLINT        REFERENCES core.conferences(conference_id),
    home_venue_id   INTEGER         REFERENCES core.venues(venue_id),
    team_name       VARCHAR(80)     NOT NULL,
    nickname        VARCHAR(40),
    abbreviation    VARCHAR(10)     NOT NULL,
    city            VARCHAR(80),
    primary_color   CHAR(7),                     -- hex color
    secondary_color CHAR(7),
    active          BOOLEAN         NOT NULL DEFAULT TRUE,
    founded_year    SMALLINT,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_team_league_abbrev UNIQUE (league_id, abbreviation)
);

CREATE INDEX idx_teams_league ON core.teams (league_id);
CREATE INDEX idx_teams_conference ON core.teams (conference_id);

-- ---------------------------------------------------------------------------
-- core.games  (the central fact table)
-- ---------------------------------------------------------------------------
CREATE TABLE core.games (
    game_id             UUID            PRIMARY KEY DEFAULT uuid_generate_v4(),
    league_id           SMALLINT        NOT NULL REFERENCES core.leagues(league_id),
    season_id           SMALLINT        NOT NULL REFERENCES core.seasons(season_id),
    home_team_id        INTEGER         NOT NULL REFERENCES core.teams(team_id),
    away_team_id        INTEGER         NOT NULL REFERENCES core.teams(team_id),
    venue_id            INTEGER         REFERENCES core.venues(venue_id),
    scheduled_at        TIMESTAMPTZ     NOT NULL,               -- tip-off / kickoff (UTC)
    actual_start_at     TIMESTAMPTZ,
    actual_end_at       TIMESTAMPTZ,
    status              core.game_status NOT NULL DEFAULT 'SCHEDULED',
    week_number         SMALLINT,                               -- NFL / NCAAF week
    game_number         SMALLINT,                               -- MLB / NBA game number in season
    series_game_number  SMALLINT,                               -- playoff series game #
    is_neutral_site     BOOLEAN         NOT NULL DEFAULT FALSE,
    is_divisional       BOOLEAN         NOT NULL DEFAULT FALSE,
    is_conference       BOOLEAN         NOT NULL DEFAULT FALSE,
    is_playoff          BOOLEAN         NOT NULL DEFAULT FALSE,
    broadcast_network   VARCHAR(20),
    attendance          INTEGER,
    -- Final scores
    home_score          SMALLINT,
    away_score          SMALLINT,
    home_score_q1       SMALLINT,
    away_score_q1       SMALLINT,
    home_score_q2       SMALLINT,
    away_score_q2       SMALLINT,
    home_score_q3       SMALLINT,
    away_score_q3       SMALLINT,
    home_score_q4       SMALLINT,
    away_score_q4       SMALLINT,
    overtime_periods    SMALLINT        NOT NULL DEFAULT 0,
    -- Derived quickly from score
    total_score         SMALLINT GENERATED ALWAYS AS (home_score + away_score) STORED,
    score_diff          SMALLINT GENERATED ALWAYS AS (home_score - away_score) STORED,
    home_win            BOOLEAN GENERATED ALWAYS AS (home_score > away_score) STORED,
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_different_teams CHECK (home_team_id <> away_team_id),
    CONSTRAINT chk_scores_non_negative CHECK (
        (home_score IS NULL OR home_score >= 0) AND
        (away_score IS NULL OR away_score >= 0)
    )
);

-- Primary access patterns
CREATE INDEX idx_games_scheduled      ON core.games (scheduled_at DESC);
CREATE INDEX idx_games_league_season  ON core.games (league_id, season_id, scheduled_at DESC);
CREATE INDEX idx_games_home_team      ON core.games (home_team_id, scheduled_at DESC);
CREATE INDEX idx_games_away_team      ON core.games (away_team_id, scheduled_at DESC);
CREATE INDEX idx_games_status         ON core.games (status) WHERE status <> 'FINAL';
CREATE INDEX idx_games_venue          ON core.games (venue_id);
CREATE INDEX idx_games_playoff        ON core.games (is_playoff, league_id, season_id) WHERE is_playoff;
-- Composite: backtesting queries that filter by team + date range
CREATE INDEX idx_games_home_sched_bt  ON core.games (home_team_id, scheduled_at DESC, home_score, away_score);
CREATE INDEX idx_games_away_sched_bt  ON core.games (away_team_id, scheduled_at DESC, home_score, away_score);
-- Partial: only completed games (most analytics queries)
CREATE INDEX idx_games_final          ON core.games (league_id, season_id, scheduled_at DESC)
    WHERE status = 'FINAL';
