package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.conversation.ChatTurn;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
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

    public Flux<String> chat(String userMessage, String userId, String tenantId) {
        return chat(List.of(), userMessage, userId, tenantId);
    }

    /**
     * 流式对话 — history 由 DB 注入，不依赖全局 Memory
     */
    public Flux<String> chat(List<ChatTurn> history, String userMessage, String userId, String tenantId) {
        log.info("[Orchestrator] user={}, history={}, msg={}", userId,
                history != null ? history.size() : 0,
                userMessage != null && userMessage.length() > 60
                        ? userMessage.substring(0, 60) + "..."
                        : userMessage);

        List<Msg> inputs = new ArrayList<>();
        if (history != null) {
            for (ChatTurn turn : history) {
                MsgRole role = "assistant".equals(turn.role()) ? MsgRole.ASSISTANT : MsgRole.USER;
                inputs.add(Msg.builder()
                        .role(role)
                        .textContent(turn.content())
                        .build());
            }
        }
        inputs.add(Msg.builder()
                .role(MsgRole.USER)
                .textContent(userMessage)
                .build());

        StreamOptions options = StreamOptions.builder()
                .incremental(true)
                .build();

        return agent.stream(inputs, options)
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
