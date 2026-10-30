SET search_path TO agent_memory, public;
ALTER TABLE sessions ADD COLUMN project_id uuid;
-- Preserve the most recently used project of existing chats, only when still owned by that user.
UPDATE sessions s SET project_id=p.id
FROM agent_projects p
WHERE s.kind='chat' AND s.tenant_id=p.tenant_id AND s.user_id=p.user_id
AND p.id::text=(SELECT r.config_snapshot->>'projectId' FROM runs r
    WHERE r.tenant_id=s.tenant_id AND r.user_id=s.user_id AND r.session_id=s.id
    ORDER BY r.created_at DESC,r.id DESC LIMIT 1);
CREATE INDEX sessions_project_owner ON sessions(tenant_id,user_id,project_id,updated_at DESC) WHERE deleted_at IS NULL;
