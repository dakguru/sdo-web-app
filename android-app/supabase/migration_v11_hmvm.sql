-- migration_v11_hmvm.sql
-- High Value Memos (HVWM) module — Karur Sub Division web app.
-- Run this once in the Supabase SQL editor (project hfzbvqpxraeoqmvhtjto).
--
-- Storage for the High Value Withdrawal Memos register:
--   * app_hmvm         — one row per memo batch (office + reference + period + memo range)
--   * app_hmvm_config  — single-row config holding the one-time opening-balance seed
--
-- RLS mirrors the rest of this app's open posture (anon key, behind the client-side
-- login): the anon role may read and write. Data is Karur Sub Division only.

-- ── Register: one row per memo batch ──────────────────────────────────────
create table if not exists public.app_hmvm (
  id              uuid primary key default gen_random_uuid(),
  office_id       text not null,
  office_name     text not null,               -- short label, e.g. "Karur HO"
  office_kind     text not null default 'HO',  -- 'HO' | 'SO'
  town            text,                         -- e.g. "Karur"
  pincode         text,                         -- e.g. "639001"
  salutation      text,                         -- "The Postmaster" | "The SPM"
  date_of_receipt date not null,
  reference       text not null,                -- e.g. "No.175/SOSB/HWVM dated 22.06.2026"
  period_from     date,
  period_to       date,
  memo_from       integer not null,
  memo_to         integer not null,
  total_memos     integer not null,            -- = memo_to - memo_from + 1
  status          text not null default 'PENDING',  -- PENDING | COMPLETED | DESPATCHED
  letter_no       text,                         -- constant "No. ASP/HMVM/dlgs"
  letter_date     date,                         -- date the cover letter was generated
  dispatch_date   date,                         -- date memos returned to the office
  created_by      text,
  created_at_ms   bigint,
  updated_at_ms   bigint
);

create index if not exists app_hmvm_office_idx  on public.app_hmvm (office_id);
create index if not exists app_hmvm_receipt_idx on public.app_hmvm (date_of_receipt);
create index if not exists app_hmvm_status_idx  on public.app_hmvm (status);

alter table public.app_hmvm enable row level security;

drop policy if exists app_hmvm_all on public.app_hmvm;
create policy app_hmvm_all on public.app_hmvm
  for all to anon, authenticated
  using (true) with check (true);

-- ── Config: one-time opening-balance seed for the very first month ─────────
create table if not exists public.app_hmvm_config (
  id            integer primary key default 1,
  seed_balance  integer not null default 0,   -- memos pending before the system started
  seed_asof     date,                          -- balance is "as of the start of" this month
  updated_at_ms bigint,
  constraint app_hmvm_config_single check (id = 1)
);

alter table public.app_hmvm_config enable row level security;

drop policy if exists app_hmvm_config_all on public.app_hmvm_config;
create policy app_hmvm_config_all on public.app_hmvm_config
  for all to anon, authenticated
  using (true) with check (true);

insert into public.app_hmvm_config (id, seed_balance, seed_asof, updated_at_ms)
values (1, 0, null, extract(epoch from now()) * 1000)
on conflict (id) do nothing;
