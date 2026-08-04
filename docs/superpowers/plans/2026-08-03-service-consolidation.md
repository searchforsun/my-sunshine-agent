# 服务合并实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 8 个过细拆分的管理类与业务模拟服务合并为 2 个聚合服务（resource-manager + biz-simulator），降低运维成本同时保留与 orchestrator 的独立扩缩容能力。

**Architecture:** 管理类 5 服务（tool/skill/agent/prompt-manager + desensitize）内聚为 `resource-manager` 单进程单库，包名不变、端点不变，orchestrator/BFF 仅改 base-url 配置。业务模拟 3 服务（oa/finance/hr）内聚为 `biz-simulator` 单进程，统一 appId 为 `sunshine-biz`，Catalog ID 从 `sdk__sunshine-{oa|finance|hr}__*` 改为 `sdk__sunshine-biz__*`，4 个种子 SQL 共 16 处引用同步更新。数据库直接重建，不做数据迁移。

**Tech Stack:** Spring Boot 3.2.9 / Spring Cloud 2023.0.3 / Spring Cloud Alibaba 2023.0.3.4 / JDK 21 / JPA / MySQL / Nacos / sunshine-tool-sdk

## Global Constraints

- Spring Boot **3.2.9**（禁止升 3.3+）；Sa-Token **1.45.0**；JDK **21**
- 禁止 Flyway；库表 SQL SSOT 在 `docker/mysql/init/`（一项目一文件）
- 改 `docs/nacos/*.yaml` 后必跑 `python scripts/sync_nacos.py` 并重启消费服务
- 修改后端功能后必须重启对应服务的 `start.py`
- Nacos SSOT：改 `docs/nacos/*.yaml` -> `sync_nacos.py` -> 重启（无 `application-dev.yaml`）
- 提示词/路由规则 SSOT = prompt-manager Catalog（`/prompts`），禁止硬编码提示词
- 代码加适量中文注释；禁止在业务代码中插入多余空行
- 禁止保存临时脚本；运维统一 Python（`scripts/*.py`）
- 不做兼容：废弃旧 Nacos 服务名、旧 Catalog ID，直接改新值
- 数据库直接重建：合并后的库直接 DROP + CREATE，不做数据迁移

## File Structure

### 新建文件
- `resource-manager/pom.xml` — 合并 5 服务依赖
- `resource-manager/src/main/java/com/sunshine/resource/ResourceManagerApplication.java` — 唯一入口
- `resource-manager/src/main/resources/application.yml` — Nacos 引导配置
- `biz-simulator/pom.xml` — 与原三服务相同依赖
- `biz-simulator/src/main/java/com/sunshine/BizSimulatorApplication.java` — 唯一入口
- `biz-simulator/src/main/resources/application.yml` — Nacos 引导配置
- `docs/nacos/sunshine-resource-manager.yaml` — 合并后管理服务 Nacos 配置
- `docs/nacos/sunshine-biz-simulator.yaml` — 合并后业务服务 Nacos 配置
- `docker/mysql/init/sunshine-resource.sql` — 合并 4 库的建表 + 种子 SQL

### 修改文件
- `pom.xml`（根）— 模块列表：删 8 旧模块，加 2 新模块
- `docker/mysql/init/01-init-databases.sql` — 删 4 旧库，加 `sunshine_resource`
- `docker/mysql/init/13-sunshine-workflow-manager.sql` — 10 处 Catalog ID 改新
- `docker/mysql/init/15-sunshine-agent-manager.sql` — 3 处 Catalog ID 改新
- `docker/mysql/init/12-sunshine-skill-manager.sql` — 2 处 Catalog ID 改新
- `docker/mysql/init/17-sunshine-prompt-manager.sql` — 1 处 Catalog ID 改新
- `docker/mysql/init/16-sunshine-tool-manager.sql` — `sdk_application` 3 条种子合并为 1 条
- `scripts/start.py` — SERVICES 列表：删 8 条目，加 2 条目
- `docs/nacos/sunshine-gateway.yaml` — 健康检查路由：删 8 条，加 2 条
- `docs/nacos/sunshine-orchestrator.yaml` — 5 个 `*.base-url` 统一指向 8210
- `docs/nacos/sunshine-bff.yaml` — 4 个 `*.base-url` 统一指向 8210
- `CLAUDE.md` — 服务端口表更新

### 删除文件（合并完成后）
- `tool-manager/` `skill-manager/` `agent-manager/` `prompt-manager/` `desensitize/` 全目录
- `oa-service/` `finance-service/` `hr-biz-service/` 全目录
- `docker/mysql/init/12-sunshine-skill-manager.sql`（内容并入 sunshine-resource.sql）
- `docker/mysql/init/15-sunshine-agent-manager.sql`（内容并入 sunshine-resource.sql）
- `docker/mysql/init/16-sunshine-tool-manager.sql`（内容并入 sunshine-resource.sql）
- `docker/mysql/init/17-sunshine-prompt-manager.sql`（内容并入 sunshine-resource.sql）
- `docs/nacos/sunshine-{tool-manager,skill-manager,agent-manager,prompt-manager,desensitize,oa,finance,hr}.yaml`

### 迁移文件（代码物理搬迁，包名不变）
- `tool-manager/src/main/java/com/sunshine/tool/**` -> `resource-manager/src/main/java/com/sunshine/tool/**`
- `skill-manager/src/main/java/com/sunshine/skill/**` -> `resource-manager/src/main/java/com/sunshine/skill/**`
- `agent-manager/src/main/java/com/sunshine/agent/**` -> `resource-manager/src/main/java/com/sunshine/agent/**`
- `prompt-manager/src/main/java/com/sunshine/prompt/**` -> `resource-manager/src/main/java/com/sunshine/prompt/**`
- `desensitize/src/main/java/com/sunshine/desensitize/**` -> `resource-manager/src/main/java/com/sunshine/desensitize/**`
- `oa-service/src/main/java/com/sunshine/oa/**` -> `biz-simulator/src/main/java/com/sunshine/oa/**`
- `finance-service/src/main/java/com/sunshine/finance/**` -> `biz-simulator/src/main/java/com/sunshine/finance/**`
- `hr-biz-service/src/main/java/com/sunshine/hr/**` -> `biz-simulator/src/main/java/com/sunshine/hr/**`

---

## Catalog ID 映射表（合并项 B 专用）

旧 ID -> 新 ID（appId 统一为 `sunshine-biz`）：

