-- ===========================================================================
-- Karur SDO Directory — migration v5: Mail Overseer tour programmes
-- Run ONCE in the Supabase SQL Editor. Backs the MO programme feature: each
-- Mail Overseer (MO I / MO II) enters, per date, what their tour programme is.
-- Today's programme is shown on the Home dashboard. Re-runnable.
-- ===========================================================================

create table if not exists app_programmes (
  id            text        primary key,
  beat          text        not null,   -- 'MO_I' | 'MO_II'
  date          text        not null,   -- ISO yyyy-MM-dd
  details       text        not null,
  author        text,
  created_at    bigint      not null,
  updated_at_ms bigint      not null,
  deleted       boolean     not null default false,
  synced_at     timestamptz not null default now()
);
create index if not exists idx_app_programmes_date on app_programmes(date);
create index if not exists idx_app_programmes_beat on app_programmes(beat);

grant select, insert, update, delete on app_programmes to anon;
alter table app_programmes enable row level security;
drop policy if exists "anon rw programmes" on app_programmes;
create policy "anon rw programmes" on app_programmes for all to anon using (true) with check (true);
