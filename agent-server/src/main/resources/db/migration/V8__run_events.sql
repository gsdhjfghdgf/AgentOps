-- V8: 运行事件流（SSE 断线重放 / 权威日志，文档 21.2）
-- 补充遗漏的 run_events 表（StateRepo.insertEvent / listAfter 依赖）
SET search_path TO agent_memory, public;

CREATE TABLE run_events (
    tenant_id uuid NOT NULL,
    user_id uuid NOT NULL,
    session_id uuid NOT NULL,
    run_id uuid NOT NULL,
    seq bigint NOT NULL CHECK (seq > 0),
    type text NOT NULL,
    safe_payload jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, run_id, seq),
    FOREIGN KEY (tenant_id, user_id, session_id, run_id)
        REFERENCES runs (tenant_id, user_id, session_id, id) ON DELETE CASCADE
);
CREATE INDEX run_events_type_idx ON run_events (tenant_id, run_id, type)
    WHERE type LIKE 'run.%';
