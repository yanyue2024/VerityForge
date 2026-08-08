CREATE INDEX idx_chunk_embedding_hnsw_1024 ON chunk_embedding
    USING hnsw ((embedding::vector(1024)) vector_cosine_ops)
    WHERE dimension = 1024;
