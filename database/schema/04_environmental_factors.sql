-- =============================================================================
-- SharpForge :: 04_environmental_factors.sql
-- Weather, temperature, wind, humidity, field/surface conditions
-- =============================================================================

-- ---------------------------------------------------------------------------
-- environ.game_weather  (pre-game forecast + actual game-time conditions)
-- One row per game (outdoor games; DOME / INDOOR games use defaults)
-- ---------------------------------------------------------------------------
CREATE TABLE environ.game_weather (
    weather_id          BIGSERIAL               PRIMARY KEY,
    game_id             UUID                    NOT NULL REFERENCES core.games(game_id) ON DELETE CASCADE,
    -- Source metadata
    data_source         VARCHAR(40),            -- e.g. 'openweathermap', 'weather.gov'
    forecast_at         TIMESTAMPTZ,            -- when forecast was pulled
    is_actual           BOOLEAN                 NOT NULL DEFAULT FALSE, -- TRUE = post-game actual
    -- Condition
    condition           environ.weather_condition NOT NULL,
    -- Temperature
    temp_f              NUMERIC(5,1),           -- Fahrenheit at game time
    temp_f_feels_like   NUMERIC(5,1),
    temp_f_low          NUMERIC(5,1),
    temp_f_high         NUMERIC(5,1),
    -- Wind
    wind_speed_mph      NUMERIC(5,1)            CHECK (wind_speed_mph >= 0),
    wind_gust_mph       NUMERIC(5,1)            CHECK (wind_gust_mph >= 0),
    wind_dir            environ.wind_direction,
    crosswind_mph       NUMERIC(5,1),           -- component perpendicular to field axis
    -- Precipitation
    precip_prob_pct     NUMERIC(5,2)            CHECK (precip_prob_pct BETWEEN 0 AND 100),
    precip_inches       NUMERIC(5,2),           -- actual / forecast accumulation
    snow_inches         NUMERIC(5,2),
    -- Humidity & Visibility
    humidity_pct        NUMERIC(5,2)            CHECK (humidity_pct BETWEEN 0 AND 100),
    visibility_miles    NUMERIC(5,2)            CHECK (visibility_miles >= 0),
    -- Atmospheric
    pressure_mb         NUMERIC(7,2),           -- barometric pressure (affects kicking/pitching)
    dew_point_f         NUMERIC(5,1),
    uv_index            NUMERIC(4,1),
    -- Composite weather impact scores (computed by ETL / ML pipeline)
    passing_impact_score    NUMERIC(4,2),       -- 0=no impact, 10=severe negative
    kicking_impact_score    NUMERIC(4,2),
    pitching_impact_score   NUMERIC(4,2),
    scoring_suppression_idx NUMERIC(4,2),       -- projected total reduction
    created_at          TIMESTAMPTZ             NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_game_weather UNIQUE (game_id, is_actual)
);

CREATE INDEX idx_gw_game              ON environ.game_weather (game_id);
CREATE INDEX idx_gw_condition         ON environ.game_weather (condition);
CREATE INDEX idx_gw_wind              ON environ.game_weather (wind_speed_mph DESC);
CREATE INDEX idx_gw_crosswind         ON environ.game_weather (crosswind_mph DESC);
CREATE INDEX idx_gw_temp              ON environ.game_weather (temp_f);
CREATE INDEX idx_gw_precip            ON environ.game_weather (precip_prob_pct DESC);
CREATE INDEX idx_gw_scoring_suppress  ON environ.game_weather (scoring_suppression_idx DESC);
-- Composite: "cold + windy + precipitation" filter (most common weather system)
CREATE INDEX idx_gw_cold_wind_precip  ON environ.game_weather
    (temp_f, wind_speed_mph, precip_prob_pct, condition);
-- Composite: impact scores for model feature joins
CREATE INDEX idx_gw_impact_scores     ON environ.game_weather
    (passing_impact_score, kicking_impact_score, scoring_suppression_idx);

