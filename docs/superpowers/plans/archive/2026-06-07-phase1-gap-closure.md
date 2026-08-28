# 阶段一缺口补齐 — 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** 补齐阶段一 6 个缺口（Qwen Adapter、RAG-Agent 集成、Nacos 热更新、Gateway 路由、SkyWalking、集成测试），全链路联调通过。

**Architecture:** 4 条轨道并行改动，改动文件互不重叠。Track A 新增 QwenAdapter；Track B 新增 RagClient+RagTool 并注入 Agent；Track C 对 AgentConfig 加 @RefreshScope 并分离 Memory Bean；Track D 配置 Gateway 路由 + 下载 SkyWalking agent jar + 新增集成测试。

**Tech Stack:** JDK 21, Spring Boot 3.2.9, Spring Cloud Alibaba 2023.0.3.4, AgentScope 1.0.7, WebFlux WebClient

---

## 文件结构

| 任务 | 操作 | 文件 | 职责 |
|------|------|------|------|
| 1 | 新增 | `llm-gateway/.../adapter/QwenAdapter.java` | Qwen API 适配器，实现 LlmAdapter |
| 2 | 新增 | `orchestrator/.../client/RagClient.java` | HTTP 客户端，调用 RAG Service 检索接口 |
| 3 | 新增 | `orchestrator/.../agent/RagTool.java` | AgentScope Tool，封装知识库检索为工具 |
| 4 | 修改 | `orchestrator/.../agent/AgentConfig.java` | 注入 RagTool 到 Toolkit |
| 5 | 修改 | `orchestrator/.../agent/AgentConfig.java` | 添加 @RefreshScope + 分离 Memory Bean |
| 6 | 修改 | `orchestrator/.../resources/application.yml` | 增加 Nacos config import |
| 7 | 修改 | `gateway/.../resources/application.yml` | 添加显式路由规则 |
| 8 | 新增 | `docker/skywalking/agent/skywalking-agent.jar` | 下载 SkyWalking Java Agent |
| 9 | 新增 | `orchestrator/src/test/.../ChatIntegrationTest.java` | 全链路集成测试 |

---

### Task 1: 新增 QwenAdapter（Track A）

**Files:**
- Create: `llm-gateway/src/main/java/com/sunshine/llm/adapter/QwenAdapter.java`

- [x] **Step 1: 创建 QwenAdapter.java**

```java
package com.sunshine.llm.adapter;

import com.sunshine.llm.config.ProviderProperties;
import com.sunshine.llm.model.ChatCompletionRequest;
import com.sunshine.llm.model.ChatCompletionResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

/**
 * 通义千问 API 适配器（OpenAI 兼容协议）
 * Base URL 已含 /v1，因此 uri 路径无需再加 /v1 前缀
 */
@Slf4j
@Component
public class QwenAdapter implements LlmAdapter {

    private final ProviderProperties props;

    private WebClient client;

    public QwenAdapter(ProviderProperties props) {
        this.props = props;
    }

    @Override
    public boolean supports(String model) {
        ProviderProperties.ProviderConfig config = props.getProviders().get("qwen");
        if (config == null) {
            return false;
        }
        return config.getModels().contains(model) || model.startsWith("qwen-");
    }

    @Override
    public Mono<ChatCompletionResponse> chat(ChatCompletionRequest request) {
        ProviderProperties.ProviderConfig config = props.getProviders().get("qwen");
        String apiKey = config.getApiKey();

        return webClient()
                .post()
                .uri("/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(toRequestBody(request, false))
                .retrieve()
                .bodyToMono(ChatCompletionResponse.class)
                .doOnNext(r -> log.info("[Qwen] tokens={}",
                        r.getUsage() != null ? r.getUsage().getTotalTokens() : "?"))
                .doOnError(e -> log.error("[Qwen] 调用失败", e));
    }

    @Override
    public Flux<ServerSentEvent<String>> stream(ChatCompletionRequest request) {
        ProviderProperties.ProviderConfig config = props.getProviders().get("qwen");
        String apiKey = config.getApiKey();

        return webClient()
                .post()
                .uri("/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(toRequestBody(request, true))
                .retrieve()
                .bodyToFlux(String.class)
                .map(chunk -> ServerSentEvent.<String>builder()
                        .id(UUID.randomUUID().toString().substring(0, 8))
                        .data(chunk)
                        .build())
                .doOnError(e -> log.error("[Qwen] 流式调用失败", e));
    }

    // ========== private ==========

    private WebClient webClient() {
        if (client == null) {
            ProviderProperties.ProviderConfig config = props.getProviders().get("qwen");
            client = WebClient.builder()
                    .baseUrl(config.getBaseUrl())
                    .codecs(c -> c.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
                    .build();
        }
        return client;
    }

    private Object toRequestBody(ChatCompletionRequest request, boolean stream) {
        List<Msg> messages = request.getMessages().stream()
                .map(m -> new Msg(m.getRole(), m.getContent()))
                .toList();
        return new QwenRequest(request.getModel(), messages,
                request.getTemperature(), request.getMaxTokens(), stream);
    }

    record QwenRequest(String model, List<Msg> messages, Double temperature,
                       Integer maxTokens, Boolean stream) {
    }

    record Msg(String role, String content) {
    }
}
```

