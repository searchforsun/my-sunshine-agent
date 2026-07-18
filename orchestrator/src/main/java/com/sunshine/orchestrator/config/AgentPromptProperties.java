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
        /** 沙箱工具时间线（path / pattern / command），SSOT：Nacos agent.timeline.sandbox */
        private SandboxTimeline sandbox = new SandboxTimeline();

        private static java.util.LinkedHashMap<String, StepTimeline> defaultSteps() {
            var map = new java.util.LinkedHashMap<String, StepTimeline>();
            var plan = new StepTimeline();
            plan.setLabel("执行计划");
            plan.setBefore("规划执行路径");
            plan.setActive("正在编排业务节点顺序");
            plan.setAfter("执行计划已生成");
            map.put("plan", plan);
            var tasks = new StepTimeline();
            tasks.setLabel("任务清单");
            tasks.setBefore("规划任务步骤");
            tasks.setActive("正在执行：{activeTask}");
            tasks.setAfter("任务清单已更新");
            tasks.setAllDone("全部任务已完成");
            map.put("tasks", tasks);
            var subagent = new StepTimeline();
            subagent.setLabel("子任务");
            subagent.setBefore("准备委派子任务");
            subagent.setActive("正在执行：{label}");
            subagent.setAfter("子任务已完成");
            subagent.setAfterFail("子任务失败");
            subagent.setAfterCancel("已取消");
            map.put("subagent", subagent);
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
        /** 首轮 think 标题（按执行模式覆盖时） */
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
        /** 专家再次发言且 Hub 内已有其他专家 */
        private String activeResponding;
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
        /** 按执行模式覆盖 */
        private java.util.LinkedHashMap<String, StepModeTimeline> modes;
        /** TaskBoard 全部完成 after */
        private String allDone;
        /** spawn_subagent 失败 after */
        private String afterFail;
        /** spawn_subagent 用户取消 after */
        private String afterCancel;
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

    /**
     * 沙箱六工具时间线 — 占位符 {displayName} {path} {fileName} {headerPath} {displayPath} {pattern} {command} {cwd}。
     */
    @Getter
    @Setter
    public static class SandboxTimeline {

        private String afterFallback = "";
        private String readAfter = "{headerPath}";
        private String writeAfter = "{headerPath}";
        private String editAfter = "{headerPath}";
        private String globAfter = "{pattern}";
        private String globAfterWithPath = "{pattern} · {path}";
        private String grepAfter = "{pattern}";
        private String execAfter = "{command}";
        private String readActive = "正在读取 {path}";
        private String writeActive = "正在写入 {path}";
        private String editActive = "正在修改 {path}";
        private String globActive = "正在查找 {pattern}";
        private String grepActive = "正在搜索 {pattern}";
        private String execActive = "正在执行 {command}";
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
            var react = new ModeIntent();
            react.setDetail("自主智能体");
            react.setAfter("{query}将由自主智能体分析并作答");
            map.put("react", react);
            var workflow = new ModeIntent();
            workflow.setAfter("{query}将按「{displayName}」流程处理");
            workflow.setForcedAfter("{query}将按您指定的「工作流」模式处理");
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
