package com.sunshine.orchestrator.routing;

import com.sunshine.orchestrator.config.RoutingRuleProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PeerPatternMatcherTest {

    @Test
    void matchesCrossReviewPhrase() {
        RoutingRuleProperties props = new RoutingRuleProperties();
        RoutingRuleProperties.Peer peer = new RoutingRuleProperties.Peer();
        peer.setEnabled(true);
        peer.setStructuralPatterns(java.util.List.of("互相验证", "交叉审查"));
        props.setPeer(peer);
        PeerPatternMatcher matcher = new PeerPatternMatcher(props);
        assertThat(matcher.looksLikePeerCollab("请制度专家和财务专家分别审查并互相验证")).isTrue();
        assertThat(matcher.looksLikePeerCollab("先查制度再查报销")).isFalse();
    }
}
