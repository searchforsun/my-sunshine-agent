package com.sunshine.orchestrator.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;

/**
 * Agent 非正文配置（温度/节点上限等）— 提示词正文 SSOT = prompt-manager Catalog；
 * 模型名 SSOT = ModelSceneResolver（注册表 scene_binding）。
 * 时间线嵌套 POJO 仅作 Catalog JSON 反序列化目标；样例文案见 {@link Timeline#fixture()}（单测）。
 */
@Getter
@Setter
@RefreshScope
@ConfigurationProperties(prefix = "agent")
public class AgentPromptProperties {

    private Planner planner = new Planner();

    @Getter
    @Setter
    public static class Planner {

        private double temperature = 0;
        private int maxTokens = 1024;
        private int maxNodes = 8;
        /** gateway/join/xgw/loop/answer 等路由节点相对 maxNodes 的 headroom */
        private int routingNodeBuffer = 6;
    }

    /** 时间线文案结构（Catalog JSON 反序列化目标） */
    @Getter
    @Setter
    public static class Timeline {

        private IntentTimeline intent = new IntentTimeline();
        private java.util.LinkedHashMap<String, StepTimeline> steps = new java.util.LinkedHashMap<>();
        private HitlTimeline hitl = new HitlTimeline();
        private PlanApprovalTimeline planApproval = new PlanApprovalTimeline();
        private AgentTimeline agent = new AgentTimeline();
        private RagAfterTimeline ragAfter = new RagAfterTimeline();
        private SandboxTimeline sandbox = new SandboxTimeline();

        /** 单测 / {@code TimelinePromptCatalog.withDefaults} 用样例；生产禁止当兜底 */
        public static Timeline fixture() {
            Timeline t = new Timeline();
            t.setIntent(fixtureIntent());
            t.setSteps(fixtureSteps());
            t.setHitl(fixtureHitl());
            t.setPlanApproval(fixturePlanApproval());
            t.setAgent(fixtureAgent());
            t.setRagAfter(fixtureRagAfter());
            t.setSandbox(fixtureSandbox());
            return t;
        }

        public static java.util.LinkedHashMap<String, StepTimeline> fixtureSteps() {
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
            generate.setBefore("撰写回复");
            generate.setActive("正在撰写并输出回复");
            generate.setAfter("已完成回复");
            map.put("generate", generate);
            var rag = new StepTimeline();
            rag.setLabel("检索知识库");
            rag.setBefore("在企业知识库中查找相关资料");
            rag.setActive("正在匹配最相关的文档片段");
            map.put("rag", rag);
            var skill = new StepTimeline();
            skill.setLabel("加载技能");
            skill.setBefore("准备加载 Skill");
            skill.setActive("正在加载 Skill 指令");
            skill.setAfter("{skillId} {skillDisplayName}");
            skill.setAfterFallback("Skill 已加载");
            map.put("skill", skill);
            var think = new StepTimeline();
            think.setLabel("深度思考");
            think.setBefore("规划工具与作答路径");
            think.setActive("正在规划工具调用方案");
            think.setAfter("工具调用方案已拟定");
            think.setBeforeFallback("规划工具与作答路径");
            think.setActiveFallback("正在规划工具调用方案");
            think.setAfterFallback("工具调用方案已拟定");
            think.setBeforeFollowUp("准备结合{toolDisplayName}结果继续分析");
            think.setActiveFollowUp("正在综合分析{toolDisplayName}返回结果");
            think.setAfterFollowUp("已完成{toolDisplayName}的工具结果综合分析");
            think.setBeforeFollowUpNoTool("准备结合工具结果分析");
            think.setActiveFollowUpNoTool("正在结合工具返回结果分析");
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
            var awaitTool = new StepTimeline();
            awaitTool.setLabel("等待结果");
            awaitTool.setLabelFollowUp("后台执行");
            awaitTool.setBefore("准备等待后台任务");
            awaitTool.setActive("正在等待后台任务");
            awaitTool.setAfter("等待完成");
            map.put("await-tool", awaitTool);
            var node = new StepTimeline();
            node.setBefore("准备{displayName}");
            node.setActive("正在{displayName}");
            node.setAfter("{displayName}完成");
            node.setBeforeWithQuery("准备「{displayName}」环节");
            map.put("node", node);
            return map;
        }

        private static IntentTimeline fixtureIntent() {
            var intent = new IntentTimeline();
            intent.setBefore("识别用户意图");
            intent.setActive("正在匹配最佳处理方式");
            intent.setLabel("识别意图");
            intent.setDefaultAfter("已完成意图判断");
            intent.setUnmatchedAfter("将按「{detail}」处理");
            var modes = new java.util.LinkedHashMap<String, ModeIntent>();
            var react = new ModeIntent();
            react.setDetail("自主智能体");
            react.setAfter("将由自主智能体分析并作答");
            modes.put("react", react);
            var workflow = new ModeIntent();
            workflow.setAfter("将按「{displayName}」流程处理");
            workflow.setForcedAfter("将按您指定的「工作流」模式处理");
            modes.put("workflow", workflow);
            var planWorkflow = new ModeIntent();
            planWorkflow.setDetail("动态规划");
            planWorkflow.setAfter("将动态规划多步执行");
            modes.put("plan-workflow", planWorkflow);
            intent.setModes(modes);
            return intent;
        }

