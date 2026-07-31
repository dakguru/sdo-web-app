-- ===========================================================================
-- Karur SDO Directory — migration v7: chat message edit / delete
-- Run ONCE in the Supabase SQL Editor. Adds an edit-time and a soft-delete flag to
-- app_messages so edits and deletions propagate to every user (last-write-wins).
-- REQUIRED for chat to keep working with app v2.1.0+. Re-runnable.
-- ===========================================================================

alter table app_messages add column if not exists updated_at_ms bigint;
alter table app_messages add column if not exists deleted boolean not null default false;

-- Backfill existing rows so the edit high-water mark is consistent.
update app_messages set updated_at_ms = created_at where updated_at_ms is null;

create index if not exists idx_app_messages_updated_at on app_messages(updated_at_ms);
