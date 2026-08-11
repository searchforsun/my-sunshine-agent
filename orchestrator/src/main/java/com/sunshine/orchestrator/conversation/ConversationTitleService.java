package com.sunshine.orchestrator.conversation;

import com.sunshine.common.model.ModelSceneKey;
import com.sunshine.orchestrator.client.LlmGatewayClient;
import com.sunshine.orchestrator.config.ConversationTitleProperties;
import com.sunshine.orchestrator.controller.stream.ChatStreamContext;
import com.sunshine.orchestrator.conversation.entity.ChatConversationEntity;
import com.sunshine.orchestrator.prompt.PromptCatalogHolder;
import com.sunshine.orchestrator.registry.ModelSceneResolver;
import com.sunshine.orchestrator.registry.ResolvedModelScene;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 首条消息标题摘要 — 小模型生成 ≤maxLength 字标题。
 * 触发条件由 {@link ChatStreamContext#autoTitle()} 表达（仅新会话首条消息为 true）；
 * 生成与主流并行（boundedElastic），完成后经 {@code meta:title} SSE 事件推送前端。
 * 失败 / 空结果 / 用户已手动改名时跳过落库与推送，保留 prepareNewMessage 同步落库的截断标题。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationTitleService {

    private final ConversationTitleProperties titleProperties;
    private final PromptCatalogHolder catalogHolder;
    private final LlmGatewayClient llmGatewayClient;
    private final ModelSceneResolver modelSceneResolver;
    private final ConversationService conversationService;
    private final GenerationFlushScheduler flushScheduler;

    /**
     * 首条消息时并行启动标题生成；非首条或未启用返回空 Flux（不推事件）。
     * future 在创建即开始执行（boundedElastic），与 SSE 主流并行，不阻塞首 token。
     */
    public Flux<ServerSentEvent<String>> titleEventSse(ChatStreamContext ctx) {
        if (!ctx.autoTitle() || !titleProperties.isEnabled()) {
            return Flux.empty();
        }
        CompletableFuture<String> future = new CompletableFuture<>();
        Schedulers.boundedElastic().schedule(() -> {
            try {
                future.complete(generateTitle(ctx));
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return Mono.fromFuture(future)
                .filter(StringUtils::hasText)
                .map(title -> sse(flushScheduler.metaTitle(ctx.conversationId(), title)))
                .onErrorResume(e -> {
                    log.warn("[Title] 标题生成失败 conv={}: {}", ctx.conversationId(), e.getMessage());
                    return Mono.empty();
                })
                .flux();
    }

    /** 调小模型生成标题并落库；返回空串表示跳过（不推事件）。 */
    String generateTitle(ChatStreamContext ctx) {
        String systemPrompt = catalogHolder.snapshot().text("conversation.title").map(String::strip).orElse("");
        if (!StringUtils.hasText(systemPrompt)) {
            return "";
        }
        ResolvedModelScene model = modelSceneResolver.resolve(ModelSceneKey.TITLE.key(), null);
        String raw = llmGatewayClient.complete(
                model.effectiveModel(), model.fallbackModel(), systemPrompt, ctx.userContent());
        String title = normalize(raw, titleProperties.getMaxLength());
        if (!StringUtils.hasText(title)) {
            log.info("[Title] 生成结果为空 conv={} raw='{}'", ctx.conversationId(), abbreviate(raw));
            return "";
        }
        // 用户流式中已手动改名则跳过覆盖（DB 仍为截断自动值才落库）
        ChatConversationEntity conv = conversationService.getOwned(ctx.conversationId(), ctx.userId(), ctx.tenantId());
        if (!ConversationService.DEFAULT_TITLE.equals(conv.getTitle())
                && !ConversationService.deriveAutoTitle(ctx.userContent()).equals(conv.getTitle())) {
            return "";
        }
        conversationService.updateTitle(ctx.conversationId(), ctx.userId(), ctx.tenantId(), title);
        log.info("[Title] 标题生成 conv={} title='{}'", ctx.conversationId(), title);
        return title;
    }

    /** 后处理：去掉引号 / markdown 围栏 / 首尾空白，截断到 maxLength。 */
    static String normalize(String raw, int maxLength) {
        if (!StringUtils.hasText(raw)) {
            return "";
        }
        String title = raw.strip()
                .replaceAll("^[`\"'「『【［（(【]+|[`\"'」』】］）)】]+$", "")
                .strip();
        if (!StringUtils.hasText(title)) {
            return "";
        }
        return title.length() > maxLength ? title.substring(0, maxLength) : title;
    }

    private static String abbreviate(String q) {
        return q != null && q.length() > 30 ? q.substring(0, 30) + "..." : String.valueOf(q);
    }

    private static ServerSentEvent<String> sse(String data) {
        return ServerSentEvent.<String>builder()
                .id(UUID.randomUUID().toString().substring(0, 8))
                .data(data)
                .build();
    }
}
