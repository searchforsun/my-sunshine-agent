package com.sunshine.orchestrator.processing;

import com.sunshine.orchestrator.catalog.SkillCatalogIndexEntry;
import com.sunshine.orchestrator.catalog.SkillCatalogService;
import com.sunshine.orchestrator.config.AgentPromptProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.Map;

/** L0 Skill 绑定后时间线「加载技能」步骤文案 — SSOT 见 Nacos agent.timeline.steps.skill */
@Service
@RefreshScope
@RequiredArgsConstructor
public class SkillLoadLabelService {

    private final SkillCatalogService skillCatalogService;
    private final AgentPromptProperties agentPromptProperties;

    @PostConstruct
    void init() {
        SkillLoadLabels.bind(this);
    }

    public String beforeLine() {
        return textOrDefault(skillTemplate().getBefore(), "准备加载 Skill");
    }

    public String activeLine() {
        return textOrDefault(skillTemplate().getActive(), "正在加载 Skill 指令");
    }

    public String afterLine(String skillId) {
        String id = skillId.strip();
        String displayName = resolveDisplayName(id);
        String template = textOrDefault(skillTemplate().getAfter(), "@{skillId} {skillDisplayName}");
        Map<String, String> vars = new HashMap<>();
        vars.put("skillId", id);
        vars.put("skillDisplayName", id.equals(displayName) ? "" : displayName);
        return applyTemplate(template, vars).replaceAll("\\s+", " ").trim();
    }

    private AgentPromptProperties.StepTimeline skillTemplate() {
        var steps = agentPromptProperties.timelineOrDefault().getSteps();
        if (steps == null) {
            return new AgentPromptProperties.StepTimeline();
        }
        AgentPromptProperties.StepTimeline skill = steps.get("skill");
        return skill != null ? skill : new AgentPromptProperties.StepTimeline();
    }

    private String resolveDisplayName(String skillId) {
        return skillCatalogService.findIndex(skillId)
                .map(SkillCatalogIndexEntry::displayName)
                .filter(StringUtils::hasText)
                .map(String::strip)
                .orElse(skillId);
    }

    private static String textOrDefault(String value, String fallback) {
        return StringUtils.hasText(value) ? value.strip() : fallback;
    }

    private static String applyTemplate(String template, Map<String, String> vars) {
        String result = template;
        for (Map.Entry<String, String> entry : vars.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", entry.getValue() != null ? entry.getValue() : "");
        }
        return result;
    }
}
