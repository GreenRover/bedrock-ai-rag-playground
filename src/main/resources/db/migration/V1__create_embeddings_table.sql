CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS embeddings
(
    embedding_id UUID PRIMARY KEY,
    embedding vector(1024), -- MUST MATCH TITAN V2 DIMENSIONS
    text TEXT,
    metadata JSONB
);
