-- V5: 知识库 RAG（文档/版本/分块）—— 文档 10 / 30 节
SET search_path TO agent_memory, public;

CREATE TABLE knowledge_documents (
    tenant_id uuid NOT NULL,
    user_id uuid NOT NULL,
    document_id uuid NOT NULL DEFAULT gen_random_uuid(),
    title text NOT NULL,
    source_type text NOT NULL DEFAULT 'upload',
    owner_scope text NOT NULL DEFAULT 'user',
    active_version integer NOT NULL DEFAULT 0,
    state text NOT NULL DEFAULT 'active' CHECK (state IN ('active','deleted')),
    valid_until timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, document_id)
);
CREATE INDEX knowledge_documents_owner_idx
    ON knowledge_documents (tenant_id, user_id, owner_scope)
    WHERE state = 'active';

CREATE TABLE document_versions (
    tenant_id uuid NOT NULL,
    user_id uuid NOT NULL,
    document_id uuid NOT NULL,
    version integer NOT NULL CHECK (version > 0),
    content_hash text NOT NULL,
    parser_version text NOT NULL,
    chunker_version text NOT NULL,
    embedding_model text,
    state text NOT NULL DEFAULT 'building'
        CHECK (state IN ('building','ready','failed','superseded')),
    byte_size bigint NOT NULL DEFAULT 0,
    chunk_count integer NOT NULL DEFAULT 0,
    error text,
    created_at timestamptz NOT NULL DEFAULT now(),
    activated_at timestamptz,
    PRIMARY KEY (tenant_id, document_id, version),
    FOREIGN KEY (tenant_id, document_id)
        REFERENCES knowledge_documents (tenant_id, document_id) ON DELETE CASCADE
);

CREATE TABLE document_chunks (
    tenant_id uuid NOT NULL,
    user_id uuid NOT NULL,
    document_id uuid NOT NULL,
    version integer NOT NULL,
    chunk_id uuid NOT NULL DEFAULT gen_random_uuid(),
    ordinal integer NOT NULL CHECK (ordinal >= 0),
    content text NOT NULL,
    heading text,
    token_count integer,
    embedding vector(1024),
    embedding_model text,
    embedding_version bigint,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, document_id, version, chunk_id),
    FOREIGN KEY (tenant_id, document_id, version)
        REFERENCES document_versions (tenant_id, document_id, version) ON DELETE CASCADE
);
CREATE INDEX document_chunks_embedding_idx
    ON document_chunks USING hnsw (embedding vector_cosine_ops)
    WHERE embedding IS NOT NULL;
