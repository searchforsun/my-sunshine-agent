package com.sunshine.orchestrator.processing;

import com.sunshine.orchestrator.config.AgentPromptProperties;
import com.sunshine.orchestrator.prompt.TimelinePromptCatalog;
import com.sunshine.orchestrator.routing.ExecutionMode;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * think / think-N 步骤 label / before / active / after — Nacos agent.timeline.steps.think
 */
@Service
@RefreshScope
@RequiredArgsConstructor
public class ThinkStepLabelService {

    private final TimelinePromptCatalog timelinePromptCatalog;

    @PostConstruct
    void init() {
        ThinkStepLabels.bind(this);
    }

    public String thinkStepLabel(String stepId, ExecutionMode mode) {
        // think 步 label 统一为「深度思考」，不再区分首轮/续轮（摘要由 think_summary 结构化输出）
        AgentPromptProperties.StepTimeline think = stepTemplate(TimelineStepId.THINK.id());
        if (think == null) {
            return null;
        }
        String label = think.getLabel();
        return StringUtils.hasText(label) ? label.strip() : null;
    }

    public String thinkStepBefore(String stepId, ExecutionMode mode, String clippedQuery, String toolDisplayName) {
        return applyThinkTemplate(stepId, mode, clippedQuery, toolDisplayName, ThinkPhase.BEFORE);
    }

    public String thinkStepActive(String stepId, ExecutionMode mode, String clippedQuery, String toolDisplayName) {
        return applyThinkTemplate(stepId, mode, clippedQuery, toolDisplayName, ThinkPhase.ACTIVE);
    }

    public String thinkStepAfter(String stepId, ExecutionMode mode, String clippedQuery, String toolDisplayName) {
        return applyThinkTemplate(stepId, mode, clippedQuery, toolDisplayName, ThinkPhase.AFTER);
    }

    private String applyThinkTemplate(String stepId, ExecutionMode mode, String clippedQuery,
            String toolDisplayName, ThinkPhase phase) {
        ExecutionMode resolved = mode != null ? mode : ExecutionMode.REACT;
        AgentPromptProperties.StepTimeline root = stepTemplate(TimelineStepId.THINK.id());
        if (root == null) {
            return null;
        }
        boolean first = ThinkStepIds.iterationOf(stepId) <= 1;
        boolean hasQuery = StringUtils.hasText(clippedQuery);
        boolean hasTool = StringUtils.hasText(toolDisplayName);
        AgentPromptProperties.StepModeTimeline modeTimeline = resolveModeTimeline(root, resolved);
        String template = pickThinkTemplate(root, modeTimeline, first, hasQuery, hasTool, phase);
        if (!StringUtils.hasText(template)) {
            return null;
        }
        String query = hasQuery ? clippedQuery.strip() : "";
        return TimelineLabelTemplates.applyTemplate(template.strip(),
                TimelineLabelTemplates.thinkVars(query, toolDisplayName));
    }

    private static AgentPromptProperties.StepModeTimeline resolveModeTimeline(
            AgentPromptProperties.StepTimeline root, ExecutionMode mode) {
        if (root.getModes() == null) {
            return null;
        }
        return root.getModes().get(TimelineLabelTemplates.modeConfigKey(mode));
    }

    private static String pickThinkTemplate(AgentPromptProperties.StepTimeline root,
            AgentPromptProperties.StepModeTimeline modeTimeline,
            boolean first, boolean hasQuery, boolean hasTool, ThinkPhase phase) {
        if (modeTimeline != null) {
            if (first) {
                String modeTemplate = phase.modeField(modeTimeline, true);
                if (StringUtils.hasText(modeTemplate)) {
                    return modeTemplate;
                }
            } else {
                String modeFollowUp = phase.modeFollowUpField(modeTimeline);
                if (StringUtils.hasText(modeFollowUp)) {
                    return modeFollowUp;
                }
            }
        }
        if (first) {
            if (hasQuery) {
                return TimelineLabelTemplates.coalesce(phase.rootField(root, true));
            }
            return TimelineLabelTemplates.coalesce(phase.rootFallbackField(root));
        }
        if (hasTool) {
            return TimelineLabelTemplates.coalesce(phase.rootFollowUpField(root));
        }
        if (hasQuery) {
            return TimelineLabelTemplates.coalesce(phase.rootFollowUpNoToolField(root));
        }
        return TimelineLabelTemplates.coalesce(phase.rootFollowUpFallbackField(root));
    }

