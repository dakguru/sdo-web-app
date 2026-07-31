-- ===========================================================================
-- Karur SDO Directory — migration v9: chat message reactions
-- Run ONCE in the Supabase SQL Editor. REQUIRED for app v2.8.0+. Re-runnable.
--
-- Adds:
--   app_chat_reactions — one row per (message, user) holding that user's current
--                        emoji reaction. emoji = '' means the reaction was removed
--                        (kept as a tombstone so removals sync last-write-wins).
-- ===========================================================================

create table if not exists app_chat_reactions (
  message_id    text   not null,
  username      text   not null,
  emoji         text   not null default '',
  updated_at_ms bigint not null,
  synced_at     timestamptz not null default now(),
  primary key (message_id, username)
);
grant select, insert, update, delete on app_chat_reactions to anon;
alter table app_chat_reactions enable row level security;
drop policy if exists "anon rw chat_reactions" on app_chat_reactions;
create policy "anon rw chat_reactions" on app_chat_reactions for all to anon using (true) with check (true);
