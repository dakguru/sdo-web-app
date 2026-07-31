-- ===========================================================================
-- Karur SDO Directory — migration v8: chat read-receipts, presence, dataset sync
-- Run ONCE in the Supabase SQL Editor. REQUIRED for app v2.3.0+. Re-runnable.
--
-- Adds:
--   app_chat_reads  — per-user "read up to" watermark (WhatsApp-style "Seen by …")
--   app_presence    — per-user last-seen heartbeat (Online / last seen)
--   app_datasets    — whole-set snapshots of monthly data + Temporary Arrangements,
--                     so an upload on one device reflects to every user
--   app_push_tokens — FCM device tokens (used by the push Edge Function, migration v9)
-- ===========================================================================

-- ---- Chat read-receipt watermarks -----------------------------------------
create table if not exists app_chat_reads (
  username      text   primary key,
  last_read_at  bigint not null,
  updated_at_ms bigint not null,
  synced_at     timestamptz not null default now()
);
grant select, insert, update, delete on app_chat_reads to anon;
alter table app_chat_reads enable row level security;
drop policy if exists "anon rw chat_reads" on app_chat_reads;
create policy "anon rw chat_reads" on app_chat_reads for all to anon using (true) with check (true);

-- ---- Presence heartbeats ---------------------------------------------------
create table if not exists app_presence (
  username      text   primary key,
  last_seen_at  bigint not null,
  updated_at_ms bigint not null,
  synced_at     timestamptz not null default now()
);
grant select, insert, update, delete on app_presence to anon;
alter table app_presence enable row level security;
drop policy if exists "anon rw presence" on app_presence;
create policy "anon rw presence" on app_presence for all to anon using (true) with check (true);

-- ---- Dataset snapshots (monthly data + arrangements) -----------------------
-- One row per dataset type: DS | GDS | OUT | TEL | OFFICES | ARR. payload holds
-- the parsed rows as a JSON string; a newer uploaded_at_ms replaces the local set.
create table if not exists app_datasets (
  type          text   primary key,
  payload       text   not null,
  count         int    not null default 0,
  uploaded_by   text,
  uploaded_at_ms bigint not null,
  synced_at     timestamptz not null default now()
);
grant select, insert, update, delete on app_datasets to anon;
alter table app_datasets enable row level security;
drop policy if exists "anon rw datasets" on app_datasets;
create policy "anon rw datasets" on app_datasets for all to anon using (true) with check (true);

-- ---- FCM push tokens (used by migration v9's Edge Function) ----------------
create table if not exists app_push_tokens (
  token         text   primary key,
  username      text,
  platform      text   not null default 'android',
  updated_at_ms bigint not null,
  synced_at     timestamptz not null default now()
);
grant select, insert, update, delete on app_push_tokens to anon;
alter table app_push_tokens enable row level security;
drop policy if exists "anon rw push_tokens" on app_push_tokens;
create policy "anon rw push_tokens" on app_push_tokens for all to anon using (true) with check (true);
