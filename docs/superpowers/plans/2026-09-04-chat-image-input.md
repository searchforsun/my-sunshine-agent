# Chat 图片输入实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 聊天输入框支持图片（选图/粘贴/拖拽），经 MinIO 上传后以 URL 随消息进入 vision 模型，用户气泡回显缩略图。

**Architecture:** `content` 保持 string，wire 新增 `imageUrls: string[]`；resource-manager 新增 MinIO 上传接口（BFF 透传 multipart）；orchestrator 仅在 ReAct 主链路当前用户消息处组装 `TextBlock + ImageBlock(URLSource)`，历史回放忽略图片；llm-gateway 零契约改动。

**Tech Stack:** Spring Boot 3.2 / WebFlux（BFF、orchestrator）、MinIO Java SDK、AgentScope 2.0（ImageBlock/URLSource/OpenAIMessageConverter）、Vue3 + Naive UI。

**Spec:** [docs/superpowers/specs/2026-09-04-chat-image-input-design.md](../specs/2026-09-04-chat-image-input-design.md)

## Global Constraints

- 提示词/种子 SQL SSOT：建表 SQL 只改 `docker/mysql/init/`，禁止 Flyway；线上库手工 ALTER（无迁移框架）
- 禁止 orchestrator/前端硬编码工具/模型 Map；模型能力一律读注册表 `capabilities.multimodal`
- Nacos 改动必须 `python3 scripts/sync_nacos.py` 并重启消费服务；后端改动后必须重启对应服务 `python3 scripts/start.py --restart <svc>`
- 验收脚本用 Python 放 `scripts/verify_chat_image_live.py`；禁止保存临时脚本
- 前端 UI 风格：`--sun-black` 背景 + 边框分区，禁止 `--sun-surface` 灰底；禁止冗余解释性文案
- 图片限制（spec §4.1）：png/jpg/jpeg/webp/gif，单张 ≤10MB，每条消息 ≤4 张；bucket `sunshine-chat-images` public-read
- 图片仅当前一条用户消息下发；workflow / spawn_subagent / worker 忽略 imageUrls

---

### Task 1: resource-manager 图片存储与上传接口

**Files:**
- Create: `resource-manager/src/main/java/com/sunshine/chatimage/config/ChatImageProperties.java`
- Create: `resource-manager/src/main/java/com/sunshine/chatimage/storage/ChatImageStorage.java`
- Create: `resource-manager/src/main/java/com/sunshine/chatimage/controller/ChatImageController.java`
- Modify: `resource-manager/src/main/java/com/sunshine/ResourceManagerApplication.java`（加 `@ConfigurationPropertiesScan` 已有则不动，确认扫描 `com.sunshine.chatimage`；应用类若按 `com.sunshine` 根包扫描则无需改）
- Modify: `docs/nacos/sunshine-resource-manager.yaml`
- Test: `resource-manager/src/test/java/com/sunshine/chatimage/ChatImageStorageTest.java`、`ChatImageControllerTest.java`

**Interfaces:**
- Produces: `POST /api/chat/images`，multipart 字段 `file`，返回 `R.ok(Map.of("url", url))`；URL 规则 `{publicEndpoint}/{bucket}/{tenantId}/{yyyyMMdd}/{uuid}.{ext}`
- Produces: 配置前缀 `chat-image`：`chat-image.minio.endpoint|access-key|secret-key|bucket|public-endpoint`（endpoint 默认 `http://ecs4c16g:9000`，bucket 默认 `sunshine-chat-images`）

- [ ] **Step 1: 写 ChatImageProperties**

```java
package com.sunshine.chatimage.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 聊天图片 MinIO 存储配置 */
@Data
@ConfigurationProperties(prefix = "chat-image")
public class ChatImageProperties {
    private Minio minio = new Minio();
    /** 允许的图片扩展名（小写，含点） */
    private String allowedExtensions = ".png,.jpg,.jpeg,.webp,.gif";
    /** 单张上限（字节） */
    private long maxBytes = 10 * 1024 * 1024;

    @Data
    public static class Minio {
        private String endpoint = "http://ecs4c16g:9000";
        private String accessKey = "minioadmin";
        private String secretKey = "minioadmin123";
        private String bucket = "sunshine-chat-images";
        /** 对外可访问基址（模型/浏览器拉取），缺省同 endpoint */
        private String publicEndpoint = "";
    }
}
```

