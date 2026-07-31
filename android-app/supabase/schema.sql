-- ===========================================================================
-- Karur SDO Directory — user-layer sync schema
-- Run ONCE in the Supabase dashboard: SQL Editor -> New query -> paste -> Run.
-- Creates the four tables the app syncs (favorites, notes, preferences, activity)
-- and locks them down with Row-Level Security.
-- ===========================================================================

create table if not exists app_favorites (
  item_type  text        not null,
  item_id    text        not null,
  label      text        not null,
  created_at bigint      not null,
  deleted    boolean     not null default false,
  updated_at timestamptz not null default now(),
  primary key (item_type, item_id)
);

create table if not exists app_notes (
  id            text primary key,
  target_type   text        not null,
  target_id     text,
  title         text,
  body          text        not null,
  created_at    bigint      not null,
  updated_at_ms bigint      not null,
  deleted       boolean     not null default false,
  synced_at     timestamptz not null default now()
);

create table if not exists app_prefs (
  key           text primary key,
  value         text        not null,
  updated_at_ms bigint      not null,
  synced_at     timestamptz not null default now()
);

create table if not exists app_activity (
  id          text primary key,
  action      text        not null,
  target_type text,
  target_id   text,
  summary     text        not null,
  created_at  bigint      not null,
  synced_at   timestamptz not null default now()
);

-- --- Access control -------------------------------------------------------
-- The app signs in with the anon (public) API key only — a single shared login —
-- so we grant the `anon` role read/write on JUST these four tables and turn on
-- Row-Level Security. See README.md ("Security") before going to production;
-- for stronger control, switch to a Supabase Auth shared account + the
-- `authenticated` role instead of `anon`.

grant select, insert, update, delete
  on app_favorites, app_notes, app_prefs, app_activity
  to anon;

alter table app_favorites enable row level security;
alter table app_notes     enable row level security;
alter table app_prefs     enable row level security;
alter table app_activity  enable row level security;

create policy "anon rw favorites" on app_favorites for all to anon using (true) with check (true);
create policy "anon rw notes"     on app_notes     for all to anon using (true) with check (true);
create policy "anon rw prefs"     on app_prefs     for all to anon using (true) with check (true);
create policy "anon rw activity"  on app_activity  for all to anon using (true) with check (true);
