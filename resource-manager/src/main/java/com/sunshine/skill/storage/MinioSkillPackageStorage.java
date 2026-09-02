package com.sunshine.skill.storage;

import com.sunshine.skill.config.SkillStorageProperties;
import com.sunshine.skill.dto.SkillFileContent;
import com.sunshine.skill.dto.SkillFileEntry;
import com.sunshine.skill.skillmd.SkillPackage;
import io.minio.BucketExistsArgs;
import io.minio.CopyObjectArgs;
import io.minio.CopySource;
import io.minio.GetObjectArgs;
import io.minio.ListObjectsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.Result;
import io.minio.messages.Item;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component
@ConditionalOnProperty(name = "skill.storage.type", havingValue = "minio", matchIfMissing = true)
@RequiredArgsConstructor
public class MinioSkillPackageStorage {

    private final MinioClient minioClient;
    private final SkillStorageProperties storageProperties;

    public void ensureBucket() {
        String bucket = bucket();
        try {
            if (!minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                log.info("[SkillStorage] 已创建 MinIO bucket={}", bucket);
            }
        } catch (Exception e) {
            throw new IllegalStateException("MinIO bucket 初始化失败: " + e.getMessage(), e);
        }
    }

    public String save(String skillId, int version, SkillPackage pkg) throws IOException {
        ensureBucket();
        String bucket = bucket();
        String prefix = SkillStorageLocator.versionPrefix(skillId, version);
        putObject(bucket, prefix + "SKILL.md", pkg.rawSkillMd().getBytes(StandardCharsets.UTF_8), "text/markdown");
        for (Map.Entry<String, byte[]> entry : pkg.files().entrySet()) {
            if ("SKILL.md".equalsIgnoreCase(entry.getKey())) {
                continue;
            }
            validateRelativePath(entry.getKey());
            putObject(bucket, prefix + entry.getKey(), entry.getValue(), guessContentType(entry.getKey()));
        }
        return SkillStorageLocator.minio(bucket, prefix + "SKILL.md");
    }

