# 工具集成（SDK + MCP）Implementation Plan

> **状态**：✅ Phase 1 完成（检查门 G1–G10，2026-07-10）。下文 Task 清单保留作实施记录；增量见 spec §6.3（Tool ID）、§10（Plan 策略）、§14（HITL）。

## Phase 1 交付摘要（相对初版计划的增量）

| 项 | 最终形态 |
|----|----------|
| Catalog Tool ID | `sdk__{app}__{name}` / `mcp__{server}__{name}`（`ToolIds.java`）；LLM function name 同 ID |
| 工具集 | `global_react_default` + `global_plan_workflow_critical`；`/tools` 子 Tab ReAct / Planner Workflow |
| Plan 执行策略 | `execution_mode_policy` 表 + Admin API；orchestrator 读 DB，非 Nacos 节点配置 |
| HITL | `require_confirmation` + `confirmation_edited`；不以 PATCH `sideEffect` 为门禁 |
| ID 校验 | `id_valid` / `id_error`；规范不一致时删旧重建（无旧 ID 迁移） |
| 调用路径 | ReAct：LLM `tool_call`；Workflow `tool` 节点：`ToolNodeHandler` 直调 |
| 可观测 | llm-gateway `LlmIoTracer` 日志字段 `toolCalls=` |
| 种子 tool_id | 见 `docker/mysql/init/16-sunshine-tool-manager.sql`（如 `sdk__sunshine-finance__list_finance_messages`） |

---

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 用 MySQL Catalog + SDK（Nacos Pull）+ MCP 动态接入替换 tool-manager 编译期 Handler 与 Nacos react 白名单；finance-service / oa-service 作为 SDK Demo；提供 `/tools` 管理页与 Live 检查门。

**Architecture:** `common/sunshine-tool-sdk` 供业务 App 声明 `@SunshineTool` 并暴露 `/sunshine/tools/*`；tool-manager 扩 JPA + SdkDiscoveryPuller + McpClientPool + InvokeRouter + Admin API；orchestrator 用 `ToolSetResolver` 解析 global/tenant ReAct 工具集并热刷新 Catalog；特殊工具 `manage_tasks` / `search_knowledge` 仍留 orchestrator。

**Tech Stack:** JDK 21 · Spring Boot 3.2 · JPA · MySQL `sunshine_tool` · Nacos Discovery · Redis pub/sub · WebClient · Vue3/Naive UI · Python Live 脚本

**设计 SSOT:** [2026-07-09-tool-integration-design.md](../specs/archive/2026-07-09-tool-integration-design.md)

**前置条件:** 设计 spec 已 review；MySQL / Nacos / Redis 可用（ecs4c16g）；实现前在 `docker/mysql/init/` 应用新库表。

---

## 文件结构（边界锁定）

| 区域 | 创建 | 修改 | 测试 |
|------|------|------|------|
| **SDK** | `common/sunshine-tool-sdk/**` | 根 `pom.xml` | `ToolSchemaGeneratorTest` |
| **DB** | `docker/mysql/init/16-sunshine-tool-manager.sql` | `01-init-databases.sql` | `mysql` 手测 |
| **Demo** | `finance-service/.../FinanceSunshineTools.java`、`oa-service/.../OaSunshineTools.java` | 两服务 `pom.xml`、`application.yml` metadata | 各服务 Controller 单测 |
| **tool-manager** | `entity/*`、`repo/*`、`service/*`、`mcp/*`、`admin/*` | 删 `client/*`、`tool/*Handler`；改 `registry`、`controller` | `SdkDiscoveryPullerTest`、`InvokeRouterTest`、`McpImportServiceTest` |
| **Nacos** | — | `sunshine-tool-manager.yaml`、`sunshine-orchestrator.yaml` | `sync_nacos.py` |
| **orchestrator** | `catalog/ToolSetResolver`、`catalog/ToolCatalogRefreshScheduler` | `DynamicToolkitFactory`、`GenericRemoteToolFactory`、`ToolCatalogClient`、`AgentExecutionProperties` | `ToolSetResolverTest`、`DynamicToolkitFactoryTest` |
| **BFF** | `ToolsAdminController`、`ToolManagerAdminClient` | `sunshine-bff.yaml` gateway 如需 | curl 手测 |
| **前端** | `api/tools.ts`、`views/ToolsView.vue` | `router/index.ts`、`MainLayout.vue` | `npx vue-tsc -b` |
| **验收** | `scripts/verify_tool_integration_live.py` | `implementation-plan.md`、`CLAUDE.md` | Live G1–G10 |

---

## 迭代排期

```
迭代 0（P0 SDK 底座）     T0 → T1 → T2 → T3           MySQL + sunshine-tool-sdk
迭代 1（P0 Demo）         T4 → T5                     finance/oa SDK 化
迭代 2（P0 Catalog DB）   T6 → T7 → T8               JPA + DB Catalog + 删旧 Handler
迭代 3（P0 SDK 链路）     T9 → T10                    Pull + SDK Invoke
迭代 4（P0 MCP）          T11 → T12 → T13             MCP pool + probe + invoke
迭代 5（P0 Admin）        T14 → T15                   Admin API + 工具集 + Redis 事件
迭代 6（P0 Orchestrator） T16 → T17 → T18             ToolSetResolver + 热刷新 + kind=mcp
迭代 7（P1 UI）           T19 → T20                   BFF + /tools 页
迭代 8（P0 验收）         T21 → T22                   live 脚本 + 文档
```

---

## Task T0: MySQL `sunshine_tool` 库表

**Files:**
- Create: `docker/mysql/init/16-sunshine-tool-manager.sql`
- Modify: `docker/mysql/init/01-init-databases.sql`

- [ ] **Step 1: 建库**

在 `01-init-databases.sql` 末尾追加：

```sql
CREATE DATABASE IF NOT EXISTS sunshine_tool DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

- [ ] **Step 2: 建表 + 种子**

创建 `16-sunshine-tool-manager.sql`（内容对齐 spec §6，含种子）：

```sql
-- sunshine-tool-manager（tool-manager :8210）
USE sunshine_tool;

