-- =============================================================================
-- SharpForge :: 00_extensions_and_types.sql
-- Extensions, schemas, and custom domain/enum types
-- =============================================================================

-- ---------------------------------------------------------------------------
-- Extensions
-- ---------------------------------------------------------------------------
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";       -- UUID generation
CREATE EXTENSION IF NOT EXISTS "pg_trgm";         -- Trigram indexes for fuzzy search
CREATE EXTENSION IF NOT EXISTS "btree_gin";       -- GIN support for scalar types
CREATE EXTENSION IF NOT EXISTS "btree_gist";      -- GiST support for exclusion constraints
CREATE EXTENSION IF NOT EXISTS "tablefunc";       -- crosstab / pivot queries
CREATE EXTENSION IF NOT EXISTS "pg_stat_statements"; -- Query performance profiling

-- ---------------------------------------------------------------------------
-- Schemas
-- ---------------------------------------------------------------------------
CREATE SCHEMA IF NOT EXISTS core;       -- Reference / lookup data
CREATE SCHEMA IF NOT EXISTS market;    -- Odds, line movement, betting markets
CREATE SCHEMA IF NOT EXISTS action;    -- Public betting, sharp money tracking
CREATE SCHEMA IF NOT EXISTS environ;   -- Environmental / situational factors
CREATE SCHEMA IF NOT EXISTS personnel; -- Rosters, officials, injuries
CREATE SCHEMA IF NOT EXISTS stats;     -- Advanced analytics & derived metrics
CREATE SCHEMA IF NOT EXISTS backtest;  -- Simulation results & strategy ledger

-- ---------------------------------------------------------------------------
-- Custom ENUM Types
-- ---------------------------------------------------------------------------

-- Sports
CREATE TYPE core.sport_type AS ENUM (
    'NFL', 'NCAAF', 'NBA', 'NCAAB', 'MLB', 'NHL',
    'MLS', 'EPL', 'UFC', 'TENNIS', 'GOLF'
);

-- Game status lifecycle
CREATE TYPE core.game_status AS ENUM (
    'SCHEDULED', 'IN_PROGRESS', 'FINAL', 'POSTPONED',
    'CANCELLED', 'SUSPENDED', 'FORFEIT'
);

-- Bet / market side
CREATE TYPE market.bet_side AS ENUM ('HOME', 'AWAY', 'OVER', 'UNDER', 'DRAW');

-- Market type
CREATE TYPE market.market_type AS ENUM (
    'SPREAD', 'MONEYLINE', 'TOTAL', 'TEAM_TOTAL',
    'FIRST_HALF', 'SECOND_HALF', 'FIRST_QUARTER',
    'FIRST_PERIOD', 'FIRST_FIVE_INNINGS', 'PROP'
);

-- Odds format
CREATE TYPE market.odds_format AS ENUM ('AMERICAN', 'DECIMAL', 'FRACTIONAL');

-- Line movement direction
CREATE TYPE market.line_move_direction AS ENUM ('UP', 'DOWN', 'FLAT', 'REVERSE');

-- Weather condition
CREATE TYPE environ.weather_condition AS ENUM (
    'CLEAR', 'PARTLY_CLOUDY', 'OVERCAST', 'LIGHT_RAIN',
    'HEAVY_RAIN', 'THUNDERSTORM', 'LIGHT_SNOW', 'HEAVY_SNOW',
    'BLIZZARD', 'FOG', 'DOME', 'INDOOR'
);

-- Wind direction (cardinal)
CREATE TYPE environ.wind_direction AS ENUM (
    'N', 'NNE', 'NE', 'ENE', 'E', 'ESE', 'SE', 'SSE',
    'S', 'SSW', 'SW', 'WSW', 'W', 'WNW', 'NW', 'NNW', 'CALM'
);

-- Surface type
CREATE TYPE core.surface_type AS ENUM (
    'GRASS', 'ARTIFICIAL_TURF', 'HARDWOOD', 'ICE',
    'CLAY', 'HARDCOURT', 'GRASS_COURT', 'DIRT', 'TURF_HYBRID'
);

-- Official role
CREATE TYPE personnel.official_role AS ENUM (
    'REFEREE', 'UMPIRE', 'LINE_JUDGE', 'BACK_JUDGE', 'FIELD_JUDGE',
    'SIDE_JUDGE', 'HEAD_LINESMAN', 'PLATE_UMPIRE', 'CREW_CHIEF',
    'VIDEO_REVIEW_OFFICIAL', 'FOURTH_OFFICIAL'
);

-- Injury status
CREATE TYPE personnel.injury_status AS ENUM (
    'ACTIVE', 'QUESTIONABLE', 'DOUBTFUL', 'OUT',
    'IR', 'PUP', 'NFI', 'SUSPENDED', 'DAY_TO_DAY'
);

-- Sharp money signal
CREATE TYPE action.sharp_signal AS ENUM (
    'NONE', 'STEAM_MOVE', 'REVERSE_LINE_MOVE', 'WISEGUY',
    'SYNDICATE', 'SQUARE', 'SHARP_FADE'
);

-- Backtest result status
CREATE TYPE backtest.result_status AS ENUM (
    'WIN', 'LOSS', 'PUSH', 'NO_ACTION', 'CANCELLED'
);