-- ---------------------------------------------------------------------------
-- environ.field_conditions  (field surface quality — especially relevant in soccer/football)
-- ---------------------------------------------------------------------------
CREATE TABLE environ.field_conditions (
    condition_id        SERIAL              PRIMARY KEY,
    game_id             UUID                NOT NULL REFERENCES core.games(game_id) ON DELETE CASCADE,
    surface             core.surface_type   NOT NULL,
    surface_quality     VARCHAR(30),        -- 'Excellent', 'Good', 'Fair', 'Poor', 'Muddy', 'Icy'
    field_temp_f        NUMERIC(5,1),
    moisture_pct        NUMERIC(5,2),
    traction_rating     NUMERIC(3,1)        CHECK (traction_rating BETWEEN 0 AND 10),
    notes               TEXT,
    created_at          TIMESTAMPTZ         NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_field_condition UNIQUE (game_id)
);

CREATE INDEX idx_fc_game    ON environ.field_conditions (game_id);
CREATE INDEX idx_fc_surface ON environ.field_conditions (surface, surface_quality);

-- ---------------------------------------------------------------------------
-- environ.altitude_effects  (pre-computed per-venue altitude adjustments)
-- Used as a static join for games at high-altitude venues (e.g., Denver)
-- ---------------------------------------------------------------------------
CREATE TABLE environ.altitude_effects (
    effect_id           SERIAL      PRIMARY KEY,
    venue_id            INTEGER     NOT NULL REFERENCES core.venues(venue_id) UNIQUE,
    altitude_ft         SMALLINT    NOT NULL,
    -- Sport-specific modifiers (as % adjustment to expected stats)
    passing_yards_adj   NUMERIC(5,3),   -- e.g., +0.023 = +2.3% passing yards
    rushing_yards_adj   NUMERIC(5,3),
    field_goal_range_adj NUMERIC(5,3),  -- e.g., +0.05 = 5% extra range
    batting_avg_adj     NUMERIC(5,3),
    hr_rate_adj         NUMERIC(5,3),
    era_adj             NUMERIC(5,3),
    fatigue_multiplier  NUMERIC(4,3),   -- e.g., 1.05 = 5% more fatigue for visitors
    visitor_acclimatize_days INTEGER,   -- days needed to fully adjust
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_ae_venue     ON environ.altitude_effects (venue_id);
CREATE INDEX idx_ae_altitude  ON environ.altitude_effects (altitude_ft DESC);

-- ---------------------------------------------------------------------------
-- environ.travel_factors  (distance and timezone travel stress per team per game)
-- ---------------------------------------------------------------------------
CREATE TABLE environ.travel_factors (
    travel_id               SERIAL      PRIMARY KEY,
    game_id                 UUID        NOT NULL REFERENCES core.games(game_id) ON DELETE CASCADE,
    team_id                 INTEGER     NOT NULL REFERENCES core.teams(team_id),
    is_home_team            BOOLEAN     NOT NULL,
    -- Travel metrics
    travel_distance_mi      INTEGER,            -- miles traveled to venue
    timezone_shifts         SMALLINT,           -- net timezone hours crossed (negative = west)
    departure_at            TIMESTAMPTZ,
    arrival_at              TIMESTAMPTZ,
    hours_in_transit        NUMERIC(5,2),
    days_at_destination     SMALLINT,
    -- Derived fatigue indicators
    is_cross_country        BOOLEAN     NOT NULL DEFAULT FALSE, -- > 2000 miles
    is_international        BOOLEAN     NOT NULL DEFAULT FALSE,
    travel_fatigue_score    NUMERIC(4,2), -- composite 0-10 (higher = more fatigued)
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_travel_factor UNIQUE (game_id, team_id)
);

CREATE INDEX idx_tf_game         ON environ.travel_factors (game_id);
CREATE INDEX idx_tf_team         ON environ.travel_factors (team_id, game_id);
CREATE INDEX idx_tf_distance     ON environ.travel_factors (travel_distance_mi DESC);
CREATE INDEX idx_tf_tz_shift     ON environ.travel_factors (timezone_shifts, is_home_team);
CREATE INDEX idx_tf_fatigue      ON environ.travel_factors (travel_fatigue_score DESC);
-- Composite: "long road trip + timezone shift" filter
CREATE INDEX idx_tf_road_travel  ON environ.travel_factors
    (is_home_team, travel_distance_mi, timezone_shifts, travel_fatigue_score)
    WHERE NOT is_home_team;