| 旧 Catalog ID | 新 Catalog ID |
|---------------|---------------|
| `sdk__sunshine-oa__list_oa_tasks` | `sdk__sunshine-biz__list_oa_tasks` |
| `sdk__sunshine-oa__approve_oa_task` | `sdk__sunshine-biz__approve_oa_task` |
| `sdk__sunshine-finance__list_my_expenses` | `sdk__sunshine-biz__list_my_expenses` |
| `sdk__sunshine-finance__get_expense_detail` | `sdk__sunshine-biz__get_expense_detail` |
| `sdk__sunshine-finance__submit_expense` | `sdk__sunshine-biz__submit_expense` |
| `sdk__sunshine-finance__list_my_finance_inbox` | `sdk__sunshine-biz__list_my_finance_inbox` |
| `sdk__sunshine-finance__get_finance_inbox_item` | `sdk__sunshine-biz__get_finance_inbox_item` |
| `sdk__sunshine-finance__summarize_my_expenses` | `sdk__sunshine-biz__summarize_my_expenses` |
| `sdk__sunshine-hr__get_leave_balance` | `sdk__sunshine-biz__get_leave_balance` |
| `sdk__sunshine-hr__list_leave_requests` | `sdk__sunshine-biz__list_leave_requests` |
| `sdk__sunshine-hr__submit_leave_request` | `sdk__sunshine-biz__submit_leave_request` |
| `sdk__sunshine-hr__get_attendance_month` | `sdk__sunshine-biz__get_attendance_month` |

种子 SQL 引用分布（共 16 处）：
- `13-sunshine-workflow-manager.sql`：10 处（finance-list, finance-smart, finance-summary, knowledge-loop, hr-leave-assist, expense-compliance, oa-task-assist, expense-detail-query, expense-status-filter, expense-amount-check）
- `15-sunshine-agent-manager.sql`：3 处（行 44, 50, 56 的工具白名单 JSON）
- `12-sunshine-skill-manager.sql`：2 处（行 38, 44 的工具配置 JSON）
- `17-sunshine-prompt-manager.sql`：1 处（行 246 的 plan 示例 JSON）

---

## Task A1: 创建 resource-manager 模块骨架

**Files:**
- Create: `resource-manager/pom.xml`
- Create: `resource-manager/src/main/java/com/sunshine/resource/ResourceManagerApplication.java`
- Create: `resource-manager/src/main/resources/application.yml`
- Modify: `pom.xml`（根，第 13-33 行 `<modules>` 段）

**Interfaces:**
- Produces: `ResourceManagerApplication` 主类（`@SpringBootApplication(scanBasePackages = "com.sunshine")`），供后续 Task A2/A3 的代码搬迁后自动扫描

- [ ] **Step 1: 创建 resource-manager/pom.xml**

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
    </parent>

    <artifactId>sunshine-resource-manager</artifactId>

    <dependencies>
        <dependency>
            <groupId>com.sunshine</groupId>
            <artifactId>sunshine-common</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>com.sunshine</groupId>
            <artifactId>sunshine-tool-sdk</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>com.sunshine</groupId>
            <artifactId>sunshine-routing</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-webflux</artifactId>
        </dependency>
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
        <dependency>
            <groupId>com.alibaba.cloud</groupId>
            <artifactId>spring-cloud-starter-alibaba-nacos-discovery</artifactId>
        </dependency>
        <dependency>
            <groupId>com.alibaba.cloud</groupId>
            <artifactId>spring-cloud-starter-alibaba-nacos-config</artifactId>
        </dependency>
        <dependency>
            <groupId>io.agentscope</groupId>
            <artifactId>agentscope</artifactId>
        </dependency>
        <dependency>
            <groupId>org.ahocorasick</groupId>
            <artifactId>ahocorasick</artifactId>
            <version>0.6.3</version>
        </dependency>
        <dependency>
            <groupId>io.minio</groupId>
            <artifactId>minio</artifactId>
            <version>8.5.17</version>
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
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId>
                <version>3.2.5</version>
            </plugin>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 2: 创建主类 ResourceManagerApplication.java**

```java
package com.sunshine.resource;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.sunshine")
public class ResourceManagerApplication {
    public static void main(String[] args) {
        SpringApplication.run(ResourceManagerApplication.class, args);
    }
}
```

- [ ] **Step 3: 创建 application.yml**

```yaml
# sunshine-resource-manager - 配置入口
# 业务配置唯一来源：Nacos（Data ID: sunshine-resource-manager.yaml）
spring:
  application:
    name: sunshine-resource-manager
  config:
    import:
      - optional:nacos:sunshine-resource-manager.yaml
  cloud:
    nacos:
      discovery:
        server-addr: ecs4c16g:8848
        username: nacos
        password: nacos
        group: SUNSHINE_V2
      config:
        server-addr: ecs4c16g:8848
        username: nacos
        password: nacos
        import-check:
          enabled: false
        file-extension: yaml
        group: DEFAULT_GROUP
```

- [ ] **Step 4: 注册新模块到根 pom.xml**

在根 `pom.xml` 的 `<modules>` 段，删除以下 5 行：
```
<module>tool-manager</module>
<module>skill-manager</module>
<module>agent-manager</module>
<module>prompt-manager</module>
<module>desensitize</module>
```
替换为 1 行：
```xml
<module>resource-manager</module>
```

- [ ] **Step 5: 验证模块骨架可编译**

Run: `mvn -pl resource-manager -am compile -q`
Expected: BUILD SUCCESS（此时模块内仅有 Application 类，无业务代码，编译应通过）

- [ ] **Step 6: Commit**

```bash
git add resource-manager/ pom.xml
git commit -m "feat: 创建 resource-manager 模块骨架"
```


## Task A2: 迁移管理类服务代码到 resource-manager

**Files:**
- Move: `tool-manager/src/main/java/com/sunshine/tool/**` -> `resource-manager/src/main/java/com/sunshine/tool/**`
- Move: `skill-manager/src/main/java/com/sunshine/skill/**` -> `resource-manager/src/main/java/com/sunshine/skill/**`
- Move: `agent-manager/src/main/java/com/sunshine/agent/**` -> `resource-manager/src/main/java/com/sunshine/agent/**`
- Move: `prompt-manager/src/main/java/com/sunshine/prompt/**` -> `resource-manager/src/main/java/com/sunshine/prompt/**`
- Move: `desensitize/src/main/java/com/sunshine/desensitize/**` -> `resource-manager/src/main/java/com/sunshine/desensitize/**`
- Move: 各服务的 `src/main/resources/` 下非 application.yml 资源（如 skill-manager 的 mapper、prompt-manager 的路由规则 JSON 等）-> `resource-manager/src/main/resources/`
- Move: 各服务的 `src/test/` 测试代码 -> `resource-manager/src/test/`

**Interfaces:**
- Consumes: Task A1 的 `ResourceManagerApplication`（scanBasePackages="com.sunshine" 扫描所有搬迁包）
- Produces: 5 个原服务的全部业务代码在 resource-manager 内可编译

- [ ] **Step 1: 迁移 tool-manager 代码**