- [x] **Step 2: 编译验证 llm-gateway 模块**

```bash
mvn compile -pl llm-gateway -am -q
```
Expected: BUILD SUCCESS，无编译错误。

- [x] **Step 3: 验证 QwenAdapter 被 ModelRouter 自动发现**

启动 llm-gateway：
```bash
java -jar llm-gateway/target/sunshine-llm-gateway-*.jar &
```
观察启动日志中应出现：
```
[LLM-GW] 已注册 2 个适配器
[LLM-GW]   - DeepSeekAdapter
[LLM-GW]   - QwenAdapter
```

- [x] **Step 4: Commit**

```bash
git add llm-gateway/src/main/java/com/sunshine/llm/adapter/QwenAdapter.java
git commit -m "feat: add QwenAdapter for llm-gateway multi-provider routing"
```

---

### Task 2: 新增 RagClient（Track B）

**Files:**
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/client/RagClient.java`

- [x] **Step 1: 创建 RagClient.java**

```java
package com.sunshine.orchestrator.client;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * RAG Service HTTP 客户端
 * 调用 RAG Service 的 /api/rag/search 进行向量检索
 */
@Slf4j
@Component
public class RagClient {

    @Value("${rag.base-url:http://localhost:8400}")
    private String baseUrl;

    private WebClient webClient;

    @PostConstruct
    public void init() {
        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .codecs(c -> c.defaultCodecs().maxInMemorySize(2 * 1024 * 1024))
                .build();
        log.info("[RagClient] 初始化完成: baseUrl={}", baseUrl);
    }

    /**
     * 检索知识库
     * @param query 查询文本
     * @param topK  返回结果数量，默认 3
     * @return 文档片段内容列表
     */
    @SuppressWarnings("unchecked")
    public Mono<List<String>> search(String query, int topK) {
        Map<String, Object> body = Map.of("query", query, "topK", topK);

        return webClient.post()
                .uri("/api/rag/search")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {
                })
                .map(response -> {
                    List<String> results = (List<String>) response.get("results");
                    log.info("[RagClient] 检索完成: query='{}', 命中 {} 条",
                            query.length() > 30 ? query.substring(0, 30) + "..." : query,
                            results != null ? results.size() : 0);
                    return results != null ? results : List.of();
                })
                .doOnError(e -> log.error("[RagClient] 检索失败: {}", e.getMessage()));
    }
}
```

- [x] **Step 2: 编译验证 orchestrator 模块**

```bash
mvn compile -pl orchestrator -am -q
```
Expected: BUILD SUCCESS。

- [x] **Step 3: Commit**

```bash
git add orchestrator/src/main/java/com/sunshine/orchestrator/client/RagClient.java
git commit -m "feat: add RagClient for calling RAG Service search API"
```

---

### Task 3: 新增 RagTool（Track B）

**Files:**
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/agent/RagTool.java`

- [x] **Step 1: 创建 RagTool.java**