- [ ] **Step 2: 写 ChatImageStorageTest（先失败）**

```java
package com.sunshine.chatimage;

import com.sunshine.chatimage.config.ChatImageProperties;
import com.sunshine.chatimage.storage.ChatImageStorage;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockPart;
import org.springframework.http.MediaType;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class ChatImageStorageTest {

    private final ChatImageStorage storage = new ChatImageStorage(props());

    private static ChatImageProperties props() {
        ChatImageProperties p = new ChatImageProperties();
        p.getMinio().setPublicEndpoint("http://ecs4c16g:9000");
        return p;
    }

    @Test
    void validateRejectsOversize() {
        MockPart part = new MockPart("file", "a.png", new byte[11]);
        part.getHeaders().setContentType(MediaType.IMAGE_PNG);
        // maxBytes 临时调小模拟超限
        ChatImageProperties p = props();
        p.setMaxBytes(10);
        assertThatThrownBy(() -> new ChatImageStorage(p)
                .validate(part, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("超过大小限制");
    }

    @Test
    void validateRejectsNonImage() {
        MockPart part = new MockPart("file", "a.exe", new byte[1]);
        part.getHeaders().setContentType(MediaType.APPLICATION_OCTET_STREAM);
        assertThatThrownBy(() -> storage.validate(part, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不支持的图片类型");
    }

    @Test
    void objectKeyUsesTenantAndDate() {
        String key = storage.objectKey("default", "a.png");
        assertThat(key).matches("default/\\d{8}/[0-9a-f-]{36}\\.png");
    }

    @Test
    void publicUrlComposesBucketAndKey() {
        String url = storage.publicUrl("default/20260904/x.png");
        assertThat(url).isEqualTo("http://ecs4c16g:9000/sunshine-chat-images/default/20260904/x.png");
    }
}
```

Run: `cd resource-manager && mvn -q test -Dtest=ChatImageStorageTest`
Expected: FAIL（`ChatImageStorage` 不存在）

- [ ] **Step 3: 实现 ChatImageStorage**

```java
package com.sunshine.chatimage.storage;

import com.sunshine.chatimage.config.ChatImageProperties;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.SetBucketPolicyArgs;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
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
@RequiredArgsConstructor
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

    /** 类型与大小校验；tenantCount 为本条消息已传张数 */
    public void validate(MultipartFile file, long sizeBytes) {
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        boolean extOk = Arrays.stream(properties.getAllowedExtensions().split(","))
                .anyMatch(name::endsWith);
        if (!extOk) {
            throw new IllegalArgumentException("不支持的图片类型，仅允许 " + properties.getAllowedExtensions());
        }
        if (sizeBytes > properties.getMaxBytes()) {
            throw new IllegalArgumentException("图片超过大小限制 " + (properties.getMaxBytes() / 1024 / 1024) + "MB");
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
```

- [ ] **Step 4: 写 ChatImageController（薄层）**

```java
package com.sunshine.chatimage.controller;

import com.sunshine.chatimage.storage.ChatImageStorage;
import com.sunshine.common.core.exception.BizException;
import com.sunshine.common.core.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 聊天图片上传（gateway 鉴权后 x-user-id 注入） */
@RestController
@RequestMapping("/api/chat/images")
@RequiredArgsConstructor
public class ChatImageController {

    private final ChatImageStorage storage;

    @PostMapping
    public R<java.util.Map<String, Object>> upload(
            @RequestParam("file") MultipartFile file,
            @RequestHeader(value = "x-user-id", required = false) String userId,
            @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId) {
        if (file == null || file.isEmpty()) {
            throw new BizException("图片不能为空");
        }
        try {
            return R.ok(java.util.Map.of("url", storage.upload(tenantId, file)));
        } catch (IllegalArgumentException e) {
            throw new BizException(e.getMessage());
        } catch (Exception e) {
            throw new BizException("图片上传失败，请重试");
        }
    }
}
```

（若 `BizException(String)` 构造不存在，按 `ModelErrorCode` 同款方式补错误码或改用 `BizException(errorCode, message)` 现有签名。）

- [ ] **Step 5: Nacos 配置**

