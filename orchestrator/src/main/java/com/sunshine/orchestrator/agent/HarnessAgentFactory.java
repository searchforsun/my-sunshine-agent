package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.agent.runtime.AgentRole;
import com.sunshine.orchestrator.agent.runtime.AgentRunRequest;
import com.sunshine.orchestrator.memory.MemoryProperties;
import io.agentscope.core.ReActAgent;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 将 {@link ReActAgentFactory} 创建的 ReActAgent 包装为 {@link HarnessAgent}，
 * 启用原生 CompactionConfig（替代已删除的 AutoContextHook）与 stateStore checkpoint 语义。
 * <p>
 * HarnessAgent 默认注入 filesystem/shell/memory/subagent/skill 等工具，此处全部 disable，
 * 确保工具集仅含 ReActAgentFactory 已声明的 Toolkit（财务/OA/HR/RAG/沙箱等）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HarnessAgentFactory {

    private final ReActAgentFactory reactAgentFactory;
    private final MemoryProperties memoryProperties;

    public HarnessAgent create(AgentRunRequest request) {
        ReActAgent reactAgent = reactAgentFactory.create(request);
        HarnessAgent harness = HarnessAgent.builder()
                .fromAgent(reactAgent)
                .compaction(buildCompactionConfig())
                .disableFilesystemTools()
                .disableShellTool()
                .disableMemoryTools()
                .disableMemoryHooks()
                .disableSessionPersistence()
                .disableWorkspaceContext()
                .disableAtPathExpansion()
                .disableSubagents()
                .disableDynamicSubagents()
                .disableDynamicSkills()
                .disableDefaultWorkspaceSkills()
                .disableToolsConfig()
                .build();
        log.info("[HarnessAgentFactory] role={} skill={} wrapped ReActAgent -> HarnessAgent (compaction enabled)",
                request.role(), request.skillId());
        return harness;
    }

    private CompactionConfig buildCompactionConfig() {
        MemoryProperties.AutoContext ac = memoryProperties.getAutoContext();
        if (!ac.isEnabled()) {
            return CompactionConfig.builder().build();
        }
        return CompactionConfig.builder()
                .triggerMessages(ac.getMsgThreshold())
                .keepMessages(ac.getLastKeep())
                .build();
    }
}