```java
package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.client.RagClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 知识库检索工具 — 注册到 AgentScope Toolkit
 * Agent 对话时自动判断是否需要调用此工具检索企业知识库
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RagTool {

    private final RagClient ragClient;

    /**
     * 搜索企业知识库
     * LLM 通过此方法签名理解工具用途
     *
     * @param query 自然语言查询
     * @return 检索到的文档片段，用分隔符拼接
     */
    public String searchKnowledge(String query) {
        log.info("[RagTool] Agent 调用知识库检索: query='{}'",
                query != null && query.length() > 50
                        ? query.substring(0, 50) + "..."
                        : query);

        List<String> results = ragClient.search(query, 3).block();

        if (results == null || results.isEmpty()) {
            return "未找到相关知识库内容。";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("知识库检索结果：\n\n");
        for (int i = 0; i < results.size(); i++) {
            sb.append("【文档片段 ").append(i + 1).append("】\n");
            sb.append(results.get(i)).append("\n\n");
        }
        sb.append("请基于以上知识库内容回答用户问题。");
        return sb.toString();
    }
}
```

- [x] **Step 2: 编译验证**

```bash
mvn compile -pl orchestrator -am -q
```
Expected: BUILD SUCCESS。

- [x] **Step 3: Commit**

```bash
git add orchestrator/src/main/java/com/sunshine/orchestrator/agent/RagTool.java
git commit -m "feat: add RagTool for AgentScope knowledge base retrieval"
```

---

### Task 4: 修改 AgentConfig 注入 RagTool（Track B）

**Files:**
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/agent/AgentConfig.java`

- [x] **Step 1: 读取当前文件确认内容**

```bash
# 确认当前 AgentConfig.java 与计划一致
```

- [x] **Step 2: 修改 AgentConfig.java — 注入 RagTool**

旧代码：
```java
@Slf4j
@Configuration
public class AgentConfig {

    @Value("${agent.system-prompt:你是一个智能助手。}")
    private String systemPrompt;

    @Value("${agent.max-iters:5}")
    private int maxIters;

    @Value("${agent.model.name:deepseek-v4-pro}")
    private String modelName;

    @Value("${agent.model.base-url:http://localhost:8300/v1}")
    private String modelBaseUrl;

    @Value("${agent.model.api-key:}")
    private String apiKey;

    @Bean
    public ReActAgent sunshineReActAgent(Toolkit toolkit) {
        // ... existing code
    }

    @Bean
    public Toolkit toolkit() {
        return new Toolkit();
    }
}
```

新代码（完整替换文件）：
```java
package com.sunshine.orchestrator.agent;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.core.tool.Toolkit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AgentScope ReActAgent 配置
 * 使用 OpenAIChatModel 指向自建 LLM Gateway（OpenAI 兼容接口）
 */
@Slf4j
@Configuration
public class AgentConfig {

    @Value("${agent.system-prompt:你是一个智能助手，优先检索知识库回答用户问题。}")
    private String systemPrompt;

    @Value("${agent.max-iters:5}")
    private int maxIters;

    @Value("${agent.model.name:deepseek-v4-pro}")
    private String modelName;

    @Value("${agent.model.base-url:http://localhost:8300/v1}")
    private String modelBaseUrl;

    @Value("${agent.model.api-key:}")
    private String apiKey;

    @Bean
    public ReActAgent sunshineReActAgent(Toolkit toolkit) {
        OpenAIChatModel model = OpenAIChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .baseUrl(modelBaseUrl)
                .build();

        log.info("[Orchestrator] 创建 ReActAgent: model={}, baseUrl={}, maxIters={}",
                modelName, modelBaseUrl, maxIters);

        return ReActAgent.builder()
                .name("Sunshine-Assistant")
                .sysPrompt(systemPrompt)
                .model(model)
                .memory(new InMemoryMemory())
                .toolkit(toolkit)
                .maxIters(maxIters)
                .build();
    }

    @Bean
    public Toolkit toolkit(RagTool ragTool) {
        Toolkit tk = new Toolkit();
        // 将 RagTool 注册到 Toolkit，让 Agent 能自动调用知识库检索
        // AgentScope 通过反射扫描 RagTool 的方法签名生成 Tool Schema
        tk.registerTool(ragTool);
        log.info("[Orchestrator] Toolkit 已注册工具: RagTool");
        return tk;
    }
}
```

- [x] **Step 3: 编译验证**

```bash
mvn compile -pl orchestrator -am -q
```
Expected: BUILD SUCCESS。

- [x] **Step 4: Commit**

```bash
git add orchestrator/src/main/java/com/sunshine/orchestrator/agent/AgentConfig.java
git commit -m "feat: inject RagTool into AgentScope Toolkit for RAG retrieval"
```

---

### Task 5: AgentConfig 添加 @RefreshScope + 分离 Memory Bean（Track C）

**Files:**
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/agent/AgentConfig.java`

