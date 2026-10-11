-- ===========================================================================
-- migration_v20_quiz.sql
-- Employee Online Quiz & Assessment Management System
-- National Postal Week 2026 — Department of Posts, Karur Division, Karur 639001
--
-- Run ONCE in the Supabase SQL editor (project hfzbvqpxraeoqmvhtjto), after
-- migration_v19_pli_master.sql.
--
-- SECURITY POSTURE — READ THIS FIRST
-- ----------------------------------
-- Every other app_* table in this project is deliberately open to the `anon`
-- role, because those pages sit behind the client-side portal login and hold no
-- secrets. An examination is different: the question bank contains the ANSWER
-- KEY. If `anon` could read quiz_questions, any participant could open the
-- browser console and fetch the correct answers straight from PostgREST.
--
-- Therefore every table below REVOKES all privileges from anon and
-- authenticated, and carries RLS with NO permissive policy. These tables are
-- reachable only through the service-role key, which lives exclusively in the
-- Vercel environment (env SUPABASE_SERVICE_ROLE_KEY) and is used only by the
-- /api/quiz-* serverless functions. Nothing in the browser can reach them.
--
-- Do NOT add an "anon rw" policy to these tables. That single line would hand
-- the answer key to every participant.
-- ===========================================================================

create extension if not exists pgcrypto;

-- ─────────────────────────────────────────────────────────────
--  1. Employee Master Directory
--     Imported from the administrator Excel roster. employee_id is TEXT so
--     leading zeros survive; the Karur Division roster uses 8-digit HRMS ids.
-- ─────────────────────────────────────────────────────────────
create table if not exists public.quiz_employees (
  employee_id     text primary key,
  employee_name   text    not null,
  designation     text    not null,
  office          text    not null,
  division        text    default 'Karur Division',
  category        text,                                  -- DS / GDS / Outsourced, optional
  active          boolean not null default true,
  import_job_id   uuid,
  created_at_ms   bigint  not null,
  updated_at_ms   bigint  not null
);
create index if not exists quiz_employees_name_idx   on public.quiz_employees (employee_name);
create index if not exists quiz_employees_office_idx on public.quiz_employees (office);
create index if not exists quiz_employees_desig_idx  on public.quiz_employees (designation);

-- ─────────────────────────────────────────────────────────────
--  2. Google Identity Mapping
--     google_sub is the stable Google subject id — never the email, which can
--     be re-assigned. provider='employee_id' rows are the admin-enabled roster
--     fallback sign-in (no Google account involved).
-- ─────────────────────────────────────────────────────────────
create table if not exists public.quiz_identities (
  id              uuid primary key default gen_random_uuid(),
  provider        text    not null default 'google',     -- 'google' | 'employee_id'
  google_sub      text,                                  -- unique when present
  email           text,
  email_verified  boolean not null default false,
  display_name    text,
  picture_url     text,
  created_at_ms   bigint  not null,
  last_login_ms   bigint
);
create unique index if not exists quiz_identities_sub_idx on public.quiz_identities (google_sub)
  where google_sub is not null;
create index if not exists quiz_identities_email_idx on public.quiz_identities (lower(email));

-- ─────────────────────────────────────────────────────────────
--  3. Quiz Registrations
--     One registration per employee, and one per identity: the two UNIQUE
--     constraints are what stop a second Google account claiming an
--     already-registered Employee ID. Name/designation/office are SNAPSHOT at
--     registration time so a later roster correction cannot rewrite history.
-- ─────────────────────────────────────────────────────────────
create table if not exists public.quiz_registrations (
  id              uuid primary key default gen_random_uuid(),
  reg_code        text    not null unique,                -- e.g. NPW2026-0137
  identity_id     uuid    not null references public.quiz_identities(id) on delete restrict,
  employee_id     text    not null references public.quiz_employees(employee_id) on delete restrict,
  employee_name   text    not null,
  designation     text    not null,
  office          text    not null,
  status          text    not null default 'PENDING',     -- PENDING|APPROVED|REJECTED|SUSPENDED|CANCELLED
  roster_verified boolean not null default false,         -- matched the uploaded master roster
  manual_entry    boolean not null default false,         -- admin-created, not yet identity-linked
  approved_by     text,
  approved_at_ms  bigint,
  remarks         text,
  created_at_ms   bigint  not null,
  updated_at_ms   bigint  not null,
  constraint quiz_reg_employee_uniq unique (employee_id),
  constraint quiz_reg_identity_uniq unique (identity_id),
  constraint quiz_reg_status_chk check (status in ('PENDING','APPROVED','REJECTED','SUSPENDED','CANCELLED'))
);
create index if not exists quiz_registrations_status_idx on public.quiz_registrations (status);
create index if not exists quiz_registrations_office_idx on public.quiz_registrations (office);

