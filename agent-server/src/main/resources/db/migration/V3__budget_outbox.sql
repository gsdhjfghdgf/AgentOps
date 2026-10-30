-- V3: 预算账户/预留与 Outbox —— 文档 8.1 / 17 / 18.3 节
SET search_path TO agent_memory, public;

-- ============ 预算账户 ============
CREATE TABLE budget_accounts (
    tenant_id uuid NOT NULL,
    user_id uuid,
    period_key text NOT NULL,
    budget_type text NOT NULL CHECK (budget_type IN ('run','user_month','tenant_month')),
    currency text NOT NULL DEFAULT 'CNY',
    max_total_tokens bigint NOT NULL,
    max_cost_micros bigint NOT NULL DEFAULT 0,
    max_model_attempts integer NOT NULL,
    max_tool_attempts integer NOT NULL,
    used_tokens bigint NOT NULL DEFAULT 0 CHECK (used_tokens >= 0),
    used_cost_micros bigint NOT NULL DEFAULT 0 CHECK (used_cost_micros >= 0),
    reserved_tokens bigint NOT NULL DEFAULT 0 CHECK (reserved_tokens >= 0),
    reserved_cost_micros bigint NOT NULL DEFAULT 0 CHECK (reserved_cost_micros >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, user_id, period_key, budget_type)
);

-- ============ 预算预留（结算幂等） ============
CREATE TABLE budget_reservations (
    tenant_id uuid NOT NULL,
    user_id uuid NOT NULL,
    session_id uuid NOT NULL,
    run_id uuid NOT NULL,
    attempt_id uuid NOT NULL,
    account_period_key text NOT NULL,
    account_user_id uuid,
    reserved_tokens bigint NOT NULL DEFAULT 0,
    reserved_cost_micros bigint NOT NULL DEFAULT 0,
    actual_tokens bigint NOT NULL DEFAULT 0,
    actual_cost_micros bigint NOT NULL DEFAULT 0,
    usage_known boolean NOT NULL DEFAULT false,
    price_version text,
    status text NOT NULL DEFAULT 'reserved'
        CHECK (status IN ('reserved','settled','released')),
    settled_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, attempt_id),
    FOREIGN KEY (tenant_id, user_id, session_id, run_id)
        REFERENCES runs (tenant_id, user_id, session_id, id) ON DELETE CASCADE
);

-- ============ Outbox（可靠后台任务队列） ============
CREATE TABLE outbox (
    id uuid NOT NULL DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL,
    user_id uuid,
    event_key text NOT NULL,
    type text NOT NULL,
    payload jsonb NOT NULL DEFAULT '{}'::jsonb,
    status text NOT NULL DEFAULT 'pending'
        CHECK (status IN ('pending','processing','done','dead')),
    available_at timestamptz NOT NULL DEFAULT now(),
    attempt_count integer NOT NULL DEFAULT 0,
    claim_token uuid,
    lease_until timestamptz,
    last_error text,
    created_at timestamptz NOT NULL DEFAULT now(),
    finished_at timestamptz,
    PRIMARY KEY (id),
    UNIQUE (tenant_id, event_key)
);
CREATE INDEX outbox_pending_idx
    ON outbox (available_at, id)
    WHERE status = 'pending';
CREATE INDEX outbox_lease_expired_idx
    ON outbox (lease_until, id)
    WHERE status = 'processing';
