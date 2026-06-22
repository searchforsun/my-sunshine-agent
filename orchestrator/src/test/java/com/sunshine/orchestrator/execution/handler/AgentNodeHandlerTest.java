package com.sunshine.orchestrator.execution.handler;

import com.sunshine.orchestrator.agent.runtime.AgentRole;
import com.sunshine.orchestrator.agent.runtime.AgentRunRequest;
import com.sunshine.orchestrator.agent.runtime.AgentRuntime;
import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.execution.ExecutionStreamContext;
import com.sunshine.orchestrator.execution.NodeSpec;
import com.sunshine.orchestrator.execution.WorkflowContext;
import com.sunshine.orchestrator.memory.MemoryContext;
import com.sunshine.orchestrator.routing.ExecutionMode;
import com.sunshine.orchestrator.routing.ExecutionPlan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentNodeHandlerTest {

    @Mock
    private AgentRuntime agentRuntime;

    @InjectMocks
    private AgentNodeHandler handler;

    @Test
    void run_buildsSubAgentRunRequestAndCollectsAnswer() {
        when(agentRuntime.run(any(AgentRunRequest.class)))
                .thenReturn(Flux.just(
                        StreamToken.content("待审批 5 笔单据存在合规风险，建议优先处理 1004。")));

        WorkflowContext ctx = new WorkflowContext();
        ExecutionStreamContext streamCtx = new ExecutionStreamContext(
                "c1", "m1", "待审批是否合规", MemoryContext.empty(),
                null, null, null, "u1", "default",
                new ExecutionPlan(ExecutionMode.WORKFLOW, "finance-smart", Map.of(), "test"));

        NodeSpec spec = new NodeSpec("analyze", "agent",
                Map.of("query", "待审批是否合规", "context", "制度摘要"));

        var result = handler.run(spec, ctx, streamCtx).block();
        assertThat(result).isNotNull();
        assertThat(result.success()).isTrue();
        assertThat(result.safeOutputs().get("answer")).contains("合规风险");
        assertThat(result.safeOutputs().get("detail")).contains("合规风险");

        ArgumentCaptor<AgentRunRequest> captor = ArgumentCaptor.forClass(AgentRunRequest.class);
        verify(agentRuntime).run(captor.capture());
        AgentRunRequest req = captor.getValue();
        assertThat(req.role()).isEqualTo(AgentRole.SUB);
        assertThat(req.query()).isEqualTo("待审批是否合规");
        assertThat(req.assistantMessageId()).isNull();
        assertThat(req.injectedBlocks()).containsExactly("制度摘要");
    }
}
