-- ===========================================================================
-- Karur SDO Directory — migration v3: Mail Overseer visit sync
-- Run ONCE in the Supabase SQL Editor. Lets MO beat visit updates (and who made
-- them + when) sync across all users. Keyed by (beat, serial_no) — stable across
-- devices, unlike the local auto-increment id.
-- ===========================================================================

create table if not exists app_mo_visits (
  beat          text        not null,
  serial_no     int         not null,
  visits        text        not null,   -- comma-separated ISO dates
  updated_by    text,                   -- display name / username of the editor
  updated_at_ms bigint      not null,
  synced_at     timestamptz not null default now(),
  primary key (beat, serial_no)
);

grant select, insert, update, delete on app_mo_visits to anon;
alter table app_mo_visits enable row level security;
drop policy if exists "anon rw mo_visits" on app_mo_visits;
create policy "anon rw mo_visits" on app_mo_visits for all to anon using (true) with check (true);