`docs/nacos/sunshine-resource-manager.yaml` 追加：

```yaml
chat-image:
  minio:
    endpoint: http://ecs4c16g:9000
    access-key: minioadmin
    secret-key: minioadmin123
    bucket: sunshine-chat-images
    public-endpoint: http://ecs4c16g:9000
```

Run: `python3 scripts/sync_nacos.py && python3 scripts/start.py --restart resource-manager`

- [ ] **Step 6: 编译 + 单测 + 提交**

Run: `cd resource-manager && mvn -q test -Dtest='ChatImage*' && cd .. && python3 scripts/start.py --restart resource-manager`
Expected: PASS；`curl http://localhost:8240/api/chat/images -F "file=@test.png"`（直连验证）返回 200 url

```bash
git add resource-manager/src/main/java/com/sunshine/chatimage resource-manager/src/test/java/com/sunshine/chatimage docs/nacos/sunshine-resource-manager.yaml
git commit -m "feat(chat-image): resource-manager 图片上传接口（MinIO public-read）"
```

---

### Task 2: BFF 透传上传与聊天字段

**Files:**
- Create: `bff/src/main/java/com/sunshine/bff/client/ChatImageClient.java`
- Modify: `bff/src/main/java/com/sunshine/bff/controller/ChatController.java`（新增 `/api/chat/images` POST）
- Modify: `bff/src/main/java/com/sunshine/bff/model/ChatRequest.java`（新增 `imageUrls`）
- Test: `bff/src/main/java/com/sunshine/bff/model/ChatRequestTest.java`（若已有 model 测试则并入）

**Interfaces:**
- Produces: `POST /api/chat/images`（multipart `file`）→ resource-manager；`ChatRequest.imageUrls: List<String>`（新增，可空）

- [ ] **Step 1: ChatImageClient（仿 SkillManagerClient.upload 的 multipart 转发）**

```java
package com.sunshine.bff.client;

import com.sunshine.bff.config.ResourceManagerProperties; // 以现有 skill client 的配置类为准，名字不同则复用它
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import org.springframework.http.codec.multipart.FilePart;

/** 聊天图片上传转发 resource-manager */
@Component
@RequiredArgsConstructor
public class ChatImageClient {

    private final WebClient resourceManagerWebClient; // 复用现有 resource-manager WebClient Bean

    public Mono<java.util.Map<String, Object>> upload(FilePart file, String userId, String tenantId) {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.asyncPart("file", file.content().map(buf -> {
            byte[] bytes = new byte[buf.readableByteCount()];
            buf.read(bytes);
            org.springframework.core.io.DataBufferUtils.release(buf);
            return bytes;
        }).collectList().map(list -> {
            int n = list.stream().mapToInt(b -> b.length).sum();
            byte[] all = new byte[n];
            int pos = 0;
            for (byte[] b : list) { System.arraycopy(b, 0, all, pos, b.length); pos += b.length; }
            return new ByteArrayResource(all) {
                @Override public String getFilename() { return file.filename(); }
            };
        })).contentType(MediaType.APPLICATION_OCTET_STREAM);
        return resourceManagerWebClient.post()
                .uri("/api/chat/images")
                .header("x-user-id", userId == null ? "" : userId)
                .header("x-tenant-id", tenantId)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<java.util.Map<String, Object>>() {});
    }
}
```

（实施时以 `SkillManagerClient.upload` 的实际写法为准做同构简化；关键点：`FilePart` → 字节聚合 → `ByteArrayResource` 带 filename。）

- [ ] **Step 2: BFF ChatController 加路由**

```java
    @PostMapping(value = "/api/chat/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<Map<String, Object>> uploadChatImage(
            @RequestPart(value = "file") FilePart file,
            @RequestHeader(value = "x-user-id", required = false) String userId,
            @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId) {
        return chatImageClient.upload(file, userId, tenantId);
    }
```

- [ ] **Step 3: ChatRequest 加字段**

```java
    /** 图片 URL 列表（聊天多模态，≤4 张） */
    private java.util.List<String> imageUrls;
```

（BFF → orchestrator 直接 `bodyValue(request)` 序列化，字段自动透传。）

- [ ] **Step 4: 重启验证 + 提交**

