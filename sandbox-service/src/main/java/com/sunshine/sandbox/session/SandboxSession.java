package com.sunshine.sandbox.session;

import com.sunshine.common.sandbox.SandboxPolicy;

import java.nio.file.Path;

public record SandboxSession(
        String sessionId,
        String containerName,
        Path hostRoot,
        SandboxPolicy policy,
        /** 外部工作区宿主路径：null 时等价于 hostRoot.resolve("workspace") */
        Path workspaceHostPath) {

    /** @return 实际 workspace 在宿主机上的目录 */
    public Path workspaceHostDir() {
        return workspaceHostPath != null ? workspaceHostPath : hostRoot.resolve("workspace");
    }
}
