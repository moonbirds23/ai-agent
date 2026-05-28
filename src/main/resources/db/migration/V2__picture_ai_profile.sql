CREATE TABLE picture_ai_profile (
    id BIGSERIAL PRIMARY KEY,
    picture_id BIGINT NOT NULL,
    subject TEXT,
    scene TEXT,
    style TEXT,
    colors TEXT,
    composition TEXT,
    lighting TEXT,
    mood TEXT,
    image_prompt TEXT,
    index_text TEXT,
    vector_status INTEGER NOT NULL DEFAULT 0,
    analyzed_at TIMESTAMP,
    create_time TIMESTAMP NOT NULL DEFAULT now(),
    update_time TIMESTAMP NOT NULL DEFAULT now(),
    UNIQUE (picture_id)
);