Run: `python3 scripts/start.py --restart bff`
验证：`curl -s http://localhost:8000/api/chat/images -H "x-user-id: u1" -F "file=@test.png"` 返回 200 url

```bash
git add bff/src/main/java/com/sunshine/bff
git commit -m "feat(chat-image): BFF 透传图片上传与 imageUrls 契约字段"
```

---

### Task 3: orchestrator 契约字段 + 落库 + 读侧

**Files:**
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/model/ChatMessage.java`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/conversation/entity/ChatMessageEntity.java`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/conversation/ConversationService.java:350-380`（appendMessage 重载）
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/controller/stream/ChatStreamContextFactory.java:72-77`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/conversation/dto/ConversationDetailDto.java`（MessageDto）
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/controller/ChatController.java`（validateRequest 校验 ≤4 张）
- Modify: `docker/mysql/init/11-sunshine-orchestrator.sql`（chat_message 加列）
- Test: `orchestrator/src/test/java/com/sunshine/orchestrator/conversation/ChatImagePersistenceTest.java`

**Interfaces:**
- Produces: `ChatMessage.imageUrls: List<String>`；`ChatMessageEntity.imageUrlsJson: String`（JSON 数组）；`MessageDto.imageUrls: List<String>`；`ConversationService.appendMessage(convId, role, content, status, executionPreference, imageUrls)`

- [ ] **Step 1: 写失败测试**

```java
package com.sunshine.orchestrator.conversation;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class ChatImagePersistenceTest {

    @Test
    void imageUrlsRoundTripThroughJson() {
        String json = ConversationService.writeImageUrls(List.of("http://a/1.png", "http://a/2.png"));
        assertThat(ConversationService.readImageUrls(json))
                .containsExactly("http://a/1.png", "http://a/2.png");
        assertThat(ConversationService.readImageUrls(null)).isEmpty();
    }
}
```

Run: `cd orchestrator && mvn -q test -Dtest=ChatImagePersistenceTest` → FAIL

- [ ] **Step 2: 实现 DTO/实体/服务**

`ChatMessage`：

```java
    /** 聊天图片 URL（多模态，≤4 张；仅当前消息下发模型，历史回放忽略） */
    private java.util.List<String> imageUrls;
```

`ChatMessageEntity`：

```java
    /** 图片 URL JSON 数组；历史回放不参与模型上下文，仅回显 */
    @Column(name = "image_urls_json", columnDefinition = "TEXT")
    private String imageUrlsJson;
```

`ConversationService` 静态工具 + 重载：

```java
    private static final com.fasterxml.jackson.databind.ObjectMapper IMAGE_URL_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    public static String writeImageUrls(java.util.List<String> urls) {
        if (urls == null || urls.isEmpty()) return null;
        try { return IMAGE_URL_MAPPER.writeValueAsString(urls); }
        catch (Exception e) { throw new IllegalStateException("imageUrls 序列化失败", e); }
    }

    public static java.util.List<String> readImageUrls(String json) {
        if (json == null || json.isBlank()) return java.util.List.of();
        try { return IMAGE_URL_MAPPER.readValue(json,
                IMAGE_URL_MAPPER.getTypeFactory().constructCollectionType(java.util.List.class, String.class)); }
        catch (Exception e) { return java.util.List.of(); }
    }

    public ChatMessageEntity appendMessage(
            String convId, String role, String content, String status, String executionPreference,
            java.util.List<String> imageUrls) {
        ChatMessageEntity saved = appendMessage(convId, role, content, status, executionPreference);
        saved.setImageUrlsJson(writeImageUrls(imageUrls));
        return messageRepo.save(saved);
    }
```

`ChatStreamContextFactory` L72-77 改为携带图片落库：

```java
        conversationService.appendMessage(conv.getId(), "user",
                userContent, MessageStatus.COMPLETED, preference.wireValue(),
                normalizeImageUrls(msg.getImageUrls()));
```

`normalizeImageUrls`：null 安全、strip、去空、最多 4 张（超过截断并 warn）。

- [ ] **Step 3: 读侧 MessageDto 加字段**

`ConversationDetailDto.MessageDto` 增加 `private java.util.List<String> imageUrls;`，`from(...)` 内 `dto.setImageUrls(ConversationService.readImageUrls(m.getImageUrlsJson()))`。

