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

        private static java.util.LinkedHashMap<String, StepTimeline> defaultSteps() {
            var map = new java.util.LinkedHashMap<String, StepTimeline>();
            var plan = new StepTimeline();
            plan.setBefore("规划执行路径");
            plan.setActive("正在编排业务节点顺序");
            map.put("plan", plan);
            var generate = new StepTimeline();
            generate.setBefore("为{query}撰写回复");
            generate.setActive("正在撰写并输出针对{query}的回复");
            map.put("generate", generate);
            var rag = new StepTimeline();
            rag.setBefore("在企业知识库中查找与{query}相关的资料");
            rag.setActive("正在匹配与{query}最相关的文档片段");
            map.put("rag", rag);
            var skill = new StepTimeline();
            skill.setBefore("准备加载 Skill");
            skill.setActive("正在加载 Skill 指令");
            skill.setAfter("@{skillId} {skillDisplayName}");
            map.put("skill", skill);
            return map;
        }
    }

    @Getter
    @Setter
    public static class StepTimeline {

        private String before;
        private String active;
        /** skill 等步骤完成态主行模板，占位符见 SkillLoadLabelService */
        private String after;
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
