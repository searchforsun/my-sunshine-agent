-- sunshine-expert-manager（expert-manager :8235）
USE sunshine_expert;

CREATE TABLE expert_definition (
    id              VARCHAR(64) PRIMARY KEY,
    display_name    VARCHAR(128) NOT NULL,
    description     VARCHAR(512),
    system_prompt   MEDIUMTEXT NOT NULL,
    enabled         TINYINT(1) NOT NULL DEFAULT 1,
    tags_json       VARCHAR(512) NOT NULL DEFAULT '[]',
    tools_json      VARCHAR(512) NOT NULL DEFAULT '[]',
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE expert_skill_link (
    expert_id       VARCHAR(64) NOT NULL,
    skill_id        VARCHAR(64) NOT NULL,
    PRIMARY KEY (expert_id, skill_id),
    CONSTRAINT fk_expert_skill_def FOREIGN KEY (expert_id) REFERENCES expert_definition (id)
);

INSERT INTO expert_definition (id, display_name, description, system_prompt, enabled, tags_json) VALUES
('policy-expert', '制度专家', '企业制度检索与条款解读', '你是制度专家。仅基于检索到的制度材料做专业分析；可质疑其他专家观点，但不对用户直接致辞。', 1, '["knowledge"]'),
('finance-expert', '财务专家', '待审批单据与财务合规分析', '你是财务专家。基于待办/单据材料做合规分析；可回应制度专家的质疑。', 1, '["finance"]'),
('compliance-expert', '合规专家', '制度与业务数据合规对比', '你是合规专家。对比制度条款与待办/单据等业务数据，指出合规缺口与风险点；可回应财务、制度专家的质疑，但不对用户直接致辞。', 1, '["compliance","finance"]'),
('legal-expert', '法务专家', '合同与法务风险审查', '你是法务专家。从合同与法律责任角度审查制度与业务材料，识别法律风险与条款冲突；可与其他专家辩论，但不对用户直接致辞。', 1, '["legal","knowledge"]');

INSERT INTO expert_skill_link (expert_id, skill_id) VALUES
('policy-expert', 'policy-review'),
('finance-expert', 'finance-analysis'),
('compliance-expert', 'compliance-check'),
('legal-expert', 'policy-review');