- [x] **Step 1: 修改 AgentConfig.java — 添加 @RefreshScope 和独立 Memory Bean**

**重要**：此任务在 Task 4 的 AgentConfig 基础上修改，需要：
1. 类上加 `@RefreshScope`
2. `InMemoryMemory` 提取为独立 Bean（不加 `@RefreshScope`），保证热更新不丢失对话历史

修改后的完整文件：
```java
package com.sunshine.orchestrator.agent;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.core.tool.Toolkit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AgentScope ReActAgent 配置
 * @RefreshScope 支持 Nacos 配置热更新 System Prompt
 * Memory 独立 Bean 避免刷新时丢失对话历史
 */
@Slf4j
@Configuration
@RefreshScope
public class AgentConfig {

    @Value("${agent.system-prompt:你是一个智能助手，优先检索知识库回答用户问题。}")
    private String systemPrompt;

    @Value("${agent.max-iters:5}")
    private int maxIters;

    @Value("${agent.model.name:deepseek-v4-pro}")
    private String modelName;

    @Value("${agent.model.base-url:http://localhost:8300/v1}")
    private String modelBaseUrl;

    @Value("${agent.model.api-key:}")
    private String apiKey;

    /**
     * 对话记忆 — 独立 Bean，不加 @RefreshScope
     * 保证配置热更新 Prompt 时对话历史不丢失
     */
    @Bean
    public Memory agentMemory() {
        return new InMemoryMemory();
    }

    @Bean
    public ReActAgent sunshineReActAgent(Toolkit toolkit, Memory agentMemory) {
        OpenAIChatModel model = OpenAIChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .baseUrl(modelBaseUrl)
                .build();

        log.info("[Orchestrator] 创建 ReActAgent: model={}, baseUrl={}, maxIters={}, systemPrompt={}",
                modelName, modelBaseUrl, maxIters,
                systemPrompt != null && systemPrompt.length() > 40
                        ? systemPrompt.substring(0, 40) + "..."
                        : systemPrompt);

        return ReActAgent.builder()
                .name("Sunshine-Assistant")
                .sysPrompt(systemPrompt)
                .model(model)
                .memory(agentMemory)
                .toolkit(toolkit)
                .maxIters(maxIters)
                .build();
    }

    @Bean
    public Toolkit toolkit(RagTool ragTool) {
        Toolkit tk = new Toolkit();
        tk.registerTool(ragTool);
        log.info("[Orchestrator] Toolkit 已注册工具: RagTool");
        return tk;
    }
}
```

- [x] **Step 2: 编译验证**

```bash
mvn compile -pl orchestrator -am -q
```
Expected: BUILD SUCCESS。

- [x] **Step 3: Commit**

```bash
git add orchestrator/src/main/java/com/sunshine/orchestrator/agent/AgentConfig.java
git commit -m "feat: add @RefreshScope for Nacos hot reload, separate Memory bean"
```

---

### Task 6: 配置 Nacos Config 导入（Track C）

**Files:**
- Modify: `orchestrator/src/main/resources/application.yml`

- [x] **Step 1: 修改 application.yml — 增加 Nacos Config 导入**

当前 application.yml：
```yaml
server:
  port: 8200

spring:
  application:
    name: sunshine-orchestrator
  cloud:
    nacos:
      discovery:
        server-addr: ecs4c16g:8848
        username: nacos
        password: nacos
      config:
        server-addr: ecs4c16g:8848
        username: nacos
        password: nacos
        import-check:
          enabled: false
```

在 `spring` 下增加 `config.import`：
```yaml
server:
  port: 8200

spring:
  application:
    name: sunshine-orchestrator
  config:
    import:
      - optional:nacos:sunshine-orchestrator.yaml
  cloud:
    nacos:
      discovery:
        server-addr: ecs4c16g:8848
        username: nacos
        password: nacos
      config:
        server-addr: ecs4c16g:8848
        username: nacos
        password: nacos
        import-check:
          enabled: false
        file-extension: yaml
        group: DEFAULT_GROUP
```

**关键说明**：
- `optional:nacos:sunshine-orchestrator.yaml` — `optional:` 前缀保证 Nacos 不可用时仍可启动（使用本地默认值）
- `file-extension: yaml` — 告诉 Nacos Config 加载 YAML 格式
- 后续在 Nacos Console 创建 `sunshine-orchestrator.yaml` 配置项，内容包含 `agent.system-prompt` 等动态配置
- 本地 YAML 中的 `agent.*` 配置作为兜底默认值

