SET search_path TO agent_memory, public;
CREATE TABLE task_plans(tenant_id uuid NOT NULL,user_id uuid NOT NULL,run_id uuid NOT NULL,revision bigint NOT NULL DEFAULT 1,steps jsonb NOT NULL,updated_at timestamptz NOT NULL DEFAULT now(),PRIMARY KEY(tenant_id,user_id,run_id));
CREATE TABLE mcp_connections(id uuid PRIMARY KEY,tenant_id uuid NOT NULL,user_id uuid NOT NULL,name text NOT NULL,url text NOT NULL,key_cipher text NOT NULL,enabled boolean NOT NULL DEFAULT true,created_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE task_schedules(id uuid PRIMARY KEY,tenant_id uuid NOT NULL,user_id uuid NOT NULL,session_id uuid NOT NULL,name text NOT NULL,prompt text NOT NULL,interval_minutes int NOT NULL CHECK(interval_minutes BETWEEN 5 AND 525600),next_at timestamptz NOT NULL,enabled boolean NOT NULL DEFAULT true,created_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE schedule_fires(schedule_id uuid NOT NULL,slot timestamptz NOT NULL,tenant_id uuid NOT NULL,user_id uuid NOT NULL,session_id uuid NOT NULL,run_id uuid,status text NOT NULL DEFAULT 'pending',attempts int NOT NULL DEFAULT 0,next_attempt_at timestamptz NOT NULL DEFAULT now(),last_error text,PRIMARY KEY(schedule_id,slot));
CREATE TABLE user_notifications(id uuid PRIMARY KEY,tenant_id uuid NOT NULL,user_id uuid NOT NULL,event_key text NOT NULL,title text NOT NULL,run_id uuid,read_at timestamptz,created_at timestamptz NOT NULL DEFAULT now(),UNIQUE(tenant_id,user_id,event_key));
CREATE TABLE usage_preferences(tenant_id uuid NOT NULL,user_id uuid NOT NULL,price_per_million numeric NOT NULL DEFAULT 0 CHECK(price_per_million>=0),reminder_tokens bigint NOT NULL DEFAULT 100000 CHECK(reminder_tokens>0),PRIMARY KEY(tenant_id,user_id));
CREATE TABLE file_change_batches(id uuid PRIMARY KEY,tenant_id uuid NOT NULL,user_id uuid NOT NULL,run_id uuid NOT NULL,files jsonb NOT NULL,status text NOT NULL DEFAULT 'draft',result jsonb NOT NULL DEFAULT '[]',created_at timestamptz NOT NULL DEFAULT now());
CREATE INDEX task_schedule_due ON task_schedules(next_at) WHERE enabled;
CREATE INDEX schedule_fire_pending ON schedule_fires(next_attempt_at) WHERE status='pending';
CREATE INDEX notifications_owner ON user_notifications(tenant_id,user_id,created_at DESC);
CREATE TABLE run_sandbox_permissions(tenant_id uuid NOT NULL,user_id uuid NOT NULL,run_id uuid NOT NULL,approval_id uuid NOT NULL,granted_at timestamptz NOT NULL DEFAULT now(),PRIMARY KEY(tenant_id,user_id,run_id));
CREATE TABLE memory_revisions(id uuid PRIMARY KEY DEFAULT gen_random_uuid(),tenant_id uuid NOT NULL,user_id uuid NOT NULL,memory_id uuid NOT NULL,version bigint NOT NULL,content text NOT NULL,authority text NOT NULL,state text NOT NULL,valid_until timestamptz,created_at timestamptz NOT NULL DEFAULT now());
CREATE FUNCTION keep_memory_revision() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF OLD.content IS DISTINCT FROM NEW.content OR OLD.state IS DISTINCT FROM NEW.state OR OLD.valid_until IS DISTINCT FROM NEW.valid_until THEN
 INSERT INTO memory_revisions(tenant_id,user_id,memory_id,version,content,authority,state,valid_until)VALUES(OLD.tenant_id,OLD.user_id,OLD.id,OLD.version,OLD.content,OLD.authority,OLD.state,OLD.valid_until);
 END IF; RETURN NEW;
END $$;
CREATE TRIGGER memory_revision_before_update BEFORE UPDATE ON memories FOR EACH ROW EXECUTE FUNCTION keep_memory_revision();
