SET search_path TO agent_memory, public;
CREATE TABLE run_network_permissions (
 tenant_id uuid NOT NULL, user_id uuid NOT NULL, run_id uuid NOT NULL,
 approval_id uuid NOT NULL, granted_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY (tenant_id, user_id, run_id)
);
