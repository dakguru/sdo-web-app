-- ===========================================================================
--  v21 — Examination scheduling and timing refinements
--
--  duration_mode lets the administrator drive the overall time limit from the
--  per-question timer instead of typing it twice. AUTO keeps
--  total_seconds = seconds_per_question x question_count at every save, so the
--  two numbers can never drift apart; FIXED preserves the old behaviour where
--  the overall duration is entered by hand.
--
--  Existing rows keep FIXED, which is exactly what they are doing today.
-- ===========================================================================
alter table public.quiz_exams
  add column if not exists duration_mode text not null default 'FIXED';

do $$
begin
  if not exists (
    select 1 from pg_constraint where conname = 'quiz_exams_duration_mode_chk'
  ) then
    alter table public.quiz_exams
      add constraint quiz_exams_duration_mode_chk
      check (duration_mode in ('FIXED', 'AUTO'));
  end if;
end
$$;

-- Scheduled examinations are listed and gated by their opening time on every
-- dashboard load; an index keeps that ordering cheap as the archive grows.
create index if not exists quiz_exams_open_idx
  on public.quiz_exams (exam_open_ms);
