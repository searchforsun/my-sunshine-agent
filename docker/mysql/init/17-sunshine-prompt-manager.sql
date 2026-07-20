-- sunshine-prompt-manager（prompt-manager :8500）
USE sunshine_prompt;

CREATE TABLE prompt_definition (
    id              VARCHAR(128) PRIMARY KEY,
    kind            VARCHAR(32)  NOT NULL,
    display_name    VARCHAR(128) NOT NULL,
    description     VARCHAR(512) NULL,
    enabled         TINYINT(1)   NOT NULL DEFAULT 1,
    priority        INT          NOT NULL DEFAULT 0,
    active_version  INT          NOT NULL DEFAULT 1,
    catalog_version BIGINT       NOT NULL DEFAULT 1,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_prompt_kind (kind),
    KEY idx_prompt_priority (priority)
);

CREATE TABLE prompt_version (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    prompt_id       VARCHAR(128) NOT NULL,
    version         INT          NOT NULL,
    status          VARCHAR(24)  NOT NULL DEFAULT 'published',
    content_text    MEDIUMTEXT   NULL,
    content_json    MEDIUMTEXT   NULL,
    change_note     VARCHAR(512) NULL,
    maintainer      VARCHAR(64)  NULL,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_prompt_version (prompt_id, version),
    CONSTRAINT fk_prompt_version_def FOREIGN KEY (prompt_id) REFERENCES prompt_definition (id)
);

CREATE TABLE prompt_catalog_meta (
    id              TINYINT PRIMARY KEY DEFAULT 1,
    catalog_version BIGINT NOT NULL DEFAULT 1,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);
INSERT INTO prompt_catalog_meta (id, catalog_version) VALUES (1, 1);

INSERT INTO prompt_definition (id, kind, display_name, enabled, priority, active_version) VALUES
('routing-rule.structural-plan', 'routing-rule', '多步跨域→Plan', 1, 100, 1),
('routing-rule.peer-phrase', 'routing-rule', 'Peer句式→协作', 1, 90, 1),
('routing-rule.rule-finance-smart-compliance', 'routing-rule', '财务合规→finance-smart', 1, 20, 1),
('routing-rule.rule-knowledge-budget-travel', 'routing-rule', '预算出差→knowledge-qa', 1, 15, 1),
('routing-rule.rule-finance-list-pending', 'routing-rule', '待审批列表→finance-list', 1, 10, 1);

INSERT INTO prompt_version (prompt_id, version, status, content_json) VALUES
('routing-rule.structural-plan', 1, 'published',
 '{"matchType":"structural","minDomainGroups":2,"patterns":["先.+再","再.+(并|然后|接着)","分步","多步","并对.+?(分析|审查|检查|评估)","完整处理","一套.+(分析|流程|处理)"],"domainGroups":{"knowledge":["制度","检索","知识库","政策","差旅办法","报销规定"],"finance":["待审批","报销","财务","付款","单据"],"analysis":["合规","分析","审查","对比","评估","结论"]},"plan":{"mode":"plan-workflow","params":{}}}'),
('routing-rule.peer-phrase', 1, 'published',
 '{"matchType":"peer_phrase","patterns":["互相验证","交叉审查","多专家讨论","分别分析并质疑","两个角度.*审查","专家.*分别.*审查"],"plan":{"mode":"peer-collab","params":{}}}'),
('routing-rule.rule-finance-smart-compliance', 1, 'published',
 '{"matchType":"regex","match":"any","patterns":["是否合规","合规吗","合不合规","对比制度"],"plan":{"mode":"workflow","workflowId":"finance-smart","params":{"status":"pending"}}}'),
('routing-rule.rule-knowledge-budget-travel', 1, 'published',
 '{"matchType":"regex","match":"any","patterns":["预算.*出差","出差.*预算","预算超支","预算不够.*出差"],"plan":{"mode":"workflow","workflowId":"knowledge-qa","params":{}}}'),
('routing-rule.rule-finance-list-pending', 1, 'published',
 '{"matchType":"regex","match":"any","patterns":["有哪些待审批","查询待审批","列出待审批","待审批的.*报销","待审批.*付款"],"plan":{"mode":"workflow","workflowId":"finance-list","params":{"status":"pending"}}}');
