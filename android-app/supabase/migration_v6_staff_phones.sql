-- ===========================================================================
-- Karur SDO Directory — migration v6: shared staff mobile-number edits
-- Run ONCE in the Supabase SQL Editor. Any user's add/update of a staff mobile
-- number is stored here and pulled by every device (last-write-wins), so the
-- change reflects to all users. Keyed by (target_type, target_id). Re-runnable.
-- ===========================================================================

create table if not exists app_staff_phones (
  target_type   text        not null,   -- 'EMPLOYEE' | 'OUTSIDER'
  target_id     text        not null,   -- employeeId or resourceId
  phone         text        not null,   -- '' = cleared
  updated_by    text,
  updated_at_ms bigint      not null,
  synced_at     timestamptz not null default now(),
  primary key (target_type, target_id)
);

grant select, insert, update, delete on app_staff_phones to anon;
alter table app_staff_phones enable row level security;
drop policy if exists "anon rw staff phones" on app_staff_phones;
create policy "anon rw staff phones" on app_staff_phones for all to anon using (true) with check (true);