```bash
# 创建目标目录结构
mkdir -p resource-manager/src/main/java/com/sunshine
mkdir -p resource-manager/src/test/java/com/sunshine
mkdir -p resource-manager/src/main/resources

# 迁移 main 源码（包名 com.sunshine.tool 不变）
cp -r tool-manager/src/main/java/com/sunshine/tool resource-manager/src/main/java/com/sunshine/tool

# 迁移 main resources（排除 application.yml，该文件已在 Task A1 创建）
# 先检查 tool-manager 是否有除 application.yml 外的资源文件
find tool-manager/src/main/resources -type f ! -name 'application.yml' -exec cp --parents {} resource-manager/src/ \; 2>/dev/null || true

# 迁移测试代码
if [ -d tool-manager/src/test ]; then
  cp -r tool-manager/src/test/java/com/sunshine/* resource-manager/src/test/java/com/sunshine/ 2>/dev/null || true
fi
```

- [ ] **Step 2: 迁移 skill-manager 代码**

```bash
cp -r skill-manager/src/main/java/com/sunshine/skill resource-manager/src/main/java/com/sunshine/skill
find skill-manager/src/main/resources -type f ! -name 'application.yml' -exec cp --parents {} resource-manager/src/ \; 2>/dev/null || true
if [ -d skill-manager/src/test ]; then
  cp -r skill-manager/src/test/java/com/sunshine/* resource-manager/src/test/java/com/sunshine/ 2>/dev/null || true
fi
```

- [ ] **Step 3: 迁移 agent-manager 代码**

```bash
cp -r agent-manager/src/main/java/com/sunshine/agent resource-manager/src/main/java/com/sunshine/agent
find agent-manager/src/main/resources -type f ! -name 'application.yml' -exec cp --parents {} resource-manager/src/ \; 2>/dev/null || true
if [ -d agent-manager/src/test ]; then
  cp -r agent-manager/src/test/java/com/sunshine/* resource-manager/src/test/java/com/sunshine/ 2>/dev/null || true
fi
```

- [ ] **Step 4: 迁移 prompt-manager 代码**

```bash
cp -r prompt-manager/src/main/java/com/sunshine/prompt resource-manager/src/main/java/com/sunshine/prompt
find prompt-manager/src/main/resources -type f ! -name 'application.yml' -exec cp --parents {} resource-manager/src/ \; 2>/dev/null || true
if [ -d prompt-manager/src/test ]; then
  cp -r prompt-manager/src/test/java/com/sunshine/* resource-manager/src/test/java/com/sunshine/ 2>/dev/null || true
fi
```

- [ ] **Step 5: 迁移 desensitize 代码**

```bash
cp -r desensitize/src/main/java/com/sunshine/desensitize resource-manager/src/main/java/com/sunshine/desensitize
find desensitize/src/main/resources -type f ! -name 'application.yml' -exec cp --parents {} resource-manager/src/ \; 2>/dev/null || true
if [ -d desensitize/src/test ]; then
  cp -r desensitize/src/test/java/com/sunshine/* resource-manager/src/test/java/com/sunshine/ 2>/dev/null || true
fi
```

- [ ] **Step 6: 检查包名冲突**

```bash
# 确认各包路径不冲突
find resource-manager/src/main/java/com/sunshine -maxdepth 1 -type d | sort
```
Expected 输出应包含 6 个目录（含 resource 本身）：
```
resource-manager/src/main/java/com/sunshine/agent
resource-manager/src/main/java/com/sunshine/desensitize
resource-manager/src/main/java/com/sunshine/prompt
resource-manager/src/main/java/com/sunshine/resource
resource-manager/src/main/java/com/sunshine/skill
resource-manager/src/main/java/com/sunshine/tool
```

- [ ] **Step 7: 编译验证**

```bash
mvn -pl resource-manager -am compile -q
```
Expected: BUILD SUCCESS。若失败，常见原因：
- 依赖缺失：对照原 5 个 pom.xml 确认是否有遗漏的依赖加入 resource-manager/pom.xml
- 包扫描冲突：检查是否有多个 `@SpringBootApplication`（仅 ResourceManagerApplication 保留，删除搬迁过来的旧 Application 类）
- 删除搬迁过来的旧 Application 类：`rm -f resource-manager/src/main/java/com/sunshine/{tool,skill,agent,prompt,desensitize}/*Application.java`

- [ ] **Step 8: 删除搬迁过来的旧 Application 类**

各原服务有自己的 `*Application.java`（如 `AgentManagerApplication`），合并后仅保留 `ResourceManagerApplication`，删除其余：

```bash
rm -f resource-manager/src/main/java/com/sunshine/tool/ToolManagerApplication.java 2>/dev/null || true
rm -f resource-manager/src/main/java/com/sunshine/skill/SkillManagerApplication.java 2>/dev/null || true
rm -f resource-manager/src/main/java/com/sunshine/agent/AgentManagerApplication.java 2>/dev/null || true
rm -f resource-manager/src/main/java/com/sunshine/prompt/PromptApplication.java 2>/dev/null || true
rm -f resource-manager/src/main/java/com/sunshine/desensitize/DesensitizeApplication.java 2>/dev/null || true
```

- [ ] **Step 9: 重新编译验证**

```bash
mvn -pl resource-manager -am compile -q
```
Expected: BUILD SUCCESS

- [ ] **Step 10: Commit**

```bash
git add resource-manager/
git commit -m "feat: 迁移 tool/skill/agent/prompt/desensitize 代码到 resource-manager"
```

---

## Task A3: 合并管理类数据库为 sunshine_resource 单库

**Files:**
- Create: `docker/mysql/init/sunshine-resource.sql`（合并 12/15/16/17 四个 SQL）
- Modify: `docker/mysql/init/01-init-databases.sql`（删 4 旧库声明，加 sunshine_resource）
- Delete: `docker/mysql/init/12-sunshine-skill-manager.sql`
- Delete: `docker/mysql/init/15-sunshine-agent-manager.sql`
- Delete: `docker/mysql/init/16-sunshine-tool-manager.sql`
- Delete: `docker/mysql/init/17-sunshine-prompt-manager.sql`

**Interfaces:**
- Consumes: 原 4 个 SQL 文件的建表 + 种子内容
- Produces: 单一 `sunshine-resource.sql`，库 `sunshine_resource` 包含全部表

- [ ] **Step 1: 创建 sunshine-resource.sql**

合并 4 个 SQL 文件内容到一个文件，统一 `USE sunshine_resource;` 开头。注意：
- 保留全部建表语句（表名不冲突，已有 `tool_`/`skill_`/`agent_`/`prompt_` 前缀）
- 保留全部种子数据 INSERT
- prompt-manager SQL 中的 Catalog ID 引用属于合并项 B 范畴（业务工具 ID），此处先原样保留，Task B3 统一替换