-- ─────────────────────────────────────────────────────────────
--  4. Quiz Examinations
--     All instants are epoch milliseconds (UTC). The UI renders them in IST
--     (Asia/Kolkata); the server never trusts a client clock.
-- ─────────────────────────────────────────────────────────────
create table if not exists public.quiz_exams (
  id                   uuid primary key default gen_random_uuid(),
  title                text    not null,
  description          text,
  instructions         text,
  reg_open_ms          bigint,
  reg_close_ms         bigint,
  exam_open_ms         bigint,
  exam_close_ms        bigint,
  latest_start_ms      bigint,                            -- last moment a new attempt may begin
  question_count       int     not null default 20,
  seconds_per_question int     not null default 30,
  total_seconds        int     not null default 600,
  positive_marks       numeric not null default 4,
  negative_marks       numeric not null default 1,         -- stored POSITIVE, subtracted on scoring
  unanswered_marks     numeric not null default 0,
  attempts_allowed     int     not null default 1,
  navigation           text    not null default 'ONE_WAY', -- ONE_WAY | FREE
  allow_skip           boolean not null default true,
  randomize_questions  boolean not null default true,
  randomize_options    boolean not null default true,
  question_pool        jsonb,                              -- {categories:[],difficulties:[]} selection policy
  eligible_offices     jsonb,                              -- null/[] = all
  eligible_designations jsonb,
  eligible_categories  jsonb,
  late_entry_allowed   boolean not null default true,
  on_close             text    not null default 'AUTO_SUBMIT', -- AUTO_SUBMIT | GRACE
  grace_seconds        int     not null default 0,
  result_mode          text    not null default 'MANUAL',  -- AUTO | SCHEDULED | MANUAL
  result_publish_ms    bigint,
  show_answer_key      boolean not null default false,
  rank_enabled         boolean not null default true,
  status               text    not null default 'DRAFT',
  published_at_ms      bigint,
  created_by           text,
  created_at_ms        bigint  not null,
  updated_at_ms        bigint  not null,
  constraint quiz_exams_status_chk check (status in
    ('DRAFT','SCHEDULED','REGISTRATION_OPEN','REGISTRATION_CLOSED','LIVE','COMPLETED','RESULTS_PUBLISHED','CANCELLED')),
  constraint quiz_exams_nav_chk check (navigation in ('ONE_WAY','FREE')),
  constraint quiz_exams_counts_chk check (question_count between 1 and 200)
);
create index if not exists quiz_exams_status_idx on public.quiz_exams (status);

-- ─────────────────────────────────────────────────────────────
--  5. Question Bank
--     Exactly four options; exactly one correct. dedupe_hash is a normalised
--     digest of the question text used to surface likely duplicates on import.
-- ─────────────────────────────────────────────────────────────
create table if not exists public.quiz_questions (
  id              uuid primary key default gen_random_uuid(),
  qcode           text    unique,                          -- human-facing id, e.g. Q0042
  question_text   text    not null,
  option_a        text    not null,
  option_b        text    not null,
  option_c        text    not null,
  option_d        text    not null,
  correct_option  text    not null,
  explanation     text,
  source_ref      text,                                    -- official rule / manual reference
  category        text,
  difficulty      text    default 'MEDIUM',                -- EASY | MEDIUM | HARD
  status          text    not null default 'DRAFT',        -- DRAFT | ACTIVE | ARCHIVED
  dedupe_hash     text,
  version         int     not null default 1,
  import_job_id   uuid,
  created_by      text,
  created_at_ms   bigint  not null,
  updated_by      text,
  updated_at_ms   bigint  not null,
  constraint quiz_questions_correct_chk check (correct_option in ('A','B','C','D')),
  constraint quiz_questions_status_chk  check (status in ('DRAFT','ACTIVE','ARCHIVED')),
  constraint quiz_questions_diff_chk    check (difficulty in ('EASY','MEDIUM','HARD'))
);
create index if not exists quiz_questions_status_idx   on public.quiz_questions (status);
create index if not exists quiz_questions_category_idx on public.quiz_questions (category);
create index if not exists quiz_questions_dedupe_idx   on public.quiz_questions (dedupe_hash);

