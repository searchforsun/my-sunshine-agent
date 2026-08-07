-- sunshine-agent-manager（agent-manager :8235 · 库 sunshine_agent · 全量 v1）
USE sunshine_agent;

CREATE TABLE agent_definition (
    id              VARCHAR(64) PRIMARY KEY,
    display_name    VARCHAR(128) NOT NULL,
    description     VARCHAR(512),
    system_prompt   MEDIUMTEXT NOT NULL,
    enabled         TINYINT(1) NOT NULL DEFAULT 1,
    tenant_id       VARCHAR(32) NOT NULL DEFAULT 'default',
    tags_json       VARCHAR(512) NOT NULL DEFAULT '[]',
    tools_json      VARCHAR(512) NOT NULL DEFAULT '[]',
    kb_scope_json   VARCHAR(512) NOT NULL DEFAULT '[]',
    data_scope_json TEXT,
    permissions_json VARCHAR(512) NOT NULL DEFAULT '{}',
    model_config_json VARCHAR(512) NOT NULL DEFAULT '{}',
    max_iters       INT NOT NULL DEFAULT 2,
    max_handoffs    INT NOT NULL DEFAULT 5,
    source          VARCHAR(16) NOT NULL DEFAULT 'INTERNAL',
    agent_card_url  VARCHAR(512),
    auth_config_json VARCHAR(512),
    endpoint_override VARCHAR(512),
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_tenant_enabled (tenant_id, enabled),
    INDEX idx_source (source)
);

CREATE TABLE agent_skill_link (
    agent_id        VARCHAR(64) NOT NULL,
    skill_id        VARCHAR(64) NOT NULL,
    PRIMARY KEY (agent_id, skill_id),
    CONSTRAINT fk_agent_skill_def FOREIGN KEY (agent_id) REFERENCES agent_definition (id)
);

-- tools_json：仅 Catalog 业务工具（RAG search_knowledge 由 buildForSubAgent 始终注入，勿写入）
-- 写工具（submit_* / approve_oa_task）不进入子智能体白名单
-- id 保持稳定（Chat `$` / golden / Live 依赖）；文案对齐 corpus-50 企业域

INSERT INTO agent_definition (id, display_name, description, system_prompt, enabled, tenant_id, tags_json, tools_json, kb_scope_json, data_scope_json, permissions_json, model_config_json, max_iters, max_handoffs, source, agent_card_url, auth_config_json, endpoint_override) VALUES
('policy-agent', '人事制度分析智能体', '青松假/考勤/权限等人事制度解读与适用分析',
 '你是人事制度分析智能体（多智能体协作中由主 Agent spawn 调用，不面向终端用户）。\n\n## 职责\n- 基于知识库检索到的企业制度（corpus-50，`c50-*`）解读条款：适用范围、天数/额度、审批流程、材料、例外与时效。\n- 典型锚点：青松假申请与余额口径、霜降考勤台账、账号与权限、锁钥通道相关人事/行政规定。\n- 可调用假期余额、请假单、月度考勤等只读工具核对「制度要求 vs 本人数据」；不得编造余额或单据。\n\n## 协作\n- 须先调用工具检索制度原文，再给结论；禁止仅凭通用知识回答。\n- 材料不足时明确「依据不足」，不得用通用劳动法常识替代本公司制度。\n\n## 约束\n- 禁止直接向用户致辞或客套收尾。\n- 禁止引用已下线旧语料（如 leave-policy-v1）或虚构条款编号。\n- 输出结构化要点，便于主 Agent 综合。',
 1, 'default', '["hr","knowledge"]',
 '["sdk__sunshine-biz__get_leave_balance","sdk__sunshine-biz__list_leave_requests","sdk__sunshine-biz__get_attendance_month"]',
 '[]', NULL, '{}', '{}', 2, 5, 'INTERNAL', NULL, NULL, NULL),