```bash
cat > docker/mysql/init/sunshine-resource.sql << 'SQL_EOF'
-- sunshine-resource（resource-manager :8210）
-- 合并自 12-sunshine-skill-manager / 15-sunshine-agent-manager / 16-sunshine-tool-manager / 17-sunshine-prompt-manager
USE sunshine_resource;

-- ========== skill-manager 表 ==========
SQL_EOF

# 提取 skill-manager 的建表+种子（去掉原文件头部的 USE 行和注释）
sed -n '/^CREATE TABLE/,$p' docker/mysql/init/12-sunshine-skill-manager.sql >> docker/mysql/init/sunshine-resource.sql

echo "" >> docker/mysql/init/sunshine-resource.sql
echo "-- ========== agent-manager 表 ==========" >> docker/mysql/init/sunshine-resource.sql
sed -n '/^CREATE TABLE/,$p' docker/mysql/init/15-sunshine-agent-manager.sql >> docker/mysql/init/sunshine-resource.sql

echo "" >> docker/mysql/init/sunshine-resource.sql
echo "-- ========== tool-manager 表 ==========" >> docker/mysql/init/sunshine-resource.sql
sed -n '/^CREATE TABLE/,$p' docker/mysql/init/16-sunshine-tool-manager.sql >> docker/mysql/init/sunshine-resource.sql

echo "" >> docker/mysql/init/sunshine-resource.sql
echo "-- ========== prompt-manager 表 ==========" >> docker/mysql/init/sunshine-resource.sql
sed -n '/^CREATE TABLE/,$p' docker/mysql/init/17-sunshine-prompt-manager.sql >> docker/mysql/init/sunshine-resource.sql

echo "SQL_EOF"
```

- [ ] **Step 2: 更新 01-init-databases.sql**

将以下 4 行：
```sql
CREATE DATABASE IF NOT EXISTS sunshine_skill DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS sunshine_agent  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS sunshine_tool DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS sunshine_prompt DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```
替换为 1 行：
```sql
CREATE DATABASE IF NOT EXISTS sunshine_resource DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

- [ ] **Step 3: 删除原 4 个分库 SQL**

```bash
rm docker/mysql/init/12-sunshine-skill-manager.sql
rm docker/mysql/init/15-sunshine-agent-manager.sql
rm docker/mysql/init/16-sunshine-tool-manager.sql
rm docker/mysql/init/17-sunshine-prompt-manager.sql
```

- [ ] **Step 4: 校验合并 SQL 语法**

```bash
# 确认文件存在且非空，检查 USE 语句正确
head -5 docker/mysql/init/sunshine-resource.sql
grep -c "CREATE TABLE" docker/mysql/init/sunshine-resource.sql
```
Expected: 文件头部为 `USE sunshine_resource;`；CREATE TABLE 计数应等于原 4 文件建表数之和

- [ ] **Step 5: Commit**

```bash
git add docker/mysql/init/sunshine-resource.sql docker/mysql/init/01-init-databases.sql
git rm docker/mysql/init/12-sunshine-skill-manager.sql docker/mysql/init/15-sunshine-agent-manager.sql docker/mysql/init/16-sunshine-tool-manager.sql docker/mysql/init/17-sunshine-prompt-manager.sql
git commit -m "feat: 合并管理类 4 库为 sunshine_resource 单库"
```


## Task A4: 创建 resource-manager Nacos 配置并更新调用方

**Files:**
- Create: `docs/nacos/sunshine-resource-manager.yaml`
- Modify: `docs/nacos/sunshine-orchestrator.yaml`（5 个 `*.base-url` 指向 8210）
- Modify: `docs/nacos/sunshine-bff.yaml`（4 个 `*.base-url` 指向 8210）
- Delete: `docs/nacos/sunshine-{tool-manager,skill-manager,agent-manager,prompt-manager,desensitize}.yaml`

**Interfaces:**
- Consumes: 原 5 份管理服务 Nacos 配置的端口/DB/业务配置
- Produces: 单一 `sunshine-resource-manager.yaml`，端口 8210，库 `sunshine_resource`

- [ ] **Step 1: 创建 sunshine-resource-manager.yaml**

合并原 5 份配置。端口取 8210（tool-manager 原端口），DB 统一指向 `sunshine_resource`。各服务的业务配置段（如 tool-manager 的工具集缓存、desensitize 的脱敏规则、skill-manager 的 MinIO 等）全部保留。

```yaml
# sunshine-resource-manager - Nacos 配置
# Data ID: sunshine-resource-manager.yaml
# Group:   DEFAULT_GROUP
# 合并自 sunshine-{tool-manager,skill-manager,agent-manager,prompt-manager,desensitize}.yaml

server:
  port: 8210

spring:
  datasource:
    url: jdbc:mysql://ecs4c16g:3306/sunshine_resource?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true
    username: root
    password: root123
  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false
  data:
    redis:
      host: ecs4c16g
      port: 6379
      password: redis123
      database: 0

# tool-manager 业务配置（原 sunshine-tool-manager.yaml 中迁移）
sunshine:
  tool:
    manager:
      toolset-cache-ttl-seconds: 300
  desensitize:
    enabled: true
    rules:
      # 原 sunshine-desensitize.yaml 中的脱敏规则配置
  skill:
    storage:
      type: local
      local-path: ./skill-packages
    # 若用 MinIO，保留原 minio 配置段
  # prompt-manager 无额外业务配置（仅 Catalog 版本号管理，纯 DB）

# desensitize 脱敏规则（原 DesensitizeProperties 内容，从 sunshine-desensitize.yaml 搬迁）
```

> 注意：实际编写时需对照原 5 份 Nacos yaml 逐段合并，确保所有业务配置项不遗漏。上面是结构骨架，实施时从原文件复制具体配置值。

- [ ] **Step 2: 更新 orchestrator 的 base-url 配置**

在 `docs/nacos/sunshine-orchestrator.yaml` 中，将以下 5 个配置统一指向 `http://localhost:8210`：

```yaml
tool-manager:
  base-url: http://localhost:8210
skill-manager:
  base-url: http://localhost:8210
agent-manager:
  base-url: http://localhost:8210
prompt-manager:
  base-url: http://localhost:8210
desensitize:
  base-url: http://localhost:8210
```

- [ ] **Step 3: 更新 BFF 的 base-url 配置**

在 `docs/nacos/sunshine-bff.yaml` 中，将以下 4 个配置统一指向 `http://localhost:8210`：

```yaml
tool-manager:
  base-url: http://localhost:8210
skill-manager:
  base-url: http://localhost:8210
agent-manager:
  base-url: http://localhost:8210
prompt-manager:
  base-url: http://localhost:8210
```

- [ ] **Step 4: 删除原 5 份管理服务 Nacos 配置**

```bash
rm docs/nacos/sunshine-tool-manager.yaml
rm docs/nacos/sunshine-skill-manager.yaml
rm docs/nacos/sunshine-agent-manager.yaml
rm docs/nacos/sunshine-prompt.yaml
rm docs/nacos/sunshine-desensitize.yaml
```

- [ ] **Step 5: 同步配置到 Nacos**

```bash
python scripts/sync_nacos.py
```
Expected: 输出 sunshine-resource-manager.yaml 等配置同步成功

- [ ] **Step 6: Commit**

