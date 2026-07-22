package com.sunshine.orchestrator.expert;

import com.sunshine.orchestrator.catalog.ExpertCatalogEntry;
import com.sunshine.orchestrator.client.LlmGatewayClient;
import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.context.AssembledContext;
import com.sunshine.orchestrator.prompt.PromptCatalogHolder;
import com.sunshine.orchestrator.prompt.PromptComposeRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.util.List;

/** 专家发言阶段2 — Gateway 直链流式，与 {@link ConsultationSynthesizer} 同通路 */
@Component
@RequiredArgsConstructor
public class ExpertSpeakStreamer {
    private final LlmGatewayClient llmGatewayClient;
    private final PromptCatalogHolder promptCatalogHolder;

    public Flux<StreamToken> streamSpeak(
            ExpertCatalogEntry expert,
            String userQuery,
            List<String> contextBlocks,
            String gatheredContext) {
        String prompt = promptCatalogHolder.requireText("peer.speak-prompt")
                .replace("{expertName}", displayName(expert))
                .replace("{userQuery}", userQuery != null ? userQuery : "")
                .replace("{transcript}", formatContext(contextBlocks))
                .replace("{gatheredContext}", StringUtils.hasText(gatheredContext)
                        ? gatheredContext.strip()
                        : "(无工具检索材料)");
        PromptComposeRequest request = PromptComposeRequest.forExpertSpeak(
                AssembledContext.forSubAgent(),
                prompt,
                expert.primarySkillId(),
                expert.systemPrompt());
        return llmGatewayClient.streamComposed(request);
    }

    private static String displayName(ExpertCatalogEntry expert) {
        if (expert == null) {
            return "专家";
        }
        if (StringUtils.hasText(expert.displayName())) {
            return expert.displayName().strip();
        }
        return expert.id() != null ? expert.id() : "专家";
    }

    private static String formatContext(List<String> contextBlocks) {
        if (contextBlocks == null || contextBlocks.isEmpty()) {
            return "(暂无讨论上下文)";
        }
        return String.join("\n\n", contextBlocks);
    }
}