-- ─────────────────────────────────────────────────────────────
--  6. Question Revisions — complete modification history
-- ─────────────────────────────────────────────────────────────
create table if not exists public.quiz_question_revisions (
  id            uuid primary key default gen_random_uuid(),
  question_id   uuid not null references public.quiz_questions(id) on delete cascade,
  version       int  not null,
  snapshot      jsonb not null,                            -- the full row as it was
  change_note   text,
  changed_by    text,
  changed_at_ms bigint not null
);
create index if not exists quiz_qrev_question_idx on public.quiz_question_revisions (question_id, version);

-- ─────────────────────────────────────────────────────────────
--  7. Published Examination Question Sets (IMMUTABLE)
--     Frozen when an exam is published. Editing the general bank afterwards
--     cannot touch a live attempt, because attempts read only from here.
-- ─────────────────────────────────────────────────────────────
create table if not exists public.quiz_exam_questions (
  exam_id       uuid not null references public.quiz_exams(id) on delete cascade,
  seq           int  not null,
  question_id   uuid not null references public.quiz_questions(id) on delete restrict,
  snapshot      jsonb not null,                            -- text, 4 options, correct_option, explanation
  frozen_at_ms  bigint not null,
  primary key (exam_id, seq)
);
create index if not exists quiz_exam_questions_q_idx on public.quiz_exam_questions (question_id);

-- ─────────────────────────────────────────────────────────────
--  8. Participant Attempts
--     Scoring policy and timing are COPIED onto the attempt at start, so a
--     later settings change cannot retroactively rescore someone.
--     deadline_ms is the single authoritative overall deadline.
-- ─────────────────────────────────────────────────────────────
create table if not exists public.quiz_attempts (
  id                   uuid primary key default gen_random_uuid(),
  exam_id              uuid not null references public.quiz_exams(id) on delete cascade,
  registration_id      uuid references public.quiz_registrations(id) on delete set null,
  employee_id          text,
  actor_label          text,                               -- admin username for TEST attempts
  mode                 text not null default 'OFFICIAL',   -- OFFICIAL | TEST
  attempt_no           int  not null default 1,
  status               text not null default 'IN_PROGRESS',
  started_ms           bigint not null,
  deadline_ms          bigint not null,
  current_seq          int  not null default 1,
  submitted_ms         bigint,
  finalized_ms         bigint,
  question_count       int  not null,
  seconds_per_question int  not null,
  positive_marks       numeric not null,
  negative_marks       numeric not null,
  unanswered_marks     numeric not null default 0,
  navigation           text not null default 'ONE_WAY',
  allow_skip           boolean not null default true,
  session_token_hash   text,                               -- detects a second concurrent session
  ip                   text,
  user_agent           text,
  constraint quiz_attempts_mode_chk   check (mode in ('OFFICIAL','TEST')),
  constraint quiz_attempts_status_chk check (status in ('IN_PROGRESS','SUBMITTED','AUTO_SUBMITTED','EXPIRED','VOID'))
);
-- One official attempt per registration per exam per attempt_no. TEST attempts
-- are excluded from this constraint so an admin may re-run dry runs freely.
create unique index if not exists quiz_attempts_official_uniq
  on public.quiz_attempts (exam_id, registration_id, attempt_no)
  where mode = 'OFFICIAL';
create index if not exists quiz_attempts_exam_idx   on public.quiz_attempts (exam_id, mode, status);
create index if not exists quiz_attempts_live_idx   on public.quiz_attempts (status, deadline_ms);

