package com.sunshine.orchestrator.prompt;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.URLSource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** ReAct 当前用户消息多模态组装：TextBlock + N 个 ImageBlock；空图退化为纯文本单块 */
class ReactImageMessageTest {

    @Test
    void currentUserMsgContainsTextAndImageBlocks() {
        List<ContentBlock> imageBlocks = List.of(
                ImageBlock.builder().source(new URLSource(
                        "http://ecs4c16g:9000/sunshine-chat-images/default/1.png")).build());
        Msg msg = PromptComposer.buildCurrentUserMsg("这张图是什么", imageBlocks);
        assertThat(msg.getRole()).isEqualTo(MsgRole.USER);
        List<ContentBlock> blocks = msg.getContent();
        assertThat(blocks.get(0)).isInstanceOf(TextBlock.class);
        assertThat(blocks.get(1)).isInstanceOf(ImageBlock.class);
        assertThat(((TextBlock) blocks.get(0)).getText()).contains("这张图是什么");
    }

    @Test
    void emptyImageBlocksYieldsTextOnly() {
        Msg msg = PromptComposer.buildCurrentUserMsg("纯文本", List.of());
        assertThat(msg.getContent()).hasSize(1);
        assertThat(msg.getContent().get(0)).isInstanceOf(TextBlock.class);
    }
}
