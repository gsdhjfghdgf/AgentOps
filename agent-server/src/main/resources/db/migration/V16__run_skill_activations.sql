SET search_path TO agent_memory, public;
CREATE TABLE run_skill_activations (
 tenant_id uuid NOT NULL,user_id uuid NOT NULL,run_id uuid NOT NULL,skill_id text NOT NULL,
 activated_at timestamptz NOT NULL DEFAULT now(),PRIMARY KEY(tenant_id,user_id,run_id,skill_id)
);