CREATE TABLE sdk_application (
    id              VARCHAR(64) PRIMARY KEY,
    nacos_service   VARCHAR(128) NOT NULL,
    display_name    VARCHAR(128),
    catalog_path    VARCHAR(256) NOT NULL DEFAULT '/sunshine/tools/catalog',
    invoke_path     VARCHAR(256) NOT NULL DEFAULT '/sunshine/tools/invoke',
    tenant_id       VARCHAR(32) NOT NULL DEFAULT 'default',
    status          VARCHAR(16) NOT NULL DEFAULT 'offline',
    last_seen_at    TIMESTAMP NULL,
    schema_version  INT NOT NULL DEFAULT 0,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE mcp_server (
    id              VARCHAR(64) PRIMARY KEY,
    display_name    VARCHAR(128),
    transport       VARCHAR(16) NOT NULL,
    command         VARCHAR(512),
    args_json       JSON,
    endpoint        VARCHAR(512),
    env_json        JSON,
    tenant_id       VARCHAR(32) NOT NULL DEFAULT 'default',
    enabled         TINYINT(1) NOT NULL DEFAULT 0,
    last_probe_at   TIMESTAMP NULL,
    probe_status    VARCHAR(16),
    probe_error     VARCHAR(512),
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE tool_definition (
    id                  VARCHAR(128) PRIMARY KEY,
    source              VARCHAR(16) NOT NULL,
    source_ref          VARCHAR(64) NOT NULL,
    external_name       VARCHAR(128) NOT NULL,
    display_name        VARCHAR(128) NOT NULL,
    description         TEXT,
    schema_json         JSON NOT NULL,
    schema_hash         VARCHAR(64),
    kind                VARCHAR(16) NOT NULL,
    timeline_phase      VARCHAR(16) NOT NULL DEFAULT 'tool',
    output_summary_kind VARCHAR(32) NOT NULL DEFAULT 'truncate',
    side_effect         VARCHAR(16) NOT NULL DEFAULT 'read',
    tenant_id           VARCHAR(32) NOT NULL DEFAULT 'default',
    enabled             TINYINT(1) NOT NULL DEFAULT 0,
    metadata_edited     TINYINT(1) NOT NULL DEFAULT 0,
    discovered_at       TIMESTAMP NULL,
    updated_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_source_tool (source, source_ref, external_name)
);

CREATE TABLE tool_set (
    id              VARCHAR(64) PRIMARY KEY,
    set_type        VARCHAR(32) NOT NULL,
    tenant_id       VARCHAR(32),
    display_name    VARCHAR(128),
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_set_type_tenant (set_type, tenant_id)
);

CREATE TABLE tool_set_member (
    set_id          VARCHAR(64) NOT NULL,
    tool_id         VARCHAR(128) NOT NULL,
    sort_order      INT NOT NULL DEFAULT 0,
    PRIMARY KEY (set_id, tool_id)
);

INSERT INTO sdk_application (id, nacos_service, display_name, tenant_id, status) VALUES
('sunshine-finance', 'sunshine-finance', '财务 Demo 应用', 'default', 'offline'),
('sunshine-oa', 'sunshine-oa', 'OA Demo 应用', 'default', 'offline');

INSERT INTO tool_set (id, set_type, tenant_id, display_name) VALUES
('global-react-default', 'global_react_default', NULL, '平台 ReAct 默认工具集'),
('global-plan-workflow-critical', 'global_plan_workflow_critical', NULL, '平台 Plan/Workflow 关键工具集');

INSERT INTO tool_set_member (set_id, tool_id, sort_order) VALUES
('global-react-default', 'sdk__sunshine-finance__list_finance_messages', 0),
('global-react-default', 'sdk__sunshine-finance__get_finance_message_detail', 1),
('global-react-default', 'sdk__sunshine-finance__summarize_finance_by_status', 2),
('global-react-default', 'sdk__sunshine-oa__list_oa_tasks', 3),
('global-react-default', 'sdk__sunshine-oa__approve_oa_task', 4);
```

> 完整种子（含 `execution_mode_policy`）以 `docker/mysql/init/16-sunshine-tool-manager.sql` 为准。

- [ ] **Step 3: 应用 SQL**

```bash
mysql -h ecs4c16g -uroot -p < docker/mysql/init/01-init-databases.sql
mysql -h ecs4c16g -uroot -p < docker/mysql/init/16-sunshine-tool-manager.sql
```

Expected: `USE sunshine_tool; SHOW TABLES;` 返回 5 表；`tool_set_member` 5 行。

- [ ] **Step 4: Commit**

```bash
git add docker/mysql/init/01-init-databases.sql docker/mysql/init/16-sunshine-tool-manager.sql
git commit -m "feat(tool): add sunshine_tool schema and react default tool set seed"
```

---

## Task T1: `sunshine-tool-sdk` Maven 模块骨架

**Files:**
- Create: `common/sunshine-tool-sdk/pom.xml`
- Create: `common/sunshine-tool-sdk/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Modify: `pom.xml`（根 `<modules>` + `dependencyManagement`）

- [ ] **Step 1: 根 pom 注册模块**

`pom.xml` `<modules>` 在 `common/sunshine-common` 后追加：

```xml
<module>common/sunshine-tool-sdk</module>
```

`dependencyManagement` 追加：

```xml
<dependency>
    <groupId>com.sunshine</groupId>
    <artifactId>sunshine-tool-sdk</artifactId>
    <version>${project.version}</version>
</dependency>
```

- [ ] **Step 2: 创建 SDK pom**

`common/sunshine-tool-sdk/pom.xml`：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>com.sunshine</groupId>
        <artifactId>my-sunshine-agent</artifactId>
        <version>1.0.0-SNAPSHOT</version>
        <relativePath>../../pom.xml</relativePath>
    </parent>
    <artifactId>sunshine-tool-sdk</artifactId>
    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-autoconfigure</artifactId>
        </dependency>
        <dependency>
            <groupId>com.fasterxml.jackson.core</groupId>
            <artifactId>jackson-databind</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <version>3.13.0</version>
                <configuration>
                    <parameters>true</parameters>
                </configuration>
            </plugin>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
                <configuration><skip>true</skip></configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 3: AutoConfiguration 占位**

`AutoConfiguration.imports` 内容：

```
com.sunshine.tools.sdk.autoconfigure.SunshineToolAutoConfiguration
```

创建空类 `SunshineToolAutoConfiguration.java`（后续 Task T3 填充）。

- [ ] **Step 4: 编译验证**

```bash
mvn -pl common/sunshine-tool-sdk -am compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add pom.xml common/sunshine-tool-sdk/
git commit -m "feat(tool-sdk): add sunshine-tool-sdk module skeleton"
```

---

## Task T2: SDK 注解与 Schema 生成（TDD）

**Files:**
- Create: `common/sunshine-tool-sdk/src/main/java/com/sunshine/tools/sdk/annotation/SunshineTool.java`
- Create: `common/sunshine-tool-sdk/src/main/java/com/sunshine/tools/sdk/annotation/ToolParam.java`
- Create: `common/sunshine-tool-sdk/src/main/java/com/sunshine/tools/sdk/registry/ToolSchemaGenerator.java`
- Create: `common/sunshine-tool-sdk/src/main/java/com/sunshine/tools/sdk/registry/RegisteredToolMethod.java`
- Test: `common/sunshine-tool-sdk/src/test/java/com/sunshine/tools/sdk/registry/ToolSchemaGeneratorTest.java`

- [ ] **Step 1: 写失败单测**

```java
package com.sunshine.tools.sdk.registry;

import com.sunshine.tools.sdk.annotation.SunshineTool;
import com.sunshine.tools.sdk.annotation.ToolParam;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ToolSchemaGeneratorTest {

    @Component
    static class SampleTools {
        @SunshineTool(
                id = "list_finance_messages",
                displayName = "查询待审批财务消息",
                description = "按状态筛选",
                outputSummaryKind = "finance-list")
        public String list(@ToolParam(value = "status", description = "pending|approved|all") String status) {
            return "ok";
        }
    }

    @Test
    void generatesOpenAiParametersSchema() {
        List<RegisteredToolMethod> tools = ToolSchemaGenerator.scan(SampleTools.class);
        assertThat(tools).hasSize(1);
        RegisteredToolMethod t = tools.get(0);
        assertThat(t.id()).isEqualTo("list_finance_messages");
        assertThat(t.outputSummaryKind()).isEqualTo("finance-list");
        Map<String, Object> schema = t.parametersSchema();
        assertThat(schema.get("type")).isEqualTo("object");
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        assertThat(props).containsKey("status");
    }
}
```

- [ ] **Step 2: 运行单测确认 FAIL**

```bash
mvn -pl common/sunshine-tool-sdk test -Dtest=ToolSchemaGeneratorTest -q
```

Expected: FAIL（类不存在）

- [ ] **Step 3: 实现注解**

`SunshineTool.java`：

```java
package com.sunshine.tools.sdk.annotation;

import java.lang.annotation.*;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface SunshineTool {
    String id();
    String displayName();
    String description() default "";
    String sideEffect() default "read";
    String timelinePhase() default "tool";
    String outputSummaryKind() default "truncate";
}
```

`ToolParam.java`：

```java
package com.sunshine.tools.sdk.annotation;

import java.lang.annotation.*;

@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface ToolParam {
    String value();
    String description() default "";
    boolean required() default true;
}
```

- [ ] **Step 4: 实现 `ToolSchemaGenerator` + `RegisteredToolMethod` record**

核心逻辑：反射扫描 `@SunshineTool` 方法；每个 `@ToolParam` 生成 `properties.{name}.type=string`；`required` 数组写入 schema。

`RegisteredToolMethod` record 字段：`id, displayName, description, sideEffect, timelinePhase, outputSummaryKind, parametersSchema, Method, targetBean`。

- [ ] **Step 5: 单测 PASS**

```bash
mvn -pl common/sunshine-tool-sdk test -Dtest=ToolSchemaGeneratorTest -q
```

Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add common/sunshine-tool-sdk/
git commit -m "feat(tool-sdk): add SunshineTool annotations and schema generator"
```

---

## Task T3: SDK HTTP 端点 + AutoConfiguration

**Files:**
- Create: `common/sunshine-tool-sdk/src/main/java/com/sunshine/tools/sdk/registry/SunshineToolRegistry.java`
- Create: `common/sunshine-tool-sdk/src/main/java/com/sunshine/tools/sdk/web/SunshineToolController.java`
- Create: `common/sunshine-tool-sdk/src/main/java/com/sunshine/tools/sdk/dto/SdkToolCatalogResponse.java`
- Create: `common/sunshine-tool-sdk/src/main/java/com/sunshine/tools/sdk/dto/SdkToolInvokeResponse.java`
- Create: `common/sunshine-tool-sdk/src/main/java/com/sunshine/tools/sdk/config/SunshineToolProperties.java`
- Modify: `common/sunshine-tool-sdk/src/main/java/com/sunshine/tools/sdk/autoconfigure/SunshineToolAutoConfiguration.java`
- Test: `common/sunshine-tool-sdk/src/test/java/com/sunshine/tools/sdk/web/SunshineToolControllerTest.java`

- [ ] **Step 1: 写 Controller 单测（MockMvc）**

验证 `GET /sunshine/tools/catalog` 返回 `appId` + `tools[]`；`POST /sunshine/tools/invoke/list_finance_messages` 返回 `{ok:true,result:"..."}`。

- [ ] **Step 2: 实现 `SunshineToolRegistry`**

- `@PostConstruct` 扫描 Spring 容器内带 `@SunshineTool` 方法的 Bean
- `invoke(toolId, Map<String,String>)`：参数按方法签名注入（均为 String），异常包装 `{ok:false,error}`
- `catalog()`：组装 `SdkToolCatalogResponse`

`appId` 来自 `SunshineToolProperties.appId`，默认 `${spring.application.name}`。

- [ ] **Step 3: 实现 Controller**

```java
@RestController
@RequestMapping("/sunshine/tools")
public class SunshineToolController {
    @GetMapping("/catalog")
    public SdkToolCatalogResponse catalog() { ... }
    @PostMapping("/invoke/{toolId}")
    public SdkToolInvokeResponse invoke(@PathVariable String toolId, @RequestBody Map<String, String> params) { ... }
    @GetMapping("/health")
    public Map<String, String> health() { return Map.of("status", "UP"); }
}
```

- [ ] **Step 4: AutoConfiguration**

```java
@AutoConfiguration
@EnableConfigurationProperties(SunshineToolProperties.class)
@ConditionalOnProperty(name = "sunshine.tools.enabled", havingValue = "true", matchIfMissing = true)
public class SunshineToolAutoConfiguration {
    @Bean
    SunshineToolRegistry sunshineToolRegistry(ApplicationContext ctx, SunshineToolProperties props) { ... }
    @Bean
    SunshineToolController sunshineToolController(SunshineToolRegistry registry) { ... }
}
```

- [ ] **Step 5: 单测 PASS + 编译**

```bash
mvn -pl common/sunshine-tool-sdk test -q
```

- [ ] **Step 6: Commit**

```bash
git commit -m "feat(tool-sdk): expose catalog/invoke/health endpoints"
```

---

## Task T4: finance-service 接入 SDK

**Files:**
- Modify: `finance-service/pom.xml`
- Create: `finance-service/src/main/java/com/sunshine/finance/tools/FinanceSunshineTools.java`
- Modify: `finance-service/src/main/resources/application.yml`
- Test: `finance-service/src/test/java/com/sunshine/finance/tools/FinanceSunshineToolsTest.java`

- [ ] **Step 1: pom 依赖**

```xml
<dependency>
    <groupId>com.sunshine</groupId>
    <artifactId>sunshine-tool-sdk</artifactId>
    <version>${project.version}</version>
</dependency>
```

- [ ] **Step 2: Nacos metadata**

`application.yml` 追加：

```yaml
spring:
  cloud:
    nacos:
      discovery:
        metadata:
          sunshine.tool-app: "true"
          sunshine.tool-app-id: "sunshine-finance"
sunshine:
  tools:
    enabled: true
    app-id: sunshine-finance
```

- [ ] **Step 3: 实现 `FinanceSunshineTools`**

三个方法 id 必须与旧 Handler 一致：`list_finance_messages`、`get_finance_message_detail`、`summarize_finance_by_status`。

格式化文本逻辑从 `tool-manager/.../FinanceServiceClient.java` **复制迁入**（调 `FinanceMessageService`，不再 HTTP 自环）。

- [ ] **Step 4: 单测**

Mock `FinanceMessageService`，断言 invoke 路径返回非空字符串。

- [ ] **Step 5: 编译**

```bash
mvn -pl finance-service -am test -q
```

- [ ] **Step 6: Commit**

```bash
git commit -m "feat(finance): expose finance tools via sunshine-tool-sdk"
```

---

## Task T5: oa-service 接入 SDK

**Files:**
- Modify: `oa-service/pom.xml`
- Create: `oa-service/src/main/java/com/sunshine/oa/tools/OaSunshineTools.java`
- Modify: `oa-service/src/main/resources/application.yml`
- Test: `oa-service/src/test/java/com/sunshine/oa/tools/OaSunshineToolsTest.java`

- [ ] **Step 1–2:** 同 T4（metadata `sunshine-oa`）

- [ ] **Step 3: 实现 `OaSunshineTools`**

- `list_oa_tasks`：格式化逻辑从 `OaServiceClient` 迁入，调 `OaTaskService`
- `approve_oa_task`：`sideEffect=write`；返回 `"已审批待办 {taskId}（模拟写操作）"`（HITL 验收保留）

- [ ] **Step 4–6:** 编译测试 + commit

```bash
mvn -pl oa-service -am test -q
git commit -m "feat(oa): expose oa tools via sunshine-tool-sdk"
```

---

## Task T6: tool-manager JPA 依赖与 Entity

**Files:**
- Modify: `tool-manager/pom.xml`
- Create: `tool-manager/src/main/java/com/sunshine/tool/entity/*.java`
- Create: `tool-manager/src/main/java/com/sunshine/tool/repo/*.java`
- Modify: `docs/nacos/sunshine-tool-manager.yaml`

- [ ] **Step 1: pom 增加 JPA + MySQL + Redis**

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-jpa</artifactId>
</dependency>
<dependency>
    <groupId>com.mysql</groupId>
    <artifactId>mysql-connector-j</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
```

- [ ] **Step 2: Nacos 数据源**

`sunshine-tool-manager.yaml` 追加（对齐 skill-manager 模式）：

```yaml
spring:
  datasource:
    url: jdbc:mysql://ecs4c16g:3306/sunshine_tool?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai
    username: root
    password: ${MYSQL_PASSWORD:root}
  jpa:
    hibernate:
      ddl-auto: none
    open-in-view: false
  data:
    redis:
      host: ecs4c16g
      port: 6379
      password: redis123
```

- [ ] **Step 3: 创建 Entity + Repository**

包路径 `com.sunshine.tool.entity` / `com.sunshine.tool.repo`，字段与 §6 表一一对应。

`ToolDefinitionEntity.schemaJson` 用 `@JdbcTypeCode(SqlTypes.JSON)` 或 `columnDefinition = "json"`。

- [ ] **Step 4: 编译**

```bash
mvn -pl tool-manager -am compile -q
```

- [ ] **Step 5: Commit**

```bash
git commit -m "feat(tool-manager): add JPA entities for tool catalog"
```

---

## Task T7: DB Catalog 服务（替换 Bean ToolRegistry）

**Files:**
- Create: `tool-manager/src/main/java/com/sunshine/tool/service/DbToolCatalogService.java`
- Modify: `tool-manager/src/main/java/com/sunshine/tool/controller/ToolCatalogController.java`
- Modify: `tool-manager/src/main/java/com/sunshine/tool/dto/ToolCatalogEntry.java`（如需增 `source` 字段）
- Test: `tool-manager/src/test/java/com/sunshine/tool/service/DbToolCatalogServiceTest.java`

- [ ] **Step 1: 写单测（@DataJpaTest）**

插入 `tool_definition` 行，`listCatalog(tenantId="default", enabledOnly=true)` 返回 1 条。

- [ ] **Step 2: 实现 `DbToolCatalogService`**

```java
public List<ToolCatalogEntry> listCatalog(String tenantId, boolean enabledOnly) {
    // tenantId 匹配 entity.tenantId 或 'default'
    // 映射 ToolCatalogEntry(id, displayName, description, kind, timelinePhase, outputSummaryKind, parameters, sideEffect)
}
```

- [ ] **Step 3: 改 `ToolCatalogController`**

```java
@GetMapping("/api/tools/catalog")
public R<List<ToolCatalogEntry>> catalog(
        @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId,
        @RequestParam(defaultValue = "false") boolean enabledOnly) {
    return R.ok(dbToolCatalogService.listCatalog(tenantId, enabledOnly));
}
```

- [ ] **Step 4: 单测 PASS**

- [ ] **Step 5: Commit**

```bash
git commit -m "feat(tool-manager): serve catalog from MySQL"
```

---

## Task T8: 删除旧 Handler / Client

**Files:**
- Delete: `tool-manager/src/main/java/com/sunshine/tool/client/FinanceServiceClient.java`
- Delete: `tool-manager/src/main/java/com/sunshine/tool/client/OaServiceClient.java`
- Delete: `tool-manager/src/main/java/com/sunshine/tool/tool/Finance*.java`、`Oa*.java`、`ApproveOaTaskToolHandler.java`、`SearchKnowledgeToolHandler.java`
- Modify: `tool-manager/src/main/java/com/sunshine/tool/registry/ToolRegistry.java`
- Modify: `tool-manager/src/main/java/com/sunshine/tool/service/ToolInvokeService.java`
- Modify: `docs/nacos/sunshine-tool-manager.yaml`（删 finance/oa base-url）
- Test: 更新/删除依赖旧 Handler 的测试

- [ ] **Step 1: 删除文件**

- [ ] **Step 2: 重构 `ToolRegistry`**

不再注入 `List<ToolHandler>`；`invoke` 委托给后续 `InvokeRouter`（T10 前可临时抛 `BizException(TOOL_NOT_FOUND)`）。

- [ ] **Step 3: 修复测试**

删除 `ToolRegistryCatalogTest` 中对 finance handler 的断言；保留 summarize 相关测试。

- [ ] **Step 4: 编译**

```bash
mvn -pl tool-manager -am test -q
```

Expected: 无 finance/oa import 残留（`rg FinanceServiceClient tool-manager` 空）

- [ ] **Step 5: Commit**

```bash
git commit -m "refactor(tool-manager): remove hardcoded finance/oa tool handlers"
```

---

## Task T9: SdkDiscoveryPuller

**Files:**
- Create: `tool-manager/src/main/java/com/sunshine/tool/sdk/SdkDiscoveryPuller.java`
- Create: `tool-manager/src/main/java/com/sunshine/tool/sdk/SdkCatalogClient.java`
- Create: `tool-manager/src/main/java/com/sunshine/tool/sdk/SdkCatalogUpsertService.java`
- Create: `tool-manager/src/main/java/com/sunshine/tool/config/ToolIntegrationProperties.java`
- Test: `tool-manager/src/test/java/com/sunshine/tool/sdk/SdkCatalogUpsertServiceTest.java`

- [ ] **Step 1: 写 upsert 单测**

给定 SDK catalog JSON，upsert 后 `tool_definition` 行：`id=list_finance_messages`、`source=sdk`、`schema_hash` 非空；二次 upsert 相同 hash 不覆盖 `metadata_edited=1` 的 displayName。

- [ ] **Step 2: 实现 `SdkCatalogClient`**

WebClient `GET http://{host}:{port}/sunshine/tools/catalog`。

- [ ] **Step 3: 实现 `SdkDiscoveryPuller`**

- 读 Nacos `DiscoveryClient.getInstances(nacosService)`，过滤 metadata `sunshine.tool-app=true`
- 无实例 → `sdk_application.status=offline`
- 有实例 → Pull catalog → `SdkCatalogUpsertService.upsert(appId, catalog)`
- 新 app 自动 insert `sdk_application`
- `@Scheduled(fixedDelayString = "${tool.sdk.pull-interval-seconds:60}000")`

- [ ] **Step 4: 手动联调**

启动 finance-service + tool-manager，等 60s 或调 Admin sync（T14），查 DB：

```sql
SELECT id, source, enabled FROM tool_definition WHERE source='sdk';
```

Expected: 5 行（finance 3 + oa 2，首次 enabled=0）

- [ ] **Step 5: Commit**

```bash
git commit -m "feat(tool-manager): pull SDK tool catalog from Nacos apps"
```

---

## Task T10: InvokeRouter（SDK 分支）

**Files:**
- Create: `tool-manager/src/main/java/com/sunshine/tool/invoke/InvokeRouter.java`
- Create: `tool-manager/src/main/java/com/sunshine/tool/invoke/SdkInvokeExecutor.java`
- Modify: `tool-manager/src/main/java/com/sunshine/tool/service/ToolInvokeService.java`
- Modify: `tool-manager/src/main/java/com/sunshine/tool/controller/ToolInvokeController.java`
- Test: `tool-manager/src/test/java/com/sunshine/tool/invoke/SdkInvokeExecutorTest.java`

- [ ] **Step 1: 写单测**

Mock WebClient：enabled 工具 invoke 转发到 `POST /sunshine/tools/invoke/{externalName}`。

- [ ] **Step 2: 实现 `SdkInvokeExecutor`**

- Nacos LB 选实例
- 超时 `tool.sdk.invoke-timeout-seconds`（默认 30）
- 解析 `{ok,result,error}`

- [ ] **Step 3: 实现 `InvokeRouter`**

```java
public String invoke(String toolId, Map<String,String> params, String tenantId) {
    ToolDefinitionEntity tool = requireEnabled(toolId, tenantId);
    return switch (tool.getSource()) {
        case "sdk" -> sdkInvokeExecutor.invoke(tool, params);
        case "mcp" -> mcpInvokeExecutor.invoke(tool, params); // T13 实现
        default -> throw new BizException(ToolErrorCode.UNSUPPORTED_SOURCE);
    };
}
```

- [ ] **Step 4: `ToolInvokeController` 读 `x-tenant-id` header**

- [ ] **Step 5: 单测 + 编译 PASS**

- [ ] **Step 6: Commit**

```bash
git commit -m "feat(tool-manager): route tool invoke to SDK apps via Nacos"
```

---

## Task T11: MCP JSON-RPC 传输层

**Files:**
- Create: `tool-manager/src/main/java/com/sunshine/tool/mcp/McpJsonRpcClient.java`
- Create: `tool-manager/src/main/java/com/sunshine/tool/mcp/McpStdioTransport.java`
- Create: `tool-manager/src/main/java/com/sunshine/tool/mcp/McpSseTransport.java`
- Test: `tool-manager/src/test/java/com/sunshine/tool/mcp/McpJsonRpcClientTest.java`

- [ ] **Step 1: 写 stdio 集成测试（可选 @Disabled 无 npx 环境）**

对 `npx -y @modelcontextprotocol/server-filesystem /tmp` 发送 `initialize` + `tools/list`，断言返回 tools 数组。

- [ ] **Step 2: 实现 JSON-RPC 行协议**

- stdio：ProcessBuilder 启动；stdin/stdout 按行读写 JSON
- 方法：`initialize`、`tools/list`、`tools/call`
- SSE：`McpSseTransport` 用 WebClient POST endpoint（transport=sse 时）

- [ ] **Step 3: 单测 PASS（至少 JSON 序列化/解析单测）**

- [ ] **Step 4: Commit**

```bash
git commit -m "feat(tool-manager): add MCP JSON-RPC stdio/sse transport"
```

---

## Task T12: MCP Sync + import/export

**Files:**
- Create: `tool-manager/src/main/java/com/sunshine/tool/mcp/McpSyncService.java`
- Create: `tool-manager/src/main/java/com/sunshine/tool/mcp/McpImportService.java`
- Create: `tool-manager/src/main/java/com/sunshine/tool/admin/McpServerAdminService.java`
- Test: `tool-manager/src/test/java/com/sunshine/tool/mcp/McpImportServiceTest.java`

- [ ] **Step 1: import 单测**

输入 Cursor 格式 mcp.json：

```json
{"mcpServers":{"demo":{"command":"echo","args":["mcp"]}}}
```

解析为 `mcp_server` 实体（transport=stdio）。

- [ ] **Step 2: `McpSyncService.probe(serverId)`**

- 连接 MCP → `tools/list`
- upsert `tool_definition`：`id=mcp__{serverId}__{name}`（`ToolIds.mcp()`）、`kind=mcp`、`source=mcp`
- MCP schema **始终刷新**；`metadata_edited=1` 仍保留 displayName/description
- 更新 `probe_status` / `probe_error`

- [ ] **Step 3: `@Scheduled` refresh** enabled server（`tool.mcp.refresh-interval-seconds`）

- [ ] **Step 4: Commit**

```bash
git commit -m "feat(tool-manager): MCP probe sync and mcp.json import"
```

---

## Task T13: InvokeRouter（MCP 分支）

**Files:**
- Create: `tool-manager/src/main/java/com/sunshine/tool/invoke/McpInvokeExecutor.java`
- Modify: `tool-manager/src/main/java/com/sunshine/tool/invoke/InvokeRouter.java`
- Test: `tool-manager/src/test/java/com/sunshine/tool/invoke/McpInvokeExecutorTest.java`

- [ ] **Step 1: 实现 `McpInvokeExecutor`**

`McpJsonRpcClient.toolsCall(serverId, externalName, params)` → 文本结果。

- [ ] **Step 2: InvokeRouter 接通 mcp 分支**

- [ ] **Step 3: 单测 PASS**

- [ ] **Step 4: Commit**

```bash
git commit -m "feat(tool-manager): route invoke to MCP servers"
```

---

## Task T14: Admin API

**Files:**
- Create: `tool-manager/src/main/java/com/sunshine/tool/admin/ToolsAdminController.java`
- Create: `tool-manager/src/main/java/com/sunshine/tool/admin/ToolSetAdminService.java`
- Create: `tool-manager/src/main/java/com/sunshine/tool/admin/dto/*.java`

- [ ] **Step 1: SDK Admin**

```
GET  /api/admin/tools/sdk-applications
POST /api/admin/tools/sdk-applications/{id}/sync  → 触发 SdkDiscoveryPuller.syncOne
```

- [ ] **Step 2: MCP Admin**

```
GET/POST /api/admin/mcp/servers
POST     /api/admin/mcp/servers/import
GET      /api/admin/mcp/servers/export
POST     /api/admin/mcp/servers/{id}/probe
```

- [ ] **Step 3: Tool + ToolSet Admin**

```
PATCH /api/admin/tools/{toolId}          body: {enabled, displayName, description, sideEffect?}
GET   /api/admin/tools/sets/react-default?tenantId=
PUT   /api/admin/tools/sets/react-default body: {toolIds: [...]}
```

- [ ] **Step 4: curl 手测**

```bash
curl -s http://localhost:8210/api/admin/tools/sdk-applications | jq .
curl -X PATCH http://localhost:8210/api/admin/tools/list_finance_messages \
  -H 'Content-Type: application/json' -d '{"enabled":true}'
```

- [ ] **Step 5: Commit**

```bash
git commit -m "feat(tool-manager): add admin APIs for SDK MCP and tool sets"
```

---

## Task T15: Redis catalog-changed 事件

**Files:**
- Create: `tool-manager/src/main/java/com/sunshine/tool/event/ToolCatalogChangePublisher.java`
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/catalog/ToolCatalogRefreshListener.java`
- Modify: Admin / Pull / MCP sync 写路径调用 publisher

- [ ] **Step 1: Publisher**

```java
public void publish(String tenantId) {
    redis.convertAndSend("tool-catalog-changed", tenantId == null ? "default" : tenantId);
}
```

- [ ] **Step 2: orchestrator Listener**

```java
@RedisListener(topic = "tool-catalog-changed")
public void onMessage(String tenantId) {
    toolCatalogService.refresh();
    toolSetResolver.evictCache(tenantId);
}
```

- [ ] **Step 3: orchestrator 增加 `spring-boot-starter-data-redis`（若未有）**

- [ ] **Step 4: 兜底轮询**

`ToolCatalogRefreshScheduler`：`@Scheduled(fixedDelay = 30000)` → `toolCatalogService.refresh()`

- [ ] **Step 5: Commit**

```bash
git commit -m "feat: redis tool-catalog-changed for hot reload"
```

---

## Task T16: orchestrator ToolSetResolver

**Files:**
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/catalog/ToolSetResolver.java`
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/client/ToolSetClient.java`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/agent/DynamicToolkitFactory.java`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/config/AgentExecutionProperties.java`
- Test: `orchestrator/src/test/java/com/sunshine/orchestrator/catalog/ToolSetResolverTest.java`

- [ ] **Step 1: 写单测**

Mock ToolSetClient：global set = [A,B]；catalog enabled = [A,B,C] → resolve = [A,B]。

- [ ] **Step 2: 实现 `ToolSetClient`**

```
GET http://tool-manager:8210/api/admin/tools/sets/react-default?tenantId=
```

返回 `{toolIds: [...]}`。

- [ ] **Step 3: 实现 `ToolSetResolver`**

```java
public List<String> resolveReactTools(String tenantId) {
    List<String> setIds = toolSetClient.fetchReactDefault(tenantId);
    Set<String> pool = toolCatalogService.allEntries().stream()
        .filter(e -> /* enabled 由 catalog API enabledOnly 已过滤 */ true)
        .map(ToolCatalogEntry::id)
        .collect(toSet());
    return setIds.stream().filter(pool::contains).toList();
}
```

- [ ] **Step 4: 改 `DynamicToolkitFactory.build()`**

```java
public Toolkit build() {
    String tenantId = TenantContext.current(); // 或从现有租户上下文获取，默认 default
    return buildFromWhitelist(toolSetResolver.resolveReactTools(tenantId));
}
```

- [ ] **Step 5: 删 `AgentExecutionProperties.React.tools` 默认值与 Nacos 配置**

`sunshine-orchestrator.yaml` 删除 `agent.execution.react.tools` 块。

- [ ] **Step 6: 更新 `DynamicToolkitFactoryTest`**

- [ ] **Step 7: Commit**

```bash
git commit -m "feat(orchestrator): resolve ReAct tools from MySQL tool sets"
```

---

## Task T17: Catalog 热刷新 + tenant 参数

**Files:**
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/client/ToolCatalogClient.java`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/catalog/ToolCatalogService.java`

- [ ] **Step 1: CatalogClient 带 tenant + enabledOnly**

```java
.uri(uriBuilder -> uriBuilder.path("/api/tools/catalog")
    .queryParam("enabledOnly", true)
    .build())
