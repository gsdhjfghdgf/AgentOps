SET search_path TO agent_memory, public;
CREATE TABLE capability_preferences (
 tenant_id uuid NOT NULL,user_id uuid NOT NULL,kind varchar(12) NOT NULL,name varchar(120) NOT NULL,
 enabled boolean NOT NULL DEFAULT true,ask_approval boolean NOT NULL DEFAULT false,
 PRIMARY KEY(tenant_id,user_id,kind,name),CHECK(kind IN ('tool','skill'))
);
CREATE TABLE user_skills (
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL,user_id uuid NOT NULL,name varchar(100) NOT NULL,
 description varchar(500) NOT NULL,keywords varchar(500) NOT NULL,content text NOT NULL,
 source_url text NOT NULL DEFAULT '',created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,user_id,name)
);
