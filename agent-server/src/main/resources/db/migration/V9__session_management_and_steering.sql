SET search_path TO agent_memory, public;
ALTER TABLE sessions ADD COLUMN kind text NOT NULL DEFAULT 'chat' CHECK (kind IN ('chat','eval'));
ALTER TABLE sessions ADD COLUMN archived_at timestamptz;
ALTER TABLE sessions ADD COLUMN pinned boolean NOT NULL DEFAULT false;
-- Identify historical evaluations by their actual task contract, not a user-editable title.
UPDATE sessions s SET kind='eval' WHERE EXISTS (
    SELECT 1 FROM runs r WHERE r.tenant_id=s.tenant_id AND r.session_id=s.id
      AND r.config_snapshot IS NOT NULL AND r.completion_contract->>'taskType' LIKE 'eval:%'
);
CREATE INDEX sessions_sidebar_idx ON sessions (tenant_id,user_id,kind,pinned DESC,updated_at DESC,id DESC)
    WHERE deleted_at IS NULL AND archived_at IS NULL;
ALTER TABLE run_commands DROP CONSTRAINT run_commands_type_check;
ALTER TABLE run_commands ADD CONSTRAINT run_commands_type_check
    CHECK (type IN ('progress_query','supplement','contract_change','cancel','steer'));
