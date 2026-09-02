-- 模型注册表（resource-manager · 库 sunshine_resource · 设计 2026-07-27）
-- api_key_enc 种子为 UNSET：明文密钥事后在 /models 管理面配置并 AES 加密入库
USE sunshine_resource;

CREATE TABLE IF NOT EXISTS model_provider (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  provider_key  VARCHAR(64)  NOT NULL COMMENT 'deepseek/qwen/openai/...',
  display_name  VARCHAR(128) NOT NULL,
  protocol      VARCHAR(32)  NOT NULL DEFAULT 'openai-compatible',
  base_url      VARCHAR(256) NOT NULL COMMENT '不含 /chat/completions；是否含 /v1 由 path_prefix 决定',
  path_prefix   VARCHAR(32)  NOT NULL DEFAULT '' COMMENT 'deepseek=/v1，qwen dashscope compatible=空',
  api_key_enc   VARCHAR(1024) NOT NULL COMMENT 'AES 密文；UNSET=未配置；读接口永不回明文',
  enabled       TINYINT(1)   NOT NULL DEFAULT 1,
  tenant_id     VARCHAR(64)  NOT NULL DEFAULT 'default',
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_provider (provider_key, tenant_id)
);

CREATE TABLE IF NOT EXISTS model_definition (
  id               BIGINT PRIMARY KEY AUTO_INCREMENT,
  provider_key     VARCHAR(64)  NOT NULL,
  model_name       VARCHAR(128) NOT NULL COMMENT '上游真实模型名，全局路由键',
  display_name     VARCHAR(128) NOT NULL,
  context_window   INT          NOT NULL DEFAULT 32768,
  max_output_tokens INT         NOT NULL DEFAULT 8192 COMMENT '单次补全 max_tokens 上限（按上游模型）',
  encoding         VARCHAR(32)  NOT NULL DEFAULT 'cl100k_base',
  capabilities     JSON         NOT NULL COMMENT '{"reasoning":bool,"multimodal":bool,"tool_call":bool}',
  request_extras   JSON         NULL COMMENT 'OpenAI 兼容请求缺省参数：reasoning_split/temperature/top_p/thinking 等，缺键合并进上游 body',
  user_selectable  TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '1=可出现在 chat 等用户下拉',
  enabled          TINYINT(1)   NOT NULL DEFAULT 1,
  sort_order       INT          NOT NULL DEFAULT 0,
  tenant_id        VARCHAR(64)  NOT NULL DEFAULT 'default',
  created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_model_name (tenant_id, model_name),
  KEY idx_provider (provider_key, tenant_id)
);

CREATE TABLE IF NOT EXISTS model_scene_binding (
  id              BIGINT PRIMARY KEY AUTO_INCREMENT,
  scene_key       VARCHAR(64)  NOT NULL COMMENT 'default/chat/intent/planner/rewrite.*/title/subagent',
  primary_model   VARCHAR(128) NOT NULL COMMENT '须存在于 model_definition.model_name',
  fallback_model  VARCHAR(128) NULL COMMENT '可空；须存在且 enabled',
  extras          JSON         NULL COMMENT '场景专属：temperature/max_tokens/enable_thinking 等',
  enabled         TINYINT(1)   NOT NULL DEFAULT 1,
  tenant_id       VARCHAR(64)  NOT NULL DEFAULT 'default',
  remark          VARCHAR(256) NULL,
  created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_scene (tenant_id, scene_key)
);

CREATE TABLE IF NOT EXISTS model_route_policy (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  call_site   VARCHAR(64)  NOT NULL COMMENT 'chat|plan|worker|tool-call|rewrite|summarize|subagent；须为 CallSiteKey 枚举',
  models      JSON         NOT NULL COMMENT '候选模型池（有序，按序取首个 enabled）：["model-a","model-b"]',
  strategy    VARCHAR(32)  NOT NULL DEFAULT 'first-available' COMMENT 'MVP：first-available（取模型池首个 enabled）',
  enabled     TINYINT(1)   NOT NULL DEFAULT 1,
  tenant_id   VARCHAR(64)  NOT NULL DEFAULT 'default',
  remark      VARCHAR(256) NULL,
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_call_site (tenant_id, call_site)
);

INSERT INTO model_provider (provider_key, display_name, protocol, base_url, path_prefix, api_key_enc, enabled, tenant_id) VALUES
('deepseek', 'DeepSeek', 'openai-compatible', 'https://api.deepseek.com', '/v1', 'UNSET', 1, 'default'),
('minimax', 'MiniMax', 'openai-compatible', 'https://api.minimaxi.com/v1', '', 'UNSET', 1, 'default'),
('qwen', '通义千问', 'openai-compatible', 'https://dashscope.aliyuncs.com/compatible-mode/v1', '', 'UNSET', 1, 'default')
ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), base_url = VALUES(base_url), path_prefix = VALUES(path_prefix);

