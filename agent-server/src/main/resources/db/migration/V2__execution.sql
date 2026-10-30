-- V2: 执行链路（步骤、工具调用、尝试、批准、校验报告、产物）—— 文档 8.1 / 15 / 16 / 19 节
SET search_path TO agent_memory, public;

-- ============ 逻辑步骤 ============
CREATE TABLE run_steps (
    tenant_id uuid NOT NULL,
    user_id uuid NOT NULL,
    session_id uuid NOT NULL,
    run_id uuid NOT NULL,
    step_id uuid NOT NULL,
    ordinal integer NOT NULL CHECK (ordinal > 0),
    kind text NOT NULL CHECK (kind IN ('model','tool','validate')),
    status text NOT NULL DEFAULT 'pending'
        CHECK (status IN ('pending','running','succeeded','failed','skipped','cancelled')),
    definition_version text,
    effect_key text,
    description text,
    result_ref text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, run_id, step_id),
    UNIQUE (tenant_id, run_id, ordinal),
    FOREIGN KEY (tenant_id, user_id, session_id, run_id)
        REFERENCES runs (tenant_id, user_id, session_id, id) ON DELETE CASCADE
);
CREATE INDEX run_steps_pending_idx ON run_steps (tenant_id, run_id, status);

-- ============ 工具调用意图（执行前持久化） ============
CREATE TABLE tool_calls (
    tenant_id uuid NOT NULL,
    user_id uuid NOT NULL,
    session_id uuid NOT NULL,
    run_id uuid NOT NULL,
    step_id uuid NOT NULL,
    call_id text NOT NULL,
    tool_name text NOT NULL,
    tool_version text NOT NULL DEFAULT '1.0.0',
    arguments jsonb NOT NULL,
    arguments_hash text NOT NULL,
    action_digest text NOT NULL,
    effect_key text NOT NULL,
    effect_class text NOT NULL CHECK (effect_class IN ('read_only','idempotent_write','non_idempotent_write')),
    status text NOT NULL DEFAULT 'recorded'
        CHECK (status IN ('recorded','approved','rejected','executing','succeeded','failed','unknown','cancelled')),
    result_ref text,
    error_code text,
    created_at timestamptz NOT NULL DEFAULT now(),
    finished_at timestamptz,
    PRIMARY KEY (tenant_id, run_id, call_id),
    FOREIGN KEY (tenant_id, user_id, session_id, run_id)
        REFERENCES runs (tenant_id, user_id, session_id, id) ON DELETE CASCADE,
    -- 幂等：同一 owner 下 effect_key 只能登记一次（跨任务防重复副作用）
    UNIQUE (tenant_id, user_id, effect_key)
);
CREATE INDEX tool_calls_run_status_idx ON tool_calls (tenant_id, run_id, status);

-- ============ 外部尝试（attempt） ============
CREATE TABLE operation_attempts (
    tenant_id uuid NOT NULL,
    user_id uuid NOT NULL,
    session_id uuid NOT NULL,
    run_id uuid NOT NULL,
    step_id uuid NOT NULL,
    attempt_id uuid NOT NULL,
    attempt_no integer NOT NULL CHECK (attempt_no > 0),
    kind text NOT NULL CHECK (kind IN ('model','tool','validate')),
    epoch bigint NOT NULL,
    state text NOT NULL DEFAULT 'pending'
        CHECK (state IN ('pending','reserved','running','succeeded','failed','unknown')),
    provider_ref text,
    error_code text,
    outcome_unknown boolean NOT NULL DEFAULT false,
    reserved_tokens bigint NOT NULL DEFAULT 0,
    reserved_cost_micros bigint NOT NULL DEFAULT 0,
    used_tokens bigint NOT NULL DEFAULT 0,
    used_cost_micros bigint NOT NULL DEFAULT 0,
    usage_known boolean,
    started_at timestamptz,
    finished_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, run_id, attempt_id),
    UNIQUE (tenant_id, run_id, step_id, attempt_no),
    FOREIGN KEY (tenant_id, user_id, session_id, run_id)
        REFERENCES runs (tenant_id, user_id, session_id, id) ON DELETE CASCADE
);
CREATE INDEX operation_attempts_open_idx
    ON operation_attempts (tenant_id, run_id, step_id)
    WHERE state IN ('pending','reserved','running','unknown');

-- ============ 操作批准 ============
CREATE TABLE run_approvals (
    tenant_id uuid NOT NULL,
    user_id uuid NOT NULL,
    session_id uuid NOT NULL,
    run_id uuid NOT NULL,
    id uuid NOT NULL DEFAULT gen_random_uuid(),
    step_id uuid,
    call_id text,
    tool_name text NOT NULL,
    action_digest text NOT NULL,
    status text NOT NULL DEFAULT 'pending'
        CHECK (status IN ('pending','approved','rejected','expired','consumed')),
    reason text,
    approver_id uuid,
    expires_at timestamptz NOT NULL,
    consumed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    decided_at timestamptz,
    PRIMARY KEY (tenant_id, id),
    FOREIGN KEY (tenant_id, user_id, session_id, run_id)
        REFERENCES runs (tenant_id, user_id, session_id, id) ON DELETE CASCADE
);
CREATE INDEX run_approvals_pending_idx
    ON run_approvals (tenant_id, run_id, status)
    WHERE status = 'pending';

-- ============ 校验报告 ============
CREATE TABLE validation_reports (
    tenant_id uuid NOT NULL,
    user_id uuid NOT NULL,
    session_id uuid NOT NULL,
    run_id uuid NOT NULL,
    id uuid NOT NULL DEFAULT gen_random_uuid(),
    step_id uuid,
    candidate_revision uuid NOT NULL,
    contract_version bigint NOT NULL DEFAULT 1,
    validator_version text NOT NULL,
    verdict text NOT NULL CHECK (verdict IN ('pass','repairable','need_user_input','fatal')),
    errors jsonb NOT NULL DEFAULT '[]'::jsonb,
    safe_feedback text,
    verified_artifacts jsonb NOT NULL DEFAULT '[]'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, run_id, candidate_revision, validator_version),
    FOREIGN KEY (tenant_id, user_id, session_id, run_id)
        REFERENCES runs (tenant_id, user_id, session_id, id) ON DELETE CASCADE
);

-- ============ 产物/附件 ============
CREATE TABLE artifacts (
    tenant_id uuid NOT NULL,
    user_id uuid NOT NULL,
    session_id uuid,
    run_id uuid,
    object_id uuid NOT NULL DEFAULT gen_random_uuid(),
    object_key text NOT NULL,
    original_name text,
    mime_type text,
    byte_size bigint NOT NULL DEFAULT 0 CHECK (byte_size >= 0),
    sha256 text,
    kind text NOT NULL DEFAULT 'output' CHECK (kind IN ('upload','intermediate','output')),
    state text NOT NULL DEFAULT 'ready'
        CHECK (state IN ('uploading','quarantined','ready','rejected','delete_pending','deleted')),
    retained_until timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz,
    PRIMARY KEY (tenant_id, object_id),
    UNIQUE (object_id)
);
CREATE INDEX artifacts_owner_run_idx ON artifacts (tenant_id, user_id, run_id);
