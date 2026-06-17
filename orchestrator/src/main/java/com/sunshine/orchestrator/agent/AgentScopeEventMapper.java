package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.processing.ProcessingTimelineSession;
import com.sunshine.orchestrator.processing.ProcessingTimelineSupport;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 将 AgentScope {@link Event} 映射为 {@link StreamToken}，步骤直接内联到主流。
 * reasoning 统一进 {@code think}，废弃 {@code agent} 容器步。
 */
@Slf4j
public final class AgentScopeEventMapper {

    private static final String THINK = "think";
    private static final String GENERATE = "generate";

    private AgentScopeEventMapper() {
    }

    public static List<StreamToken> map(
            Event event,
            ProcessingTimelineSession session,
            AtomicBoolean generateStarted,
            String ragDetailHint,
            String userQuery) {

        List<StreamToken> out = new ArrayList<>();
        EventType type = event.getType();
        Msg msg = event.getMessage();
        if (msg == null) {
            return out;
        }

        if (type == EventType.REASONING) {
            List<String> reasoningTexts = collectReasoningTexts(msg);
            if (!reasoningTexts.isEmpty()) {
                int chars = reasoningTexts.stream().mapToInt(String::length).sum();
                log.info("[Orchestrator] ReAct 思考 blocks={} chars={} preview={}",
                        reasoningTexts.size(), chars, previewReasoning(reasoningTexts.get(0)));
            } else {
                log.info("[Orchestrator] ReAct 无 ThinkingBlock（上游未返回 reasoning_content）");
            }
            out.addAll(ensureThinkStarted(session));
            for (String text : reasoningTexts) {
                emitThinkReasoningDelta(session, out, text);
            }
            return out;
        }

        if (type == EventType.TOOL_RESULT) {
            out.addAll(appendToolResultSteps(msg, session));
            return out;
        }

        if (type == EventType.AGENT_RESULT) {
            boolean hasContent = hasAnswerContent(msg);
            if (hasContent) {
                if (generateStarted.compareAndSet(false, true)) {
                    long firstTokenAt = System.currentTimeMillis();
                    out.addAll(ProcessingTimelineSupport.run(session, () -> {
                        completeThinkIfRunning(session);
                        session.pending(GENERATE, GENERATE);
                        session.startAt(GENERATE, GENERATE, firstTokenAt);
                    }));
                }
                appendContentTokens(msg, out);
            } else if (event.isLast()) {
                out.addAll(ProcessingTimelineSupport.run(session, () -> completeThinkIfRunning(session)));
            }
            return out;
        }

        if (type == EventType.HINT) {
            for (ToolUseBlock toolUse : msg.getContentBlocks(ToolUseBlock.class)) {
                if ("search_knowledge".equals(toolUse.getName())) {
                    out.addAll(ensureRagStarted(session));
                }
            }
            return out;
        }

        return out;
    }

    private static boolean hasAnswerContent(Msg msg) {
        if (!msg.getContentBlocks(TextBlock.class).isEmpty()) {
            return true;
        }
        String text = msg.getTextContent();
        return text != null && !text.isEmpty();
    }

    private static List<StreamToken> ensureRagStarted(ProcessingTimelineSession session) {
        if (session.hasStep("rag")) {
            return List.of();
        }
        return ProcessingTimelineSupport.run(session, () -> {
            session.pending("rag", "rag");
            session.start("rag", "rag");
        });
    }

    private static List<StreamToken> ensureThinkStarted(ProcessingTimelineSession session) {
        if (session.hasStep(THINK)) {
            return List.of();
        }
        return ProcessingTimelineSupport.run(session, () -> {
            session.pending(THINK, THINK);
            session.start(THINK, THINK);
        });
    }

    private static void completeThinkIfRunning(ProcessingTimelineSession session) {
        if (session.isThinkRunning()) {
            session.complete(THINK, null);
        }
    }

    /** reasoning 写入 think 步骤 */
    static void appendThinkingTokens(Msg msg, ProcessingTimelineSession session, List<StreamToken> out) {
        out.addAll(ensureThinkStarted(session));
        for (String thinking : collectReasoningTexts(msg)) {
            emitThinkReasoningDelta(session, out, thinking);
        }
    }

    private static List<String> collectReasoningTexts(Msg msg) {
        List<String> texts = new ArrayList<>();
        for (ThinkingBlock block : msg.getContentBlocks(ThinkingBlock.class)) {
            String thinking = block.getThinking();
            if (thinking != null && !thinking.isEmpty()) {
                texts.add(thinking);
            }
        }
        return texts;
    }

    private static String previewReasoning(String text) {
        if (text == null || text.isBlank()) {
            return "-";
        }
        String normalized = text.strip().replace('\n', ' ');
        return normalized.length() <= 80 ? normalized : normalized.substring(0, 80) + "...";
    }

    private static void emitThinkReasoningDelta(
            ProcessingTimelineSession session, List<StreamToken> out, String thinking) {
        if (!session.hasStep(THINK)) {
            out.addAll(ensureThinkStarted(session));
        }
        out.add(StreamToken.stepDelta(THINK, "reasoning", thinking));
    }

    static void appendContentTokens(Msg msg, List<StreamToken> out) {
        for (TextBlock block : msg.getContentBlocks(TextBlock.class)) {
            String text = block.getText();
            if (text != null && !text.isEmpty()) {
                out.add(StreamToken.content(text));
            }
        }
        String fallback = msg.getTextContent();
        if (out.stream().noneMatch(StreamToken::isContent) && fallback != null && !fallback.isEmpty()) {
            out.add(StreamToken.content(fallback));
        }
    }

    private static List<StreamToken> appendToolResultSteps(Msg msg, ProcessingTimelineSession session) {
        List<StreamToken> steps = new ArrayList<>();
        for (ToolResultBlock block : msg.getContentBlocks(ToolResultBlock.class)) {
            if ("search_knowledge".equals(block.getName())) {
                String detail = summarizeHits(block);
                steps.addAll(ProcessingTimelineSupport.run(session, () -> {
                    if (!session.hasStep("rag")) {
                        session.pending("rag", "rag");
                        session.start("rag", "rag");
                    }
                    session.complete("rag", detail);
                }));
                continue;
            }
            String toolName = block.getName();
            if (toolName == null || toolName.isBlank()) {
                continue;
            }
            String stepId = "tool-" + toolName;
            String detail = summarizeToolOutput(block);
            steps.addAll(ProcessingTimelineSupport.run(session, () -> {
                if (!session.hasStep(stepId)) {
                    session.pending(stepId, "tool");
                    session.start(stepId, "tool");
                }
                session.complete(stepId, detail);
            }));
        }
        return steps;
    }

    private static String summarizeToolOutput(ToolResultBlock block) {
        if (block == null || block.getOutput() == null) {
            return "无结果";
        }
        String text = block.getOutput().stream()
                .filter(b -> b instanceof TextBlock)
                .map(b -> ((TextBlock) b).getText())
                .findFirst()
                .orElse("");
        if (text.isBlank()) {
            return "无结果";
        }
        if ("list_finance_messages".equals(block.getName())) {
            return RagHitSummarizer.summarize(text);
        }
        return text.length() > 80 ? text.substring(0, 80) + "…" : text;
    }

    static String summarizeHits(ToolResultBlock block) {
        if (block == null || block.getOutput() == null) {
            return "命中 0 条";
        }
        String text = block.getOutput().stream()
                .filter(b -> b instanceof TextBlock)
                .map(b -> ((TextBlock) b).getText())
                .findFirst()
                .orElse("");
        return RagHitSummarizer.summarize(text);
    }
}
