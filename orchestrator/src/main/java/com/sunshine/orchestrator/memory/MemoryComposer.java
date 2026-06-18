package com.sunshine.orchestrator.memory;

import com.sunshine.orchestrator.conversation.ChatTurn;
import com.sunshine.orchestrator.memory.ltm.LtmProfileService;
import com.sunshine.orchestrator.memory.mtm.MtmService;
import com.sunshine.orchestrator.memory.stm.StmStore;
import com.sunshine.orchestrator.memory.stm.StmWindowPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * 方案 C：LTM/MTM 摘要 + STM 完整轮次窗口。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryComposer {

    private final MemoryProperties memoryProperties;
    private final LtmProfileService ltmProfileService;
    private final MtmService mtmService;

    @Autowired(required = false)
    private StmStore stmStore;

    public MemoryContext compose(ComposeRequest request) {
        if (!memoryProperties.isEnabled()) {
            return MemoryContext.empty();
        }

        String ltm = ltmProfileService.buildSnippet(request.userId(), request.tenantId()).orElse("");
        ltmProfileService.ensureProfile(request.userId(), request.tenantId());

        String mtm = memoryProperties.getMtm().isEnabled()
                ? mtmService.recallSnippet(
                        request.userId(),
                        request.tenantId(),
                        request.currentUser(),
                        request.conversationId()).orElse("")
                : "";

        List<ChatTurn> stmSource = resolveStmSource(request);
        List<ChatTurn> stmTurns = StmWindowPolicy.selectWindow(stmSource, memoryProperties.getStm());

        log.debug("[Memory] compose conv={} ltm={} mtm={} stmTurns={}",
                request.conversationId(),
                ltm.isEmpty() ? 0 : 1,
                mtm.isEmpty() ? 0 : 1,
                stmTurns.size());

        return new MemoryContext(ltm, mtm, stmTurns);
    }

    /**
     * STM 以 MySQL 会话历史为 SSOT；Redis 仅作热缓存，不得覆盖 DB 中更完整的轮次
     * （否则直接回答路径会出现 STM 缺 assistant 正文，重复提问时 LLM 合并旧答）。
     */
    private List<ChatTurn> resolveStmSource(ComposeRequest request) {
        List<ChatTurn> fromDb = sanitizeTurns(request.loadedHistory());
        if (!fromDb.isEmpty()) {
            return fromDb;
        }
        if (stmStore != null) {
            return stmStore.load(request.userId(), request.conversationId())
                    .map(MemoryComposer::sanitizeTurns)
                    .filter(turns -> !turns.isEmpty())
                    .orElse(List.of());
        }
        return List.of();
    }

    private static List<ChatTurn> sanitizeTurns(List<ChatTurn> turns) {
        if (turns == null || turns.isEmpty()) {
            return List.of();
        }
        return turns.stream()
                .filter(t -> t.content() != null && !t.content().isBlank())
                .toList();
    }

    public record ComposeRequest(
            String userId,
            String tenantId,
            String conversationId,
            List<ChatTurn> loadedHistory,
            String currentUser
    ) {
    }
}