.header("x-tenant-id", tenantId)
```

- [ ] **Step 2: refresh 使用当前 tenant 或合并 default tenant catalog**

Phase 1 简化：**refresh 拉 `tenantId=default&enabledOnly=false`** 全量元数据（invoke 仍校验 enabled）；或 orchestrator 缓存全量、ToolSet 做 enabled 交集。与 spec §10 一致：`resolveReactTools` 做 **set ∩ enabled pool**。

- [ ] **Step 3: Commit**

```bash
git commit -m "feat(orchestrator): refresh tool catalog with tenant header"
```

---

## Task T18: GenericRemoteToolFactory 支持 kind=mcp

**Files:**
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/agent/remote/GenericRemoteToolFactory.java`
- Modify: `orchestrator/src/test/java/com/sunshine/orchestrator/agent/DynamicToolkitFactoryTest.java`

- [ ] **Step 1: 扩展 filter**

```java
.filter(entry -> "remote".equals(entry.kind()) || "mcp".equals(entry.kind()))
```

- [ ] **Step 2: 单测增加 mcp kind 工具注册**

- [ ] **Step 3: Commit**

```bash
git commit -m "feat(orchestrator): register mcp kind tools in DynamicToolkit"
```

---

## Task T19: BFF 透传 Admin API

**Files:**
- Create: `bff/src/main/java/com/sunshine/bff/client/ToolManagerAdminClient.java`
- Create: `bff/src/main/java/com/sunshine/bff/controller/ToolsAdminController.java`
- Modify: `docs/nacos/sunshine-bff.yaml`（如需路由）

