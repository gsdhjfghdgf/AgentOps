-- V1: 核心身份/会话/任务/消息/摘要/检查点（文档 34 节 + 8.1 节）
-- PostgreSQL 17 + pgvector
CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public;

CREATE SCHEMA IF NOT EXISTS agent_memory;
SET search_path TO agent_memory, public;

-- ============ 会话 ============
CREATE TABLE sessions (
    tenant_id uuid NOT NULL,
    user_id uuid NOT NULL,
    id uuid NOT NULL DEFAULT gen_random_uuid(),
    title text NOT NULL DEFAULT '',
    last_seq bigint NOT NULL DEFAULT 0 CHECK (last_seq >= 0),
    context_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz,
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, user_id, id)
);
CREATE INDEX sessions_owner_recent_idx
    ON sessions (tenant_id, user_id, updated_at DESC, id DESC)
    WHERE deleted_at IS NULL;

-- ============ 任务（run） ============
CREATE TABLE runs (
    tenant_id uuid NOT NULL,
    user_id uuid NOT NULL,
    session_id uuid NOT NULL,
    id uuid NOT NULL DEFAULT gen_random_uuid(),
    request_key text NOT NULL,
    request_hash text NOT NULL,
    status text NOT NULL DEFAULT 'queued'
        CHECK (status IN (
            'queued','running','waiting_user','waiting_approval',
            'waiting_reconcile','retry_wait','cancel_requested',
            'succeeded','failed','cancelled','timed_out','budget_exceeded'
        )),
    phase text NOT NULL DEFAULT 'prepare'
        CHECK (phase IN ('prepare','model','tools','validate','finalize')),
    state_version bigint NOT NULL DEFAULT 0,
    checkpoint_version bigint NOT NULL DEFAULT 0,
    worker_id uuid,
    worker_epoch bigint NOT NULL DEFAULT 0,
    lease_until timestamptz,
    deadline_at timestamptz NOT NULL,
    cancel_requested_at timestamptz,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    event_seq bigint NOT NULL DEFAULT 0,
    reason_code text,
    waiting_reason text,
    question_id uuid,
    question_text text,
    config_snapshot jsonb NOT NULL DEFAULT '{}'::jsonb,
    completion_contract jsonb NOT NULL DEFAULT '{}'::jsonb,
    repair_count integer NOT NULL DEFAULT 0 CHECK (repair_count >= 0),
    replan_count integer NOT NULL DEFAULT 0 CHECK (replan_count >= 0),
    token_used bigint NOT NULL DEFAULT 0 CHECK (token_used >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    started_at timestamptz,
    finished_at timestamptz,
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, user_id, session_id, id),
    UNIQUE (tenant_id, session_id, request_key),
    FOREIGN KEY (tenant_id, user_id, session_id)
        REFERENCES sessions (tenant_id, user_id, id) ON DELETE CASCADE
);
-- 同一会话最多一个未结束任务
CREATE UNIQUE INDEX runs_one_open_per_session_idx
    ON runs (tenant_id, session_id)
    WHERE status NOT IN ('succeeded','failed','cancelled','timed_out','budget_exceeded');
-- 调度器领取就绪任务
CREATE INDEX runs_ready_idx
    ON runs (next_attempt_at, created_at, id)
    WHERE status IN ('queued','retry_wait');
-- 租约过期扫描
CREATE INDEX runs_expired_lease_idx
    ON runs (lease_until, id)
    WHERE status IN ('running','cancel_requested');
-- 截止时间扫描
CREATE INDEX runs_deadline_idx
    ON runs (deadline_at, id)
    WHERE status NOT IN ('succeeded','failed','cancelled','timed_out','budget_exceeded');

-- ============ 消息 ============
CREATE TABLE messages (
    tenant_id uuid NOT NULL,
    user_id uuid NOT NULL,
    session_id uuid NOT NULL,
    seq bigint NOT NULL CHECK (seq > 0),
    run_id uuid NOT NULL,
    role text NOT NULL CHECK (role IN ('user','assistant','system','tool')),
    payload jsonb NOT NULL,
    revision bigint NOT NULL DEFAULT 1 CHECK (revision > 0),
    token_count integer CHECK (token_count >= 0),
    tokenizer_id text,
    tool_call_id text,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, session_id, seq),
    UNIQUE (tenant_id, user_id, session_id, seq),
    FOREIGN KEY (tenant_id, user_id, session_id, run_id)
        REFERENCES runs (tenant_id, user_id, session_id, id) ON DELETE CASCADE,
    CHECK (role <> 'tool' OR tool_call_id IS NOT NULL)
);
CREATE INDEX messages_run_idx ON messages (tenant_id, run_id, seq);

-- ============ 会话摘要 ============
CREATE TABLE summaries (
    tenant_id uuid NOT NULL,
    user_id uuid NOT NULL,
    session_id uuid NOT NULL,
    until_seq bigint NOT NULL CHECK (until_seq > 0),
    previous_until_seq bigint NOT NULL DEFAULT 0,
    summary text NOT NULL,
    token_count integer,
    source_context_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, session_id, until_seq),
    FOREIGN KEY (tenant_id, user_id, session_id)
        REFERENCES sessions (tenant_id, user_id, id) ON DELETE CASCADE
);

-- ============ 检查点（工作记忆） ============
CREATE TABLE checkpoints (
    tenant_id uuid NOT NULL,
    user_id uuid NOT NULL,
    session_id uuid NOT NULL,
    run_id uuid NOT NULL,
    version bigint NOT NULL CHECK (version > 0),
    worker_epoch bigint NOT NULL,
    state jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, run_id, version),
    FOREIGN KEY (tenant_id, user_id, session_id, run_id)
        REFERENCES runs (tenant_id, user_id, session_id, id) ON DELETE CASCADE
);