INSERT INTO model_definition (provider_key, model_name, display_name, context_window, max_output_tokens, encoding, capabilities, request_extras, user_selectable, enabled, sort_order, tenant_id) VALUES
('deepseek', 'deepseek-v4-pro', 'deepseek-v4-pro', 256000, 16384, 'cl100k_base',
 '{"toolCall": true, "reasoning": true, "multimodal": false}', '{"thinking": {"type": "enabled"}, "reasoning_effort": "high", "max_completion_tokens": 16384}', 1, 1, 10, 'default'),
('deepseek', 'deepseek-v4-flash', 'deepseek-v4-flash', 128000, 16384, 'cl100k_base',
 '{"toolCall": true, "reasoning": true, "multimodal": false}', '{"thinking": {"type": "enabled"}, "reasoning_effort": "high", "max_completion_tokens": 16384}', 1, 1, 20, 'default'),
('minimax', 'MiniMax-M3', 'MiniMax-M3', 1000000, 131072, 'cl100k_base',
 '{"toolCall": true, "reasoning": true, "multimodal": true}', '{"reasoning_split": true}', 1, 1, 50, 'default'),
('qwen', 'qwen-plus', 'qwen-plus', 262144, 8192, 'cl100k_base',
 '{"toolCall": true, "reasoning": false, "multimodal": false}', NULL, 1, 1, 30, 'default'),
('qwen', 'qwen-max', 'qwen-max', 65536, 8192, 'cl100k_base',
 '{"toolCall": true, "reasoning": false, "multimodal": false}', NULL, 1, 1, 40, 'default')
ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), context_window = VALUES(context_window),
  max_output_tokens = VALUES(max_output_tokens),
  capabilities = VALUES(capabilities), request_extras = VALUES(request_extras),
  user_selectable = VALUES(user_selectable), sort_order = VALUES(sort_order);

INSERT INTO model_scene_binding (scene_key, primary_model, fallback_model, extras, enabled, tenant_id, remark) VALUES
('default', 'deepseek-v4-pro', 'qwen-plus', NULL, 1, 'default', '通用缺省'),
('chat', 'deepseek-v4-pro', 'qwen-plus', NULL, 1, 'default', '对话主循环缺省'),
('intent', 'deepseek-v4-flash', 'qwen-plus', '{"max_tokens": 256, "temperature": 0}', 1, 'default', '意图分类'),
('planner', 'deepseek-v4-flash', 'qwen-plus', NULL, 1, 'default', 'Planner'),
('title', 'deepseek-v4-flash', 'qwen-plus', NULL, 1, 'default', '会话标题'),
('subagent', 'deepseek-v4-flash', 'qwen-plus', NULL, 1, 'default', 'spawn 缺省')
ON DUPLICATE KEY UPDATE primary_model = VALUES(primary_model), fallback_model = VALUES(fallback_model),
  extras = VALUES(extras), enabled = VALUES(enabled), remark = VALUES(remark);

INSERT INTO model_route_policy (call_site, models, strategy, enabled, tenant_id, remark) VALUES
('chat', JSON_ARRAY('deepseek-v4-pro', 'deepseek-v4-flash', 'qwen-plus'), 'first-available', 1, 'default', '对话主循环：强模型优先'),
('plan', JSON_ARRAY('deepseek-v4-pro', 'deepseek-v4-flash', 'qwen-plus'), 'first-available', 1, 'default', 'Planner：强模型'),
('worker', JSON_ARRAY('deepseek-v4-flash', 'qwen-plus'), 'first-available', 1, 'default', 'Worker 执行：快模型'),
('tool-call', JSON_ARRAY('deepseek-v4-flash', 'qwen-plus'), 'first-available', 1, 'default', '工具调用：快模型'),
('rewrite', JSON_ARRAY('qwen-plus', 'deepseek-v4-flash'), 'first-available', 1, 'default', '意图/改写：轻量模型'),
('summarize', JSON_ARRAY('qwen-plus', 'deepseek-v4-flash'), 'first-available', 1, 'default', '摘要/内部辅助：轻量模型'),
('subagent', JSON_ARRAY('deepseek-v4-flash', 'qwen-plus'), 'first-available', 1, 'default', '子代理：快模型')
ON DUPLICATE KEY UPDATE models = VALUES(models), strategy = VALUES(strategy),
  enabled = VALUES(enabled), remark = VALUES(remark);