- [ ] **Step 1: WebClient 透传**（对称 `SkillManagerClient`）

映射 spec §12 全部 Admin 路径。

- [ ] **Step 2: Controller**

```java
@RestController
@RequiredArgsConstructor
public class ToolsAdminController {
    @GetMapping("/api/admin/tools/sdk-applications")
    public Mono<Map<String, Object>> listSdkApps() { ... }
    // ... 其余透传
}
```

- [ ] **Step 3: curl 经 BFF 验证**

```bash
curl -s http://localhost:8080/api/admin/tools/sdk-applications -H 'x-user-id: demo'
```

- [ ] **Step 4: Commit**

```bash
git commit -m "feat(bff): proxy tool-manager admin APIs"
```

---

## Task T20: sunshine-ui `/tools` 管理页

**Files:**
- Create: `sunshine-ui/src/api/tools.ts`
- Create: `sunshine-ui/src/views/ToolsView.vue`
- Modify: `sunshine-ui/src/router/index.ts`
- Modify: `sunshine-ui/src/layouts/MainLayout.vue`

- [ ] **Step 1: API 封装 `tools.ts`**

类型：`SdkApplication`、`McpServer`、`ToolEntry`、`ToolSetConfig`；函数对齐 BFF 路径。

- [ ] **Step 2: `ToolsView.vue` 骨架**

