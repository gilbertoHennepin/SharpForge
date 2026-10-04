# SharpForge

> **A sports betting backtesting platform** capable of analyzing millions of data points across 400+ situational filters. Built on a highly normalized PostgreSQL schema designed for millisecond-latency backtesting queries.

---

## Database Architecture

The schema is organized into **7 PostgreSQL schemas**, each responsible for a distinct domain:

| Schema | Responsibility |
|---|---|
| `core` | Reference data: leagues, seasons, teams, venues, **games** |
| `market` | Sportsbooks, opening/closing lines, juice/vig, line movement |
| `action` | Public betting splits (ticket % vs. money %), sharp signals |
| `environ` | Weather, field conditions, altitude effects, travel fatigue |
| `personnel` | Players, injuries, game starters (QB/SP/G), officials, coaches |
| `stats` | Pythagorean %, xG/EPA, rest days, power ratings, situational splits |
| `backtest` | Strategy definitions, simulation runs, P&L ledger |

---

## Schema Files

| File | Description |
|---|---|
| `00_extensions_and_types.sql` | pg extensions, custom ENUMs |
| `01_core_reference_tables.sql` | Leagues, seasons, venues, teams, **games** (central fact table) |
| `02_historical_odds.sql` | Consensus lines, per-book lines (partitioned), line movement log |
| `03_public_betting.sql` | Betting splits (partitioned), sharp signal events, sharp report |
| `04_environmental_factors.sql` | Weather forecast/actual, field conditions, altitude, travel |
| `05_personnel.sql` | Players, rosters, injuries, game starters, officials + tendencies |
| `06_advanced_stats.sql` | Pythagorean %, xG, EPA, Elo, rest/fatigue, situational splits |
| `07_backtest_ledger.sql` | Strategies (JSONB filters), simulation runs, bet results (partitioned) |
| `08_materialized_views.sql` | `mv_game_context` — denormalized master view + 20+ composite indexes |
| `09_triggers_and_functions.sql` | auto-`updated_at`, Pythagorean fn, CLV fn, fatigue fn, rollup fn |
| `10_example_backtest_queries.sql` | 7 canonical backtesting queries with ROI summaries |

---

## Key Design Decisions

### 1. Materialized View as Backtest Engine (`stats.mv_game_context`)
The central workhorse. Collapses 8+ JOINs (games × odds × sharp report × weather × rest × starters × officials × power ratings) into **one pre-computed row per game**. This is the primary target for all backtesting filter queries.

Three **mega-composite indexes** are defined for the three most common filter patterns:
- `idx_mvc_backtest_core` — league + date + sharp signal + spread move + rest + weather
- `idx_mvc_totals_bt` — dome + weather + official + total move
- `idx_mvc_sharp_system` — signal strength + steam moves + ticket/money divergence

### 2. Partitioned Tables for Scale
Three large tables are partitioned by year to maintain query speed at millions of rows:
- `market.book_odds` — per-sportsbook line snapshots
- `market.line_movements` — timestamped line change log
- `action.betting_splits` — ticket/money % snapshots
- `backtest.bet_results` — individual bet records

### 3. Smart Index Strategy
- **Partial indexes** on `WHERE is_dome`, `WHERE is_playoff`, `WHERE is_sharp_vs_public` to minimize index size
- **GIN indexes** on JSONB strategy filters and player name trigrams
- **LATERAL joins** with index-ordered sub-selects for "most recent power rating as of game date"
- **Generated columns** stored for `spread_move`, `total_move`, `xg_diff`, `rest_advantage`, `turnover_diff` — computed once on write, never on read

### 4. Custom Types
All categorical dimensions (sport, game status, bet side, market type, weather condition, wind direction, surface, official role, injury status, sharp signal) use **PostgreSQL ENUMs** for storage efficiency and index compression.

---

## Quick Start

```bash
# Create the database
createdb sharpforge

# Install the full schema
psql -d sharpforge -f database/schema/run_all.sql

# Run example backtest queries
psql -d sharpforge -f database/schema/10_example_backtest_queries.sql
```

---

## Backtesting Query Examples

See [`10_example_backtest_queries.sql`](database/schema/10_example_backtest_queries.sql) for full implementations of:

1. **Sharp Money + CLV System** — steam moves + reverse line + public fade
2. **Extreme Weather Totals** — wind > 20 mph + temp < 35°F + precipitation
3. **Rest Advantage System** — bye week home team vs. short-week road team
4. **Referee Over Tendency** — crew chief over rate × weather suppression
5. **Fade the Public (Contrarian)** — 65%+ public tickets + reverse line move
6. **Pythagorean Regression** — lucky vs. unlucky teams poised to regress
7. **Full Multi-Factor ROI Summary** — all filters combined with ROI calculation

---

## License

MIT
