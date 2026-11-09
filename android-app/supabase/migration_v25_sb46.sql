-- ===========================================================================
--  v25 — SB-46 notices: the register that sits between verification and the
--        Final Report
--
--  Cent Percent Verification already knows two things about an account: that
--  it exists (app_cpv) and whether the Mail Overseer could physically verify
--  the passbook (app_cpv_verification). What it has never known is what
--  happened to the accounts he could NOT verify.
--
--  In the office those accounts become SB-46 notices. One notice goes to one
--  depositor, listing every unverified account of theirs in one scheme, and
--  travels by Speed Post under a single ET________IN article number taken in
--  order from the physical sheet of labels. Weeks later the office knows, for
--  each notice, some combination of: the acknowledgement card came back, the
--  depositor replied, or the cover came back undelivered with a reason
--  written on it. Those three facts, counted scheme by scheme, ARE the Final
--  Report of Cent Percent Verification.
--
--  Until now that was a paper register and a hand-typed report. This
--  migration gives it a table.
--
--  TWO TABLES, TWO DIFFERENT KINDS OF FACT
--
--    app_sb46         one row per NOTICE — what was sent, to whom, under
--                     which article number, and what came back. Every column
--                     here is a fact about the post.
--
--    app_sb46_report  one row per OFFICE — the handful of things the Final
--                     Report needs that no amount of tracking can produce:
--                     the letter and reference numbers, the dates the
--                     verification ran between, how the SB total splits
--                     between SBGEN and SBBAS, the discrepancy counts the
--                     Sub Divisional Head arrives at by reading the replies,
--                     and the closing certificate. Everything else in the
--                     report is derived from app_sb46 and app_cpv at the
--                     moment the document is generated, so the report can
--                     never drift from the register it describes.
--
--  WHY THE REGISTER IS PER OFFICE AND NOT PER SCHEME LIST
--
--  An office's accounts are stored one app_cpv batch per scheme, because
--  that is how the Last Balance Report arrives. The notices are not: the
--  clerk works through one sheet of Speed Post labels for the whole office,
--  so article numbers run continuously across SB, RD, SSA, TD and RPLI.
--  office_id (the branch code, falling back to the office name) is therefore
--  the identity of a register, and `seq` is the position of a notice within
--  that single run. scheme is carried on every notice because the Final
--  Report counts scheme by scheme — but it does not divide the register.
--
--  A notice groups the accounts of ONE depositor within ONE scheme, which is
--  what the Tamil SB-46 form itself assumes when it says "your RD accounts".
--  sl_from / sl_to are the serial numbers that notice covers in the printed
--  account list, so the address label, the notice and the list can always be
--  read against one another.
--
--  RE-RAISING A BATCH replaces the office's notices for the schemes in it:
--  the web app deletes those rows and inserts the new run. Tracking already
--  recorded against a notice is therefore lost when a batch is re-raised,
--  which is correct — a re-raised batch is a different set of envelopes.
--
--  RLS mirrors the rest of this app's open posture (anon key, behind the
--  client-side login): the anon role may read and write. Data is Karur Sub
--  Division only.
--
--  Run this once in the Supabase SQL editor (project hfzbvqpxraeoqmvhtjto),
--  AFTER migration_v12_cpv.sql and migration_v15_cpv_verification.sql.
--
--  Idempotent: `if not exists` throughout, safe to re-run.
-- ===========================================================================

-- ─────────────────────────────────────────────────────────────────────────
--  One row per SB-46 notice
-- ─────────────────────────────────────────────────────────────────────────
create table if not exists public.app_sb46 (
  notice_id       text primary key,              -- "<office_id>::<scheme>::<seq>" — upsert identity
  office_id       text not null,                 -- branch code (app_cpv.branch_id), or office name when the office has none
  office_name     text,                          -- display name at the time the batch was raised
  sol_id          text,
  branch_id       text,
  office_key      text,                          -- the app_cpv batch these accounts were read from
  scheme          text not null,                 -- SB | RD | SSA | TD1 | TD2 | TD3 | TD5 | PLI | RPLI
  seq             integer not null,              -- position in the office's continuous notice run (1-based)

  -- what the notice covers
  sl_from         integer,                       -- first serial number in the printed account list
  sl_to           integer,                       -- last serial number — equal to sl_from for a single account
  depositor       text,
  address         text,
  pincode         text,
  cif             text,
  accounts        jsonb not null default '[]'::jsonb,   -- [{acct, balance, date}] — the accounts on this notice

  -- the post
  article_no      text,                          -- Speed Post article number, e.g. "ET123456785IN"
  issued_on       text,                          -- ISO date (yyyy-mm-dd) the batch was issued

  -- what came back
  ack_received    boolean not null default false,
  ack_at_ms       bigint,
  reply_received  boolean not null default false,
  reply_at_ms     bigint,
  returned        boolean not null default false,
  return_reason   text,                          -- Deceased | Unclaimed | Insufficient address | No such addressee | Not known | Left
  returned_at_ms  bigint,
  remarks         text,

  created_by      text,
  created_at_ms   bigint,
  updated_by      text,
  updated_at_ms   bigint
);

create index if not exists app_sb46_office_idx  on public.app_sb46 (office_id);
create index if not exists app_sb46_scheme_idx  on public.app_sb46 (office_id, scheme);
create index if not exists app_sb46_article_idx on public.app_sb46 (article_no);

alter table public.app_sb46 enable row level security;

drop policy if exists app_sb46_all on public.app_sb46;
create policy app_sb46_all on public.app_sb46
  for all to anon, authenticated
  using (true) with check (true);

-- ─────────────────────────────────────────────────────────────────────────
--  One row per office — the Final Report's hand-entered parts
-- ─────────────────────────────────────────────────────────────────────────
create table if not exists public.app_sb46_report (
  office_id       text primary key,              -- same identity as app_sb46.office_id
  office_name     text,
  sol_id          text,
  branch_id       text,

  letter_no       text,                          -- "ASP/CPV/KGP/Dlgs/2025-26"
  letter_date     text,                          -- ISO date of the report letter
  ref_line        text,                          -- the whole "SPOs Lr. No. … dated …" reference line
  bo_name         text,                          -- the BO as it reads in the subject line, e.g. "Kodangipatti"
  bo_so           text,                          -- "Kodangipatti BO a/w Thanthonimalai SO"
  period_from     text,                          -- ISO date the verification began
  period_to       text,                          -- ISO date it was completed

  -- The SB row of the first table is printed as three lines, because an office's
  -- savings accounts are reported split between SBGEN and SBBAS. The web app
  -- pre-fills these from the office's stored SB lists; the ASP may correct them.
  sb_split        jsonb not null default '[]'::jsonb,   -- ["SBGEN:178","SBBAS:96","Total:274"]

  -- Counts no tracking can produce — they come from reading the replies.
  discrepancies   jsonb not null default '{}'::jsonb,   -- {"SB":0,"RD":0,…} accounts on which discrepancies were found
  notice_discrep  jsonb not null default '{}'::jsonb,   -- {"SB":0,"RD":0,…} accounts in which discrepancies were noticed from SB-46 replies

  cert_pre        text,                          -- certificate wording before the returned-notice count
  cert_post       text,                          -- …and after it

  updated_by      text,
  updated_at_ms   bigint
);

alter table public.app_sb46_report enable row level security;

drop policy if exists app_sb46_report_all on public.app_sb46_report;
create policy app_sb46_report_all on public.app_sb46_report
  for all to anon, authenticated
  using (true) with check (true);