    private AgentPromptProperties.StepTimeline stepTemplate(String stepId) {
        var steps = timelinePromptCatalog.steps();
        if (steps == null || !StringUtils.hasText(stepId)) {
            return null;
        }
        return steps.get(stepId);
    }

    private enum ThinkPhase {
        BEFORE {
            @Override
            String modeField(AgentPromptProperties.StepModeTimeline mode, boolean first) {
                return mode.getBefore();
            }

            @Override
            String modeFollowUpField(AgentPromptProperties.StepModeTimeline mode) {
                return mode.getBeforeFollowUp();
            }

            @Override
            String rootField(AgentPromptProperties.StepTimeline root, boolean first) {
                return root.getBefore();
            }

            @Override
            String rootFallbackField(AgentPromptProperties.StepTimeline root) {
                return root.getBeforeFallback();
            }

            @Override
            String rootFollowUpField(AgentPromptProperties.StepTimeline root) {
                return root.getBeforeFollowUp();
            }

            @Override
            String rootFollowUpNoToolField(AgentPromptProperties.StepTimeline root) {
                return root.getBeforeFollowUpNoTool();
            }

            @Override
            String rootFollowUpFallbackField(AgentPromptProperties.StepTimeline root) {
                return root.getBeforeFollowUpFallback();
            }
        },
        ACTIVE {
            @Override
            String modeField(AgentPromptProperties.StepModeTimeline mode, boolean first) {
                return mode.getActive();
            }

            @Override
            String modeFollowUpField(AgentPromptProperties.StepModeTimeline mode) {
                return mode.getActiveFollowUp();
            }

            @Override
            String rootField(AgentPromptProperties.StepTimeline root, boolean first) {
                return root.getActive();
            }

            @Override
            String rootFallbackField(AgentPromptProperties.StepTimeline root) {
                return root.getActiveFallback();
            }

            @Override
            String rootFollowUpField(AgentPromptProperties.StepTimeline root) {
                return root.getActiveFollowUp();
            }

            @Override
            String rootFollowUpNoToolField(AgentPromptProperties.StepTimeline root) {
                return root.getActiveFollowUpNoTool();
            }

            @Override
            String rootFollowUpFallbackField(AgentPromptProperties.StepTimeline root) {
                return root.getActiveFollowUpFallback();
            }
        },
        AFTER {
            @Override
            String modeField(AgentPromptProperties.StepModeTimeline mode, boolean first) {
                return mode.getAfter();
            }

            @Override
            String modeFollowUpField(AgentPromptProperties.StepModeTimeline mode) {
                return mode.getAfterFollowUp();
            }

            @Override
            String rootField(AgentPromptProperties.StepTimeline root, boolean first) {
                return root.getAfter();
            }

            @Override
            String rootFallbackField(AgentPromptProperties.StepTimeline root) {
                return root.getAfterFallback();
            }

            @Override
            String rootFollowUpField(AgentPromptProperties.StepTimeline root) {
                return root.getAfterFollowUp();
            }

            @Override
            String rootFollowUpNoToolField(AgentPromptProperties.StepTimeline root) {
                return root.getAfterFollowUpNoTool();
            }

            @Override
            String rootFollowUpFallbackField(AgentPromptProperties.StepTimeline root) {
                return root.getAfterFollowUpFallback();
            }
        };

        abstract String modeField(AgentPromptProperties.StepModeTimeline mode, boolean first);

        abstract String modeFollowUpField(AgentPromptProperties.StepModeTimeline mode);

        abstract String rootField(AgentPromptProperties.StepTimeline root, boolean first);

        abstract String rootFallbackField(AgentPromptProperties.StepTimeline root);

        abstract String rootFollowUpField(AgentPromptProperties.StepTimeline root);

        abstract String rootFollowUpNoToolField(AgentPromptProperties.StepTimeline root);

        abstract String rootFollowUpFallbackField(AgentPromptProperties.StepTimeline root);
    }
}
