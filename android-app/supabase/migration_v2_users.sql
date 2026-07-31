-- ===========================================================================
-- Karur SDO Directory — migration v2: user accounts + note authorship
-- Run this ONCE in the Supabase SQL Editor (after the original schema.sql).
-- Adds the login-accounts table (so admin-created accounts sync across devices)
-- and an `author` column on notes (so each note records who wrote it).
-- ===========================================================================

create table if not exists app_users (
  username             text primary key,
  password_hash        text        not null,   -- salted PBKDF2 hash, never plaintext
  salt                 text        not null,
  iterations           int         not null,
  display_name         text        not null,
  role                 text        not null,    -- 'ADMIN' | 'USER'
  active               boolean     not null default true,
  must_change_password boolean     not null default false,
  created_at           bigint      not null,
  updated_at_ms        bigint      not null,
  synced_at            timestamptz not null default now()
);

grant select, insert, update, delete on app_users to anon;
alter table app_users enable row level security;
drop policy if exists "anon rw users" on app_users;
create policy "anon rw users" on app_users for all to anon using (true) with check (true);

-- Note authorship
alter table app_notes add column if not exists author text;
