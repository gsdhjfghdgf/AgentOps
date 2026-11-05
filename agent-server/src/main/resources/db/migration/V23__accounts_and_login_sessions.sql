SET search_path TO agent_memory, public;

CREATE TABLE user_accounts (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    user_id uuid NOT NULL,
    username varchar(32) NOT NULL UNIQUE,
    display_name varchar(48) NOT NULL,
    password_hash varchar(512) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    password_changed_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id,user_id)
);
CREATE TABLE login_sessions (
    id uuid PRIMARY KEY,
    account_id uuid NOT NULL REFERENCES user_accounts(id),
    token_hash char(64) NOT NULL UNIQUE,
    device varchar(160) NOT NULL,
    created_at timestamptz NOT NULL,
    last_used_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz
);
CREATE INDEX login_sessions_owner ON login_sessions(account_id,created_at DESC);
CREATE TABLE auth_rate_limits (
    scope_hash char(64) PRIMARY KEY,
    window_start timestamptz NOT NULL,
    attempts integer NOT NULL
);
