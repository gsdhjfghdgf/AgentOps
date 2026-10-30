SET search_path TO agent_memory, public;
CREATE TABLE chat_model_profiles (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL, user_id uuid NOT NULL,
    api_url text NOT NULL, model_name varchar(200) NOT NULL, key_cipher text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(), UNIQUE(tenant_id,user_id,id)
);
CREATE TABLE chat_model_settings (
    tenant_id uuid NOT NULL,user_id uuid NOT NULL,profile_id uuid NOT NULL,
    PRIMARY KEY(tenant_id,user_id),
    FOREIGN KEY(tenant_id,user_id,profile_id) REFERENCES chat_model_profiles(tenant_id,user_id,id)
);
