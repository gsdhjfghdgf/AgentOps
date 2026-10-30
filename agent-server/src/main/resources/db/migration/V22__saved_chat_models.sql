SET search_path TO agent_memory, public;

-- Catalog entries are user-visible identities. Their profile revisions remain immutable for pinned runs.
CREATE TABLE chat_model_entries (
    id uuid NOT NULL, tenant_id uuid NOT NULL, user_id uuid NOT NULL,
    name varchar(100) NOT NULL, profile_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz,
    PRIMARY KEY (tenant_id,user_id,id),
    FOREIGN KEY (tenant_id,user_id,profile_id) REFERENCES chat_model_profiles(tenant_id,user_id,id)
);
ALTER TABLE chat_model_profiles ADD COLUMN entry_id uuid;

-- Recover previously saved services/models. Prefer the active revision when the same pair was saved repeatedly.
INSERT INTO chat_model_entries(id,tenant_id,user_id,name,profile_id,created_at)
SELECT DISTINCT ON (p.tenant_id,p.user_id,p.api_url,p.model_name)
    p.id,p.tenant_id,p.user_id,left(p.model_name,100),p.id,p.created_at
FROM chat_model_profiles p
LEFT JOIN chat_model_settings s ON s.tenant_id=p.tenant_id AND s.user_id=p.user_id
ORDER BY p.tenant_id,p.user_id,p.api_url,p.model_name,(p.id=s.profile_id) DESC NULLS LAST,p.created_at DESC,p.id;

UPDATE chat_model_profiles p SET entry_id=e.id
FROM chat_model_entries e JOIN chat_model_profiles chosen ON chosen.id=e.profile_id
WHERE p.tenant_id=e.tenant_id AND p.user_id=e.user_id
  AND p.api_url=chosen.api_url AND p.model_name=chosen.model_name;

ALTER TABLE chat_model_profiles ADD CONSTRAINT chat_model_profile_entry_fk
    FOREIGN KEY (tenant_id,user_id,entry_id) REFERENCES chat_model_entries(tenant_id,user_id,id)
    DEFERRABLE INITIALLY DEFERRED;
CREATE INDEX chat_model_entries_owner_idx ON chat_model_entries(tenant_id,user_id,created_at,id)
    WHERE deleted_at IS NULL;
