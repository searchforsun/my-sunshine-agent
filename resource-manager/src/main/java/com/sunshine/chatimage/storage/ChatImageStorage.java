package com.sunshine.chatimage.storage;

import com.sunshine.chatimage.config.ChatImageProperties;
import com.sunshine.chatimage.exception.ChatImageErrorCode;
import com.sunshine.common.core.exception.BizException;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.SetBucketPolicyArgs;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;

/**
 * 聊天图片 MinIO 存储：bucket public-read，模型侧无鉴权按 URL 拉取。
 */
@Slf4j
@Component
public class ChatImageStorage {

    private static final String PUBLIC_READ_POLICY = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"AWS":["*"]},\
            "Action":["s3:GetObject"],"Resource":["arn:aws:s3:::%s/*"]}]}""";

    private final ChatImageProperties properties;
    private final MinioClient minioClient;

    public ChatImageStorage(ChatImageProperties properties) {
        this.properties = properties;
        this.minioClient = MinioClient.builder()
                .endpoint(properties.getMinio().getEndpoint())
                .credentials(properties.getMinio().getAccessKey(), properties.getMinio().getSecretKey())
                .build();
    }

    @PostConstruct
    public void ensureBucket() {
        String bucket = properties.getMinio().getBucket();
        try {
            if (!minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            }
            minioClient.setBucketPolicy(SetBucketPolicyArgs.builder()
                    .bucket(bucket).config(PUBLIC_READ_POLICY.formatted(bucket)).build());
            log.info("[ChatImage] MinIO bucket={} 已就绪（public-read）", bucket);
        } catch (Exception e) {
            throw new IllegalStateException("聊天图片 bucket 初始化失败: " + e.getMessage(), e);
        }
    }

    /** 类型与大小校验；sizeBytes 为待校验的字节数（调用方可先累计限流再落盘） */
    public void validate(MultipartFile file, long sizeBytes) {
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        boolean extOk = Arrays.stream(properties.getAllowedExtensions().split(","))
                .anyMatch(name::endsWith);
        if (!extOk) {
            throw new BizException(ChatImageErrorCode.IMAGE_TYPE_UNSUPPORTED);
        }
        if (sizeBytes > properties.getMaxBytes()) {
            throw new BizException(ChatImageErrorCode.IMAGE_TOO_LARGE);
        }
    }

    public String objectKey(String tenantId, String originalName) {
        String ext = originalName.substring(originalName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        String day = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        return "%s/%s/%s.%s".formatted(tenantId, day, UUID.randomUUID(), ext);
    }

    public String upload(String tenantId, MultipartFile file) throws Exception {
        validate(file, file.getSize());
        String key = objectKey(tenantId, file.getOriginalFilename());
        minioClient.putObject(PutObjectArgs.builder()
                .bucket(properties.getMinio().getBucket())
                .object(key)
                .contentType(file.getContentType())
                .stream(new ByteArrayInputStream(file.getBytes()), file.getSize(), -1)
                .build());
        return publicUrl(key);
    }

    public String publicUrl(String key) {
        String base = properties.getMinio().getPublicEndpoint() == null
                || properties.getMinio().getPublicEndpoint().isBlank()
                ? properties.getMinio().getEndpoint()
                : properties.getMinio().getPublicEndpoint();
        String origin = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return "%s/%s/%s".formatted(origin, properties.getMinio().getBucket(), key);
    }
}
