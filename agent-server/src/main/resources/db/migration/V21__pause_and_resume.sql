-- User pause is a durable non-terminal state. Completed side effects remain committed.
SET search_path TO agent_memory, public;
ALTER TABLE runs ADD COLUMN paused_at timestamptz;
ALTER TABLE runs DROP CONSTRAINT runs_status_check;
ALTER TABLE runs ADD CONSTRAINT runs_status_check CHECK (status IN (
    'queued','running','paused','waiting_user','waiting_approval','waiting_reconcile','retry_wait',
    'cancel_requested','succeeded','failed','cancelled','timed_out','budget_exceeded'));
ALTER TABLE run_commands DROP CONSTRAINT run_commands_type_check;
ALTER TABLE run_commands ADD CONSTRAINT run_commands_type_check CHECK (type IN (
    'progress_query','supplement','cancel','contract_change','steer','pause','resume'));
