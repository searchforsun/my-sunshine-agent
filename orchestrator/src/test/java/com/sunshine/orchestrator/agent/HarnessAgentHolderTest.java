package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.agent.runtime.AgentRunRequest;
import com.sunshine.orchestrator.catalog.ToolCatalogService;
import com.sunshine.orchestrator.config.AgentExecutionProperties;
import com.sunshine.orchestrator.context.AssembledContext;
import com.sunshine.orchestrator.memory.MemoryProperties;
import com.sunshine.orchestrator.prompt.PromptCatalogHolder;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P2-1（E5）：HarnessAgentHolder 指纹缓存单测。
 * 同指纹两次 get 返回同一实例（且仅 create 一次）；不同指纹（skillId 变）返回不同实例。
 */
class HarnessAgentHolderTest {

    private static AgentRunRequest mainReq(String skillId) {
        return AgentRunRequest.main(
                AssembledContext.empty(), "q", "u1", "default", "msg-1", List.of(), skillId);
    }

    @Test
    void sameFingerprintReturnsCachedInstance() {
        HarnessAgentFactory factory = mock(HarnessAgentFactory.class);
        HarnessAgent agentA = mock(HarnessAgent.class);
        HarnessAgent agentB = mock(HarnessAgent.class);
        when(factory.fingerprint(any())).thenAnswer(inv -> {
            AgentRunRequest req = inv.getArgument(0);
            return "fp-" + (req.skillId() == null ? "none" : req.skillId());
        });
        when(factory.create(any())).thenReturn(agentA, agentB);

        HarnessAgentHolder holder = new HarnessAgentHolder(factory);
        AgentRunRequest req1 = mainReq("skill-a");
        AgentRunRequest req2 = mainReq("skill-a");
        AgentRunRequest req3 = mainReq("skill-b");

        HarnessAgent a1 = holder.get(req1);
        HarnessAgent a2 = holder.get(req2);
        HarnessAgent b = holder.get(req3);

        assertThat(a1).isSameAs(a2);
        assertThat(b).isNotSameAs(a1);
        assertThat(b).isSameAs(agentB);
        verify(factory, times(2)).create(any());
    }

    @Test
    void getAllReturnsCachedInstances() {
        HarnessAgentFactory factory = mock(HarnessAgentFactory.class);
        when(factory.fingerprint(any())).thenReturn("fp-x");
        when(factory.create(any())).thenReturn(mock(HarnessAgent.class));

        HarnessAgentHolder holder = new HarnessAgentHolder(factory);
        holder.get(mainReq(null));

        assertThat(holder.getAll()).hasSize(1);
    }

    @Test
    void differentModelOverrideProducesDifferentFingerprint() {
        // 真实 fingerprint：modelOverride 变化必须改变指纹——模型是实例不可变构建项，
        // 指纹缺模型维度会把 A 模型实例复用给 B 模型请求，静默丢弃 override。
        ReActAgentFactory reactFactory = mock(ReActAgentFactory.class);
        ToolCatalogService catalog = mock(ToolCatalogService.class);
        PromptCatalogHolder catalogHolder = mock(PromptCatalogHolder.class);
        when(catalogHolder.requireText(any())).thenReturn("summary-prompt");
        HarnessAgentFactory factory = new HarnessAgentFactory(
                reactFactory, new MemoryProperties(), new AgentExecutionProperties(),
                catalog, catalogHolder);
        when(reactFactory.resolveModel(any())).thenReturn(
                new com.sunshine.orchestrator.registry.ResolvedModelScene(
                        "model-x", null, java.util.Map.of(), 8192, 4096, null, false),
                new com.sunshine.orchestrator.registry.ResolvedModelScene(
                        "model-y", null, java.util.Map.of(), 8192, 4096, null, false));
        when(reactFactory.resolveToolkit(any())).thenReturn(mock(Toolkit.class));
        when(reactFactory.composeSystemPrompt(any())).thenReturn("sp");
        when(reactFactory.resolveMaxIters(any())).thenReturn(8);
        when(catalog.catalogVersion()).thenReturn(1L);

        AgentRunRequest reqX = mainReq(null).withModelOverride("model-x");
        AgentRunRequest reqY = mainReq(null).withModelOverride("model-y");

        assertThat(factory.fingerprint(reqX)).isNotEqualTo(factory.fingerprint(reqY));
        // 同一模型重复计算指纹稳定
        assertThat(factory.fingerprint(reqX)).isEqualTo(factory.fingerprint(reqX));
    }
}
