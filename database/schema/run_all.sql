-- =============================================================================
-- SharpForge :: run_all.sql
-- Execute all schema files in dependency order
-- Usage: psql -d sharpforge -f database/schema/run_all.sql
-- =============================================================================

\echo '===================================================='
\echo ' SharpForge Database Schema Installation'
\echo '===================================================='

\echo '[1/10] Extensions and Custom Types...'
\ir 00_extensions_and_types.sql

\echo '[2/10] Core Reference Tables (leagues, teams, venues, games)...'
\ir 01_core_reference_tables.sql

\echo '[3/10] Historical Odds (opening/closing lines, book odds, line movement)...'
\ir 02_historical_odds.sql

\echo '[4/10] Public Betting (splits, sharp signals, sharp reports)...'
\ir 03_public_betting.sql

\echo '[5/10] Environmental Factors (weather, field conditions, travel)...'
\ir 04_environmental_factors.sql

\echo '[6/10] Personnel (players, starters, officials, coaches)...'
\ir 05_personnel.sql

\echo '[7/10] Advanced Stats (Pythagorean, xG, rest/fatigue, power ratings)...'
\ir 06_advanced_stats.sql

\echo '[8/10] Backtest Ledger (strategies, simulation runs, bet results)...'
\ir 07_backtest_ledger.sql

\echo '[9/10] Materialized Views and composite indexes...'
\ir 08_materialized_views.sql

\echo '[10/10] Triggers and Functions...'
\ir 09_triggers_and_functions.sql

\echo '===================================================='
\echo ' Schema installation complete.'
\echo ' Run example queries from 10_example_backtest_queries.sql'
\echo '===================================================='