- 顶栏 Tab：`SDK 应用 | MCP 服务 | 平台工具（disabled） | 工具集配置`
- 左列表 + 右详情（复制 `ExpertsView.vue` 布局与 `--sun-black` 样式）
- SDK Tab：应用列表、同步按钮、工具表（enabled 开关、描述编辑弹窗、schema 只读 JSON 预览）
- MCP Tab：Server 表单、probe、import mcp.json（文件上传）、工具列表
- 工具集 Tab：租户 selector + 勾选 enabled 工具 + 保存

- [ ] **Step 3: 路由与侧栏**

`router/index.ts`：`{ path: '/tools', component: ToolsView }`

`MainLayout.vue` 侧栏增加「工具」入口（在 Skills 与 Experts 之间）。

- [ ] **Step 4: 类型检查**

```bash
cd sunshine-ui && npx vue-tsc -b
```

Expected: 无 error

- [ ] **Step 5: Commit**

```bash
git commit -m "feat(ui): add /tools management page for SDK MCP and tool sets"
```

---

## Task T21: Live 验收脚本

**Files:**
- Create: `scripts/verify_tool_integration_live.py`

- [ ] **Step 1: 脚本骨架**（对称 `verify_hitl_live.py`）

```python
#!/usr/bin/env python3
"""4.8 工具集成 Live：SDK 发现 + invoke + MCP probe + 工具集 + HITL + 动态生效。"""
```

