CREATE TABLE chat_message (
    id              BIGSERIAL PRIMARY KEY,
    conversation_id VARCHAR(64)   NOT NULL,
    role            VARCHAR(16)   NOT NULL,
    content         TEXT,
    image_refs      JSONB,
    metadata        JSONB,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX idx_chat_message_conv_time ON chat_message (conversation_id, created_at);
CREATE INDEX idx_chat_message_created_at ON chat_message (created_at);
