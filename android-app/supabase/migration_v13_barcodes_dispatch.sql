-- migration_v13_barcodes_dispatch.sql
-- Address Labels module — barcode-serial inventory + dispatch register.
-- Karur Sub Division web app. Run this ONCE in the Supabase SQL editor
-- (project hfzbvqpxraeoqmvhtjto).
--
-- Feature 1 — Barcode inventory (app_barcode_serials)
--   India Post EMS/Speed Post barcode sticker sheets carry 36 pre-printed S10
--   article numbers (e.g. ET909871256IN … ET909871291IN). The Address Labels
--   page registers a sheet by its first serial; the app expands it to 36
--   sequential serials and stores ONE row per serial so the operator can:
--     * see the full list of serials (grouped by sheet),
--     * pick which serial to start printing from,
--     * mark any serial Used / free,
--   and — most importantly — never has to re-type the number: the next free
--   serial is remembered across devices (web + Android share this DB).
--
-- Feature 2 — Dispatch register (app_dispatch_register)
--   Each "Generate print-ready PDF" run (with barcodes on) writes ONE row per
--   label: date of despatch, barcode number, addressee, pincode, mobile. The
--   register is viewable in-app and exportable to Excel / PDF for ready
--   reference.
--
-- RLS mirrors the rest of this app (anon key, behind the client-side login):
-- the anon role may read and write. Data is Karur Sub Division only.

-- ── Barcode serial inventory ────────────────────────────────────────────────
create table if not exists public.app_barcode_serials (
  serial         text primary key,             -- full article no, e.g. "ET909871256IN"
  prefix         text not null,                -- "ET"
  seq            bigint not null,              -- 8-digit serial as a number, for ordering
  suffix         text not null,                -- "IN"
  sheet_id       text not null,                -- first serial of this sheet of 36 (groups a sheet)
  sheet_pos      integer not null default 0,   -- 0..35 position within the sheet
  status         text not null default 'available',  -- 'available' | 'used'
  note           text,                          -- addressee / reason it was used
  used_at_ms     bigint,
  updated_by     text,
  created_at_ms  bigint
);

create index if not exists app_barcode_serials_seq_idx    on public.app_barcode_serials (seq);
create index if not exists app_barcode_serials_sheet_idx  on public.app_barcode_serials (sheet_id);
create index if not exists app_barcode_serials_status_idx on public.app_barcode_serials (status);

alter table public.app_barcode_serials enable row level security;
drop policy if exists app_barcode_serials_all on public.app_barcode_serials;
create policy app_barcode_serials_all on public.app_barcode_serials
  for all to anon, authenticated
  using (true) with check (true);

-- ── Dispatch register ───────────────────────────────────────────────────────
create table if not exists public.app_dispatch_register (
  id             text primary key,             -- uid per label row
  batch_id       text not null,                -- one "Generate PDF" run = one batch
  dispatch_date  text not null,                -- YYYY-MM-DD (date of despatch)
  barcode_no     text,                         -- S10 article number, if barcodes were on
  addressee      text not null,                -- name of the addressee / office
  pincode        text,
  mobile         text,                          -- if available
  office         text,                          -- extra context (address line / a/w office)
  created_by     text,
  created_at_ms  bigint
);

create index if not exists app_dispatch_register_date_idx  on public.app_dispatch_register (dispatch_date);
create index if not exists app_dispatch_register_batch_idx on public.app_dispatch_register (batch_id);
create index if not exists app_dispatch_register_bc_idx    on public.app_dispatch_register (barcode_no);

alter table public.app_dispatch_register enable row level security;
drop policy if exists app_dispatch_register_all on public.app_dispatch_register;
create policy app_dispatch_register_all on public.app_dispatch_register
  for all to anon, authenticated
  using (true) with check (true);