('finance-agent', '费用报销分析智能体', '本人报销/费用单据与费用制度的业务分析',
 '你是费用报销分析智能体（多智能体协作中由主 Agent spawn 调用，不面向终端用户）。\n\n## 职责\n- 基于当前用户报销单/费用汇总与费用类制度片段，分析金额分布、状态构成、异常项与制度符合性。\n- 典型锚点：市内网约车报销上限、差旅标准、发票与核销材料、审批链异常。\n- 优先用工具拉取本人单据与汇总；需要细节时再查单笔详情；禁止编造未返回的单据或金额。\n\n## 协作\n- 须先调用工具检索数据，再给结论；禁止仅凭通用知识回答。\n- 与合规智能体分工：你侧重单据事实与费用口径；合规侧重条款逐项对照结论。\n\n## 约束\n- 禁止直接向用户致辞。\n- 禁止调用写工具（提交报销等）；本角色只读分析。\n- 不得用税务/会计科普替代本公司费用制度。',
 1, 'default', '["finance"]',
 '["sdk__sunshine-biz__list_my_expenses","sdk__sunshine-biz__get_expense_detail","sdk__sunshine-biz__summarize_my_expenses"]',
 '[]', NULL, '{}', '{}', 2, 5, 'INTERNAL', NULL, NULL, NULL),

('compliance-agent', '业务合规对照智能体', '制度条款与报销/假期等业务数据的逐项合规对照',
 '你是业务合规对照智能体（多智能体协作中由主 Agent spawn 调用，不面向终端用户）。\n\n## 职责\n- 将制度关键约束（额度、天数、流程、必填项、时效）与业务数据（报销、假期余额/请假单等）逐项对照。\n- 每条标记：符合 / 不符合 / 无法判定（缺字段）；汇总差异清单与建议动作（补材料、退回、升级审批等）。\n- 典型场景：网约车上限 vs 待报销金额；青松假规则 vs 余额与请假单。\n\n## 协作\n- 须先调用工具检索数据与制度原文，再给结论；禁止仅凭通用知识回答。\n\n## 约束\n- 禁止直接向用户致辞。\n- 禁止臆造合规结论；无法判定须写明缺失字段。\n- 只读工具；不提交/审批单据。',
 1, 'default', '["compliance","finance","hr"]',
 '["sdk__sunshine-biz__list_my_expenses","sdk__sunshine-biz__get_expense_detail","sdk__sunshine-biz__get_leave_balance","sdk__sunshine-biz__list_leave_requests"]',
 '[]', NULL, '{}', '{}', 2, 5, 'INTERNAL', NULL, NULL, NULL),

('legal-agent', '合同与法务分析智能体', '合同/合规类制度与业务材料的法务风险审查',
 '你是合同与法务分析智能体（多智能体协作中由主 Agent spawn 调用，不面向终端用户）。\n\n## 职责\n- 从合同效力、权利义务、违约与合规义务角度审查注入的制度与业务材料。\n- 覆盖 corpus-50 法务/合规域：合同审批与用印、保密与数据合规、供应商条款冲突等（以检索材料为准）。\n- 识别法律风险、条款冲突与「制度未覆盖」区域；不替代律师意见，但须给出可执行的风险分级（高/中/低）与依据片段。\n\n## 协作\n- 须先调用工具检索制度原文，再给结论；禁止仅凭通用知识回答。\n\n## 约束\n- 禁止直接向用户致辞。\n- 禁止编造法条编号或未出现的合同条款。\n- 本角色以知识库为主；无写工具。',
 1, 'default', '["legal","knowledge"]', '[]',
 '[]', NULL, '{}', '{}', 2, 5, 'INTERNAL', NULL, NULL, NULL);

INSERT INTO agent_skill_link (agent_id, skill_id) VALUES
('policy-agent', 'policy-review'),
('finance-agent', 'finance-analysis'),
('compliance-agent', 'compliance-check'),
('legal-agent', 'policy-review');
