package com.sunshine.orchestrator.sandbox;

import java.io.File;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * 判断宿主侧 git clone --mirror 裸库是否已可用来建 worktree。
 * 仅有 objects/ 不足以视为完成：并发 clone 中途会留下 tmp_pack，HEAD 尚不可解析。
 */
public final class BareMirrorCloneProbe {

    private BareMirrorCloneProbe() {}

    public static boolean isReady(Path repoGit) {
        if (repoGit == null) {
            return false;
        }
        return isReady(repoGit.toFile());
    }

    public static boolean isReady(File dir) {
        if (dir == null || !dir.isDirectory()) {
            return false;
        }
        if (!new File(dir, "objects").isDirectory()) {
            return false;
        }
        // 进行中的 clone 常留下 objects/pack/tmp_pack_*；此时不得当作已克隆
        File packDir = new File(dir, "objects/pack");
        if (packDir.isDirectory()) {
            File[] packs = packDir.listFiles((d, name) -> name != null && name.startsWith("tmp_pack_"));
            if (packs != null && packs.length > 0) {
                return false;
            }
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "git", "-C", dir.getAbsolutePath(), "rev-parse", "--verify", "HEAD");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            boolean finished = p.waitFor(10, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return false;
            }
            // 丢弃输出，避免撑满管道
            p.getInputStream().readAllBytes();
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