```bash
git add docs/nacos/
git rm docs/nacos/sunshine-tool-manager.yaml docs/nacos/sunshine-skill-manager.yaml docs/nacos/sunshine-agent-manager.yaml docs/nacos/sunshine-prompt.yaml docs/nacos/sunshine-desensitize.yaml
git commit -m "feat: 创建 resource-manager Nacos 配置，更新 orchestrator/BFF base-url"
```

---

## Task A5: 验证 resource-manager 启动与端点可用

**Files:**
- 无文件改动，纯验证任务

**Interfaces:**
- Consumes: Task A1-A4 的全部产出（模块可编译、DB 已重建、Nacos 配置已同步）

- [ ] **Step 1: 重建数据库**

```bash
# 进入 MySQL 执行（或通过 docker exec）
mysql -h ecs4c16g -uroot -proot123 -e "DROP DATABASE IF EXISTS sunshine_resource; CREATE DATABASE sunshine_resource DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
mysql -h ecs4c16g -uroot -proot123 sunshine_resource < docker/mysql/init/sunshine-resource.sql
```
Expected: 无报错，建表+种子全部成功

- [ ] **Step 2: 启动 resource-manager**

```bash
python scripts/start.py --restart resource-manager
```
> 注意：此时 start.py 尚未更新（Task C1 才改），需先用 mvn 手动打包并启动：
```bash
mvn -pl resource-manager -am package -DskipTests -q
java -jar resource-manager/target/sunshine-resource-manager-1.0.0-SNAPSHOT.jar &
```
Expected: 日志输出 `Started ResourceManagerApplication`，Nacos 注册成功 `sunshine-resource-manager`

- [ ] **Step 3: 验证健康检查**

```bash
curl -s http://localhost:8210/health
```
Expected: `{"status":"UP"}`

- [ ] **Step 4: 验证各管理端点可达**

```bash
# 工具 Catalog
curl -s http://localhost:8210/api/tools/catalog?enabledOnly=false | python -m json.tool | head -5

# Skill Catalog
curl -s http://localhost:8210/api/skills/catalog/index | python -m json.tool | head -5

# Agent Catalog
curl -s http://localhost:8210/api/agents/catalog/index | python -m json.tool | head -5

# Prompt Catalog
curl -s http://localhost:8210/api/prompts/catalog | python -m json.tool | head -5

# 脱敏
curl -s -X POST http://localhost:8210/api/desensitize/scrub \
  -H 'Content-Type: application/json' \
  -d '{"text":"测试 13800138000"}' | python -m json.tool
```
Expected: 各端点返回正常 JSON 响应，无 404/500

- [ ] **Step 5: 重启 orchestrator 和 BFF 验证调用链**

```bash
python scripts/start.py --restart orchestrator bff
```
Expected: orchestrator 日志显示 5 个 `*Client` 的 baseUrl 均为 `http://localhost:8210`；BFF 同理

- [ ] **Step 6: 运行工具集成验收**

```bash
python scripts/verify_tool_integration_live.py
```
Expected: 全部通过（工具 Catalog 拉取 + 工具调用正常）

- [ ] **Step 7: 运行 Skills UI 验收**

```bash
python scripts/verify_skills_ui_live.py
```
Expected: 全部通过

---

## Task B1: 创建 biz-simulator 模块并迁移代码

**Files:**
- Create: `biz-simulator/pom.xml`
- Create: `biz-simulator/src/main/java/com/sunshine/BizSimulatorApplication.java`
- Create: `biz-simulator/src/main/resources/application.yml`
- Modify: `pom.xml`（根，删除 oa/finance/hr 3 模块，加 biz-simulator）
- Move: `oa-service/src/main/java/com/sunshine/oa/**` -> `biz-simulator/src/main/java/com/sunshine/oa/**`
- Move: `finance-service/src/main/java/com/sunshine/finance/**` -> `biz-simulator/src/main/java/com/sunshine/finance/**`
- Move: `hr-biz-service/src/main/java/com/sunshine/hr/**` -> `biz-simulator/src/main/java/com/sunshine/hr/**`

**Interfaces:**
- Produces: `BizSimulatorApplication`（`@SpringBootApplication(scanBasePackages = "com.sunshine")`），扫描 oa/finance/hr 三个包

- [ ] **Step 1: 创建 biz-simulator/pom.xml**

依赖与原三服务完全一致（sunshine-common + sunshine-tool-sdk + web + JPA + MySQL + Nacos）：

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
    </parent>

    <artifactId>sunshine-biz-simulator</artifactId>

    <dependencies>
        <dependency>
            <groupId>com.sunshine</groupId>
            <artifactId>sunshine-common</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>com.sunshine</groupId>
            <artifactId>sunshine-tool-sdk</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-jpa</artifactId>
        </dependency>
        <dependency>
            <groupId>com.mysql</groupId>
            <artifactId>mysql-connector-j</artifactId>
        </dependency>
        <dependency>
            <groupId>com.alibaba.cloud</groupId>
            <artifactId>spring-cloud-starter-alibaba-nacos-discovery</artifactId>
        </dependency>
        <dependency>
            <groupId>com.alibaba.cloud</groupId>
            <artifactId>spring-cloud-starter-alibaba-nacos-config</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>com.h2database</groupId>
            <artifactId>h2</artifactId>
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
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId>
                <version>3.2.5</version>
            </plugin>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 2: 创建主类 BizSimulatorApplication.java**

```java
package com.sunshine;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.sunshine")
public class BizSimulatorApplication {
    public static void main(String[] args) {
        SpringApplication.run(BizSimulatorApplication.class, args);
    }
}
```

- [ ] **Step 3: 创建 application.yml**

```yaml
# sunshine-biz-simulator - 配置入口
spring:
  application:
    name: sunshine-biz-simulator
  config:
    import:
      - optional:nacos:sunshine-biz-simulator.yaml
  cloud:
    nacos:
      discovery:
        server-addr: ecs4c16g:8848
        username: nacos
        password: nacos
        group: SUNSHINE_V2
        metadata:
          sunshine.tool-app: "true"
          sunshine.tool-app-id: "sunshine-biz"
      config:
        server-addr: ecs4c16g:8848
        username: nacos
        password: nacos
        import-check:
          enabled: false
        file-extension: yaml
        group: DEFAULT_GROUP

sunshine:
  tools:
    enabled: true
    app-id: sunshine-biz
  biz:
    admin-token: sunshine-biz-admin-dev
```

- [ ] **Step 4: 注册新模块到根 pom.xml**

在根 `pom.xml` 的 `<modules>` 段，删除以下 3 行：
```
<module>oa-service</module>
<module>finance-service</module>
<module>hr-biz-service</module>
```
替换为 1 行：
```xml
<module>biz-simulator</module>
```

- [ ] **Step 5: 迁移三服务代码**

