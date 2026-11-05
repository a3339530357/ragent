-- 运行时配置中心：草稿与历史配置使用应用层 AES-GCM 加密
CREATE TABLE IF NOT EXISTS t_runtime_config_version (
    id BIGSERIAL PRIMARY KEY,
    schema_version INTEGER NOT NULL,
    payload TEXT NOT NULL,
    created_by VARCHAR(128) NOT NULL,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    rollback_from BIGINT
);
CREATE TABLE IF NOT EXISTS t_runtime_config_state (
    id SMALLINT PRIMARY KEY CHECK (id = 1),
    active_version BIGINT NOT NULL DEFAULT 0,
    draft_revision BIGINT NOT NULL DEFAULT 0,
    draft_base_version BIGINT NOT NULL DEFAULT 0,
    draft_payload TEXT,
    updated_by VARCHAR(128),
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
INSERT INTO t_runtime_config_state(id) VALUES (1) ON CONFLICT (id) DO NOTHING;
COMMENT ON TABLE t_runtime_config_version IS '不可变运行时配置版本，内容加密存储';
COMMENT ON TABLE t_runtime_config_state IS '运行时配置发布指针及共享草稿';
