package com.sunshine.orchestrator.context.l3;

import com.sunshine.orchestrator.agent.ProcessingStep;
import com.sunshine.orchestrator.agent.ProcessingStepSerde;
import com.sunshine.orchestrator.context.ContextProperties;
import com.sunshine.orchestrator.context.ContextProperties.L3;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * L3 对话历史 ingest：
 * ① body 层（原文 chunk）— v28 chat 场景退役（仅 task 保留，供 session_search 深挖原文）；
 *    task 写路径经 turn-pair 攒批 + 语义提取按轮门禁：abstain 轮 body+semantic 均不落库
 *    （v26.2 body 非全量；门禁关闭走即时全量）；scene=chat|task（scene 隔离写入）；
 * ② semantic 层（v26 §7.4）— turn-pair 攒批缓冲（N 轮 / M 分钟触发）→ LLM 语义提取 → 独立入库 layer=semantic；
 *    v28 chat 场景 L3 仅此层；语义提取前经 L2 对账（与 L2 结构化内容重复的段 abstain）；
 * ③ process 层（v26 §7.4.4）— task 会话 assistant 消息的 ProcessingStep.result 截断 200 chars → layer=process。
 * 分块与去重在 rag-service 侧完成；失败仅日志，不阻断用户路径。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class L3IngestService {

    private final ContextProperties contextProperties;
    private final HistoryRagClient historyRagClient;
    private final LLMSemanticExtractor semanticExtractor;

    /** 攒批缓冲：conversation_id → 未消费 turn-pair 队列（含 body 原文与 msgId，门禁后同批落库） */
    private final Map<String, Deque<PendingPair>> pendingPairs = new ConcurrentHashMap<>();
    /** 防抖：同一 conv 已在 flush 中则不再重复触发 */
    private final Map<String, Boolean> flushing = new ConcurrentHashMap<>();

    /**
     * 写路径单入口（v26.2 + v28）：semantic-extract 开启时按 turn-pair 攒批，flush 时以语义提取 abstain 为置信门禁。
     * v28：chat 场景 body 原文层退役——只保留 semantic 摘要层，不再双写 body（消除 user/assistant 零散 chunk 结构）。
     * task 场景保留 body + process（session_search 深挖原文依赖）。body-gate 关闭时仅 task 回退即时 body 全量。
     */
    public void ingestTurnPair(
            String userId,
            String tenantId,
            String convId,
            String scene,
            String userMsgId,
            String userContent,
            String assistantMsgId,
            String assistantContent,
            long createdAtMs) {
        L3 l3 = contextProperties.getL3();
        if (!contextProperties.isEnabled()) {
            return;
        }
        String sc = scene != null ? scene : "chat";
        boolean semantic = l3.isSemanticExtractEnabled();
        boolean gateBody = semantic && l3.isBodyGateEnabled();
        // v28：chat 场景不写 body 原文层；task 场景 gateBody 关闭时即时全量写 body
        if (!"chat".equals(sc) && !gateBody) {
            upsertBody(userId, tenantId, convId, userMsgId, userContent, createdAtMs, sc);
            upsertBody(userId, tenantId, convId, assistantMsgId, assistantContent, createdAtMs, sc);
        }
        if (semantic) {
            accumulateTurnPair(userId, tenantId, convId, sc,
                    userMsgId, userContent, assistantMsgId, assistantContent, createdAtMs);
        }
    }

    /** 运维重建路径（ContextAdminService reingest）：显式全量 body 落库（escape hatch，不经过语义门禁）。
     *  v28：chat 场景 body 原文层退役——重建也不写 body（仅 task 场景写，供 session_search 深挖原文）。
     *  scene 传会话 kind（chat/task）；调用方已按 task 过滤，此处对 chat 仍防御性短路。 */
    public void ingest(
            String userId,
            String tenantId,
            String convId,
            String msgId,
            String content,
            long createdAtMs,
            String scene) {
        if (!contextProperties.isEnabled()) {
            return;
        }
        if (!StringUtils.hasText(userId) || !StringUtils.hasText(msgId) || !StringUtils.hasText(content)) {
            return;
        }
        String sc = scene != null ? scene : "chat";
        // chat 场景不写 body 原文层（v28）
        if ("chat".equals(sc)) {
            return;
        }
        upsertBody(userId, tenantId, convId, msgId, content, createdAtMs, sc);
    }

    /**
     * 攒批 turn-pair（user + assistant 消息对 + body msgId）：semantic 开启时缓冲，
     * 累积 N 轮（默认 3）或 M 分钟（默认 5）后批量提取+嵌入（§7.4.2）。
     */
    public void accumulateTurnPair(
            String userId,
            String tenantId,
            String convId,
            String scene,
            String userMsgId,
            String userContent,
            String assistantMsgId,
            String assistantContent,
            long createdAtMs) {
        L3 l3 = contextProperties.getL3();
        if (!contextProperties.isEnabled() || !l3.isSemanticExtractEnabled()) {
            return;
        }
        if (!StringUtils.hasText(userContent) && !StringUtils.hasText(assistantContent)) {
            return;
        }
        LLMSemanticExtractor.TurnPair pair = new LLMSemanticExtractor.TurnPair(
                userId, tenantId, convId,
                scene != null ? scene : "chat",
                userContent != null ? userContent : "",
                assistantContent != null ? assistantContent : "",
                createdAtMs);
        Deque<PendingPair> queue = pendingPairs.computeIfAbsent(convId, k -> new ConcurrentLinkedDeque<>());
        queue.addLast(new PendingPair(pair, userMsgId, assistantMsgId));
        if (queue.size() >= Math.max(1, l3.getSemanticBatchTurns())) {
            flush(convId);
        }
    }

    /** 定时兜底：达到 M 分钟未满 N 轮也 flush 该会话缓冲（§7.4.2）。 */
    @Scheduled(fixedDelayString = "${agent.context.l3.semantic-batch-interval-ms:300000}")
    public void flushDuePairs() {
        if (pendingPairs.isEmpty()) {
            return;
        }
        for (String convId : List.copyOf(pendingPairs.keySet())) {
            flush(convId);
        }
    }

    /**
     * flush：per-pair 置信门禁（v26.2）——提取器判定某轮 abstain → 该轮 body 原文 + semantic 段均跳过；
     * 重要轮 → body 原文（真实 msgId，可去重/删除）+ 语义段（sem: 合成 id）双写。
     */
    private void flush(String convId) {
        Deque<PendingPair> queue = pendingPairs.remove(convId);
        if (queue == null || queue.isEmpty()) {
            return;
        }
        if (flushing.putIfAbsent(convId, Boolean.TRUE) != null) {
            return;
        }
        try {
            List<PendingPair> pending = new ArrayList<>(queue);
            List<LLMSemanticExtractor.TurnPair> pairs = pending.stream()
                    .map(PendingPair::pair)
                    .toList();
            List<List<String>> perPair = semanticExtractor.extractByPair(pairs);
            L3 l3 = contextProperties.getL3();
            boolean gateBody = l3.isSemanticExtractEnabled() && l3.isBodyGateEnabled();
            int stored = 0;
            int noise = 0;
            for (int i = 0; i < pending.size(); i++) {
                PendingPair pp = pending.get(i);
                List<String> segments = i < perPair.size() ? perPair.get(i) : List.of();
                if (segments.isEmpty()) {
                    noise++;
                    continue;
                }
                // v28：仅 task 场景对重要轮双写 body（session_search 深挖原文）；chat 场景只落 semantic 摘要
                if (gateBody && !"chat".equals(pp.pair().scene())) {
                    upsertBody(pp.pair().userId(), pp.pair().tenantId(), pp.pair().convId(),
                            pp.userMsgId(), pp.pair().userContent(), pp.pair().createdAtMs(), pp.pair().scene());
                    upsertBody(pp.pair().userId(), pp.pair().tenantId(), pp.pair().convId(),
                            pp.assistantMsgId(), pp.pair().assistantContent(), pp.pair().createdAtMs(), pp.pair().scene());
                }
                String semMsgId = "sem:" + pp.pair().convId() + ":" + pp.pair().createdAtMs();
                for (int j = 0; j < segments.size(); j++) {
                    historyRagClient.upsert(
                                    pp.pair().userId(), pp.pair().tenantId(), pp.pair().convId(),
                                    semMsgId + ":" + j, segments.get(j), pp.pair().createdAtMs(),
                                    pp.pair().scene(), "semantic", true)
                            .block();
                }
                stored++;
            }
            if (stored > 0 || noise > 0) {
                log.info("[ContextL3] semantic flush conv={} stored={} noise_skip={}", convId, stored, noise);
            }
        } catch (Exception e) {
            log.warn("[ContextL3] semantic flush 失败 conv={}: {}", convId, e.getMessage());
        } finally {
            flushing.remove(convId);
        }
    }

    /** body 层 upsert（去重开关沿用 semantic-dedupe-enabled；空 msgId/内容跳过）。 */
    private void upsertBody(
            String userId,
            String tenantId,
            String convId,
            String msgId,
            String content,
            long createdAtMs,
            String scene) {
        if (!StringUtils.hasText(msgId) || !StringUtils.hasText(content)) {
            return;
        }
        boolean dedupe = contextProperties.getL3().isSemanticDedupeEnabled();
        historyRagClient.upsert(
                        userId, tenantId, convId, msgId, content.strip(), createdAtMs,
                        scene != null ? scene : "chat", "body", dedupe)
                .block();
    }

    /** 攒批缓冲行：语义提取输入 turn-pair + body 原文的原始 msgId（门禁通过时原样落 body 层）。 */
    private record PendingPair(LLMSemanticExtractor.TurnPair pair, String userMsgId, String assistantMsgId) {
    }

    /** task 写路径 process 层：assistant 消息 steps 中每步 result 截断 200 chars → layer=process（§7.4.4）。 */
    @Async
    public void ingestProcessAsync(
            String userId,
            String tenantId,
            String convId,
            String assistantMsgId,
            String stepsJson,
            long createdAtMs) {
        try {
            ingestProcess(userId, tenantId, convId, assistantMsgId, stepsJson, createdAtMs);
        } catch (Exception e) {
            log.warn("[ContextL3] process ingest 失败 msg={}: {}", assistantMsgId, e.getMessage());
        }
    }

    public void ingestProcess(
            String userId,
            String tenantId,
            String convId,
            String assistantMsgId,
            String stepsJson,
            long createdAtMs) {
        ContextProperties.L3 l3 = contextProperties.getL3();
        if (!contextProperties.isEnabled() || !l3.isProcessLayerEnabled()) {
            return;
        }
        List<ProcessingStep> steps = ProcessingStepSerde.fromJson(stepsJson);
        if (steps == null || steps.isEmpty()) {
            return;
        }
        int maxChars = Math.max(50, l3.getProcessResultMaxChars());
        int idx = 0;
        for (ProcessingStep step : steps) {
            if (step == null || !StringUtils.hasText(step.result())) {
                continue;
            }
            String result = step.result().strip();
            if (result.length() > maxChars) {
                result = result.substring(0, maxChars);
            }
            String label = StringUtils.hasText(step.label()) ? step.label() : step.phase();
            String line = (StringUtils.hasText(label) ? label + ": " : "") + result;
            // 合成 msgId 与 body 层隔离（body 用真实 assistantMsgId）；process 层独立管理
            String procMsgId = assistantMsgId + "#proc:" + idx++;
            historyRagClient.upsert(
                            userId, tenantId, convId, procMsgId, line, createdAtMs,
                            "task", "process", true)
                    .block();
        }
    }
}