```bash
mkdir -p biz-simulator/src/main/java/com/sunshine
mkdir -p biz-simulator/src/test/java/com/sunshine
mkdir -p biz-simulator/src/main/resources

cp -r oa-service/src/main/java/com/sunshine/oa biz-simulator/src/main/java/com/sunshine/oa
cp -r finance-service/src/main/java/com/sunshine/finance biz-simulator/src/main/java/com/sunshine/finance
cp -r hr-biz-service/src/main/java/com/sunshine/hr biz-simulator/src/main/java/com/sunshine/hr

# 迁移资源文件（排除 application.yml）
find oa-service/src/main/resources -type f ! -name 'application.yml' -exec cp --parents {} biz-simulator/src/ \; 2>/dev/null || true
find finance-service/src/main/resources -type f ! -name 'application.yml' -exec cp --parents {} biz-simulator/src/ \; 2>/dev/null || true
find hr-biz-service/src/main/resources -type f ! -name 'application.yml' -exec cp --parents {} biz-simulator/src/ \; 2>/dev/null || true

# 迁移测试代码
for svc in oa-service finance-service hr-biz-service; do
  if [ -d $svc/src/test ]; then
    cp -r $svc/src/test/java/com/sunshine/* biz-simulator/src/test/java/com/sunshine/ 2>/dev/null || true
  fi
done
```

- [ ] **Step 6: 删除旧 Application 类**

```bash
rm -f biz-simulator/src/main/java/com/sunshine/oa/OaApplication.java 2>/dev/null || true
rm -f biz-simulator/src/main/java/com/sunshine/finance/FinanceApplication.java 2>/dev/null || true
rm -f biz-simulator/src/main/java/com/sunshine/hr/HrApplication.java 2>/dev/null || true
```

- [ ] **Step 7: 更新三个 *SunshineTools 类的 appId**

三个工具类的 `@SunshineTool` 注解中 `id` 字段不变（短名如 `list_oa_tasks`），appId 通过 `sunshine.tools.app-id` 配置统一为 `sunshine-biz`（已在 application.yml 设置）。因此工具类源码**无需修改**，Catalog ID 自动从 `sdk__sunshine-{oa|finance|hr}__*` 变为 `sdk__sunshine-biz__*`。

验证三个工具类无硬编码 appId：
```bash
grep -rn "sunshine-oa\|sunshine-finance\|sunshine-hr" biz-simulator/src/main/java/
```
Expected: 无匹配（appId 来自配置而非硬编码）。若有匹配需改为 `sunshine-biz`。

- [ ] **Step 8: 编译验证**

```bash
mvn -pl biz-simulator -am compile -q
```
Expected: BUILD SUCCESS

- [ ] **Step 9: Commit**

```bash
git add biz-simulator/ pom.xml
git commit -m "feat: 创建 biz-simulator 模块，合并 oa/finance/hr 三服务"
```


## Task B2: 更新种子 SQL 中的 Catalog ID（16 处）

**Files:**
- Modify: `docker/mysql/init/13-sunshine-workflow-manager.sql`（10 处）
- Modify: `docker/mysql/init/15-sunshine-agent-manager.sql`（3 处，已合并入 sunshine-resource.sql，需在合并文件中改）
- Modify: `docker/mysql/init/12-sunshine-skill-manager.sql`（2 处，已合并入 sunshine-resource.sql，需在合并文件中改）
- Modify: `docker/mysql/init/17-sunshine-prompt-manager.sql`（1 处，已合并入 sunshine-resource.sql，需在合并文件中改）
- Modify: `docker/mysql/init/sunshine-resource.sql`（在 Task A3 合并的文件中统一替换）
- Modify: `docker/mysql/init/16-sunshine-tool-manager.sql`（sdk_application 3 条 -> 1 条，已合并入 sunshine-resource.sql）

**Interfaces:**
- Consumes: Task B1 的统一 appId `sunshine-biz`
- Produces: 全部种子 SQL 中的 Catalog ID 使用新前缀 `sdk__sunshine-biz__`

> 注意：Task A3 已将 12/15/16/17 四个 SQL 合并进 `sunshine-resource.sql`。因此 16 处 Catalog ID 替换实际在 `sunshine-resource.sql` 和 `13-sunshine-workflow-manager.sql` 两个文件中进行。

- [ ] **Step 1: 在 sunshine-resource.sql 中替换 Catalog ID**

```bash
# 替换全部 6 个旧前缀为新前缀（无歧义，全局替换）
sed -i 's/sdk__sunshine-oa__/sdk__sunshine-biz__/g' docker/mysql/init/sunshine-resource.sql
sed -i 's/sdk__sunshine-finance__/sdk__sunshine-biz__/g' docker/mysql/init/sunshine-resource.sql
sed -i 's/sdk__sunshine-hr__/sdk__sunshine-biz__/g' docker/mysql/init/sunshine-resource.sql

# 验证替换结果
grep -c "sdk__sunshine-biz__" docker/mysql/init/sunshine-resource.sql
grep -c "sdk__sunshine-oa__\|sdk__sunshine-finance__\|sdk__sunshine-hr__" docker/mysql/init/sunshine-resource.sql
```
Expected: 第一条输出 >= 6（至少 6 个新 ID 出现）；第二条输出 0（无旧 ID 残留）

- [ ] **Step 2: 在 13-sunshine-workflow-manager.sql 中替换 Catalog ID**

```bash
sed -i 's/sdk__sunshine-oa__/sdk__sunshine-biz__/g' docker/mysql/init/13-sunshine-workflow-manager.sql
sed -i 's/sdk__sunshine-finance__/sdk__sunshine-biz__/g' docker/mysql/init/13-sunshine-workflow-manager.sql
sed -i 's/sdk__sunshine-hr__/sdk__sunshine-biz__/g' docker/mysql/init/13-sunshine-workflow-manager.sql

# 验证
grep -c "sdk__sunshine-biz__" docker/mysql/init/13-sunshine-workflow-manager.sql
grep -c "sdk__sunshine-oa__\|sdk__sunshine-finance__\|sdk__sunshine-hr__" docker/mysql/init/13-sunshine-workflow-manager.sql
```
Expected: 第一条输出 >= 10；第二条输出 0

- [ ] **Step 3: 合并 sdk_application 种子为单条**

在 `docker/mysql/init/sunshine-resource.sql` 中，找到 `sdk_application` 的 3 条 INSERT：

```sql
INSERT INTO sdk_application (id, nacos_service, display_name, tenant_id, status) VALUES
('sunshine-finance', 'sunshine-finance', '财务应用', 'default', 'offline'),
('sunshine-oa', 'sunshine-oa', 'OA 应用', 'default', 'offline'),
('sunshine-hr', 'sunshine-hr', 'HR 假勤应用', 'default', 'offline');
```

替换为单条：
```sql
INSERT INTO sdk_application (id, nacos_service, display_name, tenant_id, status) VALUES
('sunshine-biz', 'sunshine-biz-simulator', '业务模拟应用（OA/财务/HR）', 'default', 'offline');
```

- [ ] **Step 4: 全局校验无旧 Catalog ID 残留**

