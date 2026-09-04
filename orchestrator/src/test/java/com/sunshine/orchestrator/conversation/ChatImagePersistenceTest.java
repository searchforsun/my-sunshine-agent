package com.sunshine.orchestrator.conversation;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class ChatImagePersistenceTest {

    @Test
    void imageUrlsRoundTripThroughJson() {
        String json = ConversationService.writeImageUrls(List.of("http://a/1.png", "http://a/2.png"));
        assertThat(ConversationService.readImageUrls(json))
                .containsExactly("http://a/1.png", "http://a/2.png");
        assertThat(ConversationService.readImageUrls(null)).isEmpty();
    }
}
