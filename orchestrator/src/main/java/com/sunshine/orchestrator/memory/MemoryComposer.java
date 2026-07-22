package com.sunshine.orchestrator.memory;

import com.sunshine.orchestrator.context.AssembledContext;
import com.sunshine.orchestrator.conversation.ChatTurn;
import com.sunshine.orchestrator.memory.ltm.LtmProfileService;
import com.sunshine.orchestrator.memory.mtm.MtmService;
import com.sunshine.orchestrator.memory.stm.StmStore;
import com.sunshine.orchestrator.memory.stm.StmWindowPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 过渡：仍读旧 LTM/MTM/STM，映射为 {@link AssembledContext}（Task 4 由 ContextAssembler 替换）。
 * ltm+mtm → l2SystemBlock；stm → nearTurns。
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

    public AssembledContext compose(ComposeRequest request) {
        if (!memoryProperties.isEnabled()) {
            return AssembledContext.empty();
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
        String l2 = joinNonBlank(ltm, mtm);

        log.debug("[Memory] compose conv={} l2={} nearTurns={}",
                request.conversationId(),
                l2.isEmpty() ? 0 : 1,
                stmTurns.size());

        return new AssembledContext(l2, "", List.of(), stmTurns, "");
    }

    private static String joinNonBlank(String a, String b) {
        boolean ha = StringUtils.hasText(a);
        boolean hb = StringUtils.hasText(b);
        if (ha && hb) {
            return a.strip() + "\n" + b.strip();
        }
        if (ha) {
            return a.strip();
        }
        if (hb) {
            return b.strip();
        }
        return "";
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