        private static HitlTimeline fixtureHitl() {
            var hitl = new HitlTimeline();
            hitl.setPending("将调用工具 {toolDisplayName}");
            hitl.setAwaiting("等待用户确认执行写操作");
            hitl.setApproved("用户已确认，正在调用 {toolDisplayName}");
            hitl.setDenied("用户取消调用");
            hitl.setSkippedAfter("用户取消调用，已跳过");
            return hitl;
        }

        private static PlanApprovalTimeline fixturePlanApproval() {
            var p = new PlanApprovalTimeline();
            p.setAwaiting("等待确认执行计划");
            p.setApproved("已确认执行计划");
            p.setRegenerating("正在根据修改意见重新规划…");
            p.setTimedOut("确认超时，将改由自主智能体继续");
            return p;
        }

        private static AgentTimeline fixtureAgent() {
            var a = new AgentTimeline();
            a.setBefore("理解问题，规划作答思路");
            a.setActive("结合上下文进行分析");
            a.setProgress("深入分析背景与上下文");
            a.setAfterNoContext("完成问题分析，开始生成回复");
            a.setAfterOutline("已梳理作答要点");
            a.setAfterZeroHits("知识库暂无匹配内容，将结合通用知识作答");
            a.setAfterWithHits("已从 {hitCount} 条文档中提取关键信息");
            a.setAfterDefault("已完成分析，开始生成回复");
            return a;
        }

        private static RagAfterTimeline fixtureRagAfter() {
            var r = new RagAfterTimeline();
            r.setHitsWithSources("找到 {hitCount} 条参考片段，来源：{sources}");
            r.setHitsWithQuery("找到 {hitCount} 条相关参考文档");
            r.setZeroHits("未找到直接相关的制度或文档");
            r.setGenericDone("已完成知识库检索");
            return r;
        }

        private static SandboxTimeline fixtureSandbox() {
            var s = new SandboxTimeline();
            s.setAfterFallback("");
            s.setReadAfter("{headerPath}");
            s.setWriteAfter("{headerPath}");
            s.setEditAfter("{headerPath}");
            s.setGlobAfter("{pattern}");
            s.setGlobAfterWithPath("{pattern} · {path}");
            s.setGrepAfter("{pattern}");
            s.setExecAfter("{command}");
            s.setReadActive("正在读取 {displayPath}");
            s.setWriteActive("正在写入 {displayPath}");
            s.setEditActive("正在修改 {displayPath}");
            s.setGlobActive("正在查找 {pattern}");
            s.setGrepActive("正在搜索 {pattern}");
            s.setExecActive("正在执行 {command}");
            return s;
        }
    }

    @Getter
    @Setter
    public static class StepModeTimeline {
        private String label;
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

        private String label;
        private String labelFollowUp;
        private String before;
        private String active;
        private String activeResponding;
        private String after;
        private String afterFallback;
        private String beforeFallback;
        private String activeFallback;
        private String beforeFollowUp;
        private String activeFollowUp;
        private String afterFollowUp;
        private String beforeFollowUpNoTool;
        private String activeFollowUpNoTool;
        private String afterFollowUpNoTool;
        private String beforeFollowUpFallback;
        private String activeFollowUpFallback;
        private String afterFollowUpFallback;
        private String beforeWithQuery;
        private java.util.LinkedHashMap<String, StepModeTimeline> modes;
        private String allDone;
        private String afterFail;
        private String afterCancel;
    }

    @Getter
    @Setter
    public static class AgentTimeline {

        private String before;
        private String active;
        private String progress;
        private String afterNoContext;
        private String afterOutline;
        private String afterZeroHits;
        private String afterWithHits;
        private String afterDefault;
    }

    @Getter
    @Setter
    public static class RagAfterTimeline {

        private String hitsWithSources;
        private String hitsWithQuery;
        private String zeroHits;
        private String genericDone;
    }

    @Getter
    @Setter
    public static class PlanApprovalTimeline {

        private String awaiting;
        private String approved;
        private String regenerating;
        private String timedOut;
    }

    @Getter
    @Setter
    public static class HitlTimeline {

        private String pending;
        private String awaiting;
        private String approved;
        private String denied;
        private String skippedAfter;
    }

    @Getter
    @Setter
    public static class SandboxTimeline {

        private String afterFallback;
        private String readAfter;
        private String writeAfter;
        private String editAfter;
        private String globAfter;
        private String globAfterWithPath;
        private String grepAfter;
        private String execAfter;
        private String webfetchAfter;
        private String websearchAfter;
        private String readActive;
        private String writeActive;
        private String editActive;
        private String globActive;
        private String grepActive;
        private String execActive;
        private String webfetchActive;
        private String websearchActive;
    }

    @Getter
    @Setter
    public static class IntentTimeline {

        private String before;
        private String active;
        private String label;
        private String defaultAfter;
        private String unmatchedAfter;
        private java.util.LinkedHashMap<String, ModeIntent> modes;
    }

    @Getter
    @Setter
    public static class ModeIntent {

        private String detail;
        private String after;
        private String forcedAfter;
    }

    public Planner plannerOrDefault() {
        return planner != null ? planner : new Planner();
    }
}
