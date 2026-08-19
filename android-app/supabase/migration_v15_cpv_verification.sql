-- migration_v15_cpv_verification.sql
-- Cent Percent Verification (CPV) — per-account VERIFICATION layer.
-- Run this once in the Supabase SQL editor (project hfzbvqpxraeoqmvhtjto),
-- AFTER migration_v12_cpv.sql (which creates public.app_cpv).
--
-- Each account in a stored "Last Balance Report" batch (app_cpv) is physically
-- verified by the Mail Overseer during the field visit. This table records that
-- act — one row per (batch, account):
--   * verified      : true once the MO has verified the account
--   * remarks        : any discrepancy noted for the account during verification
--   * verified_by / verified_at_ms : who verified & when
--
-- The account list itself stays in app_cpv.records (a JSON blob that both the web
-- app and the Android app read cheaply). Verification is split into its own row-
-- per-account table so that:
--   * single AND bulk (same customer / CIF) verification are simple upserts,
--   * the web app and the Android app (Mail Overseer, in the field) can both
--     write verifications concurrently without rewriting the whole batch blob.
--
-- office_key matches app_cpv.office_key exactly; acct is the account number as it
-- appears in that batch. Composite primary key => an upsert (merge-duplicates)
-- toggles verification / edits remarks in place.
--
-- RLS mirrors the rest of this app's open posture (anon key, behind the
-- client-side login): the anon role may read and write. Data is Karur Sub
-- Division only.

create table if not exists public.app_cpv_verification (
  office_key      text not null,                 -- FK-in-spirit to app_cpv.office_key (the batch)
  acct            text not null,                 -- account number within the batch
  verified        boolean not null default true, -- MO has physically verified this account
  remarks         text,                          -- discrepancy / note recorded during verification
  verified_by     text,                          -- MO / user who verified
  verified_at_ms  bigint,                        -- epoch millis of the verification
  primary key (office_key, acct)
);

create index if not exists app_cpv_ver_office_idx on public.app_cpv_verification (office_key);

-- Cascade-delete verifications when the parent batch is removed. Best-effort:
-- the FK is added only if app_cpv exists and the constraint is not already there.
do $$
begin
  if exists (select 1 from information_schema.tables
             where table_schema = 'public' and table_name = 'app_cpv')
     and not exists (select 1 from information_schema.table_constraints
             where constraint_schema = 'public'
               and constraint_name = 'app_cpv_verification_office_key_fkey') then
    alter table public.app_cpv_verification
      add constraint app_cpv_verification_office_key_fkey
      foreign key (office_key) references public.app_cpv (office_key) on delete cascade;
  end if;
end $$;

alter table public.app_cpv_verification enable row level security;

drop policy if exists app_cpv_verification_all on public.app_cpv_verification;
create policy app_cpv_verification_all on public.app_cpv_verification
  for all to anon, authenticated
  using (true) with check (true);
