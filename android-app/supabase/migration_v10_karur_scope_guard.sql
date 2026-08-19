-- ===========================================================================
-- Karur SDO Directory — migration v10: server-side Karur Sub Division guard
-- Run ONCE in the Supabase SQL Editor. Re-runnable (idempotent).
--
-- WHY: the app serves ONLY the Karur Sub Division, but a whole-Division payroll
-- export (DS / GDS / Outsiders / Temporary Arrangements) carries the three
-- neighbouring sub-divisions (Aravakurichi, Kulittalai, Manaparai). The web app
-- clamps to Karur before upload, but the native Android app's on-device import
-- path does NOT — so a payroll uploaded from a phone re-pollutes the shared data
-- for every user. This trigger enforces the boundary at the database, so no app,
-- version or upload path can ever persist out-of-scope records.
--
-- HOW: on every write to app_datasets, DS / GDS / OUT / ARR payloads are filtered
-- to offices in the Karur scope. Scope authority = the OFFICES snapshot (itself
-- already Karur-clamped): an office is in scope when its officeId matches, or its
-- normalised officeName matches — mirroring Leave Orders/karur-scope.js inScope().
-- OFFICES / TEL and every other type are left untouched. Fails OPEN (keeps all)
-- when no OFFICES snapshot exists yet, matching the app's own behaviour.
-- ===========================================================================

-- normalise an office name the same way karur-scope.js normOffice() does:
-- lower-case, drop every non-alphanumeric, then strip a trailing S.O/B.O/H.O/SSO.
create or replace function public.karur_norm_office(name text)
returns text
language sql
immutable
as $$
  select regexp_replace(
           regexp_replace(lower(coalesce(name, '')), '[^a-z0-9]', '', 'g'),
           '(s\.?o|b\.?o|h\.?o|sso)$', ''
         )
$$;

create or replace function public.clamp_karur_scope()
returns trigger
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  off_payload  jsonb;
  scope_ids    text[];
  scope_names  text[];
  kept         jsonb;
begin
  -- Only the staff / arrangement datasets are office-scoped. Leave OFFICES, TEL
  -- and anything else exactly as written.
  if new.type not in ('DS', 'GDS', 'OUT', 'ARR') then
    return new;
  end if;

  -- The OFFICES snapshot is the scope authority (already clamped to Karur).
  select payload::jsonb into off_payload
    from public.app_datasets
   where type = 'OFFICES'
   limit 1;

  -- No office master yet → don't drop anything (fail open, like karurIds.isEmpty()).
  if off_payload is null or jsonb_typeof(off_payload) <> 'array'
     or jsonb_array_length(off_payload) = 0 then
    return new;
  end if;

  select array_agg(distinct o->>'officeId')
    into scope_ids
    from jsonb_array_elements(off_payload) o
   where coalesce(o->>'officeId', '') <> '';

  select array_agg(distinct n)
    into scope_names
    from (
      select public.karur_norm_office(o->>'officeName') as n
        from jsonb_array_elements(off_payload) o
    ) s
   where n <> '';

  -- Safety: if the scope somehow came back empty, keep everything rather than wipe.
  if coalesce(array_length(scope_ids, 1), 0) = 0
     and coalesce(array_length(scope_names, 1), 0) = 0 then
    return new;
  end if;

  -- Keep only records whose office is in Karur scope (id OR normalised name),
  -- preserving the original array order.
  select coalesce(jsonb_agg(r order by ord), '[]'::jsonb)
    into kept
    from jsonb_array_elements(new.payload::jsonb) with ordinality as t(r, ord)
   where (coalesce(r->>'officeId', '') = any(scope_ids))
      or (public.karur_norm_office(r->>'officeName') = any(scope_names));

  new.payload := kept::text;
  new.count   := jsonb_array_length(kept);
  return new;
end;
$$;

drop trigger if exists trg_clamp_karur_scope on public.app_datasets;
create trigger trg_clamp_karur_scope
  before insert or update on public.app_datasets
  for each row execute function public.clamp_karur_scope();

-- One-time self-heal: re-clamp any already-stored snapshots and bump their
-- timestamp so every device re-syncs the corrected set. Safe on clean data.
update public.app_datasets
   set uploaded_at_ms = (extract(epoch from now()) * 1000)::bigint
 where type in ('DS', 'GDS', 'OUT', 'ARR');
