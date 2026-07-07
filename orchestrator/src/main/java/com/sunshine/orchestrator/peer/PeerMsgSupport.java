package com.sunshine.orchestrator.peer;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import org.springframework.util.StringUtils;

import java.util.List;

public final class PeerMsgSupport {

    private PeerMsgSupport() {
    }

    public static String extractText(Msg msg) {
        if (msg == null || msg.getContent() == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (var block : msg.getContent()) {
            if (block instanceof TextBlock tb && StringUtils.hasText(tb.getText())) {
                sb.append(tb.getText());
            }
        }
        return sb.toString().strip();
    }

    public static String formatTranscriptBlock(String roleName, String content) {
        if (!StringUtils.hasText(content)) {
            return "";
        }
        return "【" + roleName + "】\n" + content.strip();
    }
}
