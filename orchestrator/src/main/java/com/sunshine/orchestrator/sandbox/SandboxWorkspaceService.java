package com.sunshine.orchestrator.sandbox;

import com.sunshine.common.core.exception.BizException;
import com.sunshine.orchestrator.client.SandboxClient;
import com.sunshine.common.sandbox.FsContentDto;
import com.sunshine.common.sandbox.FsNodeDto;
import com.sunshine.orchestrator.config.AgentSandboxProperties;
import com.sunshine.orchestrator.conversation.ConversationService;
import com.sunshine.orchestrator.exception.OrchestratorErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Slf4j
@Service
@RequiredArgsConstructor
public class SandboxWorkspaceService {

    private final ConversationService conversationService;
    private final ConversationSandboxStore conversationSandboxStore;
    private final WorkspaceSandboxStore workspaceSandboxStore;
    private final SandboxClient sandboxClient;
    private final AgentSandboxProperties sandboxProperties;
    private final SandboxSessionLifecycle sandboxSessionLifecycle;
    private final WorkspaceSandboxLifecycle workspaceSandboxLifecycle;

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

    /** 工作区级别：直接用 workspaceId 找 sandbox session */
    public FsNodeDto.FsListResponse listByWorkspace(
            String workspaceId, String tenantId, String path) {
        assertBrowsablePath(path);
        String sessionId = workspaceSandboxStore.find(tenantId, workspaceId)
                .map(WorkspaceSandboxBinding::sessionId)
                .orElseGet(() -> {
                    log.info("[SandboxWorkspace] binding not found, ensuring session ws={}", workspaceId);
                    return workspaceSandboxLifecycle.ensureWorkspaceSession(workspaceId, "system", tenantId);
                });
        FsNodeDto.FsListResponse resp = sandboxClient.listFs(sessionId, path);
        if (resp == null) {
            throw new BizException(OrchestratorErrorCode.SANDBOX_WORKSPACE_NOT_FOUND);
        }
        return resp;
    }

    /** 会话级别：递归列举文件索引（扁平化路径列表） */
    public FsNodeDto.FsIndexResponse index(
            String conversationId, String userId, String tenantId, String path, int maxDepth) {
        assertBrowsablePath(path);
        String sessionId = requireOrEnsureSession(conversationId, userId, tenantId);
        conversationSandboxStore.touch(tenantId, conversationId);
        FsNodeDto.FsIndexResponse resp = sandboxClient.listFsIndex(sessionId, path, maxDepth);
        if (resp == null) {
            throw new BizException(OrchestratorErrorCode.SANDBOX_WORKSPACE_NOT_FOUND);
        }
        return resp;
    }

    /** 工作区级别：递归列举文件索引（扁平化路径列表） */
    public FsNodeDto.FsIndexResponse indexByWorkspace(
            String workspaceId, String tenantId, String path, int maxDepth) {
        assertBrowsablePath(path);
        String sessionId = workspaceSandboxStore.find(tenantId, workspaceId)
                .map(WorkspaceSandboxBinding::sessionId)
                .orElseGet(() -> {
                    log.info("[SandboxWorkspace] binding not found, ensuring session ws={}", workspaceId);
                    return workspaceSandboxLifecycle.ensureWorkspaceSession(workspaceId, "system", tenantId);
                });
        FsNodeDto.FsIndexResponse resp = sandboxClient.listFsIndex(sessionId, path, maxDepth);
        if (resp == null) {
            throw new BizException(OrchestratorErrorCode.SANDBOX_WORKSPACE_NOT_FOUND);
        }
        return resp;
    }

    public FsContentDto content(
            String conversationId, String userId, String tenantId, String path, int offset) {
        assertBrowsablePath(path);
        String sessionId = requireOrEnsureSession(conversationId, userId, tenantId);
        conversationSandboxStore.touch(tenantId, conversationId);
        FsContentDto resp = sandboxClient.readFsContent(
                sessionId, path, sandboxProperties.getWorkspaceContentMaxChars(), offset);
        if (resp == null) {
            throw new BizException(OrchestratorErrorCode.SANDBOX_WORKSPACE_NOT_FOUND);
        }
        return resp;
    }

    /** 工作区级别：直接用 workspaceId 找 sandbox session */
    public FsContentDto contentByWorkspace(
            String workspaceId, String tenantId, String path, int offset) {
        assertBrowsablePath(path);
        String sessionId = workspaceSandboxStore.find(tenantId, workspaceId)
                .map(WorkspaceSandboxBinding::sessionId)
                .orElseGet(() -> {
                    log.info("[SandboxWorkspace] binding not found, ensuring session ws={}", workspaceId);
                    return workspaceSandboxLifecycle.ensureWorkspaceSession(workspaceId, "system", tenantId);
                });
        FsContentDto resp = sandboxClient.readFsContent(
                sessionId, path, sandboxProperties.getWorkspaceContentMaxChars(), offset);
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
        var conv = conversationService.getOwned(conversationId, userId, tenantId);
        // Task 会话：走工作区 session（含 git clone + workspace volume mount）
        if (conv != null && "task".equals(conv.getKind()) && StringUtils.hasText(conv.getWorkspaceId())) {
            return workspaceSandboxLifecycle.ensureWorkspaceSession(
                    conv.getWorkspaceId(), userId, tenantId);
        }
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