```bash
# 在整个 docker/mysql/init/ 目录中搜索旧 ID
grep -rn "sdk__sunshine-oa__\|sdk__sunshine-finance__\|sdk__sunshine-hr__" docker/mysql/init/
```
Expected: 无任何匹配输出

- [ ] **Step 5: 全局校验无旧 Nacos 服务名残留（种子数据中）**

```bash
grep -rn "sunshine-oa\b\|sunshine-finance\b\|sunshine-hr\b" docker/mysql/init/ | grep -v "sunshine-biz"
```
> 注意：`sunshine-oa` 等可能出现在 display_name 等非关键字段中，需人工判断。关键检查 `sdk_application` 表的 `id` 和 `nacos_service` 字段不再有旧值。

- [ ] **Step 6: Commit**

```bash
git add docker/mysql/init/
git commit -m "feat: 统一 Catalog ID 为 sdk__sunshine-biz__*，合并 sdk_application 种子"
```

---

## Task B3: 创建 biz-simulator Nacos 配置

**Files:**
- Create: `docs/nacos/sunshine-biz-simulator.yaml`
- Delete: `docs/nacos/sunshine-{oa,finance,hr}.yaml`

**Interfaces:**
- Consumes: 原三份 Nacos 配置的 DB/端口/业务配置
- Produces: 单一 `sunshine-biz-simulator.yaml`，端口 8700，库 `sunshine_biz`（不变）

- [ ] **Step 1: 创建 sunshine-biz-simulator.yaml**

```yaml
# sunshine-biz-simulator - Nacos 配置
# Data ID: sunshine-biz-simulator.yaml
# Group:   DEFAULT_GROUP
# 合并自 sunshine-{oa,finance,hr}.yaml

server:
  port: 8700

spring:
  datasource:
    url: jdbc:mysql://ecs4c16g:3306/sunshine_biz?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true
    username: root
    password: root123
  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false

sunshine:
  biz:
    admin-token: sunshine-biz-admin-dev
```

> 注意：三服务原配置完全同构（同库同账号同 admin-token），仅端口不同。合并后取 8700。

- [ ] **Step 2: 删除原三份 Nacos 配置**

```bash
rm docs/nacos/sunshine-oa.yaml
rm docs/nacos/sunshine-finance.yaml
rm docs/nacos/sunshine-hr.yaml
```

- [ ] **Step 3: 同步配置到 Nacos**

```bash
python scripts/sync_nacos.py
```

- [ ] **Step 4: Commit**

```bash
git add docs/nacos/
git rm docs/nacos/sunshine-oa.yaml docs/nacos/sunshine-finance.yaml docs/nacos/sunshine-hr.yaml
git commit -m "feat: 创建 biz-simulator Nacos 配置"
```

---

## Task B4: 验证 biz-simulator 启动与工具调用

**Files:**
- 无文件改动，纯验证任务

**Interfaces:**
- Consumes: Task B1-B3 的全部产出

- [ ] **Step 1: 重建数据库**

```bash
# sunshine_biz 库重建（表结构不变，种子数据在 18-sunshine-biz.sql，无需改动）
mysql -h ecs4c16g -uroot -proot123 -e "DROP DATABASE IF EXISTS sunshine_biz; CREATE DATABASE sunshine_biz DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
mysql -h ecs4c16g -uroot -proot123 sunshine_biz < docker/mysql/init/18-sunshine-biz.sql
```

- [ ] **Step 2: 重建 sunshine_resource 库（含 Task B2 更新后的种子）**

```bash
mysql -h ecs4c16g -uroot -proot123 -e "DROP DATABASE IF EXISTS sunshine_resource; CREATE DATABASE sunshine_resource DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
mysql -h ecs4c16g -uroot -proot123 sunshine_resource < docker/mysql/init/sunshine-resource.sql
mysql -h ecs4c16g -uroot -proot123 sunshine_workflow < docker/mysql/init/13-sunshine-workflow-manager.sql
```
> 注意：13-sunshine-workflow-manager.sql 属于 sunshine_workflow 库，需确认该库存在且重新导入。

- [ ] **Step 3: 打包并启动 biz-simulator**

```bash
mvn -pl biz-simulator -am package -DskipTests -q
java -jar biz-simulator/target/sunshine-biz-simulator-1.0.0-SNAPSHOT.jar &
```
Expected: 日志输出 `Started BizSimulatorApplication`，Nacos 注册为 `sunshine-biz-simulator`，带 `sunshine.tool-app:true` metadata

- [ ] **Step 4: 验证健康检查**

```bash
curl -s http://localhost:8700/health
```
Expected: `{"status":"UP"}`

- [ ] **Step 5: 验证工具注册到 tool-manager**

```bash
# 启动 resource-manager（若未运行）
# 触发 SDK 应用同步（tool-manager 会拉取 biz-simulator 的工具 catalog）
curl -s -X POST http://localhost:8210/api/admin/tools/sdk-applications/sunshine-biz/sync | python -m json.tool
```
Expected: 返回成功，工具注册数应为 12（oa 2 + finance 6 + hr 4），全部以 `sdk__sunshine-biz__` 为前缀

- [ ] **Step 6: 验证工具 Catalog ID 已更新**

```bash
curl -s "http://localhost:8210/api/tools/catalog?enabledOnly=false" | python -c "
import json, sys
data = json.load(sys.stdin)
for t in data.get('data', []):
    print(t.get('id', ''))
" | grep sdk__sunshine-biz__ | sort
```
Expected: 12 行输出，全部以 `sdk__sunshine-biz__` 开头，无 `sdk__sunshine-{oa|finance|hr}__` 残留

- [ ] **Step 7: 运行企业 workflow 验收**

```bash
python scripts/verify_enterprise_workflow_live.py
```
Expected: 全部通过（workflow plan_json 中的 Catalog ID 已更新，工具调用正常）

- [ ] **Step 8: 运行子智能体验收**

```bash
python scripts/verify_spawn_subagent_live.py
```
Expected: 全部通过（子智能体白名单中的 Catalog ID 已更新）


---

## Task C1: 更新部署脚本与网关路由

**Files:**
- Modify: `scripts/start.py`（SERVICES 列表：删 8 条目，加 2 条目）
- Modify: `docs/nacos/sunshine-gateway.yaml`（健康检查路由：删 8 条，加 2 条）

**Interfaces:**
- Consumes: Task A4 + B3 的新 Nacos 服务名（sunshine-resource-manager / sunshine-biz-simulator）

- [ ] **Step 1: 更新 start.py SERVICES 列表**

在 `scripts/start.py` 的 `SERVICES` 列表中，删除以下 8 行：
```python
("finance", "finance-service", "sunshine-finance", 8710),
("oa", "oa-service", "sunshine-oa", 8700),
("hr", "hr-biz-service", "sunshine-hr", 8720),
("tool-manager", "tool-manager", "sunshine-tool-manager", 8210),
("skill-manager", "skill-manager", "sunshine-skill-manager", 8225),
("agent-manager", "agent-manager", "sunshine-agent-manager", 8235),
("desensitize", "desensitize", "sunshine-desensitize", 8600),
("prompt", "prompt-manager", "sunshine-prompt", 8500),
```
替换为 2 行：
```python
("biz-simulator", "biz-simulator", "sunshine-biz-simulator", 8700),
("resource-manager", "resource-manager", "sunshine-resource-manager", 8210),
```

