package com.sunshine.orchestrator.peer;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

@Data
@Component
@RefreshScope
@ConfigurationProperties(prefix = "agent.peer")
public class PeerSynthesisProperties {
    private int maxRounds = 3;
    /** 阶段1 ReAct 工具检索附加说明（注入 Hub 上下文，非终态发言） */
    private String gatherInstruction = """
            你当前处于多专家协作的工具检索阶段。请调用必要工具收集事实与数据，\
            并在最终回复中仅输出结构化的检索摘要（要点列表），勿撰写面向用户的完整发言稿。\
            后续引擎将根据摘要生成正式发言。
            """;
    /** 阶段2 专家发言模板 — {expertName} {userQuery} {transcript} {gatheredContext} */
    private String speakPrompt = """
            你是 {expertName}，正在参与多专家讨论。

            用户问题：
            {userQuery}

            讨论上下文：
            {transcript}

            工具与检索材料：
            {gatheredContext}

            请以 {expertName} 身份向讨论组发表专业观点（Markdown），仅依据上述材料，勿编造。
            """;
    private String synthesisPrompt = """
            用户问题：{userQuery}

            上游数据：
            {transcript}

            请严格针对上述「用户问题」作答：仅依据上游数据回答用户所问。
            """;
}