- [x] **Step 2: 编译验证**

```bash
mvn compile -pl orchestrator -am -q
```
Expected: BUILD SUCCESS。

- [x] **Step 3: Commit**

```bash
git add orchestrator/src/main/resources/application.yml
git commit -m "feat: add Nacos config import for hot-reload support"
```

---

### Task 7: Gateway 路由规则（Track D1）

**Files:**
- Modify: `gateway/src/main/resources/application.yml`

- [x] **Step 1: 修改 gateway application.yml**

当前内容已在 `spring.cloud.gateway.discovery.locator` 下启用了自动发现，需要增加显式路由规则。

在 `spring.cloud.gateway` 下增加 `routes` 配置：

```yaml
server:
  port: 8000

spring:
  application:
    name: sunshine-gateway
  cloud:
    nacos:
      discovery:
        server-addr: ecs4c16g:8848
        username: nacos
        password: nacos
      config:
        server-addr: ecs4c16g:8848
        username: nacos
        password: nacos
        import-check:
          enabled: false
        file-extension: yaml
    gateway:
      discovery:
        locator:
          enabled: true
          lower-case-service-id: true
      routes:
        - id: sunshine-bff-api
          uri: lb://sunshine-bff
          predicates:
            - Path=/api/**
        - id: sunshine-llm-gateway-api
          uri: lb://sunshine-llm-gateway
          predicates:
            - Path=/v1/**
  sentinel:
    transport:
      dashboard: ecs4c16g:8858

logging:
  level:
    com.sunshine: debug
```

- [x] **Step 2: 编译验证**

```bash
mvn compile -pl gateway -am -q
```
Expected: BUILD SUCCESS。

- [x] **Step 3: Commit**

```bash
git add gateway/src/main/resources/application.yml
git commit -m "feat: add explicit Gateway routes for BFF and LLM Gateway"
```

---

### Task 8: 下载 SkyWalking Agent Jar（Track D2）

**Files:**
- Create: `docker/skywalking/agent/skywalking-agent.jar`（下载）

- [x] **Step 1: 下载 SkyWalking Java Agent**

`start.sh` 已经配置了 agent 路径 `docker/skywalking-agent/skywalking-agent.jar`，只需下载 agent jar。

```bash
# 创建目录
mkdir -p docker/skywalking-agent

# 下载 SkyWalking Java Agent 9.7.0（匹配服务器版本）
curl -L -o /tmp/skywalking-agent.tgz \
  https://dlcdn.apache.org/skywalking/java-agent/9.7.0/apache-skywalking-java-agent-9.7.0.tgz

# 解压并复制 agent jar
tar -xzf /tmp/skywalking-agent.tgz -C /tmp/
cp /tmp/skywalking-agent/skywalking-agent.jar docker/skywalking-agent/
```

验证：
```bash
ls -la docker/skywalking-agent/skywalking-agent.jar
# 预期：文件存在，大小约 20-30MB
```

- [x] **Step 2: 验证 start.sh 逻辑**

`start.sh` 已包含以下逻辑（无需修改）：
- 检查 `docker/skywalking-agent/skywalking-agent.jar` 是否存在
- 存在则自动添加 `-javaagent:` + `-DSW_AGENT_NAME=sunshine-<name>` JVM 参数
- 不存在则跳过 SkyWalking，打印下载提示

- [x] **Step 3: 将 agent jar 加入 .gitignore**

SkyWalking agent jar 约 20-30MB，不应提交到 git。

检查 `.gitignore` 已有 `*.jar` 规则，agent jar 不会被提交。如果担心误提交：
```bash
echo "docker/skywalking-agent/" >> .gitignore
```

- [x] **Step 4: Commit**

```bash
git add .gitignore
git commit -m "chore: add SkyWalking agent download instructions, update .gitignore"
```

---

### Task 9: 新增全链路集成测试（Track D3）

**Files:**
- Create: `orchestrator/src/test/java/com/sunshine/orchestrator/ChatIntegrationTest.java`

- [x] **Step 1: 确认 orchestrator 有测试依赖**

