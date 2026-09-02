package com.sunshine.orchestrator.context;

import com.sunshine.common.model.ModelSceneKey;
import com.sunshine.orchestrator.conversation.ChatTurn;
import com.sunshine.orchestrator.conversation.entity.ChatConversationEntity;
import com.sunshine.orchestrator.conversation.repo.ChatConversationRepository;
import com.sunshine.orchestrator.context.l1.ConversationContextL1Entity;
import com.sunshine.orchestrator.context.l1.ConversationContextL1Store;
import com.sunshine.orchestrator.context.l1.L1Compressor;
import com.sunshine.orchestrator.context.l2.L2StateStore;
import com.sunshine.orchestrator.context.l3.L3RecallService;
import com.sunshine.orchestrator.workspace.repo.WorkspaceProjectGuideRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 跨轮上下文读路径：L2 system + L1 Near/Mid/Far + L3 材料（含 Far 回填）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContextAssembler {

    private final ContextProperties contextProperties;
    private final ConversationContextL1Store l1Store;
    private final L2StateStore l2StateStore;
    private final L3RecallService l3RecallService;
    private final TokenEstimator tokenEstimator;
    private final ModelWindowCache modelWindowCache;
    private final ChatConversationRepository conversationRepo;
    private final WorkspaceProjectGuideRepository projectGuideRepo;
    private final com.sunshine.orchestrator.registry.ModelSceneResolver modelSceneResolver;
    /** 同步推进压缩点（§5.5 ①）：溢出时零 LLM 前移压缩点，本轮按新 P 组装。 */
    private final L1Compressor l1Compressor;

    public AssembledContext assemble(AssembleRequest request) {
        if (!contextProperties.isEnabled()) {
            return AssembledContext.empty();
        }
        List<SessionTurn> source = sanitizeTurns(request.history());
        ContextProperties.L1 l1 = contextProperties.getL1();
        int budgetTokens = budgetTokens(request);

        ConversationContextL1Entity entity = l1Store.find(request.conversationId()).orElse(null);
        Map<String, String> midAnswers = l1Store.parseMidAnswers(entity);
        String farSummary = l1Store.farSummaryOf(entity);
        // task-scene §2.1/§2.2：task×workflow 退出本套（不注入 KV workspace/P0）；
        // 其余 task（fast|pro）读 workspace scope；chat 读 user scope。
        boolean taskWorkflow = "task".equals(request.kind())
                && "workflow".equalsIgnoreCase(request.executionMode());
        String l2Block = taskWorkflow ? ""
                : "task".equals(request.kind())
                        ? l2StateStore.assembleWorkspaceBlock(request.workspaceId(), request.tenantId())
                        : l2StateStore.assembleSystemBlock(request.userId(), request.tenantId());

        // 压缩点模式（§5.5）：Near 起点 = 压缩点（far_folded_msg_ids）之后，只增不减；
        // 其余（chat / workflow / 开关关闭）继续滑动窗基线。
        boolean compressionPoint = L1Compressor.compressionPointActive(
                contextProperties, request.kind(), request.executionMode());
        L1Compressor.WindowBands bands;
        Set<String> pointIds = Set.of();
        if (compressionPoint) {
            Set<String> foldedIds = l1Store.parseFarFoldedMsgIds(entity);
            bands = L1Compressor.partitionByPoint(source, foldedIds, midAnswers.keySet());
            // 同步推进 P（§5.5 ① / task-scene §4.2.1 / §8.2 退役并入）：L1 组装超预算时
            // 零 LLM 前移压缩点（纯写库），本轮按新 P 重组；退役轮的 LLM 折叠由轮末写路径异步补。
            // Near 永不从头部丢轮次（C2）。
            if (l1OverBudget(bands, midAnswers, farSummary, l2Block, budgetTokens)) {
                Set<String> advanced = l1Compressor.advanceCompressionPoint(
                        request.userId(), request.tenantId(), request.conversationId(),
                        request.kind(), source);
                // 契约：推进单调（P 只增不减），仅接受旧压缩点超集，防止读侧回退分区
                if (advanced != null && !advanced.equals(foldedIds) && advanced.containsAll(foldedIds)) {
                    bands = L1Compressor.partitionByPoint(source, advanced, midAnswers.keySet());
                    foldedIds = advanced;
                }
            }
            pointIds = foldedIds;
        } else {
            int nearN = Math.max(1, l1.getNearTurns());
            int midN = Math.max(0, l1.getMidTurns());
            bands = L1Compressor.partition(source, nearN, midN);
        }

        List<ChatTurn> mid = projectMid(bands.mid(), midAnswers);
        // 压缩点模式禁止从 Near 头部丢轮次（C2 敌对动作）：溢出走压缩/预算降级，不裁剪近窗
        List<ChatTurn> near = compressionPoint
                ? toChatTurns(bands.near())
                : toChatTurns(trimByTokens(bands.near(), budgetTokens, tokenEstimator));
        String farBlock = StringUtils.hasText(farSummary) ? farSummary.strip() : "";

        Set<String> nearMidIds = collectMsgIds(bands.near(), bands.mid());
        Set<String> farIds = collectMsgIds(bands.far());
        boolean farSummaryNonEmpty = StringUtils.hasText(farBlock);
        // M3：task 会话不自动注入 L3（对齐 task-scene §6.3/§6.4「只写不自动注入」），
        // 由 sunshine_session_search 按需恢复本会话正文；L3 自动召回仅 chat。
        // M0（authority §2.2 方案 A）：deferL3 时 L3 不早于资源召回——只挂分区锚点，
        // 召回交给路由后 attachL3（ReactExecutor，与业务块并行）。
        boolean l3Eligible = !"task".equals(request.kind())
                && StringUtils.hasText(request.currentUserQuery());
        String l3Block = "";
        if (!request.deferL3() && l3Eligible) {
            try {
                l3Block = l3RecallService.recall(
                        request.userId(),
                        request.tenantId(),
                        request.currentUserQuery(),
                        nearMidIds,
                        farIds,
                        farSummaryNonEmpty);
            } catch (Exception e) {
                log.warn("[Context] L3 recall 失败 conv={}: {}", request.conversationId(), e.getMessage());
            }
        }
        AssembledContext.L3Anchor anchor = request.deferL3() && l3Eligible
                ? new AssembledContext.L3Anchor(nearMidIds, farIds, farSummaryNonEmpty)
                : AssembledContext.L3Anchor.EMPTY;

        AssembledContext assembled = new AssembledContext(
                l2Block != null ? l2Block : "",
                farBlock,
                mid,
                near,
                l3Block != null ? l3Block : "",
                taskWorkflow ? "" : resolveProjectGuide(request.conversationId()),
                "")
                .withL3Anchor(anchor);
        // 压缩点模式：Budget「丢」改「退役并入」（§8.2）——Mid 退役进 P 而非静默丢；
        // 滑动窗模式保持基线静默丢弃（L3→Far→Mid，Near/L2 永不丢）。
        AssembledContext trimmed = compressionPoint
                ? applyBudgetAtPoint(request, source, assembled, midAnswers, pointIds, budgetTokens)
                : applyBudget(assembled, budgetTokens, tokenEstimator);

        log.debug("[Context] assemble conv={} l2={} far={} mid={} near={} l3={} guide={}",
                request.conversationId(),
                trimmed.l2SystemBlock().isBlank() ? 0 : 1,
                trimmed.farSummaryBlock().isBlank() ? 0 : 1,
                trimmed.midTurns().size(),
                trimmed.nearTurns().size(),
                trimmed.l3MaterialBlock().isBlank() ? 0 : 1,
                trimmed.projectGuideBlock().isBlank() ? 0 : 1);
        return trimmed;
    }

    /**
     * 压缩点模式预算裁剪——「丢」改「退役并入」（§8.2）：超预算不静默丢弃，
     * 而是把最旧活跃轮次退役进压缩点（P），由轮末写路径异步折叠进 far_summary，
     * 保住「压缩不删原文、摘要可查」。顺序：丢 L3（零损失）→ 退役 Mid 头部 → 丢 Far 摘要块；
     * Near 与 L2 永不丢（既有不变量）。
     */
    private AssembledContext applyBudgetAtPoint(
            AssembleRequest request, List<SessionTurn> source, AssembledContext ctx,
            Map<String, String> midAnswers, Set<String> pointIds, int budgetTokens) {
        if (ctx == null) {
            return AssembledContext.empty();
        }
        if (budgetTokens <= 0 || tokenEstimator.countAssembled(ctx) <= budgetTokens) {
            return ctx;
        }
        List<ChatTurn> mid = ctx.midTurns();
        List<ChatTurn> near = ctx.nearTurns();
        String far = ctx.farSummaryBlock();
        // ① 丢 L3（零语义损失，run 内召回下一轮重新拉取）
        AssembledContext dropL3 = new AssembledContext(
                ctx.l2SystemBlock(), far, mid, near, "", ctx.projectGuideBlock(), "");
        if (tokenEstimator.countAssembled(dropL3) <= budgetTokens) {
            return dropL3;
        }
        // ② 退役 Mid 头部进压缩点（零 LLM；S 不动，写路径异步折叠进 far_summary）
        Set<String> advanced = l1Compressor.advanceCompressionPoint(
                request.userId(), request.tenantId(), request.conversationId(), request.kind(), source);
        if (advanced != null && !advanced.equals(pointIds) && advanced.containsAll(pointIds)) {
            L1Compressor.WindowBands reBands =
                    L1Compressor.partitionByPoint(source, advanced, midAnswers.keySet());
            mid = projectMid(reBands.mid(), midAnswers);
            near = toChatTurns(reBands.near());
            AssembledContext retired = new AssembledContext(
                    ctx.l2SystemBlock(), far, mid, near, "", ctx.projectGuideBlock(), "");
            if (tokenEstimator.countAssembled(retired) <= budgetTokens) {
                return retired;
            }
        }
        // ③ 仍超预算 → 丢 Far 摘要块（原文已在压缩点内，可再折叠 / L3 回填）；Near / L2 永不丢
        return new AssembledContext(
                ctx.l2SystemBlock(), "", mid, near, "", ctx.projectGuideBlock(), "");
    }

    /** L1 组装块（L2 + Far + Mid + Near，不含 L3）是否超预算——同步推进 P 的触发判据。 */
    private boolean l1OverBudget(
            L1Compressor.WindowBands bands, Map<String, String> midAnswers,
            String farSummary, String l2Block, int budgetTokens) {
        if (budgetTokens <= 0) {
            return false;
        }
        int total = tokenEstimator.count(l2Block) + tokenEstimator.count(farSummary);
        total += tokenEstimator.count(projectMid(bands.mid(), midAnswers));
        total += tokenEstimator.count(toChatTurns(bands.near()));
        return total > budgetTokens;
    }

    /** 模型窗口 × L1 max-tokens-ratio 的组装预算（attachL3 与 assemble 同源）。 */
    private int budgetTokens(AssembleRequest request) {
        String effectiveModel = StringUtils.hasText(request.modelName())
                ? modelSceneResolver.resolveChat(request.modelName()).effectiveModel()
                : modelSceneResolver.resolve(ModelSceneKey.CHAT.key(), null).effectiveModel();
        int modelWindow = modelWindowCache.windowFor(effectiveModel);
        return (int) (modelWindow * contextProperties.getL1().getMaxTokensRatio());
    }

    /**
     * L3 延后装配（authority §2.2 方案 A / M0）：assemble 以 deferL3 挂载分区锚点后，
     * 本方法在资源召回后调用——按锚点排除 Near/Mid 已覆盖消息召回 L3，并做剩余预算裁剪。
     * L3 是「绝对尾部动态段」（五层 §7.5）：仅当剩余预算可容纳时注入，超限丢弃（与
     * 预算链「L3 最先让位」语义一致）。无锚点（task / 无 query / 未 defer）或已含 L3 时原样返回。
     */
    public AssembledContext attachL3(AssembledContext base, AssembleRequest request) {
        if (base == null) {
            return null;
        }
        if (base.l3Anchor() == null || AssembledContext.L3Anchor.EMPTY.equals(base.l3Anchor())
                || StringUtils.hasText(base.l3MaterialBlock())) {
            return base;
        }
        AssembledContext.L3Anchor anchor = base.l3Anchor();
        String l3Block;
        try {
            l3Block = l3RecallService.recall(
                    request.userId(), request.tenantId(), request.currentUserQuery(),
                    anchor.excludeMsgIds(), anchor.farMsgIds(), anchor.farSummaryNonEmpty());
        } catch (Exception e) {
            log.warn("[Context] attachL3 recall 失败 conv={}: {}", request.conversationId(), e.getMessage());
            return base;
        }
        if (!StringUtils.hasText(l3Block)) {
            log.debug("[Context] attachL3 conv={} recall 空", request.conversationId());
            return base;
        }
        int budgetTokens = budgetTokens(request);
        int used = tokenEstimator.countAssembled(base);
        if (budgetTokens > 0 && used + tokenEstimator.count(l3Block) > budgetTokens) {
            log.debug("[Context] attachL3 超剩余预算，丢弃 conv={} used={} l3={}",
                    request.conversationId(), used, tokenEstimator.count(l3Block));
            return base;
        }
        log.debug("[Context] attachL3 conv={} l3=1 tokens={}", request.conversationId(), tokenEstimator.count(l3Block));
        return base.withL3MaterialBlock(l3Block);
    }

    /**
     * 项目规范（类 CLAUDE.md）：会话所属工作区（kind=task）的共享规范，注入静态 system 层。
     * 读取失败降级为空串，不影响主链路。
     */
    private String resolveProjectGuide(String conversationId) {
        if (!StringUtils.hasText(conversationId)) {
            return "";
        }
        try {
            return conversationRepo.findById(conversationId)
                    .map(ChatConversationEntity::getWorkspaceId)
                    .filter(StringUtils::hasText)
                    .flatMap(projectGuideRepo::findById)
                    .map(g -> g.getContent() != null ? g.getContent().strip() : "")
                    .orElse("");
        } catch (Exception e) {
            log.warn("[Context] project guide 读取失败 conv={}: {}", conversationId, e.getMessage());
            return "";
        }
    }

    /**
     * 预算裁剪（token）：先丢 L3 → 再丢 Far → Mid 从头整轮丢弃 → 永不丢 L2（含 constraint 行）。
     * Near 已在组装路径 trimByTokens；此处在 L3/Far 仍超预算时再裁 Mid。
     */
    static AssembledContext applyBudget(AssembledContext ctx, int maxTokens, TokenEstimator estimator) {
        if (ctx == null) {
            return AssembledContext.empty();
        }
        if (maxTokens <= 0 || estimator == null) {
            return ctx;
        }
        if (estimator.countAssembled(ctx) <= maxTokens) {
            return ctx;
        }
        AssembledContext dropL3 = new AssembledContext(
                ctx.l2SystemBlock(),
                ctx.farSummaryBlock(),
                ctx.midTurns(),
                ctx.nearTurns(),
                "",
                ctx.projectGuideBlock(),
                "");
        if (estimator.countAssembled(dropL3) <= maxTokens) {
            return dropL3;
        }
        AssembledContext dropFar = new AssembledContext(
                ctx.l2SystemBlock(),
                "",
                ctx.midTurns(),
                ctx.nearTurns(),
                "",
                ctx.projectGuideBlock(),
                "");
        if (estimator.countAssembled(dropFar) <= maxTokens) {
            return dropFar;
        }
        List<ChatTurn> mid = ctx.midTurns() != null
                ? new ArrayList<>(ctx.midTurns())
                : new ArrayList<>();
        while (!mid.isEmpty() && estimator.countAssembled(new AssembledContext(
                ctx.l2SystemBlock(), "", mid, ctx.nearTurns(), "", ctx.projectGuideBlock(), "")) > maxTokens) {
            mid.remove(0);
        }
        return new AssembledContext(
                ctx.l2SystemBlock(),
                "",
                List.copyOf(mid),
                ctx.nearTurns(),
                "",
                ctx.projectGuideBlock(),
                "");
    }

    @SafeVarargs
    static Set<String> collectMsgIds(List<SessionTurn>... bands) {
        Set<String> ids = new HashSet<>();
        if (bands == null) {
            return ids;
        }
        for (List<SessionTurn> band : bands) {
            if (band == null) {
                continue;
            }
            for (SessionTurn t : band) {
                if (t != null && StringUtils.hasText(t.messageId())) {
                    ids.add(t.messageId());
                }
            }
        }
        return ids;
    }

    static List<ChatTurn> projectMid(List<SessionTurn> midBand, Map<String, String> midAnswers) {
        if (midBand == null || midBand.isEmpty()) {
            return List.of();
        }
        Map<String, String> answers = midAnswers != null ? midAnswers : Map.of();
        List<ChatTurn> out = new ArrayList<>(midBand.size());
        for (SessionTurn turn : midBand) {
            if ("assistant".equals(turn.role())
                    && StringUtils.hasText(turn.messageId())
                    && answers.containsKey(turn.messageId())) {
                // Mid 结构（§5.5.8 / §6.5）：摘要/短结论 + 原样 schema 行（确定性，零 LLM）
                out.add(appendSchema(answers.get(turn.messageId()), turn));
            } else {
                out.add(appendSchema(turn.content(), turn));
            }
        }
        return List.copyOf(out);
    }

    static List<ChatTurn> toChatTurns(List<SessionTurn> turns) {
        if (turns == null || turns.isEmpty()) {
            return List.of();
        }
        return turns.stream().map(ContextAssembler::appendProcess).toList();
    }

    /** task Near 完整过程（§6.6）：assistant 轮按 processLines（think + tool 序列原文）渲染，user 轮原样 */
    private static ChatTurn appendProcess(SessionTurn turn) {
        if (turn == null) {
            return new ChatTurn("assistant", null);
        }
        if (turn.processLines() != null) {
            StringBuilder sb = new StringBuilder();
            for (String line : turn.processLines()) {
                sb.append(line).append('\n');
            }
            if (turn.content() != null) {
                sb.append(turn.content());
            }
            return new ChatTurn(turn.role(), sb.toString());
        }
        return appendSchema(turn.content(), turn);
    }

    /** 工具轮确定性 schema 行原样附加在轮次正文末尾（仅 assistant 且非空） */
    private static ChatTurn appendSchema(String content, SessionTurn turn) {
        if (turn == null || turn.toolSchemaLines() == null) {
            return new ChatTurn(turn != null ? turn.role() : "assistant", content);
        }
        StringBuilder sb = new StringBuilder(content != null ? content : "");
        for (String line : turn.toolSchemaLines()) {
            sb.append('\n').append(line);
        }
        return new ChatTurn(turn.role(), sb.toString());
    }

    /** 超 maxTokens 从头整条丢弃（不截断单条 content）；入参为 Near 带 SessionTurn。 */
    static List<SessionTurn> trimByTokens(List<SessionTurn> turns, int maxTokens, TokenEstimator estimator) {
        if (turns == null || turns.isEmpty()) {
            return List.of();
        }
        if (maxTokens <= 0 || estimator == null) {
            return List.copyOf(turns);
        }
        int total = 0;
        for (SessionTurn t : turns) {
            total += t != null ? estimator.count(renderedContent(t)) : 0;
        }
        if (total <= maxTokens) {
            return List.copyOf(turns);
        }
        List<SessionTurn> out = new ArrayList<>(turns);
        while (!out.isEmpty() && total > maxTokens) {
            SessionTurn removed = out.remove(0);
            total -= removed != null ? estimator.count(renderedContent(removed)) : 0;
        }
        return List.copyOf(out);
    }

    /** Near 轮完整渲染内容（含完整过程行 / schema 行）——预算估算与实际注入一致 */
    private static String renderedContent(SessionTurn turn) {
        ChatTurn rendered = appendProcess(turn);
        return rendered != null && rendered.content() != null ? rendered.content() : "";
    }

    private static List<SessionTurn> sanitizeTurns(List<SessionTurn> turns) {
        if (turns == null || turns.isEmpty()) {
            return List.of();
        }
        return turns.stream()
                .filter(t -> t.content() != null && !t.content().isBlank())
                .toList();
    }

    public record AssembleRequest(
            String userId,
            String tenantId,
            String conversationId,
            List<SessionTurn> history,
            String currentUserQuery,
            String modelName,
            String kind,
            String workspaceId,
            /** 执行模式（fast|pro|workflow）；task×workflow 退出本套（不注入 workspace/P0，task-scene §2.2） */
            String executionMode,
            /** L3 延后装配（M0 · authority §2.2 方案 A）：路由前仅底座，L3 由路由后 attachL3 召回 */
            boolean deferL3
    ) {
    }
}
