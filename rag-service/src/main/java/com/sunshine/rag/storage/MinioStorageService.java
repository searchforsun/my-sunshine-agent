package com.sunshine.rag.storage;

import com.sunshine.rag.config.RagStorageProperties;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "rag.storage.type", havingValue = "minio", matchIfMissing = true)
public class MinioStorageService {

    private final MinioClient minioClient;
    private final RagStorageProperties storageProperties;

    public void ensureBucket() {
        String bucket = bucket();
        try {
            if (!minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                log.info("[RagStorage] 已创建 MinIO bucket={}", bucket);
            }
        } catch (Exception e) {
            throw new IllegalStateException("MinIO bucket 初始化失败: " + e.getMessage(), e);
        }
    }

    public String suiteObjectKey(String tenantId, String suiteKey, String fileName) {
        return "rag-eval/" + normalize(tenantId) + "/" + normalize(suiteKey) + "/" + fileName;
    }

    public String reportObjectKey(String tenantId, long jobId, String fileName) {
        return "rag-eval-reports/" + normalize(tenantId) + "/" + jobId + "/" + fileName;
    }

    public String documentObjectKey(String tenantId, String kbId, String docId, String version) {
        return "rag-docs/" + normalize(tenantId) + "/" + normalize(kbId) + "/"
                + normalize(docId) + "/" + version + "/content.md";
    }

    public String parseSourceObjectKey(String tenantId, String kbId, String docId, long jobId, String fileName) {
        return "rag-parse/" + normalize(tenantId) + "/" + normalize(kbId) + "/"
                + normalize(docId) + "/" + jobId + "/" + fileName;
    }

    public void putText(String objectKey, String content) {
        putBytes(objectKey, content.getBytes(StandardCharsets.UTF_8), "text/plain; charset=utf-8");
    }

    public void putBytes(String objectKey, byte[] bytes, String contentType) {
        try {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket())
                    .object(objectKey)
                    .stream(new ByteArrayInputStream(bytes), bytes.length, -1)
                    .contentType(contentType)
                    .build());
        } catch (Exception e) {
            throw new IllegalStateException("MinIO 写入失败 " + objectKey + ": " + e.getMessage(), e);
        }
    }

    public String readText(String objectKey) {
        return new String(readBytes(objectKey), StandardCharsets.UTF_8);
    }

    public byte[] readBytes(String objectKey) {
        try (InputStream stream = minioClient.getObject(
                GetObjectArgs.builder().bucket(bucket()).object(objectKey).build())) {
            return stream.readAllBytes();
        } catch (Exception e) {
            throw new IllegalStateException("MinIO 读取失败 " + objectKey + ": " + e.getMessage(), e);
        }
    }

    public boolean objectExists(String objectKey) {
        try {
            minioClient.statObject(StatObjectArgs.builder().bucket(bucket()).object(objectKey).build());
            return true;
        } catch (ErrorResponseException e) {
            String code = e.errorResponse() != null ? e.errorResponse().code() : "";
            if ("NoSuchKey".equals(code) || "NotFound".equals(code)) {
                return false;
            }
            throw new IllegalStateException("MinIO 检查对象失败 " + objectKey + ": " + e.getMessage(), e);
        } catch (Exception e) {
            throw new IllegalStateException("MinIO 检查对象失败 " + objectKey + ": " + e.getMessage(), e);
        }
    }

    public void removeObject(String objectKey) {
        try {
            minioClient.removeObject(RemoveObjectArgs.builder().bucket(bucket()).object(objectKey).build());
        } catch (ErrorResponseException e) {
            String code = e.errorResponse() != null ? e.errorResponse().code() : "";
            if ("NoSuchKey".equals(code) || "NotFound".equals(code)) {
                return;
            }
            throw new IllegalStateException("MinIO 删除失败 " + objectKey + ": " + e.getMessage(), e);
        } catch (Exception e) {
            throw new IllegalStateException("MinIO 删除失败 " + objectKey + ": " + e.getMessage(), e);
        }
    }

    public String bucket() {
        return storageProperties.getMinio().getBucket();
    }

    private static String normalize(String value) {
        return value != null && !value.isBlank() ? value.strip() : "default";
    }
}
