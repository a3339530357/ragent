-- 知识库三层去重：仅新增字段和普通索引，不回填历史数据
ALTER TABLE t_knowledge_document ADD COLUMN IF NOT EXISTS file_hash VARCHAR(64);
COMMENT ON COLUMN t_knowledge_document.file_hash IS '本地上传原始文件字节的SHA-256';
CREATE INDEX IF NOT EXISTS idx_document_kb_file_hash ON t_knowledge_document (kb_id, file_hash);

ALTER TABLE t_knowledge_chunk ADD COLUMN IF NOT EXISTS duplicate_of VARCHAR(64);
COMMENT ON COLUMN t_knowledge_chunk.duplicate_of IS '同库最早相同内容片段ID，仅供提示';
CREATE INDEX IF NOT EXISTS idx_chunk_kb_content_hash ON t_knowledge_chunk (kb_id, content_hash);