-- ─────────────────────────────────────────────────────────────
--  9. Participant Assigned Questions
--     The per-participant question order and option permutation, frozen at
--     start. A refresh or reconnect restores exactly this.
--     option_order is a 4-char permutation of ABCD: position i of the string
--     says which ORIGINAL option is shown in display slot i.
-- ─────────────────────────────────────────────────────────────
create table if not exists public.quiz_attempt_questions (
  attempt_id     uuid not null references public.quiz_attempts(id) on delete cascade,
  seq            int  not null,
  exam_seq       int  not null,                             -- row in quiz_exam_questions
  question_id    uuid not null,
  option_order   text not null,
  served_ms      bigint,
  q_deadline_ms  bigint,
  locked         boolean not null default false,
  primary key (attempt_id, seq),
  constraint quiz_aq_order_chk check (option_order ~ '^[ABCD]{4}$')
);

-- ─────────────────────────────────────────────────────────────
-- 10. Participant Answers
--     Primary key (attempt_id, seq) makes answer saving idempotent: a retried
--     submission overwrites rather than double-counting.
-- ─────────────────────────────────────────────────────────────
create table if not exists public.quiz_answers (
  attempt_id      uuid not null references public.quiz_attempts(id) on delete cascade,
  seq             int  not null,
  chosen_slot     text,                                     -- what the participant tapped: A..D as DISPLAYED
  chosen_original text,                                     -- de-permuted back to the stored option
  is_correct      boolean,
  answered_ms     bigint not null,                          -- server receipt time, never client time
  client_token    text,
  primary key (attempt_id, seq),
  constraint quiz_answers_slot_chk check (chosen_slot is null or chosen_slot in ('A','B','C','D'))
);

-- ─────────────────────────────────────────────────────────────
-- 11. Examination Results
-- ─────────────────────────────────────────────────────────────
create table if not exists public.quiz_results (
  attempt_id        uuid primary key references public.quiz_attempts(id) on delete cascade,
  exam_id           uuid not null references public.quiz_exams(id) on delete cascade,
  registration_id   uuid,
  employee_id       text,
  mode              text not null default 'OFFICIAL',
  attempted         int  not null default 0,
  correct           int  not null default 0,
  wrong             int  not null default 0,
  unanswered        int  not null default 0,
  positive_earned   numeric not null default 0,
  negative_deducted numeric not null default 0,
  score             numeric not null default 0,
  max_marks         numeric not null default 0,
  percentage        numeric not null default 0,
  elapsed_seconds   int,
  rank              int,
  published         boolean not null default false,
  withheld          boolean not null default false,
  recalc_note       text,
  computed_ms       bigint not null,
  published_ms      bigint
);
create index if not exists quiz_results_exam_idx on public.quiz_results (exam_id, mode, score desc, elapsed_seconds asc);

-- ─────────────────────────────────────────────────────────────
-- 12. Activity and Security Logs
--     Visibility changes, reconnects, expired timers, enumeration attempts.
--     Advisory only: a blur event never auto-disqualifies anyone.
-- ─────────────────────────────────────────────────────────────
create table if not exists public.quiz_events (
  id              bigserial primary key,
  attempt_id      uuid,
  registration_id uuid,
  identity_id     uuid,
  exam_id         uuid,
  type            text not null,                            -- TAB_HIDDEN | RECONNECT | AUTO_SUBMIT | ...
  severity        text not null default 'INFO',             -- INFO | NOTICE | SUSPECT
  detail          jsonb,
  ip              text,
  user_agent      text,
  created_ms      bigint not null
);
create index if not exists quiz_events_attempt_idx  on public.quiz_events (attempt_id);
create index if not exists quiz_events_severity_idx on public.quiz_events (severity, created_ms desc);

-- ─────────────────────────────────────────────────────────────
-- 13. Administrator Audit Logs
-- ─────────────────────────────────────────────────────────────
create table if not exists public.quiz_audit (
  id          bigserial primary key,
  actor       text not null,
  actor_role  text,
  action      text not null,
  entity      text,
  entity_id   text,
  before_json jsonb,
  after_json  jsonb,
  ip          text,
  created_ms  bigint not null
);
create index if not exists quiz_audit_created_idx on public.quiz_audit (created_ms desc);
create index if not exists quiz_audit_entity_idx  on public.quiz_audit (entity, entity_id);

