package com.sunshine.orchestrator.expert;

import com.sunshine.orchestrator.client.LlmGatewayClient;
import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.peer.PeerMsgSupport;
import com.sunshine.orchestrator.prompt.PromptCatalogHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.stream.Collectors;

/** Hub 结束后读 transcript 流式生成用户可见答复 */
@Component
@RequiredArgsConstructor
public class ConsultationSynthesizer {
    private final LlmGatewayClient llmGatewayClient;
    private final PromptCatalogHolder promptCatalogHolder;

    public Flux<StreamToken> synthesize(String userQuery, List<ExpertTranscriptEntry> transcript) {
        String prompt = promptCatalogHolder.requireText("peer.synthesis-prompt")
                .replace("{userQuery}", userQuery != null ? userQuery : "")
                .replace("{transcript}", formatTranscript(transcript));
        return llmGatewayClient.streamDirectly(prompt);
    }

    private String formatTranscript(List<ExpertTranscriptEntry> transcript) {
        if (transcript == null || transcript.isEmpty()) {
            return "(无专家发言记录)";
        }
        return transcript.stream()
                .filter(e -> StringUtils.hasText(e.content()))
                .map(e -> PeerMsgSupport.formatTranscriptBlock(e.displayName(), e.content()))
                .collect(Collectors.joining("\n\n"));
    }
}
