package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.agent.runtime.AgentRole;
import com.sunshine.orchestrator.agent.runtime.AgentRunRequest;
import com.sunshine.orchestrator.catalog.ToolCatalogService;
import com.sunshine.orchestrator.config.AgentExecutionProperties;
import com.sunshine.orchestrator.memory.MemoryProperties;
import com.sunshine.orchestrator.prompt.PromptCatalogHolder;
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
    private final PromptCatalogHolder promptCatalogHolder;

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
                ac.isEnabled() ? compactionFingerprint(ac, resolveSummaryPrompt()) : "off");
        return sha256(material);
    }

    /**
     * 压缩摘要提示词 SSOT：prompt-manager Catalog id=compaction.summary-prompt；
     * Catalog 缺失时回退代码默认模板（与 Nacos 旧 summary-prompt 同源），禁止空模板压缩丢思考。
     */
    private String resolveSummaryPrompt() {
        String fromCatalog = promptCatalogHolder.snapshot().text("compaction.summary-prompt")
                .map(String::strip).orElse("");
        if (!fromCatalog.isBlank()) {
            return fromCatalog;
        }
        String fromConfig = memoryProperties.getAutoContext().getSummaryPrompt();
        return fromConfig != null && !fromConfig.isBlank()
                ? fromConfig : MemoryProperties.DEFAULT_SUMMARY_PROMPT;
    }

    /** 压缩配置指纹：任一压缩参数变化 → 新实例（缓存复用安全） */
    private String compactionFingerprint(MemoryProperties.AutoContext ac, String summaryPrompt) {
        return String.join("|",
                String.valueOf(ac.getMsgThreshold()),
                String.valueOf(ac.getLastKeep()),
                String.valueOf(ac.getTriggerTokens()),
                String.valueOf(ac.getReserved()),
                String.valueOf(ac.getKeepTokens()),
                String.valueOf(ac.getKeepTokensMin()),
                String.valueOf(ac.getKeepTokensMax()),
                String.valueOf(ac.getKeepTokensRatio()),
                String.valueOf(ac.isFlushBeforeCompact()),
                String.valueOf(ac.isOffloadBeforeCompact()),
                summaryPrompt,
                String.valueOf(ac.isTruncateArgsEnabled()),
                String.valueOf(ac.getTruncateArgsMaxChars()),
                String.valueOf(ac.isPruneEnabled()),
                String.valueOf(ac.getPruneProtectTokens()),
                String.valueOf(ac.getPruneMinTokens()),
                String.valueOf(ac.getPruneMaxOutputChars()));
    }

    private CompactionConfig buildCompactionConfig() {
        MemoryProperties.AutoContext ac = memoryProperties.getAutoContext();
        if (!ac.isEnabled()) {
            return CompactionConfig.builder().build();
        }
        CompactionConfig.Builder builder = CompactionConfig.builder()
                .triggerMessages(ac.getMsgThreshold())
                .triggerTokens(ac.getTriggerTokens())
                .reserved(ac.getReserved())
                .keepMessages(ac.getLastKeep())
                .keepTokens(ac.getKeepTokens())
                .keepTokensMin(ac.getKeepTokensMin())
                .keepTokensMax(ac.getKeepTokensMax())
                .keepTokensRatio(ac.getKeepTokensRatio())
                .flushBeforeCompact(ac.isFlushBeforeCompact())
                .offloadBeforeCompact(ac.isOffloadBeforeCompact());
        String summaryPrompt = resolveSummaryPrompt();
        if (summaryPrompt != null && !summaryPrompt.isBlank()) {
            builder.summaryPrompt(summaryPrompt);
        }
        if (ac.isTruncateArgsEnabled()) {
            builder.truncateArgs(CompactionConfig.TruncateArgsConfig.builder()
                    .triggerTokens(0)
                    .triggerMessages(0)
                    .maxArgLength(ac.getTruncateArgsMaxChars())
                    .build());
        }
        if (ac.isPruneEnabled()) {
            builder.prune(CompactionConfig.PruneConfig.builder()
                    .protectTokens(ac.getPruneProtectTokens())
                    .minimumTokens(ac.getPruneMinTokens())
                    .maxOutputChars(ac.getPruneMaxOutputChars())
                    .build());
        }
        return builder.build();
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