- [ ] **Step 4: validateRequest 限 4 张**

`ChatController.validateRequest` 增加：

```java
        if (msg.getImageUrls() != null && msg.getImageUrls().size() > 4) {
            throw new BizException("每条消息最多携带 4 张图片");
        }
```

- [ ] **Step 5: SQL 种子 + 线上库**

`11-sunshine-orchestrator.sql` chat_message 列（`content` 之后任一位置，建议 `content_blocks` 前加）：

```sql
    image_urls_json  TEXT         NULL,
```

线上库执行：

```bash
mysql -h ecs4c16g -uroot -proot123 sunshine_orchestrator \
  -e "ALTER TABLE chat_message ADD COLUMN image_urls_json TEXT NULL AFTER content;"
```

Run: `mvn -q test -Dtest=ChatImagePersistenceTest` → PASS；`python3 scripts/start.py --restart orchestrator`

- [ ] **Step 6: 提交**

```bash
git add orchestrator/src docker/mysql/init/11-sunshine-orchestrator.sql
git commit -m "feat(chat-image): orchestrator 契约字段与消息图片落库/回显"
```

---

### Task 4: orchestrator 组装多模态当前用户消息

**Files:**
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/agent/runtime/AgentRunRequest.java`（record 加组件 + wither）
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/execution/ExecutionStreamContext.java`（携带 imageUrls）
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/execution/ReactExecutor.java:205-212`（传 imageUrls）
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/prompt/PromptComposeRequest.java`（forReact 增加 imageUrls 参数）
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/prompt/PromptComposer.java:180-184`（组装 TextBlock + ImageBlock）
- Test: `orchestrator/src/test/java/com/sunshine/orchestrator/prompt/ReactImageMessageTest.java`

**Interfaces:**
- Consumes: Task 3 的 `ExecutionStreamContext` 携带 `imageUrls()`
- Produces: 当前用户 `Msg` 含 `ImageBlock(new URLSource(url))`；`AgentRunRequest.imageUrls: List<String>`（默认 `List.of()`）

- [ ] **Step 1: 写失败测试（验证 Msg blocks 组成）**

```java
package com.sunshine.orchestrator.prompt;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.URLSource;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class ReactImageMessageTest {

    @Test
    void currentUserMsgContainsTextAndImageBlocks() {
        Msg msg = PromptComposer.buildCurrentUserMsg("这张图是什么",
                List.of("http://ecs4c16g:9000/sunshine-chat-images/default/1.png"));
        assertThat(msg.getRole()).isEqualTo(MsgRole.USER);
        List<ContentBlock> blocks = msg.getContent();
        assertThat(blocks.get(0)).isInstanceOf(TextBlock.class);
        assertThat(blocks.subList(1, blocks.size())).allSatisfy(b -> assertThat(b).isInstanceOf(ImageBlock.class));
        assertThat(((URLSource) ((ImageBlock) blocks.get(1)).getSource()).getUrl())
                .contains("sunshine-chat-images");
    }

    @Test
    void emptyImageUrlsYieldsTextOnly() {
        Msg msg = PromptComposer.buildCurrentUserMsg("纯文本", List.of());
        assertThat(msg.getContent()).hasSize(1);
    }
}
```

Run: `mvn -q test -Dtest=ReactImageMessageTest` → FAIL（方法不存在）

- [ ] **Step 2: PromptComposer 抽出当前用户 Msg 构造**

`PromptComposer` 新增静态方法（`appendReactTail` 改为调用它，保持 `formatCurrentUser` 文案不变）：

```java
    /** ReAct 当前用户消息：TextBlock + N 个 ImageBlock（图片仅本条下发，历史回放不带） */
    public static Msg buildCurrentUserMsg(String userMessage, java.util.List<String> imageUrls) {
        TextBlock text = TextBlock.builder()
                .text(ContextMessageBuilder.formatCurrentUser(userMessage, null))
                .build();
        ContentBlock[] imageBlocks = imageUrls == null ? new ContentBlock[0] : imageUrls.stream()
                .filter(u -> u != null && !u.isBlank())
                .map(u -> ImageBlock.builder().source(new URLSource(u.strip())).build())
                .toArray(ContentBlock[]::new);
        return Msg.builder().role(MsgRole.USER)
                .content(java.util.stream.Stream.concat(java.util.stream.Stream.of(text),
                        java.util.Arrays.stream(imageBlocks)).toArray(ContentBlock[]::new))
                .build();
    }
