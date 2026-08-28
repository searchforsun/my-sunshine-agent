package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.catalog.SkillCatalogService;
import com.sunshine.orchestrator.agent.runtime.AgentRole;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.middleware.MiddlewareBase;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import static com.sunshine.orchestrator.agent.ProcessingStepMiddleware.CTX_AGENT_ROLE;

/**
 * 技能正文 SYSTEM 注入中间件（skill-sticky 正文权威层）。
 *
 * <p>AS 2.0 约束：{@code PreCallEvent.inputMessages} 禁止 SYSTEM 角色（见 AgentBase
 * notifyPreCall 守卫），系统提示唯一官方通道为 {@code sysPrompt} / {@code onSystemPrompt}。
 * 本中间件在 {@link #onSystemPrompt} 把触发集 skill 正文追加到 system prompt（SYSTEM 权威层），
 * 取代把 skill 塞进 USER 消息的做法——USER 角色指令权重天然低于 SYSTEM，是「模型读到但
 * 无视 HARD-GATE」的根因。
 *
 * <p>无状态共享单例：per-call 触发集经 RuntimeContext 注入（{@link #CTX_TRIGGERED_SKILL_IDS}），
 * 由 ReActAgentRuntime 在构建 rt 时 put，满足 HarnessAgent 指纹缓存安全复用。
 * 仅 MAIN 生效（SUB/WORKER 用单数 skillId 走 systemOverlay，PLANNER 走 harness）。
 */
public class SkillInjectionMiddleware implements MiddlewareBase {

    /** RuntimeContext key：本轮触发集 skill ids（仅 MAIN，skill-sticky S-T；由 runtime 注入） */
    public static final String CTX_TRIGGERED_SKILL_IDS = "sunshine.triggeredSkillIds";

    private final SkillCatalogService skillCatalogService;

    public SkillInjectionMiddleware(SkillCatalogService skillCatalogService) {
        this.skillCatalogService = skillCatalogService;
    }

    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String currentPrompt) {
        AgentRole role = ctx != null ? ctx.get(CTX_AGENT_ROLE) : null;
        if (role != AgentRole.MAIN) {
            return Mono.just(currentPrompt != null ? currentPrompt : "");
        }
        Object raw = ctx != null ? ctx.get(CTX_TRIGGERED_SKILL_IDS) : null;
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return Mono.just(currentPrompt != null ? currentPrompt : "");
        }
        LinkedHashSet<String> dedup = new LinkedHashSet<>();
        for (Object o : list) {
            if (o instanceof String s && StringUtils.hasText(s)) {
                dedup.add(s.strip());
            }
        }
        List<String> unique = new ArrayList<>(dedup);
        if (unique.isEmpty()) {
            return Mono.just(currentPrompt != null ? currentPrompt : "");
        }
        String overlay = resolveSkillOverlays(unique);
        if (!StringUtils.hasText(overlay)) {
            return Mono.just(currentPrompt != null ? currentPrompt : "");
        }
        String base = currentPrompt != null ? currentPrompt : "";
        return Mono.just(base + "\n\n" + wrapInstructionEnvelope(overlay, unique));
    }

    /** trigger 集拼接 skill 正文（已去重；对齐 PromptComposer.resolveSkillOverlays 语义） */
    private String resolveSkillOverlays(List<String> ids) {
        StringBuilder sb = new StringBuilder();
        for (String id : ids) {
            String fromCatalog = skillCatalogService.overlayOrEmpty(id);
            if (StringUtils.hasText(fromCatalog)) {
                sb.append(fromCatalog.strip()).append("\n\n");
            }
        }
        return sb.toString().strip();
    }

    /**
     * skill 正文指令信封（对齐 Claude Code {@code <skill_information>}）：在 SYSTEM 权威层给技能正文
     * 包一层明确的「指令身份」边界，让模型识别「这是须遵循的技能指令」而非普通闲聊，从而把
     * HARD-GATE 这类否定式禁令当作约束而非上下文。索引 {@code skills_referenced} 与正文
     * {@code skill_block} 一一对应。
     */
    private static String wrapInstructionEnvelope(String body, List<String> ids) {
        StringBuilder sb = new StringBuilder();
        sb.append("<skill_information>\n");
        sb.append("<skills_referenced>\n");
        for (String id : ids) {
            sb.append("- ").append(id).append('\n');
        }
        sb.append("</skills_referenced>\n");
        sb.append("<skill_block>\n");
        sb.append(body.strip());
        sb.append("\n</skill_block>\n");
        sb.append("</skill_information>");
        return sb.toString();
    }
}
