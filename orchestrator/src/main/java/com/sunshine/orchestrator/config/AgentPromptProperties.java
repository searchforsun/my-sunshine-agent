package com.sunshine.orchestrator.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Agent 提示词 SSOT — 正文维护于 Nacos {@code sunshine-orchestrator.yaml}（本地副本 docs/nacos/）。
 */
@Getter
@Setter
@RefreshScope
@ConfigurationProperties(prefix = "agent")
public class AgentPromptProperties {

    /** 主系统提示词（直连 LLM + ReActAgent） */
    private String systemPrompt = "";

    private Intent intent = new Intent();

    private Planner planner = new Planner();

    /** 时间线步骤文案（意图等），SSOT 见 Nacos agent.timeline */
    private Timeline timeline = new Timeline();

    public boolean hasSystemPrompt() {
        return StringUtils.hasText(systemPrompt);
    }

    public String systemPromptOrEmpty() {
        return systemPrompt != null ? systemPrompt.strip() : "";
    }

    @Getter
    @Setter
    public static class Intent {

        /** 意图分类模型 */
        private String model = "deepseek-v4-flash";

        /** 意图分类 system 提示词 */
        private String classifierPrompt = "";
    }

    @Getter
    @Setter
    public static class Planner {

        private String model = "deepseek-v4-flash";
        private double temperature = 0;
        private int maxTokens = 1024;
        private int maxNodes = 8;
        private String prompt = "";

        public String promptOrEmpty() {
            return prompt != null ? prompt.strip() : "";
        }
    }

    @Getter
    @Setter
    public static class Timeline {

        private IntentTimeline intent = new IntentTimeline();
        /** 通用步骤 before/active 模板（plan / generate / rag 等），占位符 {query} */
        private java.util.LinkedHashMap<String, StepTimeline> steps = defaultSteps();
        /** 写工具 HITL 各阶段 active/after 模板，占位符 {toolDisplayName} */
        private HitlTimeline hitl = new HitlTimeline();
        /** Plan 用户确认时间线文案 */
        private PlanApprovalTimeline planApproval = new PlanApprovalTimeline();
        /** ReAct agent 步骤摘要模板 */
        private AgentTimeline agent = new AgentTimeline();
        /** RAG 步骤 after 摘要模板 */
        private RagAfterTimeline ragAfter = new RagAfterTimeline();
        /** Workflow / Plan 节点 type 默认展示名（无 displayName 时） */
        private WorkflowNodeTypeTimeline workflowNodeTypes = new WorkflowNodeTypeTimeline();
        /** Workflow agent 节点时间线摘要（无正文预览时的 after / 展开区技能前缀） */
        private WorkflowAgentTimeline workflowAgent = new WorkflowAgentTimeline();
        /** Workflow / Plan 节点完成态摘要（DAG 主行 after） */
        private WorkflowNodeCompletionTimeline workflowNodeCompletion = new WorkflowNodeCompletionTimeline();

        private static java.util.LinkedHashMap<String, StepTimeline> defaultSteps() {
            var map = new java.util.LinkedHashMap<String, StepTimeline>();
            var plan = new StepTimeline();
            plan.setLabel("执行计划");
            plan.setBefore("规划执行路径");
            plan.setActive("正在编排业务节点顺序");
            plan.setAfter("执行计划已生成");
            map.put("plan", plan);
            var generate = new StepTimeline();
            generate.setLabel("生成回答");
            generate.setBefore("为{query}撰写回复");
            generate.setActive("正在撰写并输出针对{query}的回复");
            generate.setAfter("已完成对{query}的回复");
            map.put("generate", generate);
            var rag = new StepTimeline();
            rag.setLabel("检索知识库");
            rag.setBefore("在企业知识库中查找与{query}相关的资料");
            rag.setActive("正在匹配与{query}最相关的文档片段");
            map.put("rag", rag);
            var skill = new StepTimeline();
            skill.setLabel("加载技能");
            skill.setBefore("准备加载 Skill");
            skill.setActive("正在加载 Skill 指令");
            skill.setAfter("@{skillId} {skillDisplayName}");
            skill.setAfterFallback("Skill 已加载");
            map.put("skill", skill);
            var think = new StepTimeline();
            think.setLabel("规划推理");
            think.setLabelFollowUp("综合分析");
            think.setBefore("规划如何回答{query}");
            think.setActive("正在规划{query}的工具调用方案");
            think.setAfter("已完成{query}的工具调用规划");
            think.setBeforeFallback("规划工具与作答路径");
            think.setActiveFallback("正在规划工具调用方案");
            think.setAfterFallback("工具调用方案已拟定");
            think.setBeforeFollowUp("准备结合{toolDisplayName}结果继续分析");
            think.setActiveFollowUp("正在综合分析{toolDisplayName}返回结果");
            think.setAfterFollowUp("已完成{toolDisplayName}的工具结果综合分析");
            think.setBeforeFollowUpNoTool("准备结合工具结果分析{query}");
            think.setActiveFollowUpNoTool("正在结合工具返回结果分析{query}");
            think.setAfterFollowUpNoTool("工具结果综合分析已完成");
            think.setBeforeFollowUpFallback("准备结合工具结果分析");
            think.setActiveFollowUpFallback("正在综合分析工具结果");
            think.setAfterFollowUpFallback("工具结果分析完成");
            var thinkModes = new java.util.LinkedHashMap<String, StepModeTimeline>();
            var simpleLlm = new StepModeTimeline();
            simpleLlm.setLabel("构思回答");
            simpleLlm.setLabelFollowUp("整理作答");
            simpleLlm.setBefore("构思如何回答{query}");
            simpleLlm.setActive("正在构思针对{query}的作答思路");
            simpleLlm.setAfter("已完成针对{query}的作答构思");
            simpleLlm.setBeforeFollowUp("准备整理作答要点");
            simpleLlm.setActiveFollowUp("正在整理作答要点");
            simpleLlm.setAfterFollowUp("作答要点整理完成");
            thinkModes.put("simple-llm", simpleLlm);
            think.setModes(thinkModes);
            map.put("think", think);
            var tool = new StepTimeline();
            tool.setLabel("调用工具 {displayName}");
            tool.setBefore("准备{displayName}");
            tool.setActive("正在{displayName}");
            tool.setAfter("{displayName}完成");
            map.put("tool", tool);
            var node = new StepTimeline();
            node.setBefore("准备{displayName}");
            node.setActive("正在{displayName}");
            node.setAfter("{displayName}完成");
            node.setBeforeWithQuery("准备处理{query}的「{displayName}」环节");
            map.put("node", node);
            return map;
        }
    }

