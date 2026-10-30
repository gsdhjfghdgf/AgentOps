-- V7: 运行中命令与完成契约修订 —— 文档 29 节
SET search_path TO agent_memory, public;

CREATE TABLE run_commands (
    tenant_id uuid NOT NULL,
    user_id uuid NOT NULL,
    session_id uuid NOT NULL,
    run_id uuid NOT NULL,
    command_id uuid NOT NULL,
    command_seq bigint NOT NULL,
    type text NOT NULL CHECK (type IN ('progress_query','supplement','contract_change','cancel')),
    payload jsonb NOT NULL DEFAULT '{}'::jsonb,
    question_id uuid,
    expected_state_version bigint,
    expected_contract_version bigint,
    status text NOT NULL DEFAULT 'received'
        CHECK (status IN ('received','applied','rejected')),
    result text,
    reject_reason text,
    received_at timestamptz NOT NULL DEFAULT now(),
    handled_at timestamptz,
    PRIMARY KEY (tenant_id, run_id, command_id),
    UNIQUE (tenant_id, run_id, command_seq),
    FOREIGN KEY (tenant_id, user_id, session_id, run_id)
        REFERENCES runs (tenant_id, user_id, session_id, id) ON DELETE CASCADE
);
CREATE INDEX run_commands_barrier_idx
    ON run_commands (tenant_id, run_id, command_seq)
    WHERE status = 'received' AND type IN ('contract_change','cancel');

CREATE TABLE run_contract_revisions (
    tenant_id uuid NOT NULL,
    user_id uuid NOT NULL,
    session_id uuid NOT NULL,
    run_id uuid NOT NULL,
    version bigint NOT NULL CHECK (version > 0),
    contract jsonb NOT NULL,
    reason text,
    approved_by_command uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, run_id, version),
    FOREIGN KEY (tenant_id, user_id, session_id, run_id)
        REFERENCES runs (tenant_id, user_id, session_id, id) ON DELETE CASCADE
);
