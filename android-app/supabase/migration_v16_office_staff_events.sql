-- ===========================================================================
-- Karur SDO Directory — migration v16: office working-hours edits, staff
-- add/exit overlay, and dashboard events/announcements.
-- Run ONCE in the Supabase SQL Editor. Required for the Android app's Office
-- Management working-hours editing, manual staff add / exit details, and the
-- dashboard Events & Announcements banner. Re-runnable.
-- ===========================================================================

-- ---- Office working days/hours overrides -----------------------------------
-- Any user's edit to an office's working days/hours; pulled by every device
-- (last-write-wins) and applied on top of the imported office master.
create table if not exists app_office_hours (
  office_id           text        primary key,
  working_days        text,
  working_hours_from  text,
  working_hours_to    text,
  updated_by          text,
  updated_at_ms       bigint      not null,
  synced_at           timestamptz not null default now()
);
grant select, insert, update, delete on app_office_hours to anon;
alter table app_office_hours enable row level security;
drop policy if exists "anon rw office hours" on app_office_hours;
create policy "anon rw office hours" on app_office_hours for all to anon using (true) with check (true);

-- ---- Staff overlay: manual additions + exit/retirement details -------------
-- added = true  → a staff member entered in the app (materialised into the directory)
-- exit_* set     → exit/retirement details for an existing staff member
-- Keyed by (type, employee_id); last-write-wins by updated_at_ms.
create table if not exists app_staff_edits (
  type           text        not null,   -- 'DS' | 'GDS'
  employee_id    text        not null,
  added          boolean     not null default false,
  name           text,
  designation    text,
  office_id      text,
  office_name    text,
  gender         text,
  date_of_birth  text,
  date_of_join   text,
  mobile         text,
  exit_date      text,
  exit_reason    text,
  status         text,
  updated_by     text,
  updated_at_ms  bigint      not null,
  synced_at      timestamptz not null default now(),
  primary key (type, employee_id)
);
grant select, insert, update, delete on app_staff_edits to anon;
alter table app_staff_edits enable row level security;
drop policy if exists "anon rw staff edits" on app_staff_edits;
create policy "anon rw staff edits" on app_staff_edits for all to anon using (true) with check (true);

-- ---- Dashboard events / announcements --------------------------------------
-- date = '' for a standing announcement (no countdown). important pins & highlights.
-- Authored by Admin/ASP/PA users; shown on every user's dashboard banner.
create table if not exists app_events (
  id             text        primary key,
  date           text        not null default '',
  title          text        not null,
  important       boolean     not null default false,
  author         text,
  created_at     bigint      not null,
  updated_at_ms  bigint      not null,
  deleted        boolean     not null default false,
  synced_at      timestamptz not null default now()
);
grant select, insert, update, delete on app_events to anon;
alter table app_events enable row level security;
drop policy if exists "anon rw events" on app_events;
create policy "anon rw events" on app_events for all to anon using (true) with check (true);