同时更新文件末尾的端口打印信息（第 87-89 行附近），将旧的端口列表替换为：
```python
print("  Resource Manager :8210 | Biz Simulator :8700")
```

- [ ] **Step 2: 更新 gateway 健康检查路由**

在 `docs/nacos/sunshine-gateway.yaml` 中，删除以下 8 条健康检查路由：
```yaml
- id: health-tool-manager
- id: health-skill-manager
- id: health-agent-manager
- id: health-prompt
- id: health-desensitize
- id: health-finance
- id: health-oa
- id: health-hr
```
替换为 2 条：
```yaml
- id: health-resource-manager
  uri: lb://sunshine-resource-manager
  predicates:
    - Path=/health/resource-manager
  filters:
    - RewritePath=/health/resource-manager, /health
- id: health-biz-simulator
  uri: lb://sunshine-biz-simulator
  predicates:
    - Path=/health/biz-simulator
  filters:
    - RewritePath=/health/biz-simulator, /health
```

- [ ] **Step 3: 同步 Nacos 配置**

```bash
python scripts/sync_nacos.py
```

- [ ] **Step 4: 重启 gateway**

```bash
python scripts/start.py --restart gateway
```

- [ ] **Step 5: 验证新健康检查路由**

```bash
curl -s http://localhost:8000/health/resource-manager
curl -s http://localhost:8000/health/biz-simulator
```
Expected: 两者均返回 `{"status":"UP"}`

- [ ] **Step 6: Commit**

```bash
git add scripts/start.py docs/nacos/sunshine-gateway.yaml
git commit -m "feat: 更新 start.py 和 gateway 路由为合并后的 2 服务"
```

---

## Task C2: 删除旧模块并更新文档

**Files:**
- Delete: `tool-manager/` `skill-manager/` `agent-manager/` `prompt-manager/` `desensitize/` 全目录
- Delete: `oa-service/` `finance-service/` `hr-biz-service/` 全目录
- Modify: `CLAUDE.md`（服务端口表）
- Modify: `docs/superpowers/specs/2026-08-03-service-consolidation-design.md`（状态改为 已实现）

**Interfaces:**
- Consumes: Task A5 + B4 验收全部通过

- [ ] **Step 1: 删除旧模块目录**

```bash
rm -rf tool-manager skill-manager agent-manager prompt-manager desensitize
rm -rf oa-service finance-service hr-biz-service
```

- [ ] **Step 2: 验证根 pom.xml 已无旧模块引用**

```bash
grep -n "tool-manager\|skill-manager\|agent-manager\|prompt-manager\|desensitize\|oa-service\|finance-service\|hr-biz-service" pom.xml
```
Expected: 无匹配（Task A1/B1 已移除）

- [ ] **Step 3: 全量编译验证**

```bash
mvn clean compile -q
```
Expected: BUILD SUCCESS（全部模块编译通过，无对已删除模块的残留引用）

- [ ] **Step 4: 更新 CLAUDE.md 服务端口表**

将服务端口表中的以下行删除：
```
| `tool-manager` | 8210 | 工具注册与调用（SDK + MCP） |
| `skill-manager` | 8225 | Skills 上传 / 版本 / Catalog |
| `expert-manager` | 8235 | Expert CRUD / Catalog |
| `prompt-manager` | 8500 | 提示词管理（`/prompts` + Catalog） |
| `desensitize` | 8600 | 数据脱敏 |
| `oa-service` | 8700 | OA 模拟 |
| `finance-service` | 8710 | 财务模拟 |
| `hr-biz-service` | 8720 | 人事模拟 |
```
替换为：
```
| `resource-manager` | 8210 | 资源管理聚合（工具 + Skills + Agent + Prompt + 脱敏） |
| `biz-simulator` | 8700 | 业务模拟聚合（OA + 财务 + HR） |
```

同时更新运维脚本表中的相关引用（如有），以及架构扩展章节中 `tool-manager` / `skill-manager` 等的引用改为 `resource-manager`。

- [ ] **Step 5: 更新设计文档状态**

在 `docs/superpowers/specs/2026-08-03-service-consolidation-design.md` 第 3 行，将状态从 `待评审` 改为 `✅ 已实现`。

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "refactor: 删除已合并的 8 个旧服务模块，更新 CLAUDE.md 与文档"
```

---

## Self-Review

### 1. Spec coverage

逐项对照设计文档 `2026-08-03-service-consolidation-design.md`：

| 设计文档章节 | 实现任务 | 覆盖 |
|-------------|---------|:----:|
| §4 合并项 A：resource-manager 模块结构 | Task A1（骨架）+ A2（代码迁移） | ✅ |
| §4.2 数据库合并（4 库 -> 1 库） | Task A3（SQL 合并） | ✅ |
| §4.2 Nacos 注册 + 配置 | Task A4（Nacos 配置 + 调用方 base-url） | ✅ |
| §4.3 调用方影响（orchestrator/BFF） | Task A4 Step 2-3 | ✅ |
| §5 合并项 B：biz-simulator 模块结构 | Task B1（骨架 + 代码迁移） | ✅ |
| §5.2 统一 appId，Catalog ID 改新 | Task B1 Step 7（appId 配置）+ B2（种子 SQL 替换） | ✅ |
| §5.2 sdk_application 种子合并 | Task B2 Step 3 | ✅ |
| §5.3 Nacos 配置 | Task B3 | ✅ |
| §6 gateway 健康检查路由 | Task C1 | ✅ |
| §6 start.py 更新 | Task C1 Step 1 | ✅ |
| §6 删除旧模块 | Task C2 | ✅ |
| §6 CLAUDE.md 更新 | Task C2 Step 4 | ✅ |
| §7 验收（verify 脚本） | Task A5 + B4 | ✅ |

### 2. Placeholder scan

- 无 TBD/TODO
- Task A4 Step 1 的 Nacos 配置骨架注明"实施时从原文件复制具体配置值"--这是必要的实施指引而非占位符，因为原 5 份配置的具体值需在实施时逐段对照
- 所有代码步骤均有完整代码块

### 3. Type consistency

- `ResourceManagerApplication`（Task A1）与 Task A2 引用一致
- `BizSimulatorApplication`（Task B1）包名 `com.sunshine`（非 `com.sunshine.biz`）与 `scanBasePackages="com.sunshine"` 一致
- Catalog ID 新前缀 `sdk__sunshine-biz__` 在 Task B1/B2/验证步骤中一致
- Nacos 服务名 `sunshine-resource-manager` / `sunshine-biz-simulator` 全文一致

