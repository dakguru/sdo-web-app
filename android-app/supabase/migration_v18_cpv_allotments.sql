-- migration_v18_cpv_allotments.sql
-- Cent Percent Verification (CPV) — OFFICE → MAIL OVERSEER allotment layer.
-- Run this once in the Supabase SQL editor (project hfzbvqpxraeoqmvhtjto),
-- AFTER migration_v12_cpv.sql / migration_v15_cpv_verification.sql.
--
-- Admin / ASP / PA allot each office to one or more Mail Overseers (users whose
-- app_users.role = 'MO'). A Mail Overseer then sees — and can verify — ONLY the
-- offices allotted to them. Managers (ADMIN / ASP / IP / PA) always see every
-- office; ordinary USER accounts keep their existing full view. This table adds
-- the mapping only — it never touches app_cpv (the account lists) or
-- app_cpv_verification (the existing verified data), so nothing already verified
-- is affected.
--
-- An office is identified by its branch_id (the SO/BO code, e.g. "A1872"), which
-- is stable across the office's several scheme lists in app_cpv. Composite
-- primary key (branch_id, mo_username) => many MOs per office and many offices
-- per MO are both allowed, and an upsert (merge-duplicates) is idempotent.
--
-- RLS mirrors the rest of this app's open posture (anon key, behind the
-- client-side login): the anon role may read and write. Data is Karur Sub
-- Division only.

create table if not exists public.app_cpv_allotments (
  branch_id       text not null,                 -- office code (matches app_cpv.branch_id)
  mo_username     text not null,                 -- app_users.username of the allotted Mail Overseer
  office_name     text,                          -- display name at time of allotment
  sol_id          text,                          -- SOL id (display)
  allotted_by     text,                          -- ADMIN / ASP / PA who made the allotment
  allotted_at_ms  bigint,                        -- epoch millis of the allotment
  primary key (branch_id, mo_username)
);

create index if not exists app_cpv_allot_mo_idx     on public.app_cpv_allotments (mo_username);
create index if not exists app_cpv_allot_branch_idx on public.app_cpv_allotments (branch_id);

alter table public.app_cpv_allotments enable row level security;

drop policy if exists app_cpv_allotments_all on public.app_cpv_allotments;
create policy app_cpv_allotments_all on public.app_cpv_allotments
  for all to anon, authenticated
  using (true) with check (true);
