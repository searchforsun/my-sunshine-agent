package com.sunshine.llm.filter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.sunshine.llm.model.ChatCompletionRequest;
import com.sunshine.llm.registry.ModelCapabilities;
import com.sunshine.llm.registry.ModelDefinitionView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * capabilities 契约：请求校验 + 响应 reasoning 字段归一化。
 * <p>
 * MiniMax {@code reasoning_split} 流式思考常在 {@code reasoning_details[].text}（累计全文），
 * 需转为增量 {@code reasoning_content}，供 AgentScope ThinkingBlock 消费。
 */
@Component
@RequiredArgsConstructor
public class NormalizeFilter {

    public static final String MODEL_NOT_MULTIMODAL = "model_not_multimodal";
    public static final String MODEL_NOT_TOOL_CALL = "model_not_tool_call";

    private static final Pattern THINK_BLOCK = Pattern.compile(
            "(?is)<think>(.*?)</think>");

    private final ObjectMapper objectMapper;

    public void validateRequest(ChatCompletionRequest request, ModelDefinitionView definition) {
        if (request == null || definition == null) {
            return;
        }
        ModelCapabilities caps = definition.getCapabilities() != null
                ? definition.getCapabilities()
                : ModelCapabilities.defaults();
        if (!caps.isMultimodal() && messagesContainImageUrl(request.getMessages())) {
            throw new IllegalArgumentException(MODEL_NOT_MULTIMODAL);
        }
        if (!caps.isToolCall() && request.getTools() != null && !request.getTools().isEmpty()) {
            throw new IllegalArgumentException(MODEL_NOT_TOOL_CALL);
        }
    }

    public String normalizeResponseBody(String body, boolean reasoningEnabled) {
        if (body == null || body.isBlank()) {
            return body;
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            normalizeResponseNode(root, reasoningEnabled, null);
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            return body;
        }
    }

    public JsonNode normalizeResponseNode(JsonNode root, boolean reasoningEnabled) {
        return normalizeResponseNode(root, reasoningEnabled, null);
    }

    public JsonNode normalizeResponseNode(
            JsonNode root, boolean reasoningEnabled, ReasoningStreamState streamState) {
        if (root == null || !root.isObject()) {
            return root;
        }
        JsonNode choices = root.get("choices");
        if (choices != null && choices.isArray()) {
            for (JsonNode choice : choices) {
                if (!(choice instanceof ObjectNode choiceObj)) {
                    continue;
                }
                normalizeMessageContainer(choiceObj, "message", reasoningEnabled, streamState);
                normalizeMessageContainer(choiceObj, "delta", reasoningEnabled, streamState);
            }
        }
        return root;
    }

    /** SSE data 行：JSON chunk 内 reasoning 字段按 capabilities 处理 */
    public String normalizeStreamData(String data, boolean reasoningEnabled) {
        return normalizeStreamData(data, reasoningEnabled, null);
    }

    public String normalizeStreamData(
            String data, boolean reasoningEnabled, ReasoningStreamState streamState) {
        if (data == null || data.isBlank() || "[DONE]".equals(data.strip())) {
            return data;
        }
        String payload = data.strip();
        if (payload.startsWith("data:")) {
            payload = payload.substring(5).strip();
        }
        if (payload.isEmpty() || "[DONE]".equals(payload)) {
            return data;
        }
        try {
            JsonNode root = objectMapper.readTree(payload);
            normalizeResponseNode(root, reasoningEnabled, streamState);
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            return data;
        }
    }

    private void normalizeMessageContainer(
            ObjectNode parent,
            String field,
            boolean reasoningEnabled,
            ReasoningStreamState streamState) {
        JsonNode node = parent.get(field);
        if (!(node instanceof ObjectNode message)) {
            return;
        }
        if (reasoningEnabled) {
            promoteReasoningFields(message, streamState);
            splitThinkTagsFromContent(message);
            message.remove("reasoning");
            message.remove("thinking");
            // 已提升为 reasoning_content，避免下游重复消费 cumulative details
            message.remove("reasoning_details");
        } else {
            message.remove("reasoning_content");
            message.remove("reasoning");
            message.remove("thinking");
            message.remove("reasoning_details");
        }
    }

