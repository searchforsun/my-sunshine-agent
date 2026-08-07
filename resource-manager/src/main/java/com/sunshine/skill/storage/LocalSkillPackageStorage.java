package com.sunshine.skill.storage;

import com.sunshine.skill.config.SkillStorageProperties;
import com.sunshine.skill.dto.SkillFileContent;
import com.sunshine.skill.dto.SkillFileEntry;
import com.sunshine.skill.skillmd.SkillPackage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

@Slf4j
@Component
@RequiredArgsConstructor
public class LocalSkillPackageStorage {

    private final SkillStorageProperties storageProperties;

    public String save(String skillId, int version, SkillPackage pkg) throws IOException {
        Path dir = versionDir(skillId, version);
        Files.createDirectories(dir);
        Path skillMd = dir.resolve("SKILL.md");
        Files.writeString(skillMd, pkg.rawSkillMd(), StandardCharsets.UTF_8);
        writeExtraFiles(dir, pkg.files());
        return skillMd.toString();
    }

    public String readSkillMd(String storageLocator) {
        if (!StringUtils.hasText(storageLocator) || SkillStorageLocator.isMinio(storageLocator)) {
            return "";
        }
        try {
            return Files.readString(Path.of(storageLocator), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    public List<SkillFileEntry> listFiles(String skillId, int version, String storageLocator) throws IOException {
        Path root = resolveRoot(skillId, version, storageLocator);
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<SkillFileEntry> entries = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(p -> !p.equals(root))
                    .sorted(Comparator.comparing(p -> root.relativize(p).toString()))
                    .forEach(p -> {
                        try {
                            String rel = root.relativize(p).toString().replace('\\', '/');
                            boolean dir = Files.isDirectory(p);
                            long size = dir ? 0L : Files.size(p);
                            entries.add(new SkillFileEntry(rel, size, dir));
                        } catch (IOException ignored) {
                            // skip unreadable entry
                        }
                    });
        }
        return List.copyOf(entries);
    }

    public SkillFileContent readFile(String storageLocator, String skillId, int version, String relativePath)
            throws IOException {
        Path root = resolveRoot(skillId, version, storageLocator);
        Path file = root.resolve(relativePath.strip()).normalize();
        if (!file.startsWith(root.normalize())) {
            throw new IOException("非法路径: " + relativePath);
        }
        if (!Files.isRegularFile(file)) {
            throw new IOException("文件不存在: " + relativePath);
        }
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (SkillFileCodec.isTextFile(name)) {
            return new SkillFileContent(relativePath.strip(), SkillFileCodec.guessContentType(name),
                    Files.readString(file, StandardCharsets.UTF_8), false);
        }
        byte[] bytes = Files.readAllBytes(file);
        return new SkillFileContent(relativePath.strip(), SkillFileCodec.guessContentType(name),
                SkillFileCodec.toBase64(bytes), true);
    }

    public void writeTextFile(String storageLocator, String skillId, int version, String relativePath, String content)
            throws IOException {
        Path root = resolveRoot(skillId, version, storageLocator);
        String normalized = relativePath.strip().replace('\\', '/');
        Path file = root.resolve(normalized).normalize();
        if (!file.startsWith(root.normalize())) {
            throw new IOException("非法路径: " + relativePath);
        }
        String name = file.getFileName().toString();
        if (!SkillFileCodec.isTextFile(name)) {
            throw new IOException("不支持编辑二进制文件: " + relativePath);
        }
        Files.createDirectories(file.getParent());
        Files.writeString(file, content != null ? content : "", StandardCharsets.UTF_8);
    }

    /** 复制源版本全部文件到 targetVersion，返回 SKILL.md 路径 */
    public String copyVersion(String skillId, int sourceVersion, String sourceLocator, int targetVersion)
            throws IOException {
        Path sourceRoot = resolveRoot(skillId, sourceVersion, sourceLocator);
        if (!Files.isDirectory(sourceRoot)) {
            throw new IOException("源版本无文件");
        }
        Path targetDir = versionDir(skillId, targetVersion);
        if (Files.exists(targetDir)) {
            deleteVersion(skillId, targetVersion, null);
        }
        Files.createDirectories(targetDir);
        try (Stream<Path> walk = Files.walk(sourceRoot)) {
            for (Path source : walk.toList()) {
                Path rel = sourceRoot.relativize(source);
                Path dest = targetDir.resolve(rel);
                if (Files.isDirectory(source)) {
                    Files.createDirectories(dest);
                } else {
                    Files.createDirectories(dest.getParent());
                    Files.copy(source, dest, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        return targetDir.resolve("SKILL.md").toString();
    }

    private Path resolveRoot(String skillId, int version, String storageLocator) {
        if (StringUtils.hasText(storageLocator) && !SkillStorageLocator.isMinio(storageLocator)) {
            Path skillMd = Path.of(storageLocator);
            Path parent = skillMd.getParent();
            if (parent != null && Files.isDirectory(parent)) {
                return parent.normalize();
            }
        }
        return versionDir(skillId, version);
    }

    private Path versionDir(String skillId, int version) {
        return Path.of(storageProperties.getBaseDir(), skillId, String.valueOf(version)).normalize();
    }

    public void deleteVersion(String skillId, int version, String storageLocator) throws IOException {
        Path root = resolveRoot(skillId, version, storageLocator);
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // skip
                }
            });
        }
    }

    private static void writeExtraFiles(Path dir, Map<String, byte[]> files) throws IOException {
        for (Map.Entry<String, byte[]> entry : files.entrySet()) {
            if ("SKILL.md".equalsIgnoreCase(entry.getKey())) {
                continue;
            }
            Path target = dir.resolve(entry.getKey()).normalize();
            if (!target.startsWith(dir.normalize())) {
                throw new IOException("非法路径: " + entry.getKey());
            }
            Files.createDirectories(target.getParent());
            Files.write(target, entry.getValue());
        }
    }
}
