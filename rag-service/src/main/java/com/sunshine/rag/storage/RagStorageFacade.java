package com.sunshine.rag.storage;

import com.sunshine.rag.config.RagStorageProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/** 统一对象存储门面：优先 MinIO，回退本地文件 */
@Component
@RequiredArgsConstructor
public class RagStorageFacade {

    private final RagStorageProperties storageProperties;
    private final ObjectProvider<MinioStorageService> minioStorage;
    private final LocalRagStorageService localStorage;

    public void putSuiteText(String tenantId, String suiteKey, String fileName, String content) {
        if (useMinio()) {
            MinioStorageService minio = minioStorage.getObject();
            minio.ensureBucket();
            String key = minio.suiteObjectKey(tenantId, suiteKey, fileName);
            minio.putText(key, content);
        } else {
            localStorage.putText(localStorage.suiteFile(tenantId, suiteKey, fileName), content);
        }
    }

    public String readSuiteText(String tenantId, String suiteKey, String fileName, String contentRef) {
        if (useMinio()) {
            return minioStorage.getObject().readText(contentRef);
        }
        return localStorage.readText(localStorage.suiteFile(tenantId, suiteKey, fileName));
    }

    public void deleteSuiteText(String tenantId, String suiteKey, String fileName, String contentRef) {
        if (useMinio()) {
            if (contentRef != null && !contentRef.isBlank()) {
                minioStorage.getObject().removeObject(contentRef);
            }
            return;
        }
        localStorage.deleteIfExists(localStorage.suiteFile(tenantId, suiteKey, fileName));
    }

    public String suiteContentRef(String tenantId, String suiteKey, String fileName) {
        if (useMinio()) {
            return minioStorage.getObject().suiteObjectKey(tenantId, suiteKey, fileName);
        }
        return localStorage.suiteFile(tenantId, suiteKey, fileName).toString();
    }

    public WrittenObject putReportText(String tenantId, long jobId, String fileName, String content) {
        if (useMinio()) {
            MinioStorageService minio = minioStorage.getObject();
            minio.ensureBucket();
            String key = minio.reportObjectKey(tenantId, jobId, fileName);
            minio.putText(key, content);
            return new WrittenObject(key, null);
        }
        Path path = localStorage.reportFile(tenantId, jobId, fileName);
        localStorage.putText(path, content);
        return new WrittenObject(path.toString(), path);
    }

    public record WrittenObject(String objectKey, Path localPath) {
    }

    public String documentContentRef(String tenantId, String kbId, String docId, String version) {
        if (useMinio()) {
            return minioStorage.getObject().documentObjectKey(tenantId, kbId, docId, version);
        }
        return localStorage.documentFile(tenantId, kbId, docId, version).toString();
    }

    public void putDocumentMarkdown(String tenantId, String kbId, String docId, String version, String content) {
        if (useMinio()) {
            MinioStorageService minio = minioStorage.getObject();
            minio.ensureBucket();
            String key = minio.documentObjectKey(tenantId, kbId, docId, version);
            minio.putText(key, content);
            return;
        }
        localStorage.putText(localStorage.documentFile(tenantId, kbId, docId, version), content);
    }

    public String readDocumentMarkdown(String tenantId, String kbId, String docId, String version, String storagePath) {
        if (useMinio()) {
            if (storagePath == null || storagePath.isBlank()) {
                throw new IllegalStateException("MinIO storage_path 缺失");
            }
            return minioStorage.getObject().readText(storagePath);
        }
        if (storagePath != null && !storagePath.isBlank()) {
            return localStorage.readText(java.nio.file.Path.of(storagePath));
        }
        return localStorage.readText(localStorage.documentFile(tenantId, kbId, docId, version));
    }

    public void deleteDocumentMarkdown(String tenantId, String kbId, String docId, String version, String storagePath) {
        if (useMinio()) {
            if (storagePath != null && !storagePath.isBlank()) {
                minioStorage.getObject().removeObject(storagePath);
            }
            return;
        }
        if (storagePath != null && !storagePath.isBlank()) {
            localStorage.deleteIfExists(java.nio.file.Path.of(storagePath));
            return;
        }
        localStorage.deleteIfExists(localStorage.documentFile(tenantId, kbId, docId, version));
    }

    private boolean useMinio() {
        return storageProperties.isMinio() && minioStorage.getIfAvailable() != null;
    }
}
