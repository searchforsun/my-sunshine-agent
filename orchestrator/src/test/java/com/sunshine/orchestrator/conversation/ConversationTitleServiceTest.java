package com.sunshine.orchestrator.conversation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationTitleServiceTest {

    @Test
    @DisplayName("normalize 去掉引号/围栏与首尾空白")
    void normalize_stripsQuotesAndFences() {
        assertThat(ConversationTitleService.normalize("「排查订单支付失败」", 15))
                .isEqualTo("排查订单支付失败");
        assertThat(ConversationTitleService.normalize("\"新员工入职材料清单\"", 15))
                .isEqualTo("新员工入职材料清单");
        assertThat(ConversationTitleService.normalize(" 排查订单支付失败 ", 15))
                .isEqualTo("排查订单支付失败");
        assertThat(ConversationTitleService.normalize("```\n排查订单支付失败\n```", 15))
                .isEqualTo("排查订单支付失败");
    }

    @Test
    @DisplayName("normalize 超过 15 字截断、空输入返回空")
    void normalize_truncatesOverMaxLength() {
        String longTitle = "这是一个非常长的对话标题用于测试截断行为";
        assertThat(ConversationTitleService.normalize(longTitle, 15))
                .isEqualTo(longTitle.substring(0, 15));

        assertThat(ConversationTitleService.normalize("", 15)).isEmpty();
        assertThat(ConversationTitleService.normalize("   ", 15)).isEmpty();
        assertThat(ConversationTitleService.normalize(null, 15)).isEmpty();
    }

    @Test
    @DisplayName("首条消息截断兜底标题为 15 字")
    void deriveAutoTitle_keeps15Chars() {
        String userContent = "帮我排查一下最近订单支付一直失败的问题并给出解决方案";
        String title = ConversationService.deriveAutoTitle(userContent);
        assertThat(title.length()).isEqualTo(ConversationService.AUTO_TITLE_MAX_LEN);
        assertThat(title).isEqualTo(userContent.substring(0, 15));
    }
}
