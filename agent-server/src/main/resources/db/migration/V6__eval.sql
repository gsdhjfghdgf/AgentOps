-- V6: Agent 评测体系 —— 文档 26 节
SET search_path TO agent_memory, public;

CREATE TABLE eval_suites (
    id uuid NOT NULL DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL,
    name text NOT NULL,
    suite_version text NOT NULL,
    description text,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (id),
    UNIQUE (tenant_id, name, suite_version)
);

CREATE TABLE eval_cases (
    id uuid NOT NULL DEFAULT gen_random_uuid(),
    suite_id uuid NOT NULL,
    task_id text NOT NULL,
    category text NOT NULL DEFAULT 'normal'
        CHECK (category IN ('normal','ambiguous','fault','long_task','permission','runtime_change')),
    input text NOT NULL,
    initial_env jsonb NOT NULL DEFAULT '{}'::jsonb,
    allowed_tools jsonb NOT NULL DEFAULT '[]'::jsonb,
    budget jsonb NOT NULL DEFAULT '{}'::jsonb,
    completion_contract jsonb NOT NULL DEFAULT '{}'::jsonb,
    grading jsonb NOT NULL DEFAULT '{}'::jsonb,
    is_regression boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (id),
    UNIQUE (suite_id, task_id),
    FOREIGN KEY (suite_id) REFERENCES eval_suites (id) ON DELETE CASCADE
);

CREATE TABLE eval_trials (
    id uuid NOT NULL DEFAULT gen_random_uuid(),
    suite_id uuid NOT NULL,
    case_id uuid NOT NULL,
    trial_no integer NOT NULL DEFAULT 1,
    config_snapshot jsonb NOT NULL DEFAULT '{}'::jsonb,
    run_id uuid,
    session_id uuid,
    env_seed text,
    score real NOT NULL DEFAULT 0,
    passed boolean NOT NULL DEFAULT false,
    error_type text,
    duration_ms bigint,
    used_tokens bigint NOT NULL DEFAULT 0,
    used_cost_micros bigint NOT NULL DEFAULT 0,
    trace_summary jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (id),
    UNIQUE (case_id, trial_no),
    FOREIGN KEY (suite_id) REFERENCES eval_suites (id) ON DELETE CASCADE,
    FOREIGN KEY (case_id) REFERENCES eval_cases (id) ON DELETE CASCADE
);

CREATE TABLE eval_grades (
    id uuid NOT NULL DEFAULT gen_random_uuid(),
    trial_id uuid NOT NULL,
    criterion text NOT NULL,
    score real NOT NULL DEFAULT 0,
    max_score real NOT NULL DEFAULT 1,
    grader text NOT NULL CHECK (grader IN ('rule','model','human')),
    reason text,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (id),
    FOREIGN KEY (trial_id) REFERENCES eval_trials (id) ON DELETE CASCADE
);
