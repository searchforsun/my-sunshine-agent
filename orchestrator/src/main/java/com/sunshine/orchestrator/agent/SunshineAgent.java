package com.sunshine.orchestrator.agent;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.message.Msg;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * Sunshine Agent — 封装 AgentScope ReActAgent
 */
@Slf4j
@Component
public class SunshineAgent {

    private final ReActAgent agent;

    public SunshineAgent(ReActAgent agent) {
        this.agent = agent;
    }

    @PostConstruct
    public void init() {
        log.info("[Orchestrator] Agent 就绪: name={}, maxIters={}",
                agent.getName(), agent.getMaxIters());
    }

    /**
     * 流式对话 — 启用 AgentScope 原生增量流式
     */
    public Flux<String> chat(String userMessage, String userId, String tenantId) {
        log.info("[Orchestrator] user={}, msg={}", userId,
                userMessage != null && userMessage.length() > 60
                        ? userMessage.substring(0, 60) + "..."
                        : userMessage);

        Msg input = Msg.builder()
                .textContent(userMessage)
                .build();

        // incremental(true): AgentScope 逐 token 发射增量文本而非全量累积
        StreamOptions options = StreamOptions.builder()
                .incremental(true)
                .build();

        return agent.stream(List.of(input), options)
                .filter(event -> {
                    Msg msg = event.getMessage();
                    return msg != null
                            && msg.getTextContent() != null
                            && !msg.getTextContent().isEmpty();
                })
                .map(event -> event.getMessage().getTextContent())
                .doOnComplete(() -> log.info("[Orchestrator] 流式完成"))
                .doOnError(e -> log.error("[Orchestrator] 异常: {}", e.getMessage(), e));
    }
}