    @Getter
    @Setter
    public static class StepModeTimeline {
        /** 首轮 think 标题（如 simple-llm 的「构思回答」） */
        private String label;
        /** 后续轮 think-N 标题 */
        private String labelFollowUp;
        private String before;
        private String active;
        private String after;
        private String beforeFollowUp;
        private String activeFollowUp;
        private String afterFollowUp;
    }

    @Getter
    @Setter
    public static class StepTimeline {

        /** 时间线主行 step.label */
        private String label;
        /** think-2+ 等后续轮标题（ReAct 默认「综合分析」） */
        private String labelFollowUp;
        private String before;
        private String active;
        /** skill / think 等步骤完成态主行模板 */
        private String after;
        /** workflow node 有用户问句时的 before 模板 */
        private String beforeWithQuery;
        /** 无用户问句时的 before/active/after（ReAct fallback） */
        private String beforeFallback;
        private String activeFallback;
        private String afterFallback;
        /** think-2+ 且已知工具名，占位符 {toolDisplayName} */
        private String beforeFollowUp;
        private String activeFollowUp;
        private String afterFollowUp;
        /** think-2+ 且无工具名，占位符 {query} */
        private String beforeFollowUpNoTool;
        private String activeFollowUpNoTool;
        private String afterFollowUpNoTool;
        /** think-2+ 且无问句时的 fallback */
        private String beforeFollowUpFallback;
        private String activeFollowUpFallback;
        private String afterFollowUpFallback;
        /** 按执行模式覆盖（如 simple-llm） */
        private java.util.LinkedHashMap<String, StepModeTimeline> modes;
    }

    @Getter
    @Setter
    public static class AgentTimeline {

        private String before = "理解{query}，规划作答思路";
        private String active = "结合上下文分析{query}";
        private String progress = "深入分析{query}的背景与上下文";
        private String afterNoContext = "完成问题分析，开始生成回复";
        private String afterOutline = "已梳理{query}的作答要点";
        private String afterZeroHits = "知识库暂无{query}的匹配内容，将结合通用知识作答";
        private String afterWithHits = "已从 {hitCount} 条文档中提取与{query}相关的关键信息";
        private String afterDefault = "已完成对{query}的分析，开始生成回复";
    }

    @Getter
    @Setter
    public static class RagAfterTimeline {

        private String hitsWithSources = "找到 {hitCount} 条参考片段，来源：{sources}";
        private String hitsWithQuery = "找到 {hitCount} 条与{query}相关的参考文档";
        private String zeroHits = "未找到与{query}直接相关的制度或文档";
        private String genericDone = "已完成针对{query}的知识库检索";
    }

    @Getter
    @Setter
    public static class WorkflowNodeCompletionTimeline {

