package com.sunshine.orchestrator.catalog;

import com.sunshine.common.tool.ToolCatalogEntry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Skill 正文统一渲染：<b>触发集唯一正文来源</b>（skill-sticky 统一加载入口）。
 * L0 绑定 / 动态加载 / sticky 继承的技能，正文统一为「# 技能·名 + 正文 + 声明工具 schema」，
 * 与 {@link com.sunshine.orchestrator.agent.SkillSearchTool} 运行时加载返回保持一致，
 * 避免「动态加载是完整正文、L0 绑定只有 systemOverlay」的格式分叉。
 *
 * <p>声明工具 schema ⊂ 当前 (tenant, kind) 集；越界声明标注「不在当前会话工具集内，不可调用」。
 */
@Component
@RequiredArgsConstructor
public class SkillBodyRenderer {

    private final SkillCatalogService skillCatalogService;
    private final ToolCatalogService toolCatalogService;
    private final ToolSetResolver toolSetResolver;

    /** 渲染技能完整正文；技能不存在/未启用 → 空串（调用方按触发集已校验，此处兜底） */
    public String renderLoadedSkill(SkillCatalogEntry entry, String tenantId, String conversationKind) {
        if (entry == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("# 技能 ").append(entry.id());
        if (StringUtils.hasText(entry.displayName())) {
            sb.append(" · ").append(entry.displayName().strip());
        }
        sb.append("\n\n");
        if (StringUtils.hasText(entry.systemOverlay())) {
            sb.append(entry.systemOverlay().strip()).append("\n\n");
        } else {
            sb.append("（该技能无正文指引）\n\n");
        }
        List<String> declared = entry.toolIds();
        if (declared.isEmpty()) {
            sb.append("该技能未声明专属工具。");
            return sb.toString();
        }
        Set<String> currentSet = new HashSet<>(toolSetResolver.resolveDefaultTools(
                tenantId != null ? tenantId : "default",
                conversationKind));
        sb.append("## 声明工具\n");
        for (String toolId : declared) {
            if (!currentSet.contains(toolId)) {
                sb.append("- ").append(toolId).append("：不在当前会话工具集内，不可调用\n");
                continue;
            }
            ToolCatalogEntry tool = toolCatalogService.find(toolId).orElse(null);
            if (tool == null) {
                sb.append("- ").append(toolId).append("\n");
                continue;
            }
            sb.append("- **").append(tool.id()).append("**");
            if (StringUtils.hasText(tool.displayName())) {
                sb.append(" ").append(tool.displayName().strip());
            }
            if (StringUtils.hasText(tool.description())) {
                sb.append("：").append(tool.description().strip());
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /** 按 id 取 entry 后渲染；未找到/未启用 → 空串 */
    public String renderById(String skillId, String tenantId, String conversationKind) {
        if (!StringUtils.hasText(skillId)) {
            return "";
        }
        SkillCatalogEntry entry = skillCatalogService.find(skillId.strip()).orElse(null);
        if (entry == null || !entry.enabled()) {
            return "";
        }
        return renderLoadedSkill(entry, tenantId, conversationKind);
    }
}