-- ─────────────────────────────────────────────────────────────
-- 14. Quiz Settings — one row per policy key
-- ─────────────────────────────────────────────────────────────
create table if not exists public.quiz_settings (
  key           text primary key,
  value         jsonb not null,
  updated_by    text,
  updated_at_ms bigint not null
);

-- ─────────────────────────────────────────────────────────────
-- 15 + 16. Import jobs — employee directory AND question bank
-- ─────────────────────────────────────────────────────────────
create table if not exists public.quiz_import_jobs (
  id          uuid primary key default gen_random_uuid(),
  kind        text not null,                                -- EMPLOYEES | QUESTIONS
  filename    text,
  mode        text,                                         -- MERGE | REPLACE
  total_rows  int  not null default 0,
  inserted    int  not null default 0,
  updated     int  not null default 0,
  skipped     int  not null default 0,
  failed      int  not null default 0,
  issues      jsonb,
  status      text not null default 'DONE',                 -- DONE | PARTIAL | FAILED
  created_by  text,
  created_ms  bigint not null,
  constraint quiz_import_kind_chk check (kind in ('EMPLOYEES','QUESTIONS'))
);
create index if not exists quiz_import_jobs_idx on public.quiz_import_jobs (kind, created_ms desc);

-- ─────────────────────────────────────────────────────────────
-- 17. Server-side sessions
--     Only the SHA-256 of the token is stored, so a database leak does not
--     hand out live sessions. Covers both participant and admin sessions.
-- ─────────────────────────────────────────────────────────────
create table if not exists public.quiz_sessions (
  token_hash   text primary key,
  kind         text not null,                               -- PARTICIPANT | ADMIN
  identity_id  uuid,
  username     text,
  quiz_role    text,
  created_ms   bigint not null,
  expires_ms   bigint not null,
  last_seen_ms bigint,
  ip           text,
  user_agent   text,
  revoked      boolean not null default false,
  constraint quiz_sessions_kind_chk check (kind in ('PARTICIPANT','ADMIN'))
);
create index if not exists quiz_sessions_expiry_idx   on public.quiz_sessions (expires_ms);
create index if not exists quiz_sessions_identity_idx on public.quiz_sessions (identity_id);

-- ─────────────────────────────────────────────────────────────
-- 18. Quiz role grants
--     Explicit per-user quiz privileges. A portal login does NOT by itself
--     confer quiz administration; a row here (or the default role map in
--     quiz_settings) does.
-- ─────────────────────────────────────────────────────────────
create table if not exists public.quiz_admin_roles (
  username    text primary key,
  quiz_role   text not null,                                -- QUIZ_ADMIN | QUIZ_EDITOR | QUIZ_VIEWER | NONE
  granted_by  text,
  created_ms  bigint not null,
  constraint quiz_admin_roles_chk check (quiz_role in ('QUIZ_ADMIN','QUIZ_EDITOR','QUIZ_VIEWER','NONE'))
);

-- ─────────────────────────────────────────────────────────────
--  Rate limiting — per-key counters for Employee ID lookups and sign-ins,
--  so the roster cannot be enumerated 782 ids at a time.
-- ─────────────────────────────────────────────────────────────
create table if not exists public.quiz_rate_limits (
  bucket      text primary key,                             -- e.g. lookup:<identity>:<minute>
  hits        int    not null default 0,
  expires_ms  bigint not null
);
create index if not exists quiz_rate_limits_exp_idx on public.quiz_rate_limits (expires_ms);

-- ===========================================================================
--  LOCK DOWN: no browser role may touch any quiz table.
--  Access is exclusively via the service-role key held in the Vercel env.
--
--  Two belts, deliberately:
--    1. RLS is enabled and all privileges are REVOKED from anon and
--       authenticated — the two roles a browser can ever present.
--    2. service_role is granted privileges AND an explicit permissive policy.
--
--  Step 2 matters: service_role normally carries BYPASSRLS, so a policy would
--  be redundant — but if that attribute is ever absent or changed, "RLS on
--  with no policy" would lock the API out of its own tables and the whole
--  module would fail at the first request. The explicit grant makes the
--  lockdown independent of that assumption, while still denying every
--  browser-reachable role.
-- ===========================================================================
do $lockdown$
declare
  t text;
  tables text[] := array[
    'quiz_employees','quiz_identities','quiz_registrations','quiz_exams',
    'quiz_questions','quiz_question_revisions','quiz_exam_questions',
    'quiz_attempts','quiz_attempt_questions','quiz_answers','quiz_results',
    'quiz_events','quiz_audit','quiz_settings','quiz_import_jobs',
    'quiz_sessions','quiz_admin_roles','quiz_rate_limits'
  ];
