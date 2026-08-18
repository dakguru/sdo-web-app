-- migration_v14_test_letters.sql
-- Test Letters (DTLR) module — Karur Sub Division web app.
-- Run this ONCE in the Supabase SQL editor (project hfzbvqpxraeoqmvhtjto).
--
-- What it stores
--   Every month the Sub Division posts "Date & Time of Letter Register" (DTLR)
--   test letters to a random 15–20 Sub / Branch Offices to test the punctual
--   dispatch and prompt delivery of mail. Each posted letter is ONE row here:
--   which office it went to, when it was posted (office / date / time), and —
--   filled in later as the folded sheets return — when it was delivered and
--   reposted at the destination. The module reads these rows back to render the
--   monthly "Result of the DTLRs" report (Excel / PDF).
--
--   The Delivery norm (D+1 / D+2 / D+3 …) is DERIVED in the app from posting →
--   delivery, excluding Sundays, so it is intentionally NOT a column here.
--
-- RLS mirrors the rest of this app (anon key, behind the client-side login):
-- the anon role may read and write. Data is Karur Sub Division only.
-- If this migration is not run, the module degrades gracefully to browser-local
-- storage (localStorage) so a single operator can still work offline.

create table if not exists public.app_test_letters (
  id              text primary key,             -- uid per letter (batch + office)
  batch_id        text not null,                -- one monthly run (e.g. "2026-08")
  batch_month     text not null,                -- YYYY-MM the batch belongs to
  batch_label     text,                         -- pretty month label, e.g. "August 2026"

  office_id       text,                         -- Karur Sub Division office id
  office_name     text not null,                -- destination office (O/o Destination)
  office_type     text,                         -- 'SO' | 'BO' | 'HO'
  pincode         text,
  aw_office       text,                         -- parent S.O for a B.O ("a/w …")

  posting_office  text not null default 'Karur HO',  -- O/o Posting
  posting_time    text not null default '1600 hours',
  posting_date    text,                         -- YYYY-MM-DD (Date of Posting)

  delivery_date   text,                         -- YYYY-MM-DD (Date of Delivery)   — filled later
  reposting_date  text,                         -- YYYY-MM-DD (Date of Reposting @ Destination)
  remarks         text,                         -- free text / "Data not received" etc.

  created_by      text,
  created_at_ms   bigint,
  updated_at_ms   bigint
);

create index if not exists app_test_letters_batch_idx  on public.app_test_letters (batch_id);
create index if not exists app_test_letters_month_idx  on public.app_test_letters (batch_month);
create index if not exists app_test_letters_office_idx on public.app_test_letters (office_id);

alter table public.app_test_letters enable row level security;
drop policy if exists app_test_letters_all on public.app_test_letters;
create policy app_test_letters_all on public.app_test_letters
  for all to anon, authenticated
  using (true) with check (true);
