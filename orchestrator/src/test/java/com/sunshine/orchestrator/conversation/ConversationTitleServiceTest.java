package com.sunshine.orchestrator.conversation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationTitleServiceTest {

    @Test
    @DisplayName("normalize 去掉引号/围栏与首尾空白")
    void normalize_stripsQuotesAndFences() {
        assertThat(ConversationTitleService.normalize("「排查订单支付失败」"))
                .isEqualTo("排查订单支付失败");
        assertThat(ConversationTitleService.normalize("\"新员工入职材料清单\""))
                .isEqualTo("新员工入职材料清单");
        assertThat(ConversationTitleService.normalize(" 排查订单支付失败 "))
                .isEqualTo("排查订单支付失败");
        assertThat(ConversationTitleService.normalize("```\n排查订单支付失败\n```"))
                .isEqualTo("排查订单支付失败");
    }

    @Test
    @DisplayName("normalize 不截断模型输出、空输入返回空")
    void normalize_keepsFullTitle() {
        String longTitle = "子代理调研deepseekharness工具链并输出完整调研结论与后续接入建议清单";
        assertThat(ConversationTitleService.normalize(longTitle)).isEqualTo(longTitle);

        assertThat(ConversationTitleService.normalize("")).isEmpty();
        assertThat(ConversationTitleService.normalize("   ")).isEmpty();
        assertThat(ConversationTitleService.normalize(null)).isEmpty();
    }

    @Test
    @DisplayName("兜底标题取首行并去 markdown 标记")
    void deriveFallbackTitle_takesFirstLine() {
        String userContent = "帮我排查一下最近订单支付一直失败的问题\n并给出解决方案";
        String title = ConversationService.deriveFallbackTitle(userContent);
        assertThat(title).isEqualTo("帮我排查一下最近订单支付一直失败的问题");

        assertThat(ConversationService.deriveFallbackTitle("## 排查订单支付失败")).isEqualTo("排查订单支付失败");
    }

    @Test
    @DisplayName("兜底标题超列宽上限截断（仅落库安全兜底）")
    void deriveFallbackTitle_clampsToColumnLimit() {
        String longLine = "长".repeat(600);
        String title = ConversationService.deriveFallbackTitle(longLine);
        assertThat(title.length()).isEqualTo(ConversationService.TITLE_COLUMN_MAX_LEN);
    }
}
