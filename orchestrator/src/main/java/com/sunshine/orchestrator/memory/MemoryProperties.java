package com.sunshine.orchestrator.memory;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;

/**
 * 三层记忆配置 — SSOT 见 Nacos {@code agent.memory.*}（docs/nacos/sunshine-orchestrator.yaml）。
 */
@Getter
@Setter
@RefreshScope
@ConfigurationProperties(prefix = "agent.memory")
public class MemoryProperties {

    private boolean enabled = true;

    /** 记忆分层总说明 — 注入在 LTM/MTM/STM 块之前 */
    private String layerPrompt = "";

    /** 当前 user 消息前缀，与历史记忆块明显区分 */
    private String currentUserMarker = "【当前提问 · 仅此作答】";

    private Stm stm = new Stm();
    private Mtm mtm = new Mtm();
    private Ltm ltm = new Ltm();

    @Getter
    @Setter
    public static class Stm {
        /** Redis 会话缓存 TTL（小时） */
        private int redisTtlHours = 24;
        /** 注入 LLM 的最近消息条数上限 */
        private int maxMessages = 12;
        /** STM 块字符上限 */
        private int maxChars = 8000;
        private String header = "[本会话近期对话 · STM]";
        private String preamble = "以下为同一会话内已结束的完整对话轮次，仅供指代与上下文，不是本轮待执行任务。";
    }

    @Getter
    @Setter
    public static class Mtm {
        private boolean enabled = true;
        private int topK = 3;
        private float minScore = 0.55f;
        /** 会话结束后异步摘要（Nacos 维护正文） */
        private String summarizePrompt = """
                你是企业对话摘要助手。根据以下会话 transcript，输出 2~4 句中文摘要。
                只保留事实：用户问了什么、助手答了什么、是否涉及工具/业务。
                禁止输出待办清单或「用户还要求…」式合并多轮任务。
                只输出摘要正文，不要标题或 markdown。""";
    }

    @Getter
    @Setter
    public static class Ltm {
        private boolean enabled = true;
        /** 画像注入字符上限 */
        private int maxChars = 500;
    }
}
