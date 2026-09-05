# Chat 图片输入设计（chat-image-input）

> 状态：**✅ 已实现** · 日期：2026-09-04 · 范围：Chat 主链路（fast/pro）

## 1. 背景与目标

聊天输入框当前仅支持纯文本（`ChatView.vue` composer → `POST /api/chat/stream` `content: string`）。目标：用户可在输入框添加图片（选择/粘贴/拖拽），图片随消息进入 vision 模型，用户气泡回显缩略图。

**范围**：仅 Chat 主链路（fast/pro）。workflow / spawn_subagent / worker 不注入图片（组装处显式忽略）。

**非目标**：assistant 生成图片、图片 OCR 落 RAG、base64 内嵌方案。

## 2. 关键决策

| 决策 | 结论 | 依据 |
|---|---|---|
| 图片传输方式 | **MinIO 上传 + URL**，消息只带 `imageUrls[]` | URL 是存储/回显/审计的唯一事实来源，消息历史体积小 |
| 模型交付模式 | Nacos `chat-image.delivery-mode`：**`base64`（默认）** / `url`；orchestrator 组装当前用户消息前转换 | 当前部署无公网，vision 模型无法回拉 MinIO URL；base64 模式由 orchestrator 内网拉取图片字节后内嵌下发。未来具备公网/可达网络时改配置即切 `url` 模式，省请求体开销 |
| wire 契约 | `content` **保持 string**，新增 `imageUrls: string[]` 独立字段 | 审计落库、Redis 会话、SSE 回显、历史读侧均不动 `content` 类型；llm-gateway `Message.content` 本就是 `Object`，零改动 |
| 图片下发策略 | 仅**当前一条用户消息**组装 image parts；历史消息的图片不再重复下发 | 控制 token 与上下文膨胀；多轮后图片语义已进入后续文本回复 |
| 校验位置 | 前端按 `capabilities.multimodal` 置灰入口；llm-gateway `NormalizeFilter` 兜底拒绝（已有） | 不在 orchestrator 二次加工/拦截 |

## 3. 端到端链路

```
前端选图（canvas 压缩）→ POST /api/chat/images（BFF 透传 → resource-manager，MinIO bucket: sunshine-chat-images）
        ← { url }
前端发消息 { content, imageUrls[] } → BFF 透传 → orchestrator
orchestrator：消息落库（content 原文 + image_urls_json）→ 组装当前用户 Msg 前按 delivery-mode 转换：
  base64（默认）：WebClient 内网 GET url 拉字节 → ImageBlock(Base64Source(mediaType, base64))
  url：        ImageBlock(URLSource(url))
→ AgentScope OpenAIMessageConverter 自动转 OpenAI content parts（url 或 data:{mediaType};base64,...）
→ llm-gateway 原样透传（NormalizeFilter 校验 multimodal 能力）→ 上游 vision 模型
```

**base64 转换发生在 orchestrator 内部**：wire/落库/审计只有 URL；请求体大小无协议层风险（outbound body 不受 `maxInMemorySize` 约束；llm-gateway MVC 入站 JSON 无体积上限），仅消耗 orchestrator→gateway 之间带宽。

## 4. 各层设计

### 4.1 resource-manager：图片上传接口

- `POST /api/chat/images`（gateway 鉴权，`x-user-id` 注入）
- 复用 `MinioStorageConfig` 模式新建 `ChatImageStorage`：bucket `sunshine-chat-images`，**public-read**（模型侧无鉴权拉取）
- key：`{tenantId}/{yyyyMMdd}/{uuid}.{ext}`
- 限制：png/jpg/jpeg/webp/gif；单张 ≤ 10MB；每条消息 ≤ 4 张（超限 400）
- 返回：`{ url }`（`{minio.public-endpoint}/{bucket}/{key}` 拼接公网 URL）
- 配置：`docs/nacos/sunshine-resource-manager.yaml` 增加 `chat-image.minio.*`（endpoint/public-endpoint/bucket），改后跑 `python3 scripts/sync_nacos.py`

### 4.2 契约（wire + 存储）

- 前端 → BFF → orchestrator：`POST /api/chat/stream` body 新增 `imageUrls: string[]`（可选，≤4 项）
- orchestrator `ChatMessage` DTO 增加 `List<String> imageUrls`
- 落库：`chat_message` 表新增 `image_urls_json TEXT NULL`（JSON 数组字符串）；`ChatMessageEntity` / `ConversationDetailDto` 同步加字段
- SQL SSOT：`docker/mysql/init/` 对应建表文件同步加列；线上库手工 `ALTER TABLE chat_message ADD COLUMN image_urls_json TEXT NULL`（无 Flyway）

