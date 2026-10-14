-- ===========================================================================
--  v22 — Staff Category and Unit / Sub Division
--
--  National Postal Week 2026 prizes are awarded in TWO separate categories,
--  Departmental and GDS, with the top three in each. For that to be possible
--  the category has to be carried all the way from the divisional roster to
--  the result sheet, and the division's internal unit (Sub Division / Head
--  Office / Divisional Office) has to be recorded alongside it so that
--  registration can be monitored unit-wise.
--
--  SAFETY — this migration adds columns and backfills them. It changes no
--  existing value other than normalising the free-text category the roster
--  already carried, and it deletes nothing. Every registration, attempt,
--  answer and result that exists today survives untouched; the new columns
--  simply start out populated instead of empty.
--
--  Idempotent: `add column if not exists` throughout, and every backfill is a
--  no-op on a second run because it only writes where the target is still null.
--
--  Run in the Supabase SQL editor for project hfzbvqpxraeoqmvhtjto, after
--  migration_v21_quiz_duration_mode.sql.
-- ===========================================================================

-- ─────────────────────────────────────────────────────────────
--  1. Employee master directory
--
--  `category` already existed but was free text and optional. `unit` is new:
--  the "Unit/ Sub Division" column of the divisional roster — e.g.
--  "Karur Sub Division", "Karur H.O", "DO Karur".
-- ─────────────────────────────────────────────────────────────
alter table public.quiz_employees
  add column if not exists unit text;

create index if not exists quiz_employees_unit_idx     on public.quiz_employees (unit);
create index if not exists quiz_employees_category_idx on public.quiz_employees (category);

-- Normalise whatever the earlier imports put in `category` onto the two codes
-- the module now uses everywhere. Anything unrecognised is left alone rather
-- than guessed at, so it shows up as "Unspecified" and can be corrected by
-- hand instead of silently landing in the wrong prize category.
update public.quiz_employees
   set category = 'GDS'
 where category is not null
   and category <> 'GDS'
   and (
     regexp_replace(lower(category), '[^a-z]', '', 'g') like '%gds%'
     or regexp_replace(lower(category), '[^a-z]', '', 'g') like '%gramindaksevak%'
     or regexp_replace(lower(category), '[^a-z]', '', 'g') like '%extradepartmental%'
   );

update public.quiz_employees
   set category = 'DEPARTMENTAL'
 where category is not null
   and category <> 'DEPARTMENTAL'
   and (
     regexp_replace(lower(category), '[^a-z]', '', 'g') like '%departmental%'
     or regexp_replace(lower(category), '[^a-z]', '', 'g') in ('ds', 'dept', 'deptl', 'regular')
   );

-- ─────────────────────────────────────────────────────────────
--  2. Registrations
--
--  A registration keeps its OWN snapshot of name, designation and office,
--  taken at the moment the employee registered, so that a later correction to
--  the directory can never rewrite examination history. Category and unit
--  follow exactly the same rule and for exactly the same reason: once a prize
--  has been awarded in a category, that category must stay on the record.
-- ─────────────────────────────────────────────────────────────
alter table public.quiz_registrations
  add column if not exists category text,
  add column if not exists unit     text;

create index if not exists quiz_registrations_category_idx on public.quiz_registrations (category);
create index if not exists quiz_registrations_unit_idx     on public.quiz_registrations (unit);

-- Backfill the registrations that already exist, from the directory record
-- they were created against. `is null` guards both halves: a registration that
-- already carries a category is never overwritten, so re-running this file
-- after the administrator has corrected someone by hand is harmless.
update public.quiz_registrations r
   set category = e.category
  from public.quiz_employees e
 where r.employee_id = e.employee_id
   and r.category is null
   and e.category is not null;

update public.quiz_registrations r
   set unit = e.unit
  from public.quiz_employees e
 where r.employee_id = e.employee_id
   and r.unit is null
   and e.unit is not null;

-- ─────────────────────────────────────────────────────────────
--  3. Results
--
--  The leaderboard is published per category, so the category is denormalised
--  onto the result row as well. Ranking a few hundred rows by joining back to
--  the registration would work; carrying it here means a published result sheet
--  stays internally consistent even if a registration is later corrected.
-- ─────────────────────────────────────────────────────────────
alter table public.quiz_results
  add column if not exists category text,
  add column if not exists unit     text;

create index if not exists quiz_results_category_idx
  on public.quiz_results (exam_id, mode, category);

update public.quiz_results s
   set category = r.category
  from public.quiz_registrations r
 where s.registration_id = r.id
   and s.category is null
   and r.category is not null;

update public.quiz_results s
   set unit = r.unit
  from public.quiz_registrations r
 where s.registration_id = r.id
   and s.unit is null
   and r.unit is not null;

-- ─────────────────────────────────────────────────────────────
--  4. Security posture is unchanged
--
--  These are the same tables as before: RLS on, no permissive policy, every
--  privilege revoked from anon and authenticated. The statements below are
--  repeated only so that this file is safe to run on its own.
-- ─────────────────────────────────────────────────────────────
revoke all on public.quiz_employees     from anon, authenticated;
revoke all on public.quiz_registrations from anon, authenticated;
revoke all on public.quiz_results       from anon, authenticated;
