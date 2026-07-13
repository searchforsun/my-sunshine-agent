-- sunshine-workflow-manager（workflow-manager :8230）
USE sunshine_workflow;

CREATE TABLE workflow_definition (
    tenant_id       VARCHAR(64) NOT NULL DEFAULT 'default',
    id              VARCHAR(64) NOT NULL,
    display_name    VARCHAR(128) NOT NULL,
    description     VARCHAR(512),
    mode            VARCHAR(24) NOT NULL DEFAULT 'workflow',
    enabled         TINYINT(1) NOT NULL DEFAULT 0,
    active_version  INT NOT NULL DEFAULT 0,
    source          VARCHAR(16) NOT NULL DEFAULT 'studio',
    maintainer      VARCHAR(64),
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, id)
);

CREATE TABLE workflow_version (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id       VARCHAR(64) NOT NULL,
    workflow_id     VARCHAR(64) NOT NULL,
    version         INT NOT NULL,
    status          VARCHAR(16) NOT NULL DEFAULT 'draft',
    plan_json       MEDIUMTEXT NOT NULL,
    catalog_meta    JSON,
    published_at    TIMESTAMP NULL,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_workflow_version (tenant_id, workflow_id, version),
    CONSTRAINT fk_workflow_version_def FOREIGN KEY (tenant_id, workflow_id)
        REFERENCES workflow_definition (tenant_id, id)
);

-- 标杆 workflow 种子（published v1，source=seed；节点 id = {type}-{8位hex}）

-- finance-list
INSERT INTO workflow_definition (tenant_id, id, display_name, description, mode, enabled, active_version, source) VALUES ('default', 'finance-list', '财务待办查询', '仅列出待审批/已审批财务消息（报销单、付款单等），不做合规分析', 'workflow', 1, 1, 'seed');
INSERT INTO workflow_version (tenant_id, workflow_id, version, status, plan_json, catalog_meta, published_at) VALUES ('default', 'finance-list', 1, 'published', '{"planId":null,"reason":"导入自 sunshine-workflows.yaml · finance-list","nodes":[{"id":"start","type":"start","displayName":"开始","params":{}},{"id":"tool-f7a3b2c1","type":"tool","displayName":"查询待审批财务消息","params":{"tool":"sdk__sunshine-finance__list_finance_messages","status":"{{plan.params.status}}","retry.maxAttempts":"2","retry.backoffMs":"500","retry.onFailure":"continue"}},{"id":"answer","type":"answer","displayName":"生成回答","params":{"prompt":"根据财务工具查询结果回答用户问题。\\n\\n约束：\\n- 禁止向用户暴露英文流程/工具内部名（如 finance-list、list_finance_messages）。\\n- 若「数据」中非空（有条目、条数>0、含 id/金额/标题等），必须逐条列出，不得回答「没有数据」。\\n- 仅当数据明确为空数组或条数为 0 时，才可说明当前无待审批记录。\\n- 不要编造单据；不要省略工具返回的字段。\\n\\n数据：\\n{{tool-f7a3b2c1.output}}","retry.maxAttempts":"2","retry.backoffMs":"500","retry.onFailure":"fail_fast"}}],"edges":[{"from":"start","to":"tool-f7a3b2c1"},{"from":"tool-f7a3b2c1","to":"answer"}]}', '{"examples":["有哪些待审批报销","查询待审批的报销和付款消息"],"nodeSummary":["start","tool","answer"]}', CURRENT_TIMESTAMP);