子套件：

| 参数 | 覆盖 |
|------|------|
| `--suite sdk` | G1 G2 G3 |
| `--suite mcp` | G4 G5 |
| `--suite toolset` | G6 G7 |
| `--suite hitl` | G8（approve_oa_task） |
| `--suite all` | G1–G10 |

- [ ] **Step 2: SDK 流程**

1. 确保 finance + oa + tool-manager 运行
2. `POST /api/admin/tools/sdk-applications/sunshine-finance/sync`
3. `PATCH` enable 5 工具
4. `GET /api/tools/catalog?enabledOnly=true` 断言 5 id
5. Chat ReAct SSE 问「有多少待审批财务消息」→ 步骤含 `list_finance_messages`

- [ ] **Step 3: HITL 流程**

复用 `verify_hitl_live.py` 断言 `approve_oa_task` confirmation 事件。

- [ ] **Step 4: MCP 流程（可选 npx 环境）**

import demo mcp.json → probe → catalog 含 `mcp.*` 工具。

- [ ] **Step 5: 运行**

```bash
python3 scripts/verify_tool_integration_live.py --suite all
```

Expected: 全部 `[OK]`

- [ ] **Step 6: Commit**

```bash
git commit -m "test: add verify_tool_integration_live for SDK MCP tool sets"
```

