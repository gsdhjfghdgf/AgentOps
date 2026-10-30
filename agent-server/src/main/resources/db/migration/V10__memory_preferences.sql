SET search_path TO agent_memory, public;
CREATE TABLE memory_preferences (
    tenant_id uuid NOT NULL,user_id uuid NOT NULL,auto_enabled boolean NOT NULL DEFAULT true,
    PRIMARY KEY(tenant_id,user_id)
);
