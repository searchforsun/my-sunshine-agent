package com.sunshine.sandbox.jail;

import java.nio.file.Path;
import java.nio.file.Paths;

public final class PathJail {
    public static final Path SKILL = Paths.get("/skill").toAbsolutePath().normalize();
    public static final Path WORKSPACE = Paths.get("/workspace").toAbsolutePath().normalize();

    private PathJail() {}

    public static Path resolveRead(String raw) {
        return mustBeUnder(normalize(raw), SKILL, WORKSPACE);
    }

    public static Path resolveWrite(String raw) {
        return mustBeUnder(normalize(raw), WORKSPACE);
    }

    public static Path resolveCwd(String raw) {
        if (raw == null || raw.isBlank()) {
            return WORKSPACE;
        }
        return mustBeUnder(normalize(raw), SKILL, WORKSPACE);
    }

    private static Path normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("path required");
        }
        Path p = Paths.get(raw).toAbsolutePath().normalize();
        return p;
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
