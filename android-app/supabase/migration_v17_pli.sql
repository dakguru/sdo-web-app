-- migration_v17_pli.sql
-- PLI / RPLI Mega Mela — premium procurement tracker (Karur Sub Division web app).
-- Run this once in the Supabase SQL editor (project hfzbvqpxraeoqmvhtjto).
--
-- Public page /pli (no login). Karur Sub Division staff open the shared link,
-- pick their office (HO / SO -> BO) and their name from the existing staff
-- datasets, then log their procurement for the Mega Mela held on 10-09-2026:
--   * number of policies
--   * total sum assured
--   * total premium procured
-- Staff may revise their own figures any number of times (upsert on employee_id).
-- Everyone can view the live leaderboard (highest premium first) from the link.
--
-- RLS mirrors the rest of this app's open posture (anon key, self-service page):
-- the anon role may read and write. Data is Karur Sub Division only.

create table if not exists public.app_pli (
  employee_id      text primary key,             -- staff identity — upsert target
  employee_name    text not null,
  designation      text,
  category         text,                         -- DS | GDS
  office_id        text,                         -- actual posting office id (SO / BO / HO)
  office_name      text,                         -- actual posting office name
  parent_office    text,                         -- the HO / SO the staff was reached through
  bo_name          text,                         -- branch office name, if posted at a BO
  num_policies     integer not null default 0,
  sum_assured      numeric  not null default 0,
  premium          numeric  not null default 0,
  updated_by       text,
  updated_at_ms    bigint
);

create index if not exists app_pli_premium_idx on public.app_pli (premium desc);
create index if not exists app_pli_office_idx  on public.app_pli (parent_office);

alter table public.app_pli enable row level security;

drop policy if exists app_pli_all on public.app_pli;
create policy app_pli_all on public.app_pli
  for all to anon, authenticated
  using (true) with check (true);
