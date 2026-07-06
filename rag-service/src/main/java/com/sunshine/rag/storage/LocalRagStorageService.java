package com.sunshine.rag.storage;

import com.sunshine.rag.config.RagStorageProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** type=local 或 MinIO 不可用时的文件存储回退 */
@Service
@RequiredArgsConstructor
public class LocalRagStorageService {

    private final RagStorageProperties storageProperties;

    public Path suiteFile(String tenantId, String suiteKey, String fileName) {
        return baseDir().resolve("suites")
                .resolve(normalize(tenantId))
                .resolve(normalize(suiteKey))
                .resolve(fileName);
    }

    public Path reportFile(String tenantId, long jobId, String fileName) {
        return baseDir().resolve("reports")
                .resolve(normalize(tenantId))
                .resolve(String.valueOf(jobId))
                .resolve(fileName);
    }

    public Path documentFile(String tenantId, String kbId, String docId, String version) {
        return baseDir().resolve("documents")
                .resolve(normalize(tenantId))
                .resolve(normalize(kbId))
                .resolve(normalize(docId))
                .resolve(version)
                .resolve("content.md");
    }

    public Path parseSourceFile(String tenantId, String kbId, String docId, long jobId, String fileName) {
        return baseDir().resolve("parse-sources")
                .resolve(normalize(tenantId))
                .resolve(normalize(kbId))
                .resolve(normalize(docId))
                .resolve(String.valueOf(jobId))
                .resolve(fileName);
    }

    public void putBytes(Path path, byte[] bytes) {
        try {
            Files.createDirectories(path.getParent());
            Files.write(path, bytes);
        } catch (IOException e) {
            throw new IllegalStateException("本地写入失败 " + path + ": " + e.getMessage(), e);
        }
    }

    public byte[] readBytes(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new IllegalStateException("本地读取失败 " + path + ": " + e.getMessage(), e);
        }
    }

    public void putText(Path path, String content) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("本地写入失败 " + path + ": " + e.getMessage(), e);
        }
    }

    public String readText(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("本地读取失败 " + path + ": " + e.getMessage(), e);
        }
    }

    public void deleteIfExists(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            throw new IllegalStateException("本地删除失败 " + path + ": " + e.getMessage(), e);
        }
    }

    private Path baseDir() {
        Path configured = Path.of(storageProperties.getLocalReportDir()).getParent();
        if (configured == null || !configured.isAbsolute()) {
            configured = Path.of(System.getProperty("user.dir", ".")).resolve("reports/rag");
        }
        return configured;
    }

    private static String normalize(String value) {
        return value != null && !value.isBlank() ? value.strip() : "default";
    }
}
