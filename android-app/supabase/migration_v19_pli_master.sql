-- migration_v19_pli_master.sql
-- Cent Percent Verification (CPV) — DIVISION-WIDE MASTER POLICY POOL + per-BO EXTRA set.
-- Run this once in the Supabase SQL editor (project hfzbvqpxraeoqmvhtjto),
-- AFTER migration_v12_cpv.sql and migration_v15_cpv_verification.sql.
--
-- Context
-- -------
-- The per-office PLI/RPLI verification lists live in app_cpv (one JSON blob per
-- office+scheme). Separately, the division holds a single MASTER pool of *all*
-- policies (>1 lakh rows) that is NOT tied to any one Branch Office and must never
-- be counted in a BO's verification totals. It is a reference pool: when a policy
-- number searched inside a BO list is not found there, the officer can look it up
-- in this master, pull its details, and attach it to the BO as an EXTRA policy to
-- verify / unverify — kept apart from the BO's official (app_cpv) list.
--
--   app_pli_master  : the flat all-policies pool (one row per policy). Re-uploading
--                     can either merge (upsert on policy) or fully replace the pool.
--   app_cpv_extra   : policies pulled from the master into a specific BO for
--                     verification. Separate from app_cpv.records and from
--                     app_cpv_verification, so a BO's official count is untouched.
--
-- RLS mirrors the rest of this app's open posture (anon key, behind the client-side
-- login): the anon role may read and write. Data is Karur Sub Division only.

-- ─────────────────────────────────────────────────────────────
--  Master pool — every policy in the division (reference only)
-- ─────────────────────────────────────────────────────────────
create table if not exists public.app_pli_master (
  policy          text primary key,             -- policy number as stored (may be zero-padded)
  policy_norm     text,                          -- leading zeros stripped — for zero-agnostic search
  name            text,                          -- insured name
  address         text,
  doe             text,                          -- date of entry, raw (dd-mm-yyyy as in the sheet)
  sum_assured     numeric,
  premium         numeric,
  paid_to         text,                          -- "Paid To Date", raw
  months_paid     integer,
  serial          integer,                       -- serial no from the source sheet
  uploaded_by     text,
  uploaded_at_ms  bigint
);

create index if not exists app_pli_master_norm_idx on public.app_pli_master (policy_norm);
create index if not exists app_pli_master_name_idx on public.app_pli_master (name);

alter table public.app_pli_master enable row level security;

drop policy if exists app_pli_master_all on public.app_pli_master;
create policy app_pli_master_all on public.app_pli_master
  for all to anon, authenticated
  using (true) with check (true);

-- ─────────────────────────────────────────────────────────────
--  Extra policies attached to a BO from the master pool
-- ─────────────────────────────────────────────────────────────
create table if not exists public.app_cpv_extra (
  office_key      text not null,                 -- FK-in-spirit to app_cpv.office_key (the BO batch)
  policy          text not null,                 -- policy number (verification key within the BO)
  name            text,
  address         text,
  doe             text,
  sum_assured     numeric,
  premium         numeric,
  paid_to         text,
  months_paid     integer,
  verified        boolean not null default false,
  remarks         text,
  verified_by     text,
  verified_at_ms  bigint,
  added_by        text,
  added_at_ms     bigint,
  primary key (office_key, policy)
);

create index if not exists app_cpv_extra_office_idx on public.app_cpv_extra (office_key);

-- Cascade-delete a BO's extras when its parent batch is removed (best-effort).
do $$
begin
  if exists (select 1 from information_schema.tables
             where table_schema = 'public' and table_name = 'app_cpv')
     and not exists (select 1 from information_schema.table_constraints
             where constraint_schema = 'public'
               and constraint_name = 'app_cpv_extra_office_key_fkey') then
    alter table public.app_cpv_extra
      add constraint app_cpv_extra_office_key_fkey
      foreign key (office_key) references public.app_cpv (office_key) on delete cascade;
  end if;
end $$;

alter table public.app_cpv_extra enable row level security;

drop policy if exists app_cpv_extra_all on public.app_cpv_extra;
create policy app_cpv_extra_all on public.app_cpv_extra
  for all to anon, authenticated
  using (true) with check (true);
