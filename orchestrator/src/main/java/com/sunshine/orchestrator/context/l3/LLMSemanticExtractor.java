package com.sunshine.orchestrator.context.l3;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.orchestrator.client.LlmGatewayClient;
import com.sunshine.orchestrator.context.l2.UserContextStateEntity;
import com.sunshine.orchestrator.context.l2.UserContextStateRepository;
import com.sunshine.orchestrator.prompt.PromptCatalogHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Locale;
import java.util.Set;
import java.util.List;

/**
 * L3 嵌入前语义提取层（v26 §7.4.1）— 对 turn-pair（user+assistant）消息对做 LLM 抽取，
 * 产出精炼语义片段独立入库（layer=semantic）。abstain 默认：噪音消息（确认语/寒暄）输出空数组 → 跳过，不浪费向量存储。
 * 与 L2 KV Memory 解耦：L2 抽结构化键值，本层保留语义连续段落（用户画像信号 / 历史任务关键结果 / 重要实时事件）。
 * <p>v28（方案2）：写入前对账 L2——对已由 L2 结构化表达（kind/stateKey/stateValue 组合）的内容整段 abstain，
 * 避免 L3 与 L2 重复、无 L3 增量价值却占向量空间。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LLMSemanticExtractor {

    public static final String EXTRACT_PROMPT = "context.l3.semantic-extract";

    private static final ObjectMapper OM = new ObjectMapper();

    private final PromptCatalogHolder catalogHolder;
    private final LlmGatewayClient llmGatewayClient;
    private final UserContextStateRepository l2Repository;

    /** 攒批 turn-pair 输入：一条 user 消息 + 其后 assistant 消息（正文）。 */
    public record TurnPair(String userId, String tenantId, String convId, String scene,
                           String userContent, String assistantContent, long createdAtMs) {
    }

    /**
     * 按轮语义提取（v26.2 置信门禁）：对每轮 turn-pair 独立判定，返回与输入等长的二维数组；
     * 第 i 个元素是第 i 轮的语义片段；该轮无值得保留内容 → 空列表（abstain）。失败仅日志、逐轮 abstain。
     */
    public List<List<String>> extractByPair(List<TurnPair> pairs) {
        if (pairs == null || pairs.isEmpty()) {
            return List.of();
        }
        String system = catalogHolder.requireText(EXTRACT_PROMPT);
        if (!StringUtils.hasText(system)) {
            log.warn("[ContextL3] missing catalog {}", EXTRACT_PROMPT);
            return allEmpty(pairs.size());
        }
        String payload = buildPayload(pairs);
        if (!StringUtils.hasText(payload)) {
            return allEmpty(pairs.size());
        }
        String raw;
        try {
            raw = llmGatewayClient.complete(system, payload);
        } catch (Exception e) {
            log.warn("[ContextL3] semantic extract LLM 失败: {}", e.getMessage());
            return allEmpty(pairs.size());
        }
        List<List<String>> perPair = parseSegmentsByPair(raw);
        List<List<String>> aligned = alignTo(perPair, pairs.size());
        // v28 方案2：对账 L2——已由 L2 结构化表达的内容 abstain（避免与 L2 重复、无 L3 增量价值）
        if (aligned.stream().anyMatch(s -> !s.isEmpty())) {
            Set<String> l2Covered = buildL2CoveredSignals(pairs.get(0).userId(), pairs.get(0).tenantId());
            if (!l2Covered.isEmpty()) {
                aligned = aligned.stream()
                        .map(segs -> filterAgainstL2(segs, l2Covered))
                        .toList();
            }
        }
        long total = aligned.stream().mapToLong(List::size).sum();
        if (total > 0) {
            log.info("[ContextL3] semantic extract pairs={} segments={}", pairs.size(), total);
        }
        return aligned;
    }

    /**
     * 收集该用户 active L2 已结构化覆盖的具体陈述信号（v28 方案2）：
     * 以 {@code stateValue}（已由 L2 表达的具体事实内容）为准，用于过滤与 L2 重复的 L3 语义段。
     * 不把 kind/stateKey 作为信号——它们过于空泛（如 "preference"），会误伤 L3 增量段落。
     * 任一查询失败返回空集 → 不拦截（保守：宁可多留一条，不误删）。
     */
    private Set<String> buildL2CoveredSignals(String userId, String tenantId) {
        Set<String> signals = new java.util.HashSet<>();
        if (!StringUtils.hasText(userId)) {
            return signals;
        }
        try {
            List<UserContextStateEntity> active =
                    l2Repository.findByUserIdAndTenantIdAndStatus(userId, normalizeTenant(tenantId), "active");
            if (active == null) {
                return signals;
            }
            for (UserContextStateEntity e : active) {
                if (e == null) {
                    continue;
                }
                addSignal(signals, e.getStateValue());
            }
        } catch (Exception ex) {
            log.warn("[ContextL3] L2 对账信号读取失败 user={}: {}", userId, ex.getMessage());
            return Set.of();
        }
        return signals;
    }

    private static void addSignal(Set<String> signals, String value) {
        if (StringUtils.hasText(value)) {
            signals.add(value.strip().toLowerCase(Locale.ROOT));
        }
    }

    /**
     * 过滤与 L2 覆盖信号重叠的语义段：整段包含某条 L2 stateValue（强命中）即判定为与 L2 重复 → abstain。
     * 仅做强命中，不基于部分 token 判定，避免把 L3 有增量的连续语义段落误伤。
     */
    static List<String> filterAgainstL2(List<String> segments, Set<String> l2Covered) {
        if (segments == null || segments.isEmpty()) {
            return segments;
        }
        List<String> kept = new ArrayList<>(segments.size());
        for (String seg : segments) {
            if (StringUtils.hasText(seg) && overlapsL2(seg, l2Covered)) {
                continue;
            }
            kept.add(seg);
        }
        return List.copyOf(kept);
    }

    private static String normalizeTenant(String tenantId) {
        return StringUtils.hasText(tenantId) ? tenantId.strip() : "default";
    }

    /**
     * 是否与 L2 覆盖信号重叠：
     * 语义段完整包含某条 L2 stateValue（该事实已由 L2 结构化表达，L3 重复无增量价值）。
     * 仅当信号长度 ≥4 时才判定，防空泛短信号误拦截。
     */
    static boolean overlapsL2(String seg, Set<String> l2Covered) {
        if (!StringUtils.hasText(seg) || l2Covered == null || l2Covered.isEmpty()) {
            return false;
        }
        String segLower = seg.strip().toLowerCase(Locale.ROOT);
        if (!StringUtils.hasText(segLower)) {
            return false;
        }
        for (String signal : l2Covered) {
            if (signal == null || signal.length() < 4) {
                continue;
            }
            if (segLower.contains(signal)) {
                return true;
            }
        }
        return false;
    }

    /** LLM 返回轮数可能少于输入（漏答）→ 缺失轮视为 abstain，保证调用方按索引对齐。 */
    static List<List<String>> alignTo(List<List<String>> perPair, int size) {
        List<List<String>> out = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            out.add(i < perPair.size() ? perPair.get(i) : List.of());
        }
        return List.copyOf(out);
    }

    private static List<List<String>> allEmpty(int size) {
        List<List<String>> out = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            out.add(List.of());
        }
        return List.copyOf(out);
    }

    static String buildPayload(List<TurnPair> pairs) {
        StringBuilder sb = new StringBuilder();
        sb.append("【对话轮次】\n");
        for (TurnPair p : pairs) {
            if (StringUtils.hasText(p.userContent())) {
                sb.append("user: ").append(p.userContent().strip()).append('\n');
            }
            if (StringUtils.hasText(p.assistantContent())) {
                sb.append("assistant: ").append(p.assistantContent().strip()).append('\n');
            }
        }
        return sb.toString().strip();
    }

    /** 解析 LLM 输出的字符串数组；abstain（空数组/非数组）→ 空列表。 */
    static List<String> parseSegments(String raw) {
        if (!StringUtils.hasText(raw)) {
            return List.of();
        }
        try {
            JsonNode root = OM.readTree(extractJsonArray(raw));
            return parseInner(root);
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * 按轮解析：LLM 输出二维数组（第 i 元素 = 第 i 轮片段数组，abstain 轮为 []）；
     * 兼容旧平铺一维数组（单轮批次自然输出）→ 视为第 0 轮。
     */
    static List<List<String>> parseSegmentsByPair(String raw) {
        if (!StringUtils.hasText(raw)) {
            return List.of();
        }
        try {
            JsonNode root = OM.readTree(extractJsonArray(raw));
            if (root == null || !root.isArray()) {
                return List.of();
            }
            if (root.isEmpty() || !root.get(0).isArray()) {
                return List.of(parseInner(root));
            }
            List<List<String>> out = new ArrayList<>(root.size());
            for (JsonNode node : root) {
                out.add(parseInner(node));
            }
            return List.copyOf(out);
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 解析单个数组节点为语义片段（过滤非文本 / 空白 / 超 500 字）。 */
    private static List<String> parseInner(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (JsonNode item : node) {
            if (item == null || !item.isTextual()) {
                continue;
            }
            String seg = item.asText().strip();
            if (StringUtils.hasText(seg) && seg.length() <= 500) {
                out.add(seg);
            }
        }
        return List.copyOf(out);
    }

    static String extractJsonArray(String raw) {
        String trimmed = raw.strip();
        if (trimmed.startsWith("```")) {
            int nl = trimmed.indexOf('\n');
            if (nl > 0) {
                trimmed = trimmed.substring(nl + 1);
            }
            int fence = trimmed.lastIndexOf("```");
            if (fence >= 0) {
                trimmed = trimmed.substring(0, fence);
            }
            trimmed = trimmed.strip();
        }
        int start = trimmed.indexOf('[');
        int end = trimmed.lastIndexOf(']');
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1);
        }
        return trimmed;
    }
}
