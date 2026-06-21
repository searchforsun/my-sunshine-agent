package com.sunshine.orchestrator.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

/** Query 改写配置（Task 3.8.1：rag / intent / empty-recall） */
@Data
@Component
@RefreshScope
@ConfigurationProperties(prefix = "agent.rewrite")
public class AgentRewriteProperties {

    private Rag rag = new Rag();
    private Intent intent = new Intent();
    private EmptyRecall emptyRecall = new EmptyRecall();

    @Data
    public static class Rag {
        /** 进入 RAG 检索前改写；Nacos 默认开启 */
        private boolean enabled = true;
        private String model = "deepseek-v4-flash";
        private String systemPrompt = """
                你是企业知识库检索 query 优化助手。用户问题将用于向量/混合检索。
                请补全域内关键词（制度、流程、报销、请假、差旅、考勤等），标准化专有名词表述。
                保留原意，不要编造事实；若已足够清晰则轻微润色即可。
                只输出 JSON：{"query":"优化后的检索 query"}，不要 markdown 或其他文字。
                """;
        /** HyDE：生成假想制度片段再检索（默认关闭，Nacos agent.rewrite.rag.hyde.enabled） */
        private Hyde hyde = new Hyde();
    }

    @Data
    public static class Hyde {
        private boolean enabled = false;
        private String model = "deepseek-v4-flash";
        /** 假想文档最大字符数（检索 query 截断） */
        private int maxChars = 480;
        private String systemPrompt = """
                你是企业知识库 HyDE 助手。根据用户问题，写一段**可能出现在企业制度/流程文档中**的中文段落，
                用于向量检索匹配；不要写问答体，不要写「根据…规定」等元叙述，直接写制度条文式正文。
                只引用常见域内概念（报销、差旅、请假、考勤、审批等），**禁止编造**具体金额/日期/人名。
                只输出 JSON：{"document":"假想文档段落"}，不要 markdown 或其他文字。
                """;
    }

    @Data
    public static class Intent {
        /** 规则未命中且短 query 时补全意图；Nacos 默认开启 */
        private boolean enabled = true;
        private String model = "deepseek-v4-flash";
        /** 低于此字数（不含）才触发 intent 改写 */
        private int maxChars = 8;
        private String systemPrompt = """
                你是企业助手意图补全助手。用户输入过短，需补全为可分类的完整中文问句。
                保留原意，补充「查询/了解/请问」等必要语境，偏向制度、财务待办、知识库问答。
                不要编造具体业务事实。
                只输出 JSON：{"query":"补全后的问句"}，不要 markdown 或其他文字。
                """;
    }

    @Data
    public static class EmptyRecall {
        /** RAG 首次 0 命中二次检索；Nacos 默认开启 */
        private boolean enabled = true;
        private String model = "deepseek-v4-flash";
        private int maxAlternatives = 2;
        private String systemPrompt = """
                你是企业知识库检索 query 改写助手。用户原始问题在向量/混合检索中零命中。
                请生成 %d 个不同表述的中文检索 query，用于二次检索。
                要求：保留原意、补充制度/流程/报销/请假等域内关键词，不要编造事实。
                只输出 JSON：{"queries":["改写1","改写2"]}，不要 markdown 或其他文字。
                """;
    }
}
