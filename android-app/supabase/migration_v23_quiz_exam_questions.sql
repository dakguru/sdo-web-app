-- ===========================================================================
--  v23 — A question set of its own for every examination
--
--  Until now there was one question bank for the whole division, and every
--  examination drew its paper from it. That works for a single annual quiz
--  and stops working the moment there are two: a Postal Week paper and a
--  Postman Trainee paper have nothing to do with each other, yet each would
--  draw from the other's questions, and a question written for one would be
--  activated into the pool of both.
--
--  So a question may now belong to one examination. `exam_id` null keeps its
--  present meaning — the shared bank, available to any examination — and a
--  question carrying an exam_id belongs to that examination alone.
--
--  The rule the API applies on publish follows from that, and is deliberately
--  the one that needs no explanation: if an examination has questions of its
--  own, its paper is drawn only from them; if it has none, it falls back to
--  the shared bank exactly as it does today.
--
--  SAFETY — this migration adds one nullable column and two indexes. It
--  writes no existing row and deletes nothing. Every question already in the
--  bank keeps exam_id null, which is the shared bank, so every examination
--  that exists today publishes from precisely the pool it publishes from now.
--
--  `on delete set null` is chosen over cascade on purpose: deleting an
--  examination must not destroy the questions that were written for it. They
--  fall back to the shared bank, where they are visible and can be reassigned
--  or archived by hand, rather than disappearing with the examination.
--
--  Idempotent: `if not exists` throughout, and it is safe to re-run.
--
--  Run in the Supabase SQL editor for project hfzbvqpxraeoqmvhtjto, after
--  migration_v22_quiz_category_unit.sql.
-- ===========================================================================

-- ─────────────────────────────────────────────────────────────
--  1. Scope a question to one examination
-- ─────────────────────────────────────────────────────────────
alter table public.quiz_questions
  add column if not exists exam_id uuid;

-- Added separately from the column so that re-running the file cannot fail on
-- a constraint that is already there.
do $$
begin
  if not exists (
    select 1 from pg_constraint where conname = 'quiz_questions_exam_fk'
  ) then
    alter table public.quiz_questions
      add constraint quiz_questions_exam_fk
      foreign key (exam_id) references public.quiz_exams(id) on delete set null;
  end if;
end $$;

-- The two queries the module actually makes: "this examination's set", and
-- "the shared bank", both narrowed by status.
create index if not exists quiz_questions_exam_idx
  on public.quiz_questions (exam_id);
create index if not exists quiz_questions_exam_status_idx
  on public.quiz_questions (exam_id, status);

-- ─────────────────────────────────────────────────────────────
--  2. Duplicate detection is now scoped, not global
--
--  dedupe_hash flags a question that already exists. With one bank that meant
--  one question, once, anywhere. With a set per examination the same question
--  may legitimately appear in two different papers — the Post Office Act is
--  the Post Office Act — and flagging that as a duplicate would be wrong.
--  The index therefore covers (exam_id, dedupe_hash); the API compares within
--  a scope and no longer across the whole table.
--
--  Neither index is unique. A near-duplicate is reported to the administrator
--  for a decision, never rejected by the database, because an import of two
--  hundred questions must not fail on row one hundred and ninety.
-- ─────────────────────────────────────────────────────────────
create index if not exists quiz_questions_exam_dedupe_idx
  on public.quiz_questions (exam_id, dedupe_hash);

-- ─────────────────────────────────────────────────────────────
--  3. Security posture is unchanged
--
--  Same table as before: RLS on, no permissive policy, every privilege
--  revoked from anon and authenticated. The question bank holds the answer
--  key, so it is reachable only through the service role the API uses.
--  Repeated here so this file is safe to run on its own.
-- ─────────────────────────────────────────────────────────────
alter table public.quiz_questions enable row level security;
revoke all on public.quiz_questions from anon, authenticated;