### 4.3 orchestrator：组装多模态消息

- **交付转换组件** `ChatImageDeliveryResolver`（新文件 `agent/runtime/` 下）：

```java
/** delivery-mode=base64 时内网拉取图片字节转 Base64Source；url 模式原样 URLSource */
public Flux<ContentBlock> resolveImageBlocks(List<String> urls) {
    // mode=url → Flux.fromIterable(URLSource)
    // mode=base64 → WebClient GET url → bytes → Base64Source(mediaType, Base64.encode)
    //   mediaType 从响应 Content-Type 取，缺省 image/png；拉取失败该图降级为
    //   TextBlock("[图片加载失败]") 占位，不中断整条消息
}
```

  - WebClient 独立 bean（`maxInMemorySize(16MB)`，超时 10s，不出网校验：仅接受配置 MinIO publicEndpoint/endpoint 前缀的 URL，防 SSRF）
  - Nacos `sunshine-orchestrator.yaml` 新增 `chat-image.delivery-mode: base64`（默认 base64）、`chat-image.allowed-url-prefix`（MinIO 基址）
- 注入点：`AgentRunRequest` 透传 `imageUrls`；ReAct 主链路构造**当前用户 Msg** 处（`PromptComposer.buildCurrentUserMsg`）组装：

```java
// Msg.Builder.content 支持 varargs：1 个 TextBlock + N 个 ImageBlock
Msg.builder().role(MsgRole.USER)
    .content(textBlock,
             resolvedImageBlocks.toArray(ContentBlock[]::new))
    .build()
```

- AgentScope 侧已验证：`OpenAIMessageConverter` 将 `ImageBlock(URLSource)` 转 `{"type":"image_url","image_url":{"url":...}}`；`ImageBlock(Base64Source(mediaType,data))` 转 `data:{mediaType};base64,...` data URI；llm-gateway `Message.content(Object)` 原样透传
- 历史消息回放（L1/L2/上下文重建）：仅取 `content` 文本，**忽略** imageUrls
- workflow / spawn_subagent / worker 上下文组装处显式忽略 imageUrls

### 4.4 BFF

`ChatRequest` 增加 `List<String> imageUrls`，透传 orchestrator（BFF 按自有 DTO 重序列化，不加字段会被静默丢弃，必须显式声明）。

### 4.5 llm-gateway

**零改动**。`NormalizeFilter` 已有 `multimodal=false` 拒绝 `image_url` 的校验，仅将拒绝信息补为友好文案（含模型名）。

### 4.6 前端（sunshine-ui）

- `ChatView.vue` composer：图片按钮（`accept="image/*"` multiple）+ 粘贴（`paste` 事件截图）+ 拖拽；缩略图 chips 预览、可单删；上传中禁发送
- **上传前压缩**：canvas 重绘——最长边 >2048px 等比缩小 + JPEG 质量 0.85（PNG 透明图保留原格式跳过压缩）；目标单张 ≤5MB
- `chatSessions.ts` `send()`/`SendOptions` 增加 `imageUrls`
- `UserMessageContent.vue`：气泡下渲染缩略图（`n-image` 预览）；历史消息从 `ConversationDetailDto.imageUrls` 回显
- 模型选择：当前会话模型 `capabilities.multimodal=false` 时按钮置灰 + tooltip 提示（能力来自模型注册表 `/api/models` definitions）

## 5. 边界与约束

- 鉴权：上传接口经 gateway 注入 `x-user-id`；不开放匿名上传
- 滥用防护：仅大小/数量/类型限制，M0 不做配额
- 失败语义：上传失败 → 前端 toast，消息不发送；发送时模型不支持 → llm-gateway 4xx 透传错误文案
- 不做：图片压缩、CDN、预签名 URL、assistant 图片输出

## 6. 验收

新增 `scripts/verify_chat_image_live.py`：

1. 上传 png → 返回 URL，curl 可 GET 200
2. 超限（>10MB / 非图片类型 / >4 张）→ 400
3. 发消息带 imageUrls → orchestrator 落库 `image_urls_json`；`delivery-mode=base64`（默认）时 llm-gateway 收到的 messages 中图片为 `data:image/...;base64,` URI，`url` 模式时为 MinIO URL（llm-gateway 日志验证）
4. multimodal=false 模型发图 → 4xx 友好报错
5. 历史消息接口返回 imageUrls，前端气泡回显（UI 步骤人工）

前端功能按规则不做自动化测试，测试步骤随交付给出。
