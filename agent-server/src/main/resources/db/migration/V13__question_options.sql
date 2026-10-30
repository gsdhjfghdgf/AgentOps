SET search_path TO agent_memory, public;
CREATE TABLE run_question_options (
 tenant_id uuid NOT NULL,user_id uuid NOT NULL,run_id uuid NOT NULL,question_id uuid NOT NULL,
 options jsonb NOT NULL DEFAULT '[]',PRIMARY KEY(tenant_id,user_id,run_id,question_id)
);
