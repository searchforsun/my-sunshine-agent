package com.sunshine.sandbox.jail;

import java.nio.file.Path;
import java.nio.file.Paths;

public final class PathJail {
    /** 多 Skill 只读根：/skills/{skillId}/... */
    public static final Path SKILLS = Paths.get("/skills").toAbsolutePath().normalize();
    public static final Path WORKSPACE = Paths.get("/workspace").toAbsolutePath().normalize();

    private PathJail() {}

    public static Path resolveRead(String raw) {
        return mustBeUnder(normalize(raw), SKILLS, WORKSPACE);
    }

    public static Path resolveWrite(String raw) {
        return mustBeUnder(normalize(raw), WORKSPACE);
    }

    public static Path resolveCwd(String raw) {
        if (raw == null || raw.isBlank()) {
            return WORKSPACE;
        }
        return mustBeUnder(normalize(raw), SKILLS, WORKSPACE);
    }

    /** FS 浏览：允许 /workspace 与 /skills */
    public static Path resolveBrowse(String raw) {
        return mustBeUnder(normalize(raw), SKILLS, WORKSPACE);
    }

    private static Path normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("path required");
        }
        return Paths.get(raw).toAbsolutePath().normalize();
    }

    private static Path mustBeUnder(Path p, Path... roots) {
        for (Path root : roots) {
            if (p.startsWith(root)) {
                return p;
            }
        }
        throw new IllegalArgumentException("path escapes jail: " + p);
    }
}
