-- sunshine-prompt-manager ReAct 场景提示词种子
USE sunshine_prompt;

INSERT IGNORE INTO prompt_definition (id, kind, display_name, description, enabled, priority, active_version, catalog_version)
VALUES ('react-prompt.demo-scenario', 'react-prompt', '示例场景提示词', 'ReAct 场景 overlay 示例', 1, 0, 1, 1);

INSERT IGNORE INTO prompt_version (prompt_id, version, status, content_text, content_json, change_note, maintainer)
VALUES ('react-prompt.demo-scenario', 1, 'published',
'## 场景方向（示例）
- 优先用简洁中文分点作答
- 涉及制度/政策时先检索再结论
- 不确定时说明依据与局限',
NULL, 'react-prompt seed', 'prompt-ops');

UPDATE prompt_catalog_meta SET catalog_version = catalog_version + 1, updated_at = CURRENT_TIMESTAMP WHERE id = 1;
