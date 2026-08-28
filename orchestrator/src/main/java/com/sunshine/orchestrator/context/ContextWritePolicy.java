package com.sunshine.orchestrator.context;

import com.sunshine.orchestrator.context.l2.L2ConflictMerger;
import com.sunshine.orchestrator.conversation.entity.ChatConversationEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * O3（memory-ledger-view §5.2）：上下文记忆写路由单点策略。
 * <p>收敛此前分散在 {@code ContextWritePath}（kind 闸门 if/else）、{@code L2ExtractService}（scope 双入口、
 * 置信门禁、v22 门禁）、{@code L2StateStore}（L2 TTL 表）、{@code ContextMaintenanceService}（L3 分层 TTL）的写决策面：
 * <ul>
 *   <li><b>写路由矩阵</b>：kind × executionMode → KV/L3 写入开关、scope（user/workspace）、scene（chat/task）；</li>
 *   <li><b>写入门禁</b>：L2 置信分级门禁（{@link #l2MinConfidenceFor}）+ todo 类 v22 门禁
 *       （key 场景化 / background 必填 / 布尔孤值，{@link #l2TodoGatePasses}）；</li>
 *   <li><b>TTL 表</b>：L2 按 kind 分级（{@link #l2TtlDays}）、L3 按 scene/layer 分层（{@link #l3TtlDays}），
 *       数值对齐 Nacos {@code agent.context.*}，不新增配置面；</li>
 *   <li><b>决策记录</b>：{@link WriteDecision#reason()} 携带可读理由，写路径落 info 日志，写/不写/丢弃可审计。</li>
 * </ul>
 * 纯策略组件：不触库、不调 LLM，路由结果与收敛前现状完全一致。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContextWritePolicy {

    /** v22：key 必须 {domain}.{facet}（todo 类强制）。 */
    private static final Pattern L2_KEY_PATTERN =
            Pattern.compile("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$");

    /** v22：布尔孤值禁止（todo 类强制）。 */
    private static final Set<String> L2_BOOLEAN_LONE_VALUES =
            Set.of("true", "false", "yes", "no", "1", "0");

    private final ContextProperties contextProperties;

    /**
     * 单轮写路由决策。
     * <p>矩阵：
     * <pre>
     * kind × mode        | L2 抽取 | L3 向量化 | scope     | scene
     * ------------------ | ------- | -------- | --------- | -----
     * task × workflow    |   否    |    否    |    —      | task（task-scene §2.2 退出统一上下文链路；L1 折叠照常）
     * task × fast/pro/空 |   是    |    是    | workspace | task
     * chat × *           |   是    |    是    | user      | chat
     * </pre>
     */
    public WriteDecision route(ChatConversationEntity conv) {
        String kind = conv != null ? conv.getKind() : null;
        String mode = conv != null ? conv.getExecutionPreference() : null;
        if ("task".equals(kind) && "workflow".equalsIgnoreCase(mode)) {
            return new WriteDecision(false, false, null, null, "task",
                    "task×workflow 退出统一上下文链路（L1 消息折叠照常）");
        }
        if ("task".equals(kind)) {
            return new WriteDecision(true, true, "workspace", conv.getWorkspaceId(), "task",
                    "task 会话写 workspace scope");
        }
        return new WriteDecision(true, true, "user", null, "chat", "chat 会话写 user scope");
    }

    /**
     * L2 写入门禁：按 kind 分级置信阈值（低于即丢弃）。
     * <p>基础 7 类与 todo 0.75，process_note（原 reasoning/option/interim_conclusion/topic 合并）0.65。
     */
    public static double l2MinConfidenceFor(String kind, ContextProperties.L2 l2) {
        if (l2 == null) {
            l2 = new ContextProperties.L2();
        }
        return switch (L2ConflictMerger.normalizeKind(kind)) {
            case "process_note" -> l2.getProcessNoteMinConfidence();
            case "todo" -> l2.getMinConfidence();
            default -> l2.getMinConfidence();
        };
    }

    /**
     * L2 写入门禁（v22，仅 todo 强制；其他 kind 不强弃以兼容 chat 现状）：
     * key 场景化（{@code domain.facet}）+ value 非布尔孤值；background 仅 active 必填（done/void 豁免）。
     */
    public static boolean l2TodoGatePasses(String key, String value, String background, String status) {
        boolean backgroundRequired = !"done".equals(status) && !"void".equals(status);
        return (!backgroundRequired || StringUtils.hasText(background))
                && L2_KEY_PATTERN.matcher(key).matches()
                && !l2IsBooleanLoneValue(value);
    }

    public static boolean l2IsBooleanLoneValue(String value) {
        return L2_BOOLEAN_LONE_VALUES.contains(value.strip().toLowerCase(java.util.Locale.ROOT));
    }

    /** L2 TTL 表：kind 分级（天）；≤0 表示不过期。 */
    public static int l2TtlDays(String kind, ContextProperties.L2 l2) {
        if (l2 == null) {
            l2 = new ContextProperties.L2();
        }
        return switch (L2ConflictMerger.normalizeKind(kind)) {
            case "preference", "profile" -> l2.getPreferenceTtlDays();
            case "agreement" -> l2.getAgreementTtlDays();
            case "goal" -> l2.getGoalTtlDays();
            case "decision" -> l2.getDecisionTtlDays();
            case "fact" -> l2.getFactTtlDays();
            case "constraint" -> l2.getConstraintTtlDays();
            case "process_note" -> l2.getProcessNoteTtlDays();
            case "todo" -> l2.getTodoTtlDays();
            default -> l2.getFactTtlDays();
        };
    }

    /** L2 过期时间：TTL≤0 → null（永不过期）。 */
    public Instant l2ExpiresAtFor(String kind, Instant now) {
        int days = l2TtlDays(kind, contextProperties.getL2());
        if (days <= 0) {
            return null;
        }
        return now.plus(days, ChronoUnit.DAYS);
    }

    /**
     * L3 过期清理分层 TTL 表（天；≤0 跳过对应层）：
     * chat 全层共用一档；task 按 layer 分 body/process/semantic 三档（v26 §9.2 ②）。
     */
    public static int l3TtlDays(String scene, String layer, ContextProperties.Maintenance m) {
        if (m == null) {
            return 0;
        }
        if (!"task".equals(scene)) {
            return m.getL3ChatTtlDays();
        }
        return switch (layer != null ? layer : "") {
            case "process" -> m.getL3TaskProcessTtlDays();
            case "semantic" -> m.getL3TaskSemanticTtlDays();
            default -> m.getL3TaskBodyTtlDays();
        };
    }

    /**
     * 写路由决策结果：写入开关 + scope/scene 路由 + 决策理由（审计）。
     * scope/workspaceId 在不写 L2 时为 null。
     */
    public record WriteDecision(
            boolean writeL2,
            boolean writeL3,
            String scope,
            String workspaceId,
            String scene,
            String reason) {
    }
}