```

`appendReactTail` L180-184 改为：

```java
        inputs.add(buildCurrentUserMsg(
                request.userMessage(), request.imageUrls() == null ? List.of() : request.imageUrls()));
```

（`catalogText("context.current-user-marker")` 参数若 formatCurrentUser 需要则保持原传参，静态方法签名同步带上 marker。）

- [ ] **Step 3: 贯通参数链**

1. `PromptComposeRequest.forReact(...)` 增加末位参数 `List<String> imageUrls`，record 加组件；更新所有现有调用点（仅 AgentRuntime 内一处 + 测试）
2. `AgentRunRequest` record 在 `candidateSkillIds` 后加 `List<String> imageUrls` 组件，提供 `withImageUrls(List<String>)`（wither），`main(...)` 工厂补默认 `List.of()`
3. `ExecutionStreamContext` 增加字段/访问器 `imageUrls`（在 `userContent` 旁）；`ChatStreamContextFactory.prepareNewMessage` 从 `msg.getImageUrls()` 填入
4. `ReactExecutor` L205-212 `AgentRunRequest.main(...)` 链式补 `.withImageUrls(ctx.imageUrls() == null ? List.of() : ctx.imageUrls())`

- [ ] **Step 4: 全量编译 + 相关单测**

Run: `cd orchestrator && mvn -q test -Dtest='ReactImageMessageTest,PromptComposer*'` → PASS
Run: `mvn -q compile && python3 scripts/start.py --restart orchestrator`

- [ ] **Step 5: 提交**

```bash
git add orchestrator/src
git commit -m "feat(chat-image): ReAct 当前用户消息组装 TextBlock+ImageBlock(URLSource)"
```

---

### Task 5: 前端上传 + 发送 + 回显

**Files:**
- Create: `sunshine-ui/src/api/chatImages.ts`
- Modify: `sunshine-ui/src/api/chatSessions.ts:8-17 引用的 SendOptions 实际在 chatSessionRegistry.ts:8-17`（两处同步：类型加 `imageUrls?: string[]`；`send()` body 组装 L167-177 加字段）
- Modify: `sunshine-ui/src/views/ChatView.vue`（composer 图片按钮/粘贴/拖拽/缩略图 chips；发送携带；气泡传 imageUrls；multimodal 置灰）
- Modify: `sunshine-ui/src/components/chat/UserMessageContent.vue`（props 加 `imageUrls?: string[]`；气泡尾部缩略图）

**Interfaces:**
- Consumes: `POST /api/chat/images`（multipart，经 Gateway，头走 `apiHeaders()`；基址用 `uploadUrl` 同款 `resolveBffStreamBase()` 直连避免 Vite proxy 破坏 FormData——与 `skills.ts:9-12` 同构）
- Produces: `uploadChatImage(file: File): Promise<string>`；`SendOptions.imageUrls`

- [ ] **Step 1: chatImages.ts**

```typescript
import { apiHeaders } from '../stores/authStore'
import { resolveBffStreamBase } from './config'

/** 聊天图片上传：multipart 直连 Gateway（同技能包上传路径），返回公网 URL */
export async function uploadChatImage(file: File): Promise<string> {
  const form = new FormData()
  form.append('file', file)
  const res = await fetch(`${resolveBffStreamBase()}/api/chat/images`, {
    method: 'POST',
    headers: { ...apiHeaders() },
    body: form,
  })
  const body = await res.json()
  if (!res.ok || body?.code !== 200) throw new Error(body?.msg || '图片上传失败')
  return body.data.url as string
}
```

- [ ] **Step 2: SendOptions + send() body**

`chatSessionRegistry.ts` `SendOptions` 加：

```typescript
  /** 图片 URL（多模态，≤4 张；模型需 multimodal 能力） */
  imageUrls?: string[]
```

`chatSessions.ts` L156-182 body 组装加：

```typescript
  if (options.imageUrls?.length) body.imageUrls = options.imageUrls
