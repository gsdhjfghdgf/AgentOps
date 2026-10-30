SET search_path TO agent_memory, public;
CREATE TABLE task_read_pages (
 tenant_id uuid NOT NULL,user_id uuid NOT NULL,run_id uuid NOT NULL,path text NOT NULL,sha256 text NOT NULL,
 start_offset int NOT NULL,end_offset int NOT NULL,total_chars int NOT NULL,visits int NOT NULL DEFAULT 1,
 updated_at timestamptz NOT NULL DEFAULT now(),PRIMARY KEY(tenant_id,user_id,run_id,path,sha256,start_offset)
);
CREATE TABLE task_progress_state (
 tenant_id uuid NOT NULL,user_id uuid NOT NULL,run_id uuid NOT NULL,duplicate_reads int NOT NULL DEFAULT 0,
 notes text NOT NULL DEFAULT '',notes_revision bigint NOT NULL DEFAULT 0,notes_until_seq bigint NOT NULL DEFAULT 0,last_novel_at timestamptz,
 updated_at timestamptz NOT NULL DEFAULT now(),PRIMARY KEY(tenant_id,user_id,run_id)
);
