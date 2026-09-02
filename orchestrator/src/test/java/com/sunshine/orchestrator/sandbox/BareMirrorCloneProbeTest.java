package com.sunshine.orchestrator.sandbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BareMirrorCloneProbeTest {

    @TempDir
    Path tmp;

    @Test
    void emptyObjectsDirIsNotReady() throws Exception {
        Path repo = tmp.resolve("empty.git");
        Files.createDirectories(repo.resolve("objects"));
        assertFalse(BareMirrorCloneProbe.isReady(repo));
    }

    @Test
    void tmpPackMeansInProgressCloneNotReady() throws Exception {
        Path repo = tmp.resolve("partial.git");
        Path pack = repo.resolve("objects/pack");
        Files.createDirectories(pack);
        Files.writeString(pack.resolve("tmp_pack_abc"), "partial");
        assertFalse(BareMirrorCloneProbe.isReady(repo));
    }

    @Test
    void completedMirrorCloneIsReady() throws Exception {
        Path seed = tmp.resolve("seed");
        Files.createDirectories(seed);
        run(seed, "git", "init", "-b", "master");
        run(seed, "git", "config", "user.email", "t@example.com");
        run(seed, "git", "config", "user.name", "t");
        Files.writeString(seed.resolve("README"), "ok");
        run(seed, "git", "add", "README");
        run(seed, "git", "commit", "-m", "init");

        Path mirror = tmp.resolve("mirror.git");
        run(tmp, "git", "clone", "--mirror", seed.toString(), mirror.toString());
        assertTrue(BareMirrorCloneProbe.isReady(mirror));
    }

    private static void run(Path cwd, String... cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(cwd.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes());
        if (!p.waitFor(30, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IllegalStateException("timeout: " + String.join(" ", cmd));
        }
        if (p.exitValue() != 0) {
            throw new IllegalStateException("exit " + p.exitValue() + ": " + out);
        }
    }
}