```

- [ ] **Step 3: UserMessageContent 缩略图**

props 加 `imageUrls?: string[]`；模板内容区之后追加（`n-image` 支持点击预览，风格用边框圆角、不解释）：

```vue
    <div v-if="imageUrls?.length" class="msg-images">
      <n-image
        v-for="(u, i) in imageUrls" :key="i" :src="u"
        :width="96" :height="96" object-fit="cover" lazy
        class="msg-image-thumb" />
    </div>
```

样式（scoped）：

```css
.msg-images { display: flex; gap: 6px; margin-top: 6px; flex-wrap: wrap; }
.msg-image-thumb { border: 1px solid var(--sun-border, #333); border-radius: 6px; overflow: hidden; }
```

- [ ] **Step 4: ChatView composer 状态与交互**

新增响应式状态与函数（放在 `inputText` 附近）：

```typescript
const pendingImages = ref<string[]>([])          // 已上传 URL
const uploadingImages = ref(false)
const imageInputRef = ref<HTMLInputElement | null>(null)
// 当前会话模型能力（loadChatModels 结果中按 modelName 匹配）
const currentModelMultimodal = computed(() => {
  const m = chatModels.value.find(m => m.modelName === boundModelName.value)
  return m?.capabilities?.multimodal === true
})

async function addImageFiles(files: FileList | File[]) {
  const list = Array.from(files).filter(f => f.type.startsWith('image/'))
  if (!list.length) return
  const remain = 4 - pendingImages.value.length
  if (remain <= 0) { window.$message?.warning('每条消息最多 4 张图片'); return }
  uploadingImages.value = true
  try {
    for (const f of list.slice(0, remain)) pendingImages.value.push(await uploadChatImage(f))
  } catch (e: any) {
    window.$message?.error(e?.message || '图片上传失败')
  } finally { uploadingImages.value = false }
}

function onComposerPaste(e: ClipboardEvent) {
  const files = Array.from(e.clipboardData?.files ?? [])
  if (files.length) { e.preventDefault(); void addImageFiles(files) }
}

function onComposerDrop(e: DragEvent) {
  const files = Array.from(e.dataTransfer?.files ?? [])
  if (files.length) { e.preventDefault(); void addImageFiles(files) }
}

function removePendingImage(idx: number) { pendingImages.value.splice(idx, 1) }
```

`performSend`（L1182）send 选项追加 `imageUrls: pendingImages.value.length ? [...pendingImages.value] : undefined`，发送成功后 `pendingImages.value = []`；发送禁用条件加 `uploadingImages.value`。

模板（L2039 composer 区域）追加：隐藏 `<input type="file" accept="image/*" multiple>` + 图片按钮（`currentModelMultimodal` 为 false 时 `:disabled` + n-tooltip「当前模型不支持图片」）；composer 外层绑 `@paste="onComposerPaste"` `@drop="onComposerDrop"` `@dragover.prevent`；输入框上方渲染 pending 缩略图 chips（`n-image` + 删除小按钮）。`UserMessageContent` 调用处（L1720-1726）传 `:image-urls="msg.imageUrls"`。

- [ ] **Step 5: 手工验证步骤（交付用户，不自动化）**

1. 选 glm vision 模型 → 点图片按钮选 2 张 → 缩略图出现 → 输入「图里是什么」发送 → 气泡显示 2 缩略图、回复正常
2. 粘贴截图到输入框 → 出现缩略图
3. 切 deepseek-v4-flash（multimodal=false）→ 图片按钮置灰带提示
4. 历史会话刷新 → 历史用户消息缩略图回显
5. 传 5 张 → 提示上限；传 15MB 文件 → toast 报错

- [ ] **Step 6: 提交**

```bash
git add sunshine-ui/src
git commit -m "feat(chat-image): 输入框图片选择/粘贴/拖拽、发送携带 imageUrls、气泡回显"
```

---

### Task 6: llm-gateway 错误文案友好化 + 验收脚本 + 文档收尾

**Files:**
- Modify: `llm-gateway/src/main/java/com/sunshine/llm/controller/ChatController.java:65-77`（`model_not_multimodal` 文案带模型名）
- Create: `scripts/verify_chat_image_live.py`
- Modify: `CLAUDE.md`（进度行追加 chat 图片输入 ✅）
- Modify: `docs/superpowers/specs/2026-09-04-chat-image-input-design.md`（状态改 ✅ 已实现）

**Interfaces:**
- Produces: `scripts/verify_chat_image_live.py` 全链路验收

- [ ] **Step 1: 错误文案**

`@ExceptionHandler` 中 `MODEL_NOT_MULTIMODAL` 分支 message 改为：

```java
            "当前模型不支持图片输入（model=%s），请切换支持多模态的模型".formatted(modelName);
```

（从异常上下文取 model；取不到则省略 `%s` 部分，禁止吞掉原始 code。）

- [ ] **Step 2: 验收脚本**

```python
#!/usr/bin/env python3
"""聊天图片输入 live 验收：上传 → 契约 → 下发 → 能力校验。"""
import io
import json
import sys
import requests

GATEWAY = "http://localhost:8000"
ORCH = "http://localhost:8200"
PNG = bytes.fromhex(
    "89504e470d0a1a0a0000000d494844520000000100000001080600000"
    "01f15c4890000000d49444154789c6360000002000154a24f4d0000000049454e44ae426082")
OK, FAIL = [], []

def check(name, cond, detail=""):
    (OK if cond else FAIL).append(f"{name} {detail}")
    print(("✅" if cond else "❌"), name, detail)

def upload():
    r = requests.post(f"{GATEWAY}/api/chat/images",
                      headers={"x-user-id": "verify"},
                      files={"file": ("px.png", io.BytesIO(PNG), "image/png")}, timeout=15)
    return r

def main():
    r = upload()
    check("上传 200", r.status_code == 200, r.text[:120])
    url = r.json().get("data", {}).get("url", "")
    check("返回 URL", url.startswith("http"), url)
    check("URL 可拉取", requests.get(url, timeout=10).status_code == 200)
    bad = requests.post(f"{GATEWAY}/api/chat/images",
                        headers={"x-user-id": "verify"},
                        files={"file": ("a.exe", io.BytesIO(b"MZ"), "application/octet-stream")}, timeout=15)
    check("非图片 400", bad.status_code == 400, str(bad.status_code))
    payload = {"conversationId": "", "content": "图里是什么颜色",
               "imageUrls": [url], "executionMode": "fast", "modelName": "glm-5.3-flash"}
    with requests.post(f"{ORCH}/chat/stream", json=payload,
                       headers={"x-user-id": "verify", "x-tenant-id": "default"},
                       stream=True, timeout=60) as resp:
        check("chat/stream SSE 可建立", resp.status_code == 200, str(resp.status_code))
    print(f"\n通过 {len(OK)} 项，失败 {len(FAIL)} 项")
    sys.exit(1 if FAIL else 0)

if __name__ == "__main__":
    main()
```

（orchestrator 入口路径以实际网关路由为准：BFF `/api/chat/stream` 或 orchestrator `/chat/stream`，实施时核对 `gateway.yaml` 与 `ChatController` `@PostMapping`。）

- [ ] **Step 3: 全链路验证**

Run: `python3 scripts/verify_chat_image_live.py`
Expected: 全部通过；`grep image_url logs/sunshine-llm-gateway.log | tail` 能看到上游请求含 image_url part

- [ ] **Step 4: 文档收尾 + 提交**

CLAUDE.md 进度行追加 `· Chat 图片输入 ✅`（附 spec 链接）；spec 状态改 `✅ 已实现`。

```bash
git add scripts/verify_chat_image_live.py llm-gateway/src CLAUDE.md docs/superpowers/specs/2026-09-04-chat-image-input-design.md
git commit -m "feat(chat-image): 验收脚本与文档收尾；multimodal 错误文案友好化"
```

---

## Self-Review

- **Spec coverage**：§4.1→Task1、§4.2→Task2/3、§4.3→Task4、§4.4→Task2、§4.5→Task6、§4.6→Task5、§6→Task6。无缺口。
- **Placeholder scan**：无 TBD/TODO；Task2 Client 标注「以 SkillManagerClient 实际写法为准做同构简化」属于既有代码对齐指引而非占位。
- **Type consistency**：`imageUrls`（wire/DTO）、`imageUrlsJson`（实体列）、`readImageUrls/writeImageUrls`（ConversationService 静态）、`buildCurrentUserMsg`（PromptComposer 静态）、`uploadChatImage`（前端 api）各处命名一致。
