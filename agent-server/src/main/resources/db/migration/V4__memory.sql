-- V4: 长期记忆（owner 版本、记忆、来源）—— 文档 6 / 8.1 / 34 节
SET search_path TO agent_memory, public;

-- ============ owner 记忆版本（缓存失效） ============
CREATE TABLE owner_versions (
    tenant_id uuid NOT NULL,
    user_id uuid NOT NULL,
    memory_version bigint NOT NULL DEFAULT 0 CHECK (memory_version >= 0),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, user_id)
);

-- ============ 记忆 ============
CREATE TABLE memories (
    tenant_id uuid NOT NULL,
    user_id uuid NOT NULL,
    id uuid NOT NULL DEFAULT gen_random_uuid(),
    scope_key text NOT NULL DEFAULT 'user',
    memory_key text NOT NULL,
    kind text NOT NULL CHECK (kind IN ('preference','fact','experience','episode')),
    content text NOT NULL,
    authority text NOT NULL CHECK (authority IN ('explicit','inferred')),
    state text NOT NULL DEFAULT 'active' CHECK (state IN ('active','revoked')),
    version bigint NOT NULL DEFAULT 1 CHECK (version > 0),
    importance real NOT NULL DEFAULT 0.5 CHECK (importance BETWEEN 0 AND 1),
    embedding vector(1024),
    embedding_model text,
    embedding_version bigint,
    valid_until timestamptz,
    observed_at timestamptz NOT NULL DEFAULT now(),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, user_id, id),
    UNIQUE (tenant_id, user_id, scope_key, memory_key),
    -- 正文修改 version+1 后，旧 embedding 失效（版本不一致则不允许检索命中）
    CHECK (embedding IS NULL OR (
        embedding_model IS NOT NULL
        AND embedding_version IS NOT NULL
        AND embedding_version = version
    ))
);
CREATE INDEX memories_owner_scope_idx
    ON memories (tenant_id, user_id, scope_key, updated_at DESC)
    WHERE state = 'active';
-- 小规模精确向量检索（先 owner/scope 过滤再算距离，文档 8.3）
CREATE INDEX memories_embedding_idx
    ON memories USING hnsw (embedding vector_cosine_ops)
    WHERE state = 'active' AND embedding IS NOT NULL;

-- ============ 记忆来源（证据链） ============
CREATE TABLE memory_sources (
    tenant_id uuid NOT NULL,
    user_id uuid NOT NULL,
    memory_id uuid NOT NULL,
    memory_version bigint NOT NULL,
    session_id uuid,
    source_seq bigint,
    evidence text,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, memory_id, memory_version, session_id, source_seq),
    FOREIGN KEY (tenant_id, user_id, memory_id) REFERENCES memories (tenant_id, user_id, id) ON DELETE CASCADE
);
CREATE INDEX memory_sources_message_idx ON memory_sources (tenant_id, session_id, source_seq);
