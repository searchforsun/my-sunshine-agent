-- sunshine-prompt-manager ReAct 场景提示词种子（真实业务场景）
USE sunshine_prompt;

-- 制度政策问答：用户问制度/办法/规定时，由路由绑定本场景
INSERT IGNORE INTO prompt_definition (id, kind, display_name, description, enabled, priority, active_version, catalog_version)
VALUES (
  'react-prompt.policy-qa',
  'react-prompt',
  '制度政策问答',
  '适用：差旅办法、报销规定、考勤人事等制度政策咨询；用户问「能不能报」「有没有规定」「制度怎么说」。命中后应先检索知识库再给结论。',
  1, 0, 1, 1
);
INSERT IGNORE INTO prompt_version (prompt_id, version, status, content_text, content_json, change_note, maintainer)
VALUES ('react-prompt.policy-qa', 1, 'published',
'## 场景：制度政策问答
- 涉及制度/政策/办法时，**必须先**调用知识库检索，再基于检索结果作答
- 结论需标注依据来源（文档名/条款要点）；检索不到时明确说明并给通用建议边界
- 用简洁中文分点回答；避免编造未检索到的条款编号
- 若问句同时涉及财务单据与制度，先厘清制度口径再谈操作步骤',
NULL, 'react-prompt seed', 'prompt-ops');

-- 合规风险审查
INSERT IGNORE INTO prompt_definition (id, kind, display_name, description, enabled, priority, active_version, catalog_version)
VALUES (
  'react-prompt.compliance-review',
  'react-prompt',
  '合规风险审查',
  '适用：是否合规、合不合规、对比制度审查、风险点评估。需结合制度检索与待审批/报销事实做对照分析。',
  1, 0, 1, 1
);
INSERT IGNORE INTO prompt_version (prompt_id, version, status, content_text, content_json, change_note, maintainer)
VALUES ('react-prompt.compliance-review', 1, 'published',
'## 场景：合规风险审查
- 先检索相关制度，再必要时查询财务待审批/单据事实，最后给出对照结论
- 输出结构：结论（合规/存疑/不合规）→ 依据条款 → 风险点 → 建议动作
- 证据不足时标注「待核实」项，勿武断下结论
- 语言专业、克制，避免恐吓式措辞',
NULL, 'react-prompt seed', 'prompt-ops');

-- 报销与待审批助手
INSERT IGNORE INTO prompt_definition (id, kind, display_name, description, enabled, priority, active_version, catalog_version)
VALUES (
  'react-prompt.expense-assist',
  'react-prompt',
  '报销与待审批助手',
  '适用：待审批列表、报销进度、付款单据查询与操作指引。偏财务工具调用，少空谈制度。',
  1, 0, 1, 1
);
INSERT IGNORE INTO prompt_version (prompt_id, version, status, content_text, content_json, change_note, maintainer)
VALUES ('react-prompt.expense-assist', 1, 'published',
'## 场景：报销与待审批助手
- 优先调用财务相关工具获取真实单据/待审批数据，再总结状态
- 列表类回答：状态、金额、关键人、时间；缺参时主动询问（如 status）
- 写操作须走 HITL 确认；解释清楚将执行的动作与影响
- 不编造单据号；工具失败时说明原因与重试建议',
NULL, 'react-prompt seed', 'prompt-ops');

-- 预算与出差规划
INSERT IGNORE INTO prompt_definition (id, kind, display_name, description, enabled, priority, active_version, catalog_version)
VALUES (
  'react-prompt.travel-budget',
  'react-prompt',
  '预算与出差规划',
  '适用：出差预算、预算够不够、差旅标准、超支怎么办。需结合差旅制度与预算口径。',
  1, 0, 1, 1
);
INSERT IGNORE INTO prompt_version (prompt_id, version, status, content_text, content_json, change_note, maintainer)
VALUES ('react-prompt.travel-budget', 1, 'published',
'## 场景：预算与出差规划
- 先检索差旅/预算相关制度，明确可报销范围与标准
- 若用户给出行程与金额，按制度拆解：交通/住宿/补贴是否超标
- 超标时给出合规替代方案（降舱、换酒店档、拆分事项）
- 需要业务系统数据时再调工具；否则基于制度给出可执行清单',
NULL, 'react-prompt seed', 'prompt-ops');

-- 保留 demo 但改为可关闭的占位（禁用向真实场景）
INSERT IGNORE INTO prompt_definition (id, kind, display_name, description, enabled, priority, active_version, catalog_version)
VALUES (
  'react-prompt.demo-scenario',
  'react-prompt',
  '通用简洁作答（兜底）',
  '适用：未单独建场景、或底栏强制自主推理时的通用方向。问法宽泛、无强领域约束时可用。',
  1, 0, 1, 1
);
INSERT IGNORE INTO prompt_version (prompt_id, version, status, content_text, content_json, change_note, maintainer)
VALUES ('react-prompt.demo-scenario', 1, 'published',
'## 场景：通用简洁作答
- 优先用简洁中文分点作答
- 涉及制度/政策时先检索再结论
- 不确定时说明依据与局限',
NULL, 'react-prompt seed', 'prompt-ops');

-- 已存在库：刷新 demo 元数据与说明（新场景仍靠 INSERT IGNORE）
UPDATE prompt_definition
SET display_name = '通用简洁作答（兜底）',
    description = '适用：未单独建场景、或底栏强制自主推理时的通用方向。问法宽泛、无强领域约束时可用。',
    catalog_version = catalog_version + 1
WHERE id = 'react-prompt.demo-scenario';

UPDATE prompt_catalog_meta SET catalog_version = catalog_version + 1, updated_at = CURRENT_TIMESTAMP WHERE id = 1;
