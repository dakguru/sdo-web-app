-- ===========================================================================
-- Karur SDO Directory — migration v4: group chat (Message tab)
-- Run ONCE in the Supabase SQL Editor. Backs the WhatsApp-style group chat that
-- syncs every 2 seconds. Text messages only. Re-runnable.
-- ===========================================================================

create table if not exists app_messages (
  id           text        primary key,
  username     text        not null,
  display_name text        not null,
  body         text        not null,
  created_at   bigint      not null,
  synced_at    timestamptz not null default now()
);
create index if not exists idx_app_messages_created_at on app_messages(created_at);

grant select, insert, update, delete on app_messages to anon;
alter table app_messages enable row level security;
drop policy if exists "anon rw messages" on app_messages;
create policy "anon rw messages" on app_messages for all to anon using (true) with check (true);
