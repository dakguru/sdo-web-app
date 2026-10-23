-- ===========================================================================
--  v24 — Naming the employees who may sit a particular examination
--
--  Eligibility so far was described by rule: an examination could be limited
--  to a list of offices or designations, and anyone matching could sit it.
--  That is the right instrument for a divisional quiz and the wrong one for a
--  test given to a named group — a Postman Trainee batch, the staff of one
--  office, the twelve people who attended a training. For those the list IS
--  the eligibility, and there is no rule that describes it.
--
--  So an examination may now name its candidates outright.
--
--    allotment_mode = 'ALL'       open to everyone the office and designation
--                                 rules already allow — today's behaviour
--    allotment_mode = 'SELECTED'  open ONLY to the employees listed in
--                                 quiz_exam_allotments for that examination
--
--  The mode is stored explicitly rather than inferred from whether the list
--  happens to be empty, and that is deliberate. An administrator who switches
--  an examination to SELECTED and has not yet added anyone means "nobody
--  yet", not "everybody" — inferring it from an empty table would throw the
--  examination open at exactly the moment it was being restricted, which is
--  the one failure this feature must not have.
--
--  SAFETY — this migration adds one column with a default of 'ALL' and one
--  new table. It writes no existing row and deletes nothing. Every
--  examination that exists today keeps precisely the eligibility it has now,
--  because 'ALL' is what it has always done.
--
--  Idempotent: `if not exists` throughout, safe to re-run.
--
--  Run in the Supabase SQL editor for project hfzbvqpxraeoqmvhtjto, after
--  migration_v23_quiz_exam_questions.sql.
-- ===========================================================================

-- ─────────────────────────────────────────────────────────────
--  1. How an examination decides who may sit it
-- ─────────────────────────────────────────────────────────────
alter table public.quiz_exams
  add column if not exists allotment_mode text not null default 'ALL';

do $$
begin
  if not exists (
    select 1 from pg_constraint where conname = 'quiz_exams_allotment_chk'
  ) then
    alter table public.quiz_exams
      add constraint quiz_exams_allotment_chk
      check (allotment_mode in ('ALL','SELECTED'));
  end if;
end $$;

-- ─────────────────────────────────────────────────────────────
--  2. The named candidates
--
--  Keyed by employee_id rather than by registration, because an examination
--  is allotted before anybody has registered for it — that is the whole
--  point. The reference is to the employee directory, so an employee removed
--  from the division takes their allotments with them.
--
--  The primary key (exam_id, employee_id) makes a double allotment
--  impossible, so adding the same list twice is harmless.
-- ─────────────────────────────────────────────────────────────
create table if not exists public.quiz_exam_allotments (
  exam_id        uuid not null references public.quiz_exams(id)     on delete cascade,
  employee_id    text not null references public.quiz_employees(employee_id) on delete cascade,
  allotted_by    text,
  allotted_at_ms bigint not null,
  remarks        text,
  primary key (exam_id, employee_id)
);

-- The two lookups the module makes: "who is allotted this examination"
-- (the administrator's list) and "which examinations am I allotted"
-- (the participant's dashboard, on every load).
create index if not exists quiz_exam_allotments_exam_idx
  on public.quiz_exam_allotments (exam_id);
create index if not exists quiz_exam_allotments_emp_idx
  on public.quiz_exam_allotments (employee_id);

-- ─────────────────────────────────────────────────────────────
--  3. Security posture matches every other quiz table
--
--  RLS on, no permissive policy, every privilege revoked from anon and
--  authenticated. Who may sit an examination is decided by the API under the
--  service role; a participant must never be able to read the candidate list,
--  let alone add themselves to it.
-- ─────────────────────────────────────────────────────────────
alter table public.quiz_exam_allotments enable row level security;
revoke all on public.quiz_exam_allotments from anon, authenticated;
revoke all on public.quiz_exams           from anon, authenticated;