        private String complete = "{displayName}完成";
        private String hitCount = "命中 {hitCount} 条";
        private String skipped = "已跳过";
        private String skippedWithReason = "已跳过：{reason}";
        private String retrySuccess = "（第 {attemptCount} 次尝试成功）";
        private String retryFailedSuffix = "（已重试 {attemptCount} 次）";
        private String nodeFailed = "节点执行失败";
        private String attemptComplete = "完成";
        private String attemptFailed = "失败: {error}";
    }

    @Getter
    @Setter
    public static class WorkflowAgentTimeline {

        private String afterWithTools = "已完成 {toolCallCount} 次工具调用的综合分析";
        private String afterDone = "智能体分析完成";
        private String skillLoadedPrefix = "已加载技能：{skillLabel}";
    }

    @Getter
    @Setter
    public static class WorkflowNodeTypeTimeline {

        private String rag = "检索知识库";
        private String llm = "综合分析";
        private String agent = "智能体分析";
        private String answer = "生成回答";
        private String tool = "调用工具";
        private String unknownWorkflow = "未知工作流";
        private String unknownNode = "节点";
        private String subAgentDefault = "子 Agent 分析";

        public String labelFor(String type) {
            if (type == null || type.isBlank()) {
                return textOrEmpty(unknownNode, "节点");
            }
            return switch (type) {
                case String s when "rag".equals(s) -> textOrEmpty(rag, type);
                case String s when "llm".equals(s) -> textOrEmpty(llm, type);
                case String s when "agent".equals(s) -> textOrEmpty(agent, type);
                case String s when "answer".equals(s) -> textOrEmpty(answer, type);
                case String s when "tool".equals(s) -> textOrEmpty(tool, type);
                default -> type;
            };
        }

        private static String textOrEmpty(String value, String fallback) {
            return StringUtils.hasText(value) ? value.strip() : fallback;
        }
    }

    @Getter
    @Setter
    public static class PlanApprovalTimeline {

        private String awaiting = "等待确认执行计划";
        private String approved = "已确认执行计划";
        private String regenerating = "正在根据修改意见重新规划…";
        private String timedOut = "确认超时，将改由自主智能体继续";
    }

    @Getter
    @Setter
    public static class HitlTimeline {

        private String pending = "将调用工具 {toolDisplayName}";
        private String awaiting = "等待用户确认执行写操作";
        private String approved = "用户已确认，正在调用 {toolDisplayName}";
        private String denied = "用户取消调用";
        private String skippedAfter = "用户取消调用，已跳过";
    }

    public Timeline timelineOrDefault() {
        return timeline != null ? timeline : new Timeline();
    }

    /** 意图步骤 detail / before / active / after 模板，占位符：{query} {detail} {displayName} {workflowId} */
    @Getter
    @Setter
    public static class IntentTimeline {

        private String before = "阅读{query}";
        private String active = "正在分析{query}，匹配最佳处理方式";
        private String label = "识别意图";
        private String defaultAfter = "已完成对{query}的意图判断";
        private String unmatchedAfter = "{query}将按「{detail}」处理";
        private java.util.LinkedHashMap<String, ModeIntent> modes = defaultModes();

        private static java.util.LinkedHashMap<String, ModeIntent> defaultModes() {
            var map = new java.util.LinkedHashMap<String, ModeIntent>();
            var simple = new ModeIntent();
            simple.setDetail("简单对话");
            simple.setAfter("{query}属于简单对话，将直接生成回复");
            map.put("simple-llm", simple);
            var react = new ModeIntent();
            react.setDetail("自主智能体");
            react.setAfter("{query}将由自主智能体分析并作答");
            map.put("react", react);
            var workflow = new ModeIntent();
            workflow.setAfter("{query}将按「{displayName}」流程处理");
            map.put("workflow", workflow);
            var planWorkflow = new ModeIntent();
            planWorkflow.setDetail("动态规划");
            planWorkflow.setAfter("{query}将动态规划多步执行");
            map.put("plan-workflow", planWorkflow);
            return map;
        }
    }

    @Getter
    @Setter
    public static class ModeIntent {

        /** 写入 step.detail 的短标签（workflow 模式可省略，由 catalog displayName 填充） */
        private String detail;
        /** 意图完成后的用户向摘要模板 */
        private String after;
        /** 用户底栏强制模式时的 after 模板 */
        private String forcedAfter;
    }

    public IntentTimeline intentTimelineOrDefault() {
        if (timeline == null || timeline.intent == null) {
            Timeline t = new Timeline();
            return t.getIntent();
        }
        return timeline.intent;
    }

    public String intentClassifierPromptOrEmpty() {
        return intent != null && intent.classifierPrompt != null
                ? intent.classifierPrompt.strip()
                : "";
    }

    public String intentModelOrDefault() {
        if (intent == null || !StringUtils.hasText(intent.model)) {
            return "deepseek-v4-flash";
        }
        return intent.model.strip();
    }

    public Planner plannerOrDefault() {
        return planner != null ? planner : new Planner();
    }
}
