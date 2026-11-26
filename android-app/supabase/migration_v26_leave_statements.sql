-- ===========================================================================
--  v26 — Leave Orders: absentee statements kept month by month
--
--  The Leave Orders module (web, /leave-orders) reads the absentee statement
--  exported each month and turns it into leave orders split by leave type and
--  Head Office. The statement is uploaded once, twice or three times a month
--  as more leave is approved, so the page now keeps every month it has seen:
--  a later upload is merged into the month (same leave application id →
--  updated, new id → added) instead of replacing what was there.
--
--  ONE ROW PER MONTH
--
--    month          "YYYY-MM", the statement month the user saved it under
--    payload        JSON text of the whole month: every leave row uploaded so
--                   far, manual entries, leave types added by hand, and the
--                   log of uploads (file, when, by whom, rows added/updated)
--    record_count   leave rows in the month, for the month chips
--    upload_count   statements uploaded into the month
--
--  It is a table of its own, not an app_datasets row, because the Android
--  app pulls every app_datasets row on each sync; monthly leave data has no
--  use on the phone and would only grow that download every month.
--
--  The page also keeps each month in the browser (IndexedDB), so it works
--  before this migration is applied — it simply can't share months between
--  devices until it is.
-- ===========================================================================

create table if not exists public.app_leave_statements (
  month          text primary key check (month ~ '^\d{4}-\d{2}$'),
  payload        text   not null,
  record_count   int    not null default 0,
  upload_count   int    not null default 0,
  updated_by     text,
  updated_at_ms  bigint not null,
  synced_at      timestamptz not null default now()
);

alter table public.app_leave_statements enable row level security;

drop policy if exists app_leave_statements_all on public.app_leave_statements;
create policy app_leave_statements_all on public.app_leave_statements
  for all to anon, authenticated
  using (true) with check (true);

grant select, insert, update, delete on public.app_leave_statements to anon, authenticated;