---

## Task T22: 文档与运维索引

**Files:**
- Modify: `docs/implementation-plan.md`
- Modify: `CLAUDE.md`
- Modify: `docs/superpowers/specs/phase4-platformization-design.md`（§4.8 指向新 spec）
- Modify: `scripts/sync_nacos.py` / `scripts/start.py`（确认 finance/oa/tool-manager 启动顺序）

- [ ] **Step 1: implementation-plan 增 4.8.x 行**

指向 `2026-07-09-tool-integration-design.md` + live 脚本。

- [ ] **Step 2: CLAUDE.md 运维表增**

```
| verify_tool_integration_live.py | 4.8 SDK+MCP 工具集成 Live |
```

「新工具」扩展指南改为 SDK 路径。

- [ ] **Step 3: sync Nacos + 全链路编译**

```bash
python3 scripts/sync_nacos.py
mvn -pl tool-manager,orchestrator,finance-service,oa-service,bff -am test -q
```

- [ ] **Step 4: Commit**

```bash
git commit -m "docs: index tool integration plan and live verification"
```

---

## Spec 覆盖自检

| Spec § | 任务 |
|--------|------|
| §4 SDK 模块 | T1–T3 |
| §5 Demo 迁移 | T4–T5, T8 |
| §6 MySQL | T0, T6 |
| §7 SDK Pull | T9 |
| §8 MCP | T11–T13 |
| §9 Invoke | T10, T13 |
| §10 工具集 | T14, T16 |
| §11 动态生效 | T15, T17 |
| §12 /tools UI | T19–T20 |
| §14 HITL/审计 | T5 approve_oa_task, T21 hitl suite |
| §16 检查门 G1–G10 | T21 |
| D9 特殊工具 | T16 buildFromWhitelist 保留 RagTool/manage_tasks |
| D10 Phase 2 | 不在本计划 |

## 占位符扫描

- 无 TBD / TODO / implement later
- 各 Task 含具体路径与命令

---

**Plan complete and saved to `docs/superpowers/plans/2026-07-09-tool-integration.md`. Two execution options:**

**1. Subagent-Driven (recommended)** — 每个 Task 派发独立 subagent，Task 间人工 review，迭代快

**2. Inline Execution** — 本会话按 Task 顺序执行，每 2–3 个 Task 设检查点

**Which approach?**
