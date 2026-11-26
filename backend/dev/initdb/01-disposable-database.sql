-- Marks a database as disposable: fixtures and development accounts may be loaded into it.
-- Run once when a development or test database is created. Never run it on a real database.
create schema if not exists apv_disposable_database;
