package com.sunshine.orchestrator.expert;

import com.sunshine.orchestrator.catalog.ExpertCatalogEntry;
import com.sunshine.orchestrator.client.LlmGatewayClient;
import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.prompt.PromptCatalogHolder;
import com.sunshine.orchestrator.prompt.PromptComposeRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExpertSpeakStreamerTest {

    @Mock
    private LlmGatewayClient llmGatewayClient;

    @Mock
    private PromptCatalogHolder promptCatalogHolder;

    @InjectMocks
    private ExpertSpeakStreamer streamer;

    private ExpertCatalogEntry expert() {
        return new ExpertCatalogEntry(
                "finance", "财务专家", "desc", "专家系统提示", List.of("finance-analysis"),
                List.of(), "[\"*\"]", true);
    }

    @Test
    void streamSpeak_composesWithPersonalRules() {
        when(promptCatalogHolder.requireText("peer.speak-prompt"))
                .thenReturn("请{expertName}回答{userQuery}，参考{transcript}与{gatheredContext}");
        when(llmGatewayClient.streamComposed(any())).thenReturn(Flux.just(StreamToken.content("ok")));

        streamer.streamSpeak(expert(), "报销政策", List.of(), "", "用文言文回答")
                .collectList().block();

        ArgumentCaptor<PromptComposeRequest> captor = ArgumentCaptor.forClass(PromptComposeRequest.class);
        verify(llmGatewayClient).streamComposed(captor.capture());
        assertThat(captor.getValue().personalRules()).isEqualTo("用文言文回答");
    }

    @Test
    void streamSpeak_withoutPersonalRulesPassesNull() {
        when(promptCatalogHolder.requireText("peer.speak-prompt"))
                .thenReturn("请{expertName}回答{userQuery}，参考{transcript}与{gatheredContext}");
        when(llmGatewayClient.streamComposed(any())).thenReturn(Flux.just(StreamToken.content("ok")));

        streamer.streamSpeak(expert(), "报销政策", List.of(), "").collectList().block();

        ArgumentCaptor<PromptComposeRequest> captor = ArgumentCaptor.forClass(PromptComposeRequest.class);
        verify(llmGatewayClient).streamComposed(captor.capture());
        assertThat(captor.getValue().personalRules()).isNull();
    }
}