-- finance-smart
INSERT INTO workflow_definition (tenant_id, id, display_name, description, mode, enabled, active_version, source) VALUES ('default', 'finance-smart', '财务智能分析', '待审批是否合规、制度与待办单据对比分析（含 Agent 推理）', 'workflow', 1, 1, 'seed');
INSERT INTO workflow_version (tenant_id, workflow_id, version, status, plan_json, catalog_meta, published_at) VALUES ('default', 'finance-smart', 1, 'published', '{"planId":null,"reason":"导入自 sunshine-workflows.yaml · finance-smart","nodes":[{"id":"start","type":"start","displayName":"开始","params":{}},{"id":"tool-d4e8f901","type":"tool","displayName":"查询待审批财务消息","params":{"tool":"sdk__sunshine-finance__list_finance_messages","status":"{{plan.params.status}}","retry.maxAttempts":"2","retry.backoffMs":"500","retry.onFailure":"continue"}},{"id":"agent-b2c6d803","type":"agent","displayName":"智能体分析","params":{"query":"{{start.userQuery}}","context":"{{tool-d4e8f901.output}}","skill":"finance-analysis","tools":"sdk__sunshine-finance__list_finance_messages","maxIters":"4","systemOverlay":"本节点仅输出内部分析结论，不面向用户；禁止调用白名单外工具","retry.maxAttempts":"1","retry.backoffMs":"500","retry.onFailure":"continue"}},{"id":"answer","type":"answer","displayName":"生成回答","params":{"prompt":"根据 Agent 分析结果生成用户可见回答。\\n\\n约束：禁止向用户暴露英文流程/工具内部名（如 finance-smart、finance-list）。\\n\\n分析：\\n{{agent-b2c6d803.answer}}","retry.maxAttempts":"2","retry.backoffMs":"500","retry.onFailure":"fail_fast"}}],"edges":[{"from":"start","to":"tool-d4e8f901"},{"from":"tool-d4e8f901","to":"agent-b2c6d803"},{"from":"agent-b2c6d803","to":"answer"}]}', '{"examples":["待审批报销是否合规","对比制度与待办"],"nodeSummary":["start","tool","agent","answer"]}', CURRENT_TIMESTAMP);

-- finance-summary
INSERT INTO workflow_definition (tenant_id, id, display_name, description, mode, enabled, active_version, source) VALUES ('default', 'finance-summary', '财务汇总统计', '统计各状态财务消息条数与金额', 'workflow', 1, 1, 'seed');
INSERT INTO workflow_version (tenant_id, workflow_id, version, status, plan_json, catalog_meta, published_at) VALUES ('default', 'finance-summary', 1, 'published', '{"planId":null,"reason":"导入自 sunshine-workflows.yaml · finance-summary","nodes":[{"id":"start","type":"start","displayName":"开始","params":{}},{"id":"tool-a9c1e502","type":"tool","displayName":"统计财务消息","params":{"tool":"sdk__sunshine-finance__summarize_finance_by_status","status":"{{plan.params.status}}","retry.maxAttempts":"2","retry.backoffMs":"500","retry.onFailure":"continue"}},{"id":"answer","type":"answer","displayName":"生成回答","params":{"prompt":"根据财务汇总工具结果回答用户。\\n\\n约束：禁止向用户暴露英文流程/工具内部名。\\n\\n汇总：\\n{{tool-a9c1e502.output}}","retry.maxAttempts":"2","retry.backoffMs":"500","retry.onFailure":"fail_fast"}}],"edges":[{"from":"start","to":"tool-a9c1e502"},{"from":"tool-a9c1e502","to":"answer"}]}', '{"examples":["待审批有多少条","pending 财务总额"],"nodeSummary":["start","tool","answer"]}', CURRENT_TIMESTAMP);