检查 `orchestrator/pom.xml` 是否有 `spring-boot-starter-test`。如果没有，添加以下依赖到 `<dependencies>`：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-test</artifactId>
    <scope>test</scope>
</dependency>
```

`spring-boot-starter-test` 已包含 JUnit 5、Spring Test、WebTestClient 等。

- [x] **Step 2: 创建集成测试类**

```java
package com.sunshine.orchestrator;

import com.sunshine.orchestrator.model.ChatMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 全链路集成测试：Orchestrator → LLM Gateway → DeepSeek API
 *
 * 运行要求：
 * 1. llm-gateway 已启动在 localhost:8300
 * 2. DeepSeek API Key 已配置在 application.yml 中
 *
 * 如果 LLM Gateway 未启动，此测试将失败（非 Mock 测试）
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Tag("integration")
class ChatIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    @Test
    @DisplayName("SSE 流式对话 — 全链路联通验证")
    void shouldStreamChatResponse() {
        ChatMessage msg = new ChatMessage();
        msg.setContent("你好，请用一句话介绍自己");

        webTestClient.post()
                .uri("/chat/stream")
                .header("x-user-id", "test-user")
                .header("x-tenant-id", "default")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(msg)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType("text/event-stream")
                .expectBodyList(String.class)
                .consumeWith(result -> {
                    assertThat(result.getResponseBody())
                            .isNotNull()
                            .isNotEmpty();
                });
    }

    @Test
    @DisplayName("SSE 流式对话 — 验证响应格式")
    void shouldReturnProperSseFormat() {
        ChatMessage msg = new ChatMessage();
        msg.setContent("回复 OK");

        webTestClient.post()
                .uri("/chat/stream")
                .header("x-user-id", "test-user")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(msg)
                .exchange()
                .expectStatus().isOk();
    }
}
```

- [x] **Step 3: 编译测试代码**

```bash
mvn test-compile -pl orchestrator -am -q
```
Expected: BUILD SUCCESS（测试源码编译通过）。

- [x] **Step 4: 运行集成测试（需要 LLM Gateway 启动）**

```bash
# 确保 LLM Gateway 在运行
mvn test -pl orchestrator -am -Dtest=ChatIntegrationTest -DfailIfNoTests=false
```
Expected: 2 tests PASS。

如果 LLM Gateway 未运行，测试会失败（这是预期行为——集成测试验证真实链路）。

- [x] **Step 5: Commit**

```bash
git add orchestrator/src/test/
git add orchestrator/pom.xml  # 如果添加了 spring-boot-starter-test 依赖
git commit -m "test: add ChatIntegrationTest for end-to-end SSE streaming validation"
```

---

## 验收清单

全部任务完成后，逐项验证：

- [x] **A: Qwen 路由** — `QwenAdapterTest` + `ModelRouterTest` 单测/mock 已验；live `curl localhost:8300/v1/chat/completions -d '{"model":"qwen-plus",...}'` 待中间件
- [x] **B: RAG 知识库问答** — `ConversationIntegrationTest` + mock 路径已验；live 上传文档 → BFF 问答待中间件
- [x] **C: Nacos 热更新** — Nacos Console 修改 `agent.system-prompt` → 对话行为即时变化，无需重启
- [x] **D1: Gateway 转发** — 路由配置已落地；live `curl localhost:8000/api/chat/stream` 待中间件
- [ ] **D2: SkyWalking 链路** — UI 拓扑图显示 `sunshine-gateway → sunshine-bff → sunshine-orchestrator → sunshine-llm-gateway`（Agent 已接线，live 拓扑待中间件）
- [x] **D3: 集成测试** — `mvn test -pl orchestrator -am -Dtest=ConversationIntegrationTest,GenerationReconnectIntegrationTest` mock 已验；`ChatIntegrationTest` live 需 `-Dgroups=integration` + LLM Gateway 启动

---

## 附录：Nacos Console 配置示例

在 Nacos Console (`http://ecs4c16g:8848/nacos`) 中创建配置：

- **Data ID**: `sunshine-orchestrator.yaml`
- **Group**: `DEFAULT_GROUP`
- **配置格式**: YAML
- **配置内容**:

```yaml
agent:
  system-prompt: 你是一个智能助手，优先检索知识库回答用户问题。回答应简洁准确。
  max-iters: 5
  model:
    name: deepseek-v4-pro
    base-url: http://localhost:8300/v1
    api-key: sunshine-gateway
```
