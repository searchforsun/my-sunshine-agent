package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.agent.runtime.AgentRole;
import com.sunshine.orchestrator.agent.runtime.AgentRunRequest;
import com.sunshine.orchestrator.catalog.ToolCatalogService;
import com.sunshine.orchestrator.config.AgentExecutionProperties;
import com.sunshine.orchestrator.memory.MemoryProperties;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * 将 {@link ReActAgentFactory} 创建的 ReActAgent 包装为 {@link HarnessAgent}，
 * 启用原生 CompactionConfig（替代已删除的 AutoContextHook）与 stateStore checkpoint 语义。
 * <p>
 * HarnessAgent 默认注入 filesystem/shell/memory/subagent/skill 等工具，此处全部 disable，
 * 确保工具集仅含 ReActAgentFactory 已声明的 Toolkit（财务/OA/HR/RAG/沙箱等）。
 * <p>
 * 持久化：官方自动持久化由 ReActAgent 自身持有（{@code .stateStore(stateStore)}），
 * 本类不再叠加 disable——{@code disableSessionPersistence()} 自 2.0 起为 no-op。
 * 优雅停机：官方 {@code GracefulShutdownManager}（JVM shutdown hook + middleware 首位）
 * 自动 interrupt 在飞请求并落 {@code shutdown_interrupted}，重启后经
 * {@code checkAndClearShutdownInterrupted} 去重续跑，无需自研 ShutdownHook。
 * <p>
 * P2-1（E5）：实例经 {@link HarnessAgentHolder} 按 {@link #fingerprint(AgentRunRequest)}
 * 缓存复用——fingerprint 覆盖全部不可变构建项（sysPrompt/toolkit/maxIters/taskboard/catalog 版本），
 * 任意一项变化即新建，不 mutate 存活实例。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HarnessAgentFactory {

    private final ReActAgentFactory reactAgentFactory;
    private final MemoryProperties memoryProperties;
    private final AgentExecutionProperties executionProperties;
    private final ToolCatalogService toolCatalogService;

    public HarnessAgent create(AgentRunRequest request) {
        ReActAgent reactAgent = reactAgentFactory.create(request);
        HarnessAgent harness = HarnessAgent.builder()
                .fromAgent(reactAgent)
                .compaction(buildCompactionConfig())
                .disableFilesystemTools()
                .disableShellTool()
                .disableMemoryTools()
                .disableMemoryHooks()
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

    /**
     * 配置指纹（E5）：不可变构建项全量哈希。
     * role + skillId + tenantId + sysPrompt(含 overlay) + 工具名集合 + maxIters
     * + taskboard 开关 + subagent 开关 + catalogVersion + 压缩配置。
     * 任一项变化 → 指纹变化 → Holder 新建实例；相同 → 等价复用。
     */
    public String fingerprint(AgentRunRequest request) {
        Toolkit toolkit = reactAgentFactory.resolveToolkit(request);
        List<String> toolNames = toolkit.getToolNames().stream().sorted().toList();
        AgentExecutionProperties.React react = executionProperties.getReact();
        boolean taskboard = request.role() == AgentRole.MAIN
                && react != null && react.getTaskboard() != null && react.getTaskboard().isEnabled();
        boolean subagentEnabled = react != null && react.getSubagent() != null && react.getSubagent().isEnabled();
        MemoryProperties.AutoContext ac = memoryProperties.getAutoContext();
        String material = String.join("\u0001",
                String.valueOf(request.role()),
                nullToEmpty(request.skillId()),
                nullToEmpty(request.tenantId()),
                reactAgentFactory.composeSystemPrompt(request),
                String.join(",", toolNames),
                String.valueOf(reactAgentFactory.resolveMaxIters(request)),
                String.valueOf(taskboard),
                String.valueOf(subagentEnabled),
                String.valueOf(toolCatalogService.catalogVersion()),
                ac.isEnabled() ? ac.getMsgThreshold() + ":" + ac.getLastKeep() : "off");
        return sha256(material);
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

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String sha256(String material) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