-- knowledge-qa
INSERT INTO workflow_definition (tenant_id, id, display_name, description, mode, enabled, active_version, source) VALUES ('default', 'knowledge-qa', '知识库问答', '查企业制度/流程/规定（请假、报销、差旅、预算管理办法等）；即使用到「预算」「审批」等词，只要不是查待办单据列表或合规对比', 'workflow', 1, 1, 'seed');
INSERT INTO workflow_version (tenant_id, workflow_id, version, status, plan_json, catalog_meta, published_at) VALUES ('default', 'knowledge-qa', 1, 'published', '{"planId":null,"reason":"导入自 sunshine-workflows.yaml · knowledge-qa","nodes":[{"id":"start","type":"start","displayName":"开始","params":{}},{"id":"rag-c5d7e903","type":"rag","displayName":"知识检索","params":{"query":"{{start.userQuery}}","topK":"3","retry.maxAttempts":"1","retry.backoffMs":"500","retry.onFailure":"continue"}},{"id":"answer","type":"answer","displayName":"生成回答","params":{"prompt":"你是企业制度问答助手。仅根据下方「检索结果」回答，不得编造公司制度。\\n\\n若「检索结果」正文为空、或明确写「未找到与用户问题直接相关的片段」，只回复：\\n「企业知识库中暂无相关规定，请咨询 HR/财务或相关主管部门。」\\n若检索结果非空且含与用户问题相关的制度片段（表述可不完全一致，如打车/差旅/交通费/报销），须基于片段作答，不得回复暂无。\\n禁止用通用常识、网络知识或税务/法律科普代替企业制度。\\n禁止向用户输出 finance-list、finance-smart、knowledge-qa 等内部名称；改用中文业务表述。\\n\\n检索结果：\\n{{rag-c5d7e903.output}}","retry.maxAttempts":"2","retry.backoffMs":"500","retry.onFailure":"fail_fast"}}],"edges":[{"from":"start","to":"rag-c5d7e903"},{"from":"rag-c5d7e903","to":"answer"}]}', '{"examples":["年假可以请几天","项目预算超支了还能安排出差吗","报销流程规定"],"nodeSummary":["start","rag","answer"]}', CURRENT_TIMESTAMP);

-- knowledge-dual（4.7.2 并行双 RAG 标杆）
INSERT INTO workflow_definition (tenant_id, id, display_name, description, mode, enabled, active_version, source) VALUES ('default', 'knowledge-dual', '双路知识检索', '制度与财务知识库并行检索后汇总回答（4.7.2 join 标杆）', 'workflow', 1, 1, 'seed');
INSERT INTO workflow_version (tenant_id, workflow_id, version, status, plan_json, catalog_meta, published_at) VALUES ('default', 'knowledge-dual', 1, 'published', '{"planId":null,"reason":"4.7.2 并行双 RAG 标杆（BPMN parallel-gateway）","nodes":[{"id":"start","type":"start","displayName":"开始","params":{}},{"id":"pg-a1b2c3d4","type":"parallel-gateway","displayName":"并行分叉","params":{"retry.maxAttempts":"1","retry.backoffMs":"500","retry.onFailure":"continue"}},{"id":"rag-a1b2c3d4","type":"rag","displayName":"制度检索","params":{"query":"{{start.userQuery}}","topK":"3","retry.maxAttempts":"1","retry.backoffMs":"500","retry.onFailure":"continue"}},{"id":"rag-e5f6a7b8","type":"rag","displayName":"财务检索","params":{"query":"{{start.userQuery}}","topK":"3","retry.maxAttempts":"1","retry.backoffMs":"500","retry.onFailure":"continue"}},{"id":"join-c9d0e1f2","type":"join","displayName":"并行汇总","params":{"retry.maxAttempts":"2","retry.backoffMs":"500","retry.onFailure":"continue"}},{"id":"answer","type":"answer","displayName":"生成回答","params":{"prompt":"你是企业知识助手。综合下方「制度检索」与「财务检索」结果回答，不得编造。\\n\\n若两路均为空，回复：企业知识库中暂无相关规定。\\n禁止暴露 knowledge-dual 等内部名称。\\n\\n制度检索：\\n{{rag-a1b2c3d4.output}}\\n\\n财务检索：\\n{{rag-e5f6a7b8.output}}","retry.maxAttempts":"2","retry.backoffMs":"500","retry.onFailure":"fail_fast"}}],"edges":[{"from":"start","to":"pg-a1b2c3d4"},{"from":"pg-a1b2c3d4","to":"rag-a1b2c3d4"},{"from":"pg-a1b2c3d4","to":"rag-e5f6a7b8"},{"from":"rag-a1b2c3d4","to":"join-c9d0e1f2"},{"from":"rag-e5f6a7b8","to":"join-c9d0e1f2"},{"from":"join-c9d0e1f2","to":"answer"}]}', '{"examples":["年假和报销制度一起查","制度与财务规定对比"],"nodeSummary":["start","parallel-gateway","rag","join","answer"]}', CURRENT_TIMESTAMP);
