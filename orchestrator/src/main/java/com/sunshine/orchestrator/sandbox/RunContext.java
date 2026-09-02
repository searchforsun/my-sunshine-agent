package com.sunshine.orchestrator.sandbox;

import com.sunshine.orchestrator.agent.runtime.AgentRunRequest;
import com.sunshine.orchestrator.conversation.entity.ChatConversationEntity;
import com.sunshine.orchestrator.conversation.repo.ChatConversationRepository;
import org.springframework.util.StringUtils;

/**
 * Run 级上下文，供工具线程 ensure 沙箱会话。
 * 工作区级新增 workspaceId + checkoutPath 字段。
 */
public record RunContext(
        String userId, String tenantId, String conversationId,
        String skillId, String runId, String assistantMessageId,
        String workspaceId, String checkoutPath) {

    public static RunContext from(AgentRunRequest req, ChatConversationRepository convRepo) {
        String convId = req.conversationId();
        String wsId = null;
        String ckPath = null;
        if (StringUtils.hasText(convId)) {
            ChatConversationEntity conv = convRepo.findById(convId).orElse(null);
            if (conv != null && "task".equals(conv.getKind())) {
                wsId = conv.getWorkspaceId();
                ckPath = conv.getCheckoutPath();
            }
        }
        return new RunContext(
                req.userId(),
                StringUtils.hasText(req.tenantId()) ? req.tenantId().strip() : "default",
                convId, req.skillId(), req.runId(), req.assistantMessageId(),
                wsId, ckPath);
    }
}