begin
  foreach t in array tables loop
    execute format('alter table public.%I enable row level security', t);
    execute format('revoke all on public.%I from anon, authenticated', t);
    execute format('grant select, insert, update, delete on public.%I to service_role', t);
    execute format('drop policy if exists %I on public.%I', t || '_service_only', t);
    execute format(
      'create policy %I on public.%I for all to service_role using (true) with check (true)',
      t || '_service_only', t);
  end loop;
end
$lockdown$;

-- The two bigserial tables own sequences. Revoke them from the browser roles
-- specifically — NOT "all sequences in schema public", which would also strip
-- access for unrelated existing modules.
revoke all on sequence public.quiz_events_id_seq from anon, authenticated;
revoke all on sequence public.quiz_audit_id_seq  from anon, authenticated;
grant usage, select on sequence public.quiz_events_id_seq to service_role;
grant usage, select on sequence public.quiz_audit_id_seq  to service_role;

-- ===========================================================================
--  Seed defaults. Values are the recommended initial defaults; the
--  administrator can change any of them from /Quiz/admin/settings before an
--  examination is published.
-- ===========================================================================
insert into public.quiz_settings (key, value, updated_by, updated_at_ms) values
  ('exam_defaults', jsonb_build_object(
      'question_count', 20, 'min_questions', 15, 'max_questions', 25,
      'seconds_per_question', 30, 'total_seconds', 600,
      'positive_marks', 4, 'negative_marks', 1, 'unanswered_marks', 0,
      'attempts_allowed', 1, 'navigation', 'ONE_WAY', 'allow_skip', true,
      'randomize_questions', true, 'randomize_options', true), 'migration_v20', 0),
  ('registration', jsonb_build_object(
      'auto_approve_on_roster_match', true,
      'allow_employee_id_fallback',   false,
      'allowed_email_domains',        '[]'::jsonb,
      'reg_code_prefix',              'NPW2026'), 'migration_v20', 0),
  ('branding', jsonb_build_object(
      'event',    'National Postal Week 2026',
      'theme',    'One Postal Network, Infinite Possibilities',
      'org',      'Department of Posts',
      'division', 'Karur Division, Karur - 639001',
      'window',   '9th to 15th October 2026'), 'migration_v20', 0),
  ('role_map', jsonb_build_object(
      'ADMIN', 'QUIZ_ADMIN', 'ASP', 'QUIZ_ADMIN',
      'PA',    'QUIZ_EDITOR', 'MO',  'QUIZ_VIEWER'), 'migration_v20', 0)
on conflict (key) do nothing;

-- ===========================================================================
--  Atomic rate-limit counter.
--  Done as an RPC rather than read-then-write in the API, so two concurrent
--  requests cannot both read "4 hits" and both be allowed through a limit of 5.
--  Returns the hit count INCLUDING this call.
-- ===========================================================================
create or replace function public.quiz_rate_hit(p_bucket text, p_expires_ms bigint)
returns int language plpgsql security definer as $ratehit$
declare n int;
begin
  insert into public.quiz_rate_limits as rl (bucket, hits, expires_ms)
  values (p_bucket, 1, p_expires_ms)
  on conflict (bucket) do update
    set hits = rl.hits + 1,
        expires_ms = greatest(rl.expires_ms, excluded.expires_ms)
  returning rl.hits into n;
  return n;
end
$ratehit$;
revoke all on function public.quiz_rate_hit(text, bigint) from anon, authenticated;

-- ===========================================================================
--  Housekeeping helper — call from the admin API or a scheduled job.
-- ===========================================================================
create or replace function public.quiz_prune_expired(now_ms bigint)
returns void language sql security definer as $prune$
  delete from public.quiz_sessions    where expires_ms < now_ms;
  delete from public.quiz_rate_limits where expires_ms < now_ms;
$prune$;
revoke all on function public.quiz_prune_expired(bigint) from anon, authenticated;
