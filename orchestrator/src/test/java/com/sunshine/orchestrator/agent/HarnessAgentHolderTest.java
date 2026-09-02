package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.agent.runtime.AgentRunRequest;
import com.sunshine.orchestrator.context.AssembledContext;
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
}
