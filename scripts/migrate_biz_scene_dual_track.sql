-- 业务上下文 M5：biz_scene_definition 场景双轨 + embedding 向量化（v3/v4，2026-08-26）
-- 用法：mysql -h<host> -uroot -p<pass> sunshine_resource < scripts/migrate_biz_scene_dual_track.sql
-- 幂等：各列/索引 EXISTS 检查后再 ADD；重复执行安全。

ALTER TABLE biz_scene_definition
  ADD COLUMN description_vector JSON NULL
  COMMENT 'description 的 embedding 向量（1024 维 float[]，DashScope text-embedding-v4）';

ALTER TABLE biz_scene_definition
  ADD COLUMN source VARCHAR(16) NOT NULL DEFAULT 'manual'
  COMMENT 'manual=运营预定义 | auto=大模型自动发现',
  ADD COLUMN source_conversation_id VARCHAR(64) NULL
  COMMENT 'auto 场景的首次触发会话（溯源）',
  ADD COLUMN approved_by VARCHAR(64) NULL
  COMMENT '审核人（auto 场景升 active 时记录）',
  ADD COLUMN approved_at TIMESTAMP NULL
  COMMENT '审核时间',
  MODIFY COLUMN status VARCHAR(16) NOT NULL DEFAULT 'active'
  COMMENT 'active|disabled|pending_review|rejected|auto_cleaned（v4 扩展）';

CREATE INDEX idx_biz_scene_source_status ON biz_scene_definition (tenant_id, source, status);
CREATE INDEX idx_biz_scene_status_created ON biz_scene_definition (status, created_at);
