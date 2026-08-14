-- migration_v12_cpv.sql
-- Cent Percent Verification (CPV) module — Karur Sub Division web app.
-- Run this once in the Supabase SQL editor (project hfzbvqpxraeoqmvhtjto).
--
-- Storage for cleaned "Last Balance Report" account lists. The user uploads a
-- messy CSV/XLSX per office + scheme (SB, RD, SSA, TD 1/2/3/5 yr); the web app
-- cleans it and stores ONE row per office+scheme batch here:
--   * office/sol/branch metadata + scheme
--   * total accounts and a status-wise count map (for the dashboard, read cheaply)
--   * the full cleaned account records as JSON (loaded only when viewing detail)
--
-- Re-uploading the same office + scheme REPLACES the batch (upsert on office_key).
--
-- RLS mirrors the rest of this app's open posture (anon key, behind the
-- client-side login): the anon role may read and write. Data is Karur Sub
-- Division only.

create table if not exists public.app_cpv (
  office_key      text primary key,             -- "<branchId|officeName>::<scheme>" — upsert identity
  office_name     text not null,                -- e.g. "EMUR KARUR COLLECTORATE S.O"
  sol_id          text,                         -- e.g. "63900701"
  branch_id       text,                         -- e.g. "F1971"
  scheme          text not null,                -- SB | RD | SSA | TD1 | TD2 | TD3 | TD5
  scheme_label    text,                         -- human label, e.g. "Savings Bank (SB)"
  total_accounts  integer not null default 0,
  status_counts   jsonb  not null default '{}'::jsonb,  -- { "Active": 210, "Dormant": 30, ... }
  records         jsonb  not null default '[]'::jsonb,   -- cleaned account rows
  source_name     text,                          -- original uploaded file name
  uploaded_by     text,
  uploaded_at_ms  bigint
);

create index if not exists app_cpv_office_idx on public.app_cpv (office_name);
create index if not exists app_cpv_scheme_idx on public.app_cpv (scheme);

alter table public.app_cpv enable row level security;

drop policy if exists app_cpv_all on public.app_cpv;
create policy app_cpv_all on public.app_cpv
  for all to anon, authenticated
  using (true) with check (true);
