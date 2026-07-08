package com.sunshine.orchestrator.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StreamDeltaNormalizerTest {

    @Test
    @DisplayName("累积式 delta：重复全文只保留一次")
    void cumulativeDuplicate_emitsOnce() {
        String full = "好的，以下是一段 Markdown 内容示例";
        List<StreamToken> out = collect(
                StreamToken.content(full),
                StreamToken.content(full),
                StreamToken.content(full)
        );
        assertThat(out).hasSize(1);
        assertThat(out.get(0).text()).isEqualTo(full);
    }

    @Test
    @DisplayName("累积式 delta：逐帧增长时按增量输出")
    void cumulativeGrowing_emitsIncrements() {
        List<StreamToken> out = collect(
                StreamToken.content("Hello"),
                StreamToken.content("Hello, "),
                StreamToken.content("Hello, world")
        );
        assertThat(out).extracting(StreamToken::text).containsExactly("Hello", ", ", "world");
    }

    @Test
    @DisplayName("增量式 delta：逐 token 输出")
    void incremental_emitsEachToken() {
        List<StreamToken> out = collect(
                StreamToken.content("你"),
                StreamToken.content("好"),
                StreamToken.content("！")
        );
        assertThat(out).extracting(StreamToken::text).containsExactly("你", "好", "！");
    }

    @Test
    @DisplayName("累积式 delta 回退时丢弃重复帧")
    void cumulativeShrink_dropsDuplicate() {
        List<StreamToken> out = collect(
                StreamToken.content("Hello, world"),
                StreamToken.content("Hello, ")
        );
        assertThat(out).extracting(StreamToken::text).containsExactly("Hello, world");
    }

    @Test
    @DisplayName("step_delta reasoning 累积式重复只保留增量")
    void stepDeltaReasoningCumulative_emitsIncrements() {
        List<StreamToken> out = collect(
                StreamToken.stepDelta("agent", "reasoning", "好的"),
                StreamToken.stepDelta("agent", "reasoning", "好的，用户"),
                StreamToken.stepDelta("agent", "reasoning", "好的，用户问")
        );
        assertThat(out).extracting(StreamToken::text).containsExactly("好的", "，用户", "问");
    }

    @Test
    @DisplayName("每个 think stepId 独立累积基线")
    void crossThinkStep_keepsFullRoundContentPerStep() {
        String planning = "用户要求依次调用三个工具。";
        String afterTool1 = planning + "第一步结果：3条 pending。";

        List<StreamToken> out = collect(
                StreamToken.stepDelta("think", "reasoning", planning),
                StreamToken.stepDelta("think-2", "reasoning", afterTool1)
        );

        assertThat(out).hasSize(2);
        assertThat(out.get(0).text()).isEqualTo(planning);
        assertThat(out.get(1).text()).isEqualTo(afterTool1);
    }

    @Test
    @DisplayName("增量式 delta：闭合 ** 不与文首 opening 混淆丢弃")
    void incremental_closingBoldMarkersNotDropped() {
        List<StreamToken> out = collect(
                StreamToken.content("- "),
                StreamToken.content("**"),
                StreamToken.content("立即"),
                StreamToken.content("退回"),
                StreamToken.content("，"),
                StreamToken.content("不予"),
                StreamToken.content("进入"),
                StreamToken.content("审批"),
                StreamToken.content("流程"),
                StreamToken.content("**"),
                StreamToken.content("；")
        );
        assertThat(out).extracting(StreamToken::text)
                .containsExactly("- ", "**", "立即", "退回", "，", "不予", "进入", "审批", "流程", "**", "；");
        assertThat(out.stream().map(StreamToken::text).reduce("", String::concat))
                .isEqualTo("- **立即退回，不予进入审批流程**；");
    }

    @Test
    @DisplayName("content 生命周期 token 原样透传")
    void contentLifecycle_passesThrough() {
        List<StreamToken> out = collect(
                StreamToken.contentStart("content-1", "think"),
                StreamToken.contentInSegment("content-1", "你好"),
                StreamToken.contentEnd("content-1")
        );
        assertThat(out).hasSize(3);
        assertThat(out.get(0).isContentStart()).isTrue();
        assertThat(out.get(1).text()).isEqualTo("你好");
        assertThat(out.get(2).isContentEnd()).isTrue();
    }

    private static List<StreamToken> collect(StreamToken... tokens) {
        List<StreamToken> out = new ArrayList<>();
        StreamDeltaNormalizer.normalizeTokens(reactor.core.publisher.Flux.fromArray(tokens))
                .subscribe(out::add);
        return out;
    }
}
