package com.sunshine.orchestrator.sandbox;

import com.sunshine.common.core.exception.BizException;
import com.sunshine.orchestrator.client.SandboxClient;
import com.sunshine.common.sandbox.FsContentDto;
import com.sunshine.common.sandbox.FsNodeDto;
import com.sunshine.orchestrator.config.AgentSandboxProperties;
import com.sunshine.orchestrator.conversation.ConversationService;
import com.sunshine.orchestrator.exception.OrchestratorErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class SandboxWorkspaceService {

    private final ConversationService conversationService;
    private final ConversationSandboxStore conversationSandboxStore;
    private final SandboxClient sandboxClient;
    private final AgentSandboxProperties sandboxProperties;
    private final SandboxSessionLifecycle sandboxSessionLifecycle;

    public FsNodeDto.FsListResponse list(
            String conversationId, String userId, String tenantId, String path) {
        assertBrowsablePath(path);
        String sessionId = requireOrEnsureSession(conversationId, userId, tenantId);
        conversationSandboxStore.touch(tenantId, conversationId);
        FsNodeDto.FsListResponse resp = sandboxClient.listFs(sessionId, path);
        if (resp == null) {
            throw new BizException(OrchestratorErrorCode.SANDBOX_WORKSPACE_NOT_FOUND);
        }
        return resp;
    }

    public FsContentDto content(
            String conversationId, String userId, String tenantId, String path) {
        assertBrowsablePath(path);
        String sessionId = requireOrEnsureSession(conversationId, userId, tenantId);
        conversationSandboxStore.touch(tenantId, conversationId);
        FsContentDto resp = sandboxClient.readFsContent(
                sessionId, path, sandboxProperties.getWorkspaceContentMaxChars());
        if (resp == null) {
            throw new BizException(OrchestratorErrorCode.SANDBOX_WORKSPACE_NOT_FOUND);
        }
        return resp;
    }

    /** 方案 B：会话存在即可打开工作区（list 会懒 ensure）；status 反映是否已有绑定 */
    public boolean hasActiveWorkspace(String conversationId, String userId, String tenantId) {
        try {
            conversationService.getOwned(conversationId, userId, tenantId);
        } catch (BizException e) {
            return false;
        }
        return conversationSandboxStore.find(tenantId, conversationId)
                .filter(b -> sandboxClient.sessionAlive(b.sessionId()))
                .isPresent();
    }

    private String requireOrEnsureSession(String conversationId, String userId, String tenantId) {
        if (!StringUtils.hasText(conversationId)) {
            throw new BizException(OrchestratorErrorCode.SANDBOX_WORKSPACE_NOT_FOUND);
        }
        conversationService.getOwned(conversationId, userId, tenantId);
        try {
            return sandboxSessionLifecycle.ensureConversationSession(
                    userId, tenantId, conversationId, null);
        } catch (Exception e) {
            throw new BizException(OrchestratorErrorCode.SANDBOX_WORKSPACE_NOT_FOUND);
        }
    }

    private static void assertBrowsablePath(String path) {
        if (!StringUtils.hasText(path)) {
            return;
        }
        String p = path.strip();
        if (p.equals("/workspace") || p.startsWith("/workspace/")
                || p.equals("/skills") || p.startsWith("/skills/")) {
            return;
        }
        throw new BizException(OrchestratorErrorCode.SANDBOX_WORKSPACE_NOT_FOUND);
    }
}