    private void promoteReasoningFields(ObjectNode message, ReasoningStreamState streamState) {
        if (message.hasNonNull("reasoning_content")
                && !message.get("reasoning_content").asText("").isBlank()) {
            return;
        }
        if (message.has("reasoning") && message.get("reasoning").isTextual()) {
            message.set("reasoning_content", message.get("reasoning"));
            return;
        }
        if (message.has("thinking") && message.get("thinking").isTextual()) {
            message.set("reasoning_content", message.get("thinking"));
            return;
        }
        String detailsText = joinReasoningDetailsText(message.get("reasoning_details"));
        if (detailsText.isEmpty()) {
            return;
        }
        if (streamState != null) {
            String incremental = streamState.nextReasoningDetailsDelta(detailsText);
            if (!incremental.isEmpty()) {
                message.set("reasoning_content", TextNode.valueOf(incremental));
            }
        } else {
            message.set("reasoning_content", TextNode.valueOf(detailsText));
        }
    }

    /**
     * 兜底：无独立 reasoning 字段时，从 content 中拆出 {@code <think>...</think>}。
     */
    private static void splitThinkTagsFromContent(ObjectNode message) {
        if (message.hasNonNull("reasoning_content")
                && !message.get("reasoning_content").asText("").isBlank()) {
            return;
        }
        JsonNode contentNode = message.get("content");
        if (contentNode == null || !contentNode.isTextual()) {
            return;
        }
        String content = contentNode.asText("");
        if (content.isEmpty() || !content.contains("<think")) {
            return;
        }
        Matcher matcher = THINK_BLOCK.matcher(content);
        StringBuilder thinking = new StringBuilder();
        StringBuffer cleaned = new StringBuffer();
        while (matcher.find()) {
            String block = matcher.group(1);
            if (block != null && !block.isBlank()) {
                if (!thinking.isEmpty()) {
                    thinking.append('\n');
                }
                thinking.append(block.strip());
            }
            matcher.appendReplacement(cleaned, "");
        }
        matcher.appendTail(cleaned);
        if (thinking.isEmpty()) {
            return;
        }
        message.put("reasoning_content", thinking.toString());
        message.put("content", cleaned.toString().strip());
    }

    static String joinReasoningDetailsText(JsonNode details) {
        if (details == null || details.isNull()) {
            return "";
        }
        if (details.isTextual()) {
            return details.asText("");
        }
        if (!details.isArray()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode item : details) {
            if (item == null || item.isNull()) {
                continue;
            }
            if (item.isTextual()) {
                sb.append(item.asText(""));
                continue;
            }
            if (item.isObject()) {
                JsonNode text = item.get("text");
                if (text != null && text.isTextual()) {
                    sb.append(text.asText(""));
                }
            }
        }
        return sb.toString();
    }

    /** 流式 reasoning_details 累计文本 → 增量切片状态 */
    public static final class ReasoningStreamState {
        private String lastDetailsText = "";

        String nextReasoningDetailsDelta(String cumulative) {
            if (cumulative == null || cumulative.isEmpty()) {
                return "";
            }
            if (cumulative.startsWith(lastDetailsText)) {
                String delta = cumulative.substring(lastDetailsText.length());
                lastDetailsText = cumulative;
                return delta;
            }
            // 非前缀续写（新一轮思考）：整段作为增量
            lastDetailsText = cumulative;
            return cumulative;
        }
    }

    static boolean messagesContainImageUrl(List<ChatCompletionRequest.Message> messages) {
        if (messages == null) {
            return false;
        }
        for (ChatCompletionRequest.Message message : messages) {
            if (contentHasImageUrl(message.getContent())) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static boolean contentHasImageUrl(Object content) {
        if (content == null) {
            return false;
        }
        if (content instanceof String) {
            return false;
        }
        if (content instanceof List<?> list) {
            for (Object part : list) {
                if (part instanceof Map<?, ?> map) {
                    Object type = map.get("type");
                    if ("image_url".equals(type) || map.containsKey("image_url")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
