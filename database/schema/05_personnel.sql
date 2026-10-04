-- =============================================================================
-- SharpForge :: 05_personnel.sql
-- Players, rosters, injuries, starting lineups (QBs, pitchers, goalies),
-- officials / referees, and coaching staff
-- =============================================================================

-- ---------------------------------------------------------------------------
-- personnel.players
-- ---------------------------------------------------------------------------
CREATE TABLE personnel.players (
    player_id       UUID            PRIMARY KEY DEFAULT uuid_generate_v4(),
    league_id       SMALLINT        NOT NULL REFERENCES core.leagues(league_id),
    external_id     VARCHAR(40),                -- source system ID (ESPN, Sportradar, etc.)
    first_name      VARCHAR(60)     NOT NULL,
    last_name       VARCHAR(60)     NOT NULL,
    full_name       VARCHAR(120)    GENERATED ALWAYS AS (first_name || ' ' || last_name) STORED,
    position        VARCHAR(20),
    birth_date      DATE,
    height_in       SMALLINT,
    weight_lbs      SMALLINT,
    throws_bats     CHAR(1),                    -- L/R/S (MLB)
    active          BOOLEAN         NOT NULL DEFAULT TRUE,
    debut_date      DATE,
    retirement_date DATE,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX idx_players_ext_league ON personnel.players (league_id, external_id)
    WHERE external_id IS NOT NULL;
CREATE INDEX idx_players_name   ON personnel.players USING GIN (full_name gin_trgm_ops);
CREATE INDEX idx_players_pos    ON personnel.players (position, league_id);

-- ---------------------------------------------------------------------------
-- personnel.team_rosters  (player ↔ team history)
-- ---------------------------------------------------------------------------
CREATE TABLE personnel.team_rosters (
    roster_id       BIGSERIAL   PRIMARY KEY,
    player_id       UUID        NOT NULL REFERENCES personnel.players(player_id),
    team_id         INTEGER     NOT NULL REFERENCES core.teams(team_id),
    season_id       SMALLINT    NOT NULL REFERENCES core.seasons(season_id),
    jersey_number   SMALLINT,
    depth_chart_pos SMALLINT,               -- 1 = starter, 2 = backup, etc.
    is_starter      BOOLEAN     NOT NULL DEFAULT FALSE,
    signed_date     DATE,
    released_date   DATE,
    contract_value  BIGINT,                 -- USD
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_roster_player_team_season UNIQUE (player_id, team_id, season_id)
);

CREATE INDEX idx_roster_player  ON personnel.team_rosters (player_id, season_id);
CREATE INDEX idx_roster_team    ON personnel.team_rosters (team_id, season_id, is_starter);

-- ---------------------------------------------------------------------------
-- personnel.injuries  (injury status timeline)
-- ---------------------------------------------------------------------------
CREATE TABLE personnel.injuries (
    injury_id       BIGSERIAL               PRIMARY KEY,
    player_id       UUID                    NOT NULL REFERENCES personnel.players(player_id),
    team_id         INTEGER                 NOT NULL REFERENCES core.teams(team_id),
    season_id       SMALLINT                NOT NULL REFERENCES core.seasons(season_id),
    status          personnel.injury_status NOT NULL,
    body_part       VARCHAR(40),
    injury_type     VARCHAR(60),
    reported_at     TIMESTAMPTZ             NOT NULL,
    expected_return DATE,
    returned_at     TIMESTAMPTZ,
    games_missed    SMALLINT                NOT NULL DEFAULT 0,
    practice_status VARCHAR(30),            -- 'Full', 'Limited', 'DNP'
    notes           TEXT,
    created_at      TIMESTAMPTZ             NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ             NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_inj_player       ON personnel.injuries (player_id, reported_at DESC);
CREATE INDEX idx_inj_team_season  ON personnel.injuries (team_id, season_id, status);
CREATE INDEX idx_inj_status       ON personnel.injuries (status) WHERE status <> 'ACTIVE';

-- ---------------------------------------------------------------------------
-- personnel.game_starters  (which key player started each game)
-- Covers QBs, pitchers (starters + relievers), goalies, key position players
-- ---------------------------------------------------------------------------
CREATE TABLE personnel.game_starters (
    starter_id          BIGSERIAL   PRIMARY KEY,
    game_id             UUID        NOT NULL REFERENCES core.games(game_id) ON DELETE CASCADE,
    team_id             INTEGER     NOT NULL REFERENCES core.teams(team_id),
    player_id           UUID        NOT NULL REFERENCES personnel.players(player_id),
    position            VARCHAR(20) NOT NULL,       -- 'QB', 'SP', 'G', 'C', etc.
    is_confirmed        BOOLEAN     NOT NULL DEFAULT FALSE,
    confirmed_at        TIMESTAMPTZ,
    -- Pre-game injury/questionable note
    game_status         personnel.injury_status NOT NULL DEFAULT 'ACTIVE',
    -- Performance in this game (filled post-game)
    -- Generic stat slots (sport-specific meaning)
    stat_1              NUMERIC(8,3),   -- NFL QB: passer rating | MLB SP: ERA | NHL G: save%
    stat_2              NUMERIC(8,3),   -- NFL QB: completion% | MLB SP: K/9 | NHL G: GAA
    stat_3              NUMERIC(8,3),   -- NFL QB: yards | MLB SP: innings pitched
    stat_4              NUMERIC(8,3),   -- NFL QB: TDs | MLB SP: strikeouts
    stat_5              NUMERIC(8,3),   -- NFL QB: INTs | MLB SP: walks
    -- Win/loss credit
    won_game            BOOLEAN,
    player_notes        TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_starter_game_team_pos UNIQUE (game_id, team_id, position)
);

CREATE INDEX idx_gs_game          ON personnel.game_starters (game_id, team_id);
CREATE INDEX idx_gs_player        ON personnel.game_starters (player_id);
CREATE INDEX idx_gs_position      ON personnel.game_starters (position, game_id);
-- Composite: "which QB started + was healthy" for backtesting filters
CREATE INDEX idx_gs_pos_status    ON personnel.game_starters
    (position, game_status, player_id, game_id);
-- Composite: "starter with performance stats" for model features
CREATE INDEX idx_gs_player_stat1  ON personnel.game_starters (player_id, stat_1 DESC);

-- ---------------------------------------------------------------------------
-- personnel.officials  (referee / umpire reference)
-- ---------------------------------------------------------------------------
CREATE TABLE personnel.officials (
    official_id     SERIAL                  PRIMARY KEY,
    league_id       SMALLINT                NOT NULL REFERENCES core.leagues(league_id),
    external_id     VARCHAR(40),
    first_name      VARCHAR(60)             NOT NULL,
    last_name       VARCHAR(60)             NOT NULL,
    full_name       VARCHAR(120)            GENERATED ALWAYS AS (first_name || ' ' || last_name) STORED,
    jersey_number   SMALLINT,               -- NFL referee number
    primary_role    personnel.official_role NOT NULL,
    active          BOOLEAN                 NOT NULL DEFAULT TRUE,
    debut_date      DATE,
    created_at      TIMESTAMPTZ             NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ             NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_officials_league ON personnel.officials (league_id, active);
CREATE INDEX idx_officials_name   ON personnel.officials USING GIN (full_name gin_trgm_ops);

-- ---------------------------------------------------------------------------
-- personnel.game_officials  (which officials worked each game)
-- ---------------------------------------------------------------------------
CREATE TABLE personnel.game_officials (
    assignment_id   BIGSERIAL               PRIMARY KEY,
    game_id         UUID                    NOT NULL REFERENCES core.games(game_id) ON DELETE CASCADE,
    official_id     INTEGER                 NOT NULL REFERENCES personnel.officials(official_id),
    role            personnel.official_role NOT NULL,
    is_crew_chief   BOOLEAN                 NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ             NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_game_official_role UNIQUE (game_id, official_id, role)
);

CREATE INDEX idx_go_game          ON personnel.game_officials (game_id);
CREATE INDEX idx_go_official      ON personnel.game_officials (official_id, game_id);
CREATE INDEX idx_go_crew_chief    ON personnel.game_officials (official_id) WHERE is_crew_chief;

-- ---------------------------------------------------------------------------
-- personnel.official_tendencies  (per-official per-season aggregated metrics)
-- Pre-computed by ETL; used for backtesting filter "ref over/under tendency"
-- ---------------------------------------------------------------------------
CREATE TABLE personnel.official_tendencies (
    tendency_id             SERIAL      PRIMARY KEY,
    official_id             INTEGER     NOT NULL REFERENCES personnel.officials(official_id),
    season_id               SMALLINT    NOT NULL REFERENCES core.seasons(season_id),
    games_worked            SMALLINT    NOT NULL DEFAULT 0,
    -- NFL / NCAAF specific
    avg_penalty_flags       NUMERIC(5,2),
    avg_penalty_yards       NUMERIC(6,2),
    home_cover_rate         NUMERIC(5,4),   -- ATS cover rate when this ref works
    away_cover_rate         NUMERIC(5,4),
    avg_total_points        NUMERIC(6,2),
    over_rate               NUMERIC(5,4),   -- O/U over hit rate
    under_rate              NUMERIC(5,4),
    -- MLB specific
    avg_strike_zone_size    NUMERIC(5,2),   -- in square inches (PITCHf/x)
    avg_called_strikes      NUMERIC(5,2),
    -- NBA / NHL specific
    home_foul_rate          NUMERIC(5,2),
    away_foul_rate          NUMERIC(5,2),
    -- Computed composite signals
    is_over_official        BOOLEAN     NOT NULL DEFAULT FALSE,  -- statistically over-prone
    is_under_official       BOOLEAN     NOT NULL DEFAULT FALSE,
    is_home_friendly        BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_official_tendency UNIQUE (official_id, season_id)
);

CREATE INDEX idx_ot_official      ON personnel.official_tendencies (official_id, season_id);
CREATE INDEX idx_ot_over          ON personnel.official_tendencies (is_over_official, over_rate DESC);
CREATE INDEX idx_ot_under         ON personnel.official_tendencies (is_under_official, under_rate DESC);
CREATE INDEX idx_ot_home_friendly ON personnel.official_tendencies (is_home_friendly);
-- Composite: join on "referee + season + over tendency" in one pass
CREATE INDEX idx_ot_season_over   ON personnel.official_tendencies
    (season_id, is_over_official, over_rate DESC, avg_total_points DESC);

-- ---------------------------------------------------------------------------
-- personnel.coaching_staff  (head coaches, coordinators)
-- ---------------------------------------------------------------------------
CREATE TABLE personnel.coaching_staff (
    coach_id        SERIAL      PRIMARY KEY,
    first_name      VARCHAR(60) NOT NULL,
    last_name       VARCHAR(60) NOT NULL,
    full_name       VARCHAR(120) GENERATED ALWAYS AS (first_name || ' ' || last_name) STORED,
    role            VARCHAR(40) NOT NULL,   -- 'HC', 'OC', 'DC', 'Manager', 'Head Coach', etc.
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE personnel.team_coaches (
    assignment_id   BIGSERIAL   PRIMARY KEY,
    coach_id        INTEGER     NOT NULL REFERENCES personnel.coaching_staff(coach_id),
    team_id         INTEGER     NOT NULL REFERENCES core.teams(team_id),
    season_id       SMALLINT    NOT NULL REFERENCES core.seasons(season_id),
    role            VARCHAR(40) NOT NULL,
    start_date      DATE,
    end_date        DATE,
    is_interim      BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_team_coach_season UNIQUE (coach_id, team_id, season_id, role)
);

CREATE INDEX idx_tc_team_season ON personnel.team_coaches (team_id, season_id);
CREATE INDEX idx_tc_coach       ON personnel.team_coaches (coach_id, season_id);
