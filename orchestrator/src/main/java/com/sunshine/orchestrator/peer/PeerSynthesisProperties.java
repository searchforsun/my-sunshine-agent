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
    private int minRounds = 1;
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

            请严格针对上述「用户问题」作答：仅依据上游数据回答用户所问。使用 Markdown；加粗标记须成对。
            """;
    private String roundContinuePrompt = """
            你是多专家讨论轮次协调助手。根据用户问题与当前讨论记录，判断是否还需下一轮专家发言。
            若观点已收敛、无新事实待查、无未回应质疑，则 continue=false；若仍存在分歧、缺材料或未回应的质疑，则 continue=true。
            只输出 JSON：{"continue":true或false,"reason":"一句话说明"}，不要 markdown。
            """;
    private String roundSpeakersPrompt = """
            你是多专家讨论发言调度助手。第 1 轮全员已发言；请从候选专家中选出第 2 轮及以后仍需发言的人：
            仅包含对其它观点有异议、需补充材料、或尚未回应关键质疑的专家；无异议者不要选。
            若无人需要再发言，输出空数组 expertIds:[]。
            只输出 JSON：{"expertIds":["id1"],"reason":"一句话说明"}，不要 markdown。
            """;
}
