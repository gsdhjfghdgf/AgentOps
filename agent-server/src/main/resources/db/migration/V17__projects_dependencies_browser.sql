SET search_path TO agent_memory, public;
CREATE TABLE agent_projects (
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL,user_id uuid NOT NULL,name text NOT NULL,root_path text NOT NULL,
 preview_origin text NOT NULL DEFAULT '',created_at timestamptz NOT NULL DEFAULT now(),UNIQUE(tenant_id,user_id,root_path)
);
CREATE TABLE project_selections (tenant_id uuid NOT NULL,user_id uuid NOT NULL,project_id uuid,PRIMARY KEY(tenant_id,user_id));
CREATE TABLE project_file_changes (
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL,user_id uuid NOT NULL,project_id uuid NOT NULL,run_id uuid NOT NULL,
 path text NOT NULL,before_content text,before_sha text,after_sha text NOT NULL,status text NOT NULL DEFAULT 'prepared',
 created_at timestamptz NOT NULL DEFAULT now(),reverted_at timestamptz
);
CREATE INDEX project_changes_owner ON project_file_changes(tenant_id,user_id,project_id,created_at DESC);
CREATE TABLE skill_versions (
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL,user_id uuid NOT NULL,skill_id uuid NOT NULL,
 name text NOT NULL,description text NOT NULL,keywords text NOT NULL,content text NOT NULL,source_url text NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE run_browser_permissions (
 tenant_id uuid NOT NULL,user_id uuid NOT NULL,run_id uuid NOT NULL,approval_id uuid NOT NULL,
 granted_at timestamptz NOT NULL DEFAULT now(),PRIMARY KEY(tenant_id,user_id,run_id)
);
