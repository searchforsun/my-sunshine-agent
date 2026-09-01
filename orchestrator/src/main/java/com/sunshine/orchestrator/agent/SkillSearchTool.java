package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.catalog.SkillCatalogEntry;
import com.sunshine.orchestrator.catalog.SkillBodyRenderer;
import com.sunshine.orchestrator.catalog.SkillCatalogService;
import com.sunshine.orchestrator.catalog.TenantVisibility;
import com.sunshine.orchestrator.config.VirtualThreadExecutors;
import com.sunshine.orchestrator.conversation.ConversationService;
import com.sunshine.orchestrator.sandbox.SandboxSessionLifecycle;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 技能动态加载元工具（skill-sticky v3.8 §4.6 形态 3）：模型显式加载任一 enabled 技能，
 * 返回完整正文 + 声明工具 schema（结果经 tool_result 进上下文尾部，零 prefix 重建）。
 * 同时把技能物料挂载到当前沙箱会话 /skills/{id}/，使模型可在沙箱工作区读取（sandbox__read/glob）。
 *
 * <p>加载即升级 triggered：消息 {@code routing_skill_ids} 追加（后续轮次经 sticky 继承）
 * + 沙箱挂载物料；是「运行中加载技能进工作区」的唯一入口。校验 enabled + 租户可见，
 * 不再限制为采纳候选集（候选集仅用于目录「可动态加载」提权标记）。仅注册 MAIN。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SkillSearchTool implements AgentTool {

    public static final String NAME = "sunshine_search_skills";

    private final SkillCatalogService skillCatalogService;
    private final SkillBodyRenderer skillBodyRenderer;
    private final ConversationService conversationService;
    private final SandboxSessionLifecycle sandboxSessionLifecycle;

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return "运行中加载指定技能到沙箱工作区：返回该技能的完整操作指引与其声明工具的用法，"
                + "并把技能物料挂载到沙箱 /skills/{id}/ 供 sandbox__read/glob 读取。"
                + "仅当技能正文尚未加载、且当前任务确实需要该技能时才调用；已触发的技能无需重复加载。";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("skill_id", Map.of(
                "type", "string",
                "description", "要加载的技能 id（取自技能目录 / 已启用技能）"));
        return Map.of(
                "type", "object",
                "properties", props,
                "required", List.of("skill_id"));
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.fromCallable(() -> execute(param))
                .subscribeOn(VirtualThreadExecutors.scheduler());
    }

    private ToolResultBlock execute(ToolCallParam param) {
        String messageId = StepEventBridge.activeMessageId();
        StepEventBridge.ToolAuditContext audit = StringUtils.hasText(messageId)
                ? StepEventBridge.toolAuditContext(messageId) : null;
        return execute(param, messageId, audit);
    }

    /** 单测入口：messageId / audit 可注入（生产路径从 StepEventBridge 静态取） */
    ToolResultBlock execute(ToolCallParam param, String messageId, StepEventBridge.ToolAuditContext audit) {
        String toolUseId = param.getToolUseBlock() != null ? param.getToolUseBlock().getId() : null;
        String skillId = stringParam(param, "skill_id");
        if (!StringUtils.hasText(skillId)) {
            return result(toolUseId, "调用失败：skill_id 不能为空");
        }
        String id = skillId.strip();
        if (audit == null || !StringUtils.hasText(audit.messageId())) {
            return result(toolUseId, "调用失败：缺少会话上下文");
        }
        SkillCatalogEntry entry = skillCatalogService.find(id).orElse(null);
        if (entry == null || !entry.enabled()) {
            return result(toolUseId, "技能「" + id + "」不存在或未启用");
        }
        if (!TenantVisibility.visible(entry.tenantId(), audit.tenantId())) {
            return result(toolUseId, "技能「" + id + "」对当前租户不可见");
        }
        promoteTriggered(audit, id);
        String body = skillBodyRenderer.renderLoadedSkill(entry, audit.tenantId(), audit.conversationKind());
        log.info("[SkillSearchTool] 运行中加载技能升级 triggered skill={} msg={} conv={}",
                id, audit.messageId(), audit.conversationId());
        return result(toolUseId, body);
    }

    /** 升级 triggered：消息 routing_skill_ids 追加（sticky 继承）+ 沙箱懒挂；失败不阻断正文返回 */
    private void promoteTriggered(StepEventBridge.ToolAuditContext audit, String skillId) {
        try {
            conversationService.appendTriggeredSkillId(audit.messageId(), skillId);
        } catch (Exception e) {
            log.warn("[SkillSearchTool] 升级触发集落库失败 msg={} skill={}: {}",
                    audit.messageId(), skillId, e.getMessage());
        }
        try {
            String bridgeId = StepEventBridge.activeMainBridge(audit.messageId());
            if (StringUtils.hasText(bridgeId)) {
                sandboxSessionLifecycle.mountSkillForBridge(bridgeId, skillId);
            }
        } catch (Exception e) {
            log.warn("[SkillSearchTool] 沙箱懒挂失败 skill={}: {}", skillId, e.getMessage());
        }
    }

    private static String stringParam(ToolCallParam param, String key) {
        Map<String, Object> input = param.getInput();
        if (input == null || !input.containsKey(key) || input.get(key) == null) {
            return null;
        }
        return String.valueOf(input.get(key));
    }

    private static ToolResultBlock result(String toolUseId, String text) {
        return ToolResultBlock.of(toolUseId, NAME, TextBlock.builder().text(text).build());
    }
}
