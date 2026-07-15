package com.sunshine.sandbox.tool;

import com.sunshine.sandbox.jail.PathJail;
import com.sunshine.sandbox.session.SandboxSession;

import java.nio.file.Path;

/** 容器路径 → 会话 host bind 目录（v1 文件 IO 在宿主机完成） */
public final class HostPathResolver {

    private HostPathResolver() {}

    public static Path toHost(SandboxSession session, String containerPath, boolean forWrite) {
        Path jailed = forWrite ? PathJail.resolveWrite(containerPath) : PathJail.resolveRead(containerPath);
        Path hostSkill = session.hostRoot().resolve("skill").toAbsolutePath().normalize();
        Path hostWorkspace = session.hostRoot().resolve("workspace").toAbsolutePath().normalize();
        if (jailed.startsWith(PathJail.SKILL)) {
            Path rel = PathJail.SKILL.relativize(jailed);
            Path host = hostSkill.resolve(rel.toString()).normalize();
            if (!host.startsWith(hostSkill)) {
                throw new IllegalArgumentException("path escapes jail: " + containerPath);
            }
            return host;
        }
        if (jailed.startsWith(PathJail.WORKSPACE)) {
            Path rel = PathJail.WORKSPACE.relativize(jailed);
            Path host = hostWorkspace.resolve(rel.toString()).normalize();
            if (!host.startsWith(hostWorkspace)) {
                throw new IllegalArgumentException("path escapes jail: " + containerPath);
            }
            return host;
        }
        throw new IllegalArgumentException("path escapes jail: " + containerPath);
    }

    /** host bind 路径 → 容器内 `/skill` 或 `/workspace` 风格路径 */
    public static String toContainer(SandboxSession session, Path host) {
        Path abs = host.toAbsolutePath().normalize();
        Path hostSkill = session.hostRoot().resolve("skill").toAbsolutePath().normalize();
        Path hostWorkspace = session.hostRoot().resolve("workspace").toAbsolutePath().normalize();
        if (abs.startsWith(hostSkill)) {
            Path rel = hostSkill.relativize(abs);
            if (rel.toString().isEmpty()) {
                return PathJail.SKILL.toString();
            }
            return PathJail.SKILL.resolve(rel).toString().replace('\\', '/');
        }
        if (abs.startsWith(hostWorkspace)) {
            Path rel = hostWorkspace.relativize(abs);
            if (rel.toString().isEmpty()) {
                return PathJail.WORKSPACE.toString();
            }
            return PathJail.WORKSPACE.resolve(rel).toString().replace('\\', '/');
        }
        throw new IllegalArgumentException("path escapes jail: " + host);
    }
}