    public String readSkillMd(String storageLocator) {
        if (!SkillStorageLocator.isMinio(storageLocator)) {
            return "";
        }
        try {
            SkillStorageLocator.MinioRef ref = SkillStorageLocator.parseMinio(storageLocator);
            return new String(readBytes(ref.bucket(), ref.objectKey()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("[SkillStorage] 读取 MinIO SKILL.md 失败: {}", e.getMessage());
            return "";
        }
    }

    public List<SkillFileEntry> listFiles(String skillId, int version, String storageLocator) throws IOException {
        String prefix = resolvePrefix(skillId, version, storageLocator);
        String bucket = bucket();
        List<SkillFileEntry> entries = new ArrayList<>();
        Set<String> dirs = new LinkedHashSet<>();
        try {
            Iterable<Result<Item>> results = minioClient.listObjects(
                    ListObjectsArgs.builder().bucket(bucket).prefix(prefix).recursive(true).build());
            for (Result<Item> result : results) {
                Item item = result.get();
                if (item.isDir()) {
                    continue;
                }
                String key = item.objectName();
                if (!key.startsWith(prefix)) {
                    continue;
                }
                String rel = key.substring(prefix.length());
                if (!StringUtils.hasText(rel)) {
                    continue;
                }
                addVirtualDirs(dirs, rel);
                entries.add(new SkillFileEntry(rel, item.size(), false));
            }
        } catch (Exception e) {
            throw new IOException("MinIO 列举文件失败: " + e.getMessage(), e);
        }
        for (String dir : dirs) {
            entries.add(new SkillFileEntry(dir, 0L, true));
        }
        entries.sort((a, b) -> {
            if (a.directory() != b.directory()) {
                return a.directory() ? -1 : 1;
            }
            return a.path().compareTo(b.path());
        });
        return List.copyOf(entries);
    }

    public SkillFileContent readFile(String storageLocator, String skillId, int version, String relativePath)
            throws IOException {
        validateRelativePath(relativePath);
        String prefix = resolvePrefix(skillId, version, storageLocator);
        String bucket = bucket();
        String key = prefix + relativePath.strip();
        try {
            byte[] bytes = readBytes(bucket, key);
            String name = SkillFileCodec.lowerName(relativePath);
            if (SkillFileCodec.isTextFile(name)) {
                return new SkillFileContent(relativePath.strip(), SkillFileCodec.guessContentType(name),
                        new String(bytes, StandardCharsets.UTF_8), false);
            }
            return new SkillFileContent(relativePath.strip(), SkillFileCodec.guessContentType(name),
                    SkillFileCodec.toBase64(bytes), true);
        } catch (Exception e) {
            throw new IOException("MinIO 读取文件失败: " + relativePath + " — " + e.getMessage(), e);
        }
    }

    public void writeTextFile(String storageLocator, String skillId, int version, String relativePath, String content)
            throws IOException {
        validateRelativePath(relativePath);
        String name = SkillFileCodec.lowerName(relativePath);
        if (!SkillFileCodec.isTextFile(name)) {
            throw new IOException("不支持编辑二进制文件: " + relativePath);
        }
        String prefix = resolvePrefix(skillId, version, storageLocator);
        String bucket = bucket();
        String key = prefix + relativePath.strip();
        byte[] bytes = (content != null ? content : "").getBytes(StandardCharsets.UTF_8);
        putObject(bucket, key, bytes, SkillFileCodec.guessContentType(name));
    }

    /** 复制源版本对象到新 version 前缀，返回 minio:// 定位符 */
    public String copyVersion(String skillId, int sourceVersion, String sourceLocator, int targetVersion)
            throws IOException {
        ensureBucket();
        String sourcePrefix = resolvePrefix(skillId, sourceVersion, sourceLocator);
        String targetPrefix = SkillStorageLocator.versionPrefix(skillId, targetVersion);
        String bucket = bucket();
        String targetLocator = SkillStorageLocator.minio(bucket, targetPrefix + "SKILL.md");
        deleteVersion(skillId, targetVersion, targetLocator);
        boolean copied = false;
        try {
            Iterable<Result<Item>> results = minioClient.listObjects(
                    ListObjectsArgs.builder().bucket(bucket).prefix(sourcePrefix).recursive(true).build());
            for (Result<Item> result : results) {
                Item item = result.get();
                if (item.isDir()) {
                    continue;
                }
                String sourceKey = item.objectName();
                if (!sourceKey.startsWith(sourcePrefix)) {
                    continue;
                }
                String rel = sourceKey.substring(sourcePrefix.length());
                if (!StringUtils.hasText(rel)) {
                    continue;
                }
                String targetKey = targetPrefix + rel;
                minioClient.copyObject(CopyObjectArgs.builder()
                        .bucket(bucket)
                        .object(targetKey)
                        .source(CopySource.builder().bucket(bucket).object(sourceKey).build())
                        .build());
                copied = true;
            }
        } catch (Exception e) {
            throw new IOException("MinIO 复制版本失败: " + e.getMessage(), e);
        }
        if (!copied) {
            throw new IOException("源版本无文件");
        }
        return targetLocator;
    }

    public void deleteVersion(String skillId, int version, String storageLocator) throws IOException {
        String prefix = resolvePrefix(skillId, version, storageLocator);
        String bucket = bucket();
        try {
            Iterable<Result<Item>> results = minioClient.listObjects(
                    ListObjectsArgs.builder().bucket(bucket).prefix(prefix).recursive(true).build());
            for (Result<Item> result : results) {
                Item item = result.get();
                if (item.isDir()) {
                    continue;
                }
                minioClient.removeObject(RemoveObjectArgs.builder()
                        .bucket(bucket)
                        .object(item.objectName())
                        .build());
            }
        } catch (Exception e) {
            throw new IOException("MinIO 删除版本失败: " + e.getMessage(), e);
        }
    }

    private String resolvePrefix(String skillId, int version, String storageLocator) {
        if (SkillStorageLocator.isMinio(storageLocator)) {
            SkillStorageLocator.MinioRef ref = SkillStorageLocator.parseMinio(storageLocator);
            String key = ref.objectKey();
            int slash = key.lastIndexOf('/');
            if (slash >= 0) {
                return key.substring(0, slash + 1);
            }
        }
        return SkillStorageLocator.versionPrefix(skillId, version);
    }

    private void putObject(String bucket, String key, byte[] data, String contentType) throws IOException {
        try {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(key)
                    .stream(new ByteArrayInputStream(data), data.length, -1)
                    .contentType(contentType)
                    .build());
        } catch (Exception e) {
            throw new IOException("MinIO 写入失败 " + key + ": " + e.getMessage(), e);
        }
    }

    private byte[] readBytes(String bucket, String key) throws Exception {
        try (InputStream stream = minioClient.getObject(
                GetObjectArgs.builder().bucket(bucket).object(key).build())) {
            return stream.readAllBytes();
        }
    }

    private static void validateRelativePath(String path) throws IOException {
        String normalized = path.replace('\\', '/');
        if (normalized.contains("..") || normalized.startsWith("/")) {
            throw new IOException("非法路径: " + path);
        }
    }

    private static void addVirtualDirs(Set<String> dirs, String relPath) {
        int slash = relPath.lastIndexOf('/');
        if (slash <= 0) {
            return;
        }
        String dir = relPath.substring(0, slash);
        dirs.add(dir);
        addVirtualDirs(dirs, dir);
    }

    private static String guessContentType(String path) {
        return SkillFileCodec.guessContentType(SkillFileCodec.lowerName(path));
    }

    private String bucket() {
        return storageProperties.getStorage().getMinio().getBucket();
    }
}
