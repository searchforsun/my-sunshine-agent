package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.catalog.ToolCatalogService;
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

    private static final String GENERATE = "generate";
    private static volatile ToolCatalogService catalogService;

    private AgentScopeEventMapper() {
    }

    public static void bind(ToolCatalogService catalog) {
        catalogService = catalog;
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
            if (reasoningTexts.isEmpty()) {
                log.debug("[Orchestrator] ReAct 跳过空 REASONING（无 ThinkingBlock）");
                return out;
            }
            if (!session.isThinkRunning()) {
                log.debug("[Orchestrator] ReAct 跳过 REASONING（无 PreReasoning 打开的 think）");
                return out;
            }
            int chars = reasoningTexts.stream().mapToInt(String::length).sum();
            log.info("[Orchestrator] ReAct 思考 blocks={} chars={} isLast={} preview={}",
                    reasoningTexts.size(), chars, event.isLast(), previewReasoning(reasoningTexts.get(0)));
            String merged = mergeReasoningTexts(reasoningTexts);
            if (merged != null && !merged.isEmpty()) {
                emitThinkReasoningDelta(session, out, merged);
            }
            return out;
        }

        if (type == EventType.AGENT_RESULT) {
            boolean hasContent = hasAnswerContent(msg);
            if (hasContent) {
                if (generateStarted.compareAndSet(false, true)) {
                    long firstTokenAt = System.currentTimeMillis();
                    out.addAll(ProcessingTimelineSupport.run(session, () -> {
                        session.completeThinkIfRunning();
                        session.pending(GENERATE, GENERATE);
                        session.startAt(GENERATE, GENERATE, firstTokenAt);
                    }));
                }
                appendContentTokens(msg, out);
            } else if (event.isLast()) {
                out.addAll(ProcessingTimelineSupport.run(session, session::completeThinkIfRunning));
            }
            return out;
        }

        if (type == EventType.HINT) {
            for (ToolUseBlock toolUse : msg.getContentBlocks(ToolUseBlock.class)) {
                if (isRagTool(toolUse.getName())) {
                    out.addAll(ensureRagStarted(session));
                }
            }
            return out;
        }

        return out;
    }

    private static boolean isRagTool(String toolName) {
        if (catalogService != null) {
            return catalogService.isRagTool(toolName);
        }
        return "search_knowledge".equals(toolName);
    }

    private static String timelineStepId(String toolName) {
        if (catalogService != null) {
            return catalogService.timelineStepId(toolName);
        }
        return isRagTool(toolName) ? "rag" : "tool-" + toolName;
    }

    private static String summarizeOutput(String toolName, String text) {
        if (catalogService != null) {
            return catalogService.summarizeOutput(toolName, text);
        }
        return ToolResultSummarizer.summarize(toolName, text);
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

    /** reasoning 写入 Hook 已开启的 think 步骤，不自行开闭 */
    static void appendThinkingTokens(Msg msg, ProcessingTimelineSession session, List<StreamToken> out) {
        List<String> reasoningTexts = collectReasoningTexts(msg);
        if (reasoningTexts.isEmpty() || !session.isThinkRunning()) {
            return;
        }
        for (String thinking : reasoningTexts) {
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

    /** 单帧多 block 时取最长全文，避免重复 emit 导致 reasoning 翻倍 */
    private static String mergeReasoningTexts(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return null;
        }
        String best = texts.get(0);
        for (int i = 1; i < texts.size(); i++) {
            String candidate = texts.get(i);
            if (candidate.length() > best.length()) {
                best = candidate;
            } else if (best.length() > candidate.length() && best.startsWith(candidate)) {
                continue;
            } else if (candidate.length() >= best.length() && candidate.startsWith(best)) {
                best = candidate;
            }
        }
        return best;
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
        String thinkId = session.currentThinkStepId();
        if (thinkId != null && session.isThinkRunning()) {
            out.add(StreamToken.stepDelta(thinkId, "reasoning", thinking));
        }
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

    private static String extractToolResultText(ToolResultBlock block) {
        if (block == null || block.getOutput() == null) {
            return "";
        }
        return block.getOutput().stream()
                .filter(b -> b instanceof TextBlock)
                .map(b -> ((TextBlock) b).getText())
                .findFirst()
                .orElse("");
    }

    private static String summarizeToolOutput(ToolResultBlock block) {
        if (block == null) {
            return "无结果";
        }
        return summarizeOutput(block.getName(), extractToolResultText(block));
    }

    static String summarizeHits(ToolResultBlock block) {
        return ToolResultSummarizer.summarize("search_knowledge", extractToolResultText(block));
    }
}
