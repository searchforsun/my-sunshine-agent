# 服务合并实施方案

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 8 个独立微服务合并为 3 个聚合服务，进程/端口/配置/库数量从 8 减至 3。

**Architecture:** 管理类 4 服务（skill/agent/prompt/desensitize）合并为 `resource-manager`（端口 8240）；业务模拟类 3 服务（oa/finance/hr）合并为 `biz-simulator`（端口 8700）；tool-manager 更名 `tool-service` 独立保留（端口 8210 不变）。orchestrator/BFF 客户端仅改 base-url 配置，代码零改动。不做数据迁移，直接重建 DB。Catalog ID `sdk__sunshine-{oa|finance|hr}__*` 统一改为 `sdk__sunshine-biz__*`。

**Tech Stack:** Java 17 + Spring Boot 3.2.x + Spring Cloud Alibaba + Maven 多模块

## Global Constraints

- 不做数据迁移，直接重建 DB（删除旧库 SQL，新建合并 SQL）
- orchestrator/BFF 仅改 `@Value` 注解 key（4→1），客户端逻辑代码不变
- 废弃旧 Nacos 服务名、旧 Catalog ID，不保留兼容
- 不推动态 Plan-Workflow（已删除），不影响 Planner-Executor（4.14）
- tool-service 独立保留，`ToolManagerClient`/`ToolManagerAdminClient` 零改动
- Java 方法/变量英文表意，非显然逻辑用中文注释解释**为什么**
- 禁止补丁式修改、禁止死代码残留
- 前端零改动

---

### Task 1: 创建 resource-manager 模块骨架

**Files:**
- Create: `resource-manager/pom.xml`
- Create: `resource-manager/src/main/java/com/sunshine/resource/ResourceManagerApplication.java`
- Create: `resource-manager/src/main/resources/application.yml`

**Interfaces:**
- Produces: `ResourceManagerApplication` — `@SpringBootApplication(scanBasePackages = "com.sunshine")`，合并 4 服务的所有 Spring Bean

- [ ] **Step 1: 创建 resource-manager/pom.xml**

合并 skill/agent/prompt/desensitize 四个服务的依赖。关键依赖：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.sunshine</groupId>
        <artifactId>sunshine-platform</artifactId>
        <version>1.0.0</version>
        <relativePath>../pom.xml</relativePath>
    </parent>

    <artifactId>sunshine-resource-manager</artifactId>
    <packaging>jar</packaging>
    <name>resource-manager</name>
    <description>聚合管理服务：Skill / Agent / Prompt / Desensitize</description>

    <dependencies>
        <dependency>
            <groupId>com.sunshine</groupId>
            <artifactId>sunshine-common</artifactId>
        </dependency>
        <dependency>
            <groupId>com.sunshine</groupId>
            <artifactId>sunshine-routing</artifactId>
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
            <groupId>com.alibaba.cloud</groupId>
            <artifactId>spring-cloud-starter-alibaba-nacos-discovery</artifactId>
        </dependency>
        <dependency>
            <groupId>com.alibaba.cloud</groupId>
            <artifactId>spring-cloud-starter-alibaba-nacos-config</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-redis</artifactId>
        </dependency>
        <!-- MyBatis / MySQL（prompt-manager / skill-manager / agent-manager 均用） -->
        <dependency>
            <groupId>org.mybatis.spring.boot</groupId>
            <artifactId>mybatis-spring-boot-starter</artifactId>
        </dependency>
        <dependency>
            <groupId>com.mysql</groupId>
            <artifactId>mysql-connector-j</artifactId>
            <scope>runtime</scope>
        </dependency>
        <!-- MinIO（skill 物料存储） -->
        <dependency>
            <groupId>io.minio</groupId>
            <artifactId>minio</artifactId>
        </dependency>
        <!-- AhoCorasick（desensitize 关键词匹配） -->
        <dependency>
            <groupId>org.ahocorasick</groupId>
            <artifactId>ahocorasick</artifactId>
        </dependency>
    </dependencies>

    <build>
        <finalName>sunshine-resource-manager</finalName>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 2: 创建 ResourceManagerApplication.java**

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

- [ ] **Step 3: 创建 application.yml（最小配置）**

```yaml
server:
  port: 8240
spring:
  application:
    name: sunshine-resource-manager
  cloud:
    nacos:
      discovery:
        server-addr: ${NACOS_SERVER_ADDR:localhost:8848}
        namespace: ${NACOS_NAMESPACE:}
      config:
        server-addr: ${NACOS_SERVER_ADDR:localhost:8848}
        namespace: ${NACOS_NAMESPACE:}
        file-extension: yaml
  config:
    import:
      - nacos:sunshine-resource-manager.yaml
```

- [ ] **Step 4: 验证模块骨架编译**

```bash
cd /usr/local/gitproj/my-sunshine-agent && mvn compile -pl resource-manager -am -q
```

Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add resource-manager/
git commit -m "feat: 创建 resource-manager 模块骨架（端口 8240）"
```

---

### Task 2: 迁移 skill-manager 代码到 resource-manager

**Files:**
- Copy: `skill-manager/src/main/java/com/sunshine/skill/` → `resource-manager/src/main/java/com/sunshine/skill/`（全量）
- Copy: `skill-manager/src/main/resources/` 中的 MyBatis mapper XML 等（如有）→ `resource-manager/src/main/resources/`

**Interfaces:**
- Consumes: `ResourceManagerApplication`（scanBasePackages="com.sunshine" 自动扫描 `com.sunshine.skill`）
- Produces: 原有端点 `/api/skills/**` 不变

- [ ] **Step 1: 复制 skill-manager 源码**

```bash
cp -r /usr/local/gitproj/my-sunshine-agent/skill-manager/src/main/java/com/sunshine/skill \
      /usr/local/gitproj/my-sunshine-agent/resource-manager/src/main/java/com/sunshine/skill
```

- [ ] **Step 2: 复制 skill-manager 资源文件（如有 mapper XML）**

```bash
# 检查是否有 mapper XML 或其他资源文件
ls /usr/local/gitproj/my-sunshine-agent/skill-manager/src/main/resources/
# 如有，复制非 application.yml 的文件到 resource-manager
```

- [ ] **Step 3: 验证编译**

```bash
cd /usr/local/gitproj/my-sunshine-agent && mvn compile -pl resource-manager -am -q
```

Expected: BUILD SUCCESS（skill-manager 代码在新模块中成功编译）

- [ ] **Step 4: Commit**

```bash
git add resource-manager/src/main/java/com/sunshine/skill/
git commit -m "feat: 迁移 skill-manager 代码到 resource-manager"
```

---

### Task 3: 迁移 agent-manager 代码到 resource-manager

**Files:**
- Copy: `agent-manager/src/main/java/com/sunshine/agent/` → `resource-manager/src/main/java/com/sunshine/agent/`（全量）

- [ ] **Step 1: 复制 agent-manager 源码**

```bash
cp -r /usr/local/gitproj/my-sunshine-agent/agent-manager/src/main/java/com/sunshine/agent \
      /usr/local/gitproj/my-sunshine-agent/resource-manager/src/main/java/com/sunshine/agent
```

- [ ] **Step 2: 验证编译**

```bash
cd /usr/local/gitproj/my-sunshine-agent && mvn compile -pl resource-manager -am -q
```

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add resource-manager/src/main/java/com/sunshine/agent/
git commit -m "feat: 迁移 agent-manager 代码到 resource-manager"
```

---

### Task 4: 迁移 prompt-manager 代码到 resource-manager

**Files:**
- Copy: `prompt-manager/src/main/java/com/sunshine/prompt/` → `resource-manager/src/main/java/com/sunshine/prompt/`（全量）
- Copy: prompt-manager 资源文件（如有 mapper XML）

- [ ] **Step 1: 复制 prompt-manager 源码**

```bash
cp -r /usr/local/gitproj/my-sunshine-agent/prompt-manager/src/main/java/com/sunshine/prompt \
      /usr/local/gitproj/my-sunshine-agent/resource-manager/src/main/java/com/sunshine/prompt
```

- [ ] **Step 2: 复制 prompt-manager 资源文件**

```bash
# 检查并复制非 application.yml 的资源文件
ls /usr/local/gitproj/my-sunshine-agent/prompt-manager/src/main/resources/
```

- [ ] **Step 3: 验证编译**

```bash
cd /usr/local/gitproj/my-sunshine-agent && mvn compile -pl resource-manager -am -q
```

Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add resource-manager/src/main/java/com/sunshine/prompt/
git commit -m "feat: 迁移 prompt-manager 代码到 resource-manager"
```

---

### Task 5: 迁移 desensitize 代码到 resource-manager

**Files:**
- Copy: `desensitize/src/main/java/com/sunshine/desensitize/` → `resource-manager/src/main/java/com/sunshine/desensitize/`（全量）

- [ ] **Step 1: 复制 desensitize 源码**

```bash
cp -r /usr/local/gitproj/my-sunshine-agent/desensitize/src/main/java/com/sunshine/desensitize \
      /usr/local/gitproj/my-sunshine-agent/resource-manager/src/main/java/com/sunshine/desensitize
```

- [ ] **Step 2: 最终验证 resource-manager 全量编译**

```bash
cd /usr/local/gitproj/my-sunshine-agent && mvn compile -pl resource-manager -am -q
```

Expected: BUILD SUCCESS（4 服务全部代码在新模块中成功编译）

- [ ] **Step 3: Commit**

```bash
git add resource-manager/src/main/java/com/sunshine/desensitize/
git commit -m "feat: 迁移 desensitize 代码到 resource-manager，4 服务聚合完成"
```

---

### Task 6: 合并 MySQL init SQL（sunshine_resource）

**Files:**
- Create: `docker/mysql/init/19-sunshine-resource.sql`（合并 12/15/17 三个文件）
- Modify: `docker/mysql/init/01-init-databases.sql`（添加 sunshine_resource 库，移除旧 3 库）

**Interfaces:**
- Produces: `sunshine_resource` 数据库，含 skill_*/agent_*/prompt_* 全部表结构与种子数据

- [ ] **Step 1: 创建合并 SQL 文件**

读取 `12-sunshine-skill-manager.sql`、`15-sunshine-agent-manager.sql`、`17-sunshine-prompt-manager.sql`，将 `USE sunshine_skill/agent/prompt` 替换为 `USE sunshine_resource`，合并为一个文件 `19-sunshine-resource.sql`。

```bash
# 从 3 个文件收集所有 DDL/DML，写入 19-sunshine-resource.sql
echo "-- sunshine-resource-manager（resource-manager :8240 · 库 sunshine_resource · 全量 v1）" > docker/mysql/init/19-sunshine-resource.sql
echo "USE sunshine_resource;" >> docker/mysql/init/19-sunshine-resource.sql

# 追加 skill DDL（跳过头部 USE 行和注释行）
sed '1,/^USE sunshine_skill;/d' docker/mysql/init/12-sunshine-skill-manager.sql >> docker/mysql/init/19-sunshine-resource.sql

# 追加 agent DDL + 种子（跳过头部 USE 行）
sed '1,/^USE sunshine_agent;/d' docker/mysql/init/15-sunshine-agent-manager.sql >> docker/mysql/init/19-sunshine-resource.sql

# 追加 prompt DDL + 种子（跳过头部 USE 行）
sed '1,/^USE sunshine_prompt;/d' docker/mysql/init/17-sunshine-prompt-manager.sql >> docker/mysql/init/19-sunshine-resource.sql
```

> 注意：表已有 `skill_`/`agent_`/`prompt_` 前缀，无命名冲突。

- [ ] **Step 2: 更新 01-init-databases.sql**

```sql
-- 替换原有 3 行：
-- CREATE DATABASE IF NOT EXISTS sunshine_skill DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
-- CREATE DATABASE IF NOT EXISTS sunshine_agent  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
-- CREATE DATABASE IF NOT EXISTS sunshine_prompt DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
-- 改为：
CREATE DATABASE IF NOT EXISTS sunshine_resource DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

- [ ] **Step 3: Commit**

```bash
git add docker/mysql/init/19-sunshine-resource.sql docker/mysql/init/01-init-databases.sql
git commit -m "feat: 合并 sunshine_skill/agent/prompt 为 sunshine_resource 单库"
```

---

### Task 7: 创建 biz-simulator 模块

**Files:**
- Create: `biz-simulator/pom.xml`
- Create: `biz-simulator/src/main/java/com/sunshine/BizSimulatorApplication.java`
- Create: `biz-simulator/src/main/resources/application.yml`

- [ ] **Step 1: 创建 biz-simulator/pom.xml**

依赖与原三服务完全相同，关键配置：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.sunshine</groupId>
        <artifactId>sunshine-platform</artifactId>
        <version>1.0.0</version>
        <relativePath>../pom.xml</relativePath>
    </parent>

    <artifactId>sunshine-biz-simulator</artifactId>
    <packaging>jar</packaging>
    <name>biz-simulator</name>
    <description>业务模拟聚合服务：OA / Finance / HR</description>

    <dependencies>
        <dependency>
            <groupId>com.sunshine</groupId>
            <artifactId>sunshine-common</artifactId>
        </dependency>
        <dependency>
            <groupId>com.sunshine</groupId>
            <artifactId>sunshine-tool-sdk</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
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
            <artifactId>spring-boot-starter-data-redis</artifactId>
        </dependency>
        <dependency>
            <groupId>org.mybatis.spring.boot</groupId>
            <artifactId>mybatis-spring-boot-starter</artifactId>
        </dependency>
        <dependency>
            <groupId>com.mysql</groupId>
            <artifactId>mysql-connector-j</artifactId>
            <scope>runtime</scope>
        </dependency>
    </dependencies>

    <build>
        <finalName>sunshine-biz-simulator</finalName>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 2: 创建 BizSimulatorApplication.java**

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
server:
  port: 8700
spring:
  application:
    name: sunshine-biz-simulator
  cloud:
    nacos:
      discovery:
        server-addr: ${NACOS_SERVER_ADDR:localhost:8848}
        namespace: ${NACOS_NAMESPACE:}
        metadata:
          sunshine.tool-app: "true"
          sunshine.tool-app-id: "sunshine-biz"
      config:
        server-addr: ${NACOS_SERVER_ADDR:localhost:8848}
        namespace: ${NACOS_NAMESPACE:}
        file-extension: yaml
  config:
    import:
      - nacos:sunshine-biz-simulator.yaml
sunshine:
  tools:
    enabled: true
    app-id: sunshine-biz
```

关键：`app-id` 统一为 `sunshine-biz`，废弃旧的 `sunshine-oa`/`sunshine-finance`/`sunshine-hr`。

- [ ] **Step 4: 验证编译**

```bash
cd /usr/local/gitproj/my-sunshine-agent && mvn compile -pl biz-simulator -am -q
```

Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add biz-simulator/
git commit -m "feat: 创建 biz-simulator 模块骨架（端口 8700，统一 appId=sunshine-biz）"
```

---

### Task 8: 迁移 oa/finance/hr 代码到 biz-simulator

**Files:**
- Copy: `oa-service/src/main/java/com/sunshine/oa/` → `biz-simulator/src/main/java/com/sunshine/oa/`
- Copy: `finance-service/src/main/java/com/sunshine/finance/` → `biz-simulator/src/main/java/com/sunshine/finance/`
- Copy: `hr-biz-service/src/main/java/com/sunshine/hr/` → `biz-simulator/src/main/java/com/sunshine/hr/`
- Modify: 三个 `*SunshineTools.java` 类的 `appId` 配置（若类内有硬编码 `sunshine-oa`/`sunshine-finance`/`sunshine-hr` 则需改为 `sunshine-biz`）

- [ ] **Step 1: 复制 oa-service 源码**

```bash
cp -r /usr/local/gitproj/my-sunshine-agent/oa-service/src/main/java/com/sunshine/oa \
      /usr/local/gitproj/my-sunshine-agent/biz-simulator/src/main/java/com/sunshine/oa
```

- [ ] **Step 2: 复制 finance-service 源码**

```bash
cp -r /usr/local/gitproj/my-sunshine-agent/finance-service/src/main/java/com/sunshine/finance \
      /usr/local/gitproj/my-sunshine-agent/biz-simulator/src/main/java/com/sunshine/finance
```

- [ ] **Step 3: 复制 hr-biz-service 源码**

```bash
cp -r /usr/local/gitproj/my-sunshine-agent/hr-biz-service/src/main/java/com/sunshine/hr \
      /usr/local/gitproj/my-sunshine-agent/biz-simulator/src/main/java/com/sunshine/hr
```

- [ ] **Step 4: 检查并修改 *SunshineTools 类中的硬编码 appId**

搜索三个工具类中是否有硬编码 `sunshine-oa`/`sunshine-finance`/`sunshine-hr`：

```bash
rg -n "sunshine-(oa|finance|hr)" biz-simulator/src/main/java/
```

若找到，改为 `sunshine-biz`。已知这些服务的 `appId` 通过 `sunshine.tools.app-id` 配置注入，类内通常通过 `@Value("${sunshine.tools.app-id}")` 注入，已通过 Task 7 的 `application.yml` 统一为 `sunshine-biz`，大概率无需改动代码。

- [ ] **Step 5: 验证编译**

```bash
cd /usr/local/gitproj/my-sunshine-agent && mvn compile -pl biz-simulator -am -q
```

Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add biz-simulator/src/main/java/com/sunshine/oa/ biz-simulator/src/main/java/com/sunshine/finance/ biz-simulator/src/main/java/com/sunshine/hr/
git commit -m "feat: 迁移 oa/finance/hr 代码到 biz-simulator"
```

---

### Task 9: tool-manager 更名 tool-service（仅 Nacos + 配置）

**Files:**
- Rename: `docs/nacos/sunshine-tool-manager.yaml` → `docs/nacos/sunshine-tool-service.yaml`
- Modify: `docs/nacos/sunshine-tool-service.yaml`（服务名 `sunshine-tool-manager` → `sunshine-tool-service`）

> 代码零改动。`tool-manager` 模块目录、包名、端口 8210、`sunshine_tool` 库均不变。仅 Nacos 注册名和配置 Data ID 更名。

- [ ] **Step 1: 重命名 Nacos 配置文件**

```bash
mv docs/nacos/sunshine-tool-manager.yaml docs/nacos/sunshine-tool-service.yaml
```

- [ ] **Step 2: 更新文件内的 application name**

```yaml
# 将 spring.application.name 从 sunshine-tool-manager 改为 sunshine-tool-service
spring:
  application:
    name: sunshine-tool-service
```

同时检查文件中是否有其他引用 `sunshine-tool-manager` 的地方（如日志配置、健康检查路径等），如有一并更名。

- [ ] **Step 3: Commit**

> `sync_nacos.py` 的完整更新见 Task 11（含所有 7 个旧文件名的替换）。

```bash
git add docs/nacos/sunshine-tool-service.yaml
git rm docs/nacos/sunshine-tool-manager.yaml 2>/dev/null || true
git commit -m "refactor: tool-manager 更名 tool-service（仅 Nacos 服务名，代码不变）"
```

---

### Task 10: 更新种子 SQL 中的 Catalog ID

**Files:**
- Modify: `docker/mysql/init/19-sunshine-resource.sql`（原 15-sunshine-agent-manager.sql 中的子智能体白名单）
- Modify: `docker/mysql/init/13-sunshine-workflow-manager.sql`（workflow plan_json 中的 tool 节点）
- Modify: `docker/mysql/init/16-sunshine-tool-manager.sql`（sdk_application 记录）
- Modify: `docker/mysql/init/12-sunshine-skill-manager.sql`（skill 工具配置，如有）

**Catalog ID 映射规则**：`sdk__sunshine-{oa|finance|hr}__*` → `sdk__sunshine-biz__*`

- [ ] **Step 1: 更新 19-sunshine-resource.sql 中的 agent tools_json**

在 `19-sunshine-resource.sql` 中查找并替换所有旧 Catalog ID：

```sql
-- policy-agent tools_json（3 处）：
-- sdk__sunshine-hr__get_leave_balance       → sdk__sunshine-biz__get_leave_balance
-- sdk__sunshine-hr__list_leave_requests     → sdk__sunshine-biz__list_leave_requests
-- sdk__sunshine-hr__get_attendance_month    → sdk__sunshine-biz__get_attendance_month

-- finance-agent tools_json（3 处）：
-- sdk__sunshine-finance__list_my_expenses   → sdk__sunshine-biz__list_my_expenses
-- sdk__sunshine-finance__get_expense_detail → sdk__sunshine-biz__get_expense_detail
-- sdk__sunshine-finance__summarize_my_expenses → sdk__sunshine-biz__summarize_my_expenses

-- compliance-agent tools_json（4 处）：
-- 同上 + sdk__sunshine-hr__get_leave_balance / list_leave_requests
```

```bash
sed -i 's/sdk__sunshine-oa__/sdk__sunshine-biz__/g' docker/mysql/init/19-sunshine-resource.sql
sed -i 's/sdk__sunshine-finance__/sdk__sunshine-biz__/g' docker/mysql/init/19-sunshine-resource.sql
sed -i 's/sdk__sunshine-hr__/sdk__sunshine-biz__/g' docker/mysql/init/19-sunshine-resource.sql
```

- [ ] **Step 2: 更新 13-sunshine-workflow-manager.sql**

workflow plan_json 中所有 tool 节点的 `params.tool` 和 `tools` 参数包含旧 Catalog ID：

```bash
sed -i 's/sdk__sunshine-oa__/sdk__sunshine-biz__/g' docker/mysql/init/13-sunshine-workflow-manager.sql
sed -i 's/sdk__sunshine-finance__/sdk__sunshine-biz__/g' docker/mysql/init/13-sunshine-workflow-manager.sql
sed -i 's/sdk__sunshine-hr__/sdk__sunshine-biz__/g' docker/mysql/init/13-sunshine-workflow-manager.sql
```

影响的 workflow：`finance-list`、`finance-smart`、`finance-summary`、`knowledge-loop`、`hr-leave-assist`、`expense-compliance`、`oa-task-assist`、`expense-detail-query`、`expense-status-filter`、`expense-amount-check`。

- [ ] **Step 3: 更新 12-sunshine-skill-manager.sql（如有引用）**

```bash
rg -n "sdk__sunshine-(oa|finance|hr)" docker/mysql/init/12-sunshine-skill-manager.sql
```

如有匹配，同样 sed 替换。已知 `12-sunshine-skill-manager.sql` 当前仅含 DDL，无种子数据引用，大概率无需改动。

- [ ] **Step 4: 更新 16-sunshine-tool-manager.sql 的 sdk_application**

```sql
-- 原 3 条记录：
-- ('sunshine-finance', 'sunshine-finance', '财务应用', 'default', 'offline'),
-- ('sunshine-oa', 'sunshine-oa', 'OA 应用', 'default', 'offline'),
-- ('sunshine-hr', 'sunshine-hr', 'HR 假勤应用', 'default', 'offline'),

-- 合并为 1 条：
INSERT INTO sdk_application (id, nacos_service, display_name, tenant_id, status) VALUES
('sunshine-biz', 'sunshine-biz-simulator', '业务模拟应用', 'default', 'offline');
```

删除旧的 3 条 INSERT，替换为新的 1 条。

- [ ] **Step 5: 验证所有 SQL 文件中不再有旧 ID**

```bash
rg -n "sdk__sunshine-(oa|finance|hr)" docker/mysql/init/
```

Expected: 无匹配结果。

- [ ] **Step 6: Commit**

```bash
git add docker/mysql/init/
git commit -m "feat: 更新种子 SQL Catalog ID（sunshine-{oa|finance|hr} → sunshine-biz），sdk_application 3→1"
```

---

### Task 11: 创建 Nacos 配置文件

**Files:**
- Create: `docs/nacos/sunshine-resource-manager.yaml`（合并 4 份旧配置）
- Create: `docs/nacos/sunshine-biz-simulator.yaml`（合并 3 份旧配置）

- [ ] **Step 1: 创建 sunshine-resource-manager.yaml**

合并 `sunshine-skill-manager.yaml`、`sunshine-agent-manager.yaml`、`sunshine-prompt.yaml`、`sunshine-desensitize.yaml`。关键点：数据库 url 统一为 `sunshine_resource`；skill-manager 独有的 MinIO 配置保留；desensitize 无 DB，仅保留 `desensitize.rules`。

```yaml
# ============================================================
# sunshine-resource-manager — Nacos 配置
# Data ID: sunshine-resource-manager.yaml
# Group:   DEFAULT_GROUP
# 聚合管理服务（Skill / Agent / Prompt / Desensitize）
# 表结构 SSOT：docker/mysql/init/19-sunshine-resource.sql（禁止 Flyway）
# Skill 包文件存储：MinIO ecs4c16g:9000 / bucket sunshine-skills
# ============================================================

server:
  port: 8240

spring:
  datasource:
    url: jdbc:mysql://ecs4c16g:3306/sunshine_resource?useUnicode=true&characterEncoding=utf-8&serverTimezone=UTC&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true
    username: root
    password: root123
  jpa:
    properties:
      hibernate:
        jdbc:
          time_zone: UTC
        timezone:
          default_storage: NORMALIZE_UTC
    hibernate:
      ddl-auto: validate
    open-in-view: false
    show-sql: false

# 原 skill-manager 专属：MinIO 存储
skill:
  storage:
    type: minio
    base-dir: data/skills
    minio:
      endpoint: http://ecs4c16g:9000
      access-key: minioadmin
      secret-key: minioadmin123
      bucket: sunshine-skills

# 原 desensitize 专属：脱敏规则（无 DB）
desensitize:
  regex-enabled: true
  rules:
    - id: salary
      keywords: ["月薪", "年薪", "工资总额"]
      replacement: "***"
    - id: bank-account
      keywords: ["银行卡号", "银行账号"]
      replacement: "[账号已脱敏]"

logging:
  level:
    com.sunshine: debug
```

> agent-manager 和 prompt-manager 原有配置无特殊项（仅端口+数据源），已统一为 `sunshine_resource` 数据源。各子模块 Controller 路径不变（`/api/skills/**`、`/api/agents/**`、`/api/prompts/**`、`/api/desensitize/**`），无需额外路由配置。

- [ ] **Step 2: 创建 sunshine-biz-simulator.yaml**

合并 `sunshine-oa.yaml`、`sunshine-finance.yaml`、`sunshine-hr.yaml`。三服务已共享 `sunshine_biz` 库（时区 `Asia/Shanghai`），数据库配置只需一份。

```yaml
# ============================================================
# sunshine-biz-simulator — Nacos 配置
# Data ID: sunshine-biz-simulator.yaml
# Group:   DEFAULT_GROUP
# 业务模拟聚合服务（OA / Finance / HR）
# 表结构 SSOT：docker/mysql/init/18-sunshine-biz.sql（禁止 Flyway）
# ============================================================

server:
  port: 8700

spring:
  datasource:
    url: jdbc:mysql://ecs4c16g:3306/sunshine_biz?useUnicode=true&characterEncoding=utf-8&serverTimezone=Asia/Shanghai
    username: root
    password: root123
  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false

sunshine:
  biz:
    admin-token: sunshine-biz-admin-dev

logging:
  level:
    com.sunshine: debug
```

- [ ] **Step 3: 更新 sync_nacos.py 的 DEFAULT_DATA_IDS**

`scripts/sync_nacos.py` 的 `DEFAULT_DATA_IDS` 列表（第 22–40 行）引用了全部旧文件名，需替换：

```python
DEFAULT_DATA_IDS = [
    "sunshine-gateway.yaml",
    "sunshine-gateway-gw-flow-rules.json",
    "sunshine-auth.yaml",
    "sunshine-bff.yaml",
    "sunshine-orchestrator.yaml",
    "sunshine-llm-gateway.yaml",
    "sunshine-rag.yaml",
    "sunshine-biz-simulator.yaml",         # 替代 sunshine-finance/oa/hr.yaml（3→1）
    "sunshine-tool-service.yaml",         # 替代 sunshine-tool-manager.yaml（更名）
    "sunshine-resource-manager.yaml",     # 替代 sunshine-skill-manager/agent-manager/desensitize/prompt.yaml（4→1）
    "sunshine-sandbox-service.yaml",
    "sunshine-workflow-manager.yaml",
]
```

删除 7 行旧文件名：`sunshine-finance.yaml`、`sunshine-oa.yaml`、`sunshine-hr.yaml`、`sunshine-tool-manager.yaml`、`sunshine-skill-manager.yaml`、`sunshine-agent-manager.yaml`、`sunshine-desensitize.yaml`、`sunshine-prompt.yaml`。新增 2 行：`sunshine-biz-simulator.yaml`、`sunshine-resource-manager.yaml`。更名 1 行：`sunshine-tool-service.yaml`。

- [ ] **Step 4: 删除旧的 7 个 Nacos 配置文件**

```bash
git rm docs/nacos/sunshine-skill-manager.yaml
git rm docs/nacos/sunshine-agent-manager.yaml
git rm docs/nacos/sunshine-prompt.yaml
git rm docs/nacos/sunshine-desensitize.yaml
git rm docs/nacos/sunshine-oa.yaml
git rm docs/nacos/sunshine-finance.yaml
git rm docs/nacos/sunshine-hr.yaml
```

> `sunshine-tool-service.yaml` 已在 Task 9 处理（从 `sunshine-tool-manager.yaml` 重命名而来）。

- [ ] **Step 5: Commit**

```bash
git add docs/nacos/sunshine-resource-manager.yaml docs/nacos/sunshine-biz-simulator.yaml scripts/sync_nacos.py
git commit -m "feat: 创建 resource-manager 和 biz-simulator Nacos 配置，删除旧 7 份，更新 sync_nacos.py"
```

---

### Task 12: 更新 orchestrator Nacos 配置 base-url

**Files:**
- Modify: `docs/nacos/sunshine-orchestrator.yaml`（4 个管理 Client 的 base-url 统一指向 8240）

- [ ] **Step 1: 更新 4 个管理类 base-url**

```yaml
# 原值：
# skill-manager.base-url: http://localhost:8225
# agent-manager.base-url: http://localhost:8235
# prompt-manager.base-url: http://localhost:8500
# desensitize.base-url: http://localhost:8600

# 改为：
resource-manager:
  base-url: http://localhost:8240
```

同时更新 `SkillCatalogClient`、`AgentCatalogClient`、`PromptCatalogClient`、`DesensitizeClient` 的 `@Value` 注解放置，将 4 个独立 key 改为统一读取 `resource-manager.base-url`。

检查 orchestrator 中各客户端的 `@Value` 注解：

- `SkillCatalogClient.java`：`@Value("${skill-manager.base-url:http://localhost:8225}")` → `@Value("${resource-manager.base-url:http://localhost:8240}")`
- `AgentCatalogClient.java`：`@Value("${agent-manager.base-url:http://localhost:8235}")` → `@Value("${resource-manager.base-url:http://localhost:8240}")`
- `PromptCatalogClient.java`：`@Value("${prompt-manager.base-url:http://localhost:8500}")` → `@Value("${resource-manager.base-url:http://localhost:8240}")`
- `DesensitizeClient.java`：`@Value("${desensitize.base-url:http://localhost:8600}")` → `@Value("${resource-manager.base-url:http://localhost:8240}")`

`ToolManagerClient` 不变（仍指向 tool-service 8210）。

- [ ] **Step 2: 同步更新 sunshine-orchestrator.yaml Nacos 配置**

```yaml
resource-manager:
  base-url: http://localhost:8240
# 移除旧的 4 行：
# skill-manager.base-url: http://localhost:8225
# agent-manager.base-url: http://localhost:8235
# prompt-manager.base-url: http://localhost:8500
# desensitize.base-url: http://localhost:8600
# 保留：
tool-manager:
  base-url: http://localhost:8210
```

> 注意：`tool-manager.base-url` 的 key 保持不变（仅 Nacos 服务名改为 `sunshine-tool-service`，配置 key 暂不改动以避免代码变更）。

- [ ] **Step 3: 验证 orchestrator 编译**

```bash
cd /usr/local/gitproj/my-sunshine-agent && mvn compile -pl orchestrator -am -q
```

- [ ] **Step 4: Commit**

```bash
git add docs/nacos/sunshine-orchestrator.yaml orchestrator/src/main/java/com/sunshine/orchestrator/client/
git commit -m "feat: orchestrator 管理类 Client base-url 统一指向 resource-manager :8240"
```

---

### Task 13: 更新 BFF Nacos 配置 base-url

**Files:**
- Modify: `docs/nacos/sunshine-bff.yaml`（3 个管理 Client 的 base-url 统一指向 8240）
- Modify: BFF 3 个客户端类的 `@Value` 注解

- [ ] **Step 1: 更新 BFF 客户端 `@Value` 注解**

- `SkillManagerClient.java`：`@Value("${skill-manager.base-url:http://localhost:8225}")` → `@Value("${resource-manager.base-url:http://localhost:8240}")`
- `AgentManagerClient.java`：`@Value("${agent-manager.base-url:http://localhost:8235}")` → `@Value("${resource-manager.base-url:http://localhost:8240}")`
- `PromptManagerClient.java`：`@Value("${prompt-manager.base-url:http://localhost:8500}")` → `@Value("${resource-manager.base-url:http://localhost:8240}")`

`ToolManagerAdminClient` 不变（仍指向 tool-service 8210）。

- [ ] **Step 2: 更新 sunshine-bff.yaml**

```yaml
resource-manager:
  base-url: http://localhost:8240
# 移除旧的：
# skill-manager.base-url: http://localhost:8225
# agent-manager.base-url: http://localhost:8235
# prompt-manager.base-url: http://localhost:8500
```

- [ ] **Step 3: 验证 BFF 编译**

```bash
cd /usr/local/gitproj/my-sunshine-agent && mvn compile -pl bff -am -q
```

- [ ] **Step 4: Commit**

```bash
git add docs/nacos/sunshine-bff.yaml bff/src/main/java/com/sunshine/bff/client/
git commit -m "feat: BFF 管理类 Client base-url 统一指向 resource-manager :8240"
```

---

### Task 14: 更新 Gateway 健康检查路由

**Files:**
- Modify: `docs/nacos/sunshine-gateway.yaml`（健康检查路由 8→3）

- [ ] **Step 1: 替换 4 条管理类健康路由为 1 条**

```yaml
# 删除：
# - id: health-skill-manager
#   uri: lb://sunshine-skill-manager
# - id: health-agent-manager
#   uri: lb://sunshine-agent-manager
# - id: health-prompt
#   uri: lb://sunshine-prompt
# - id: health-desensitize
#   uri: lb://sunshine-desensitize

# 新增：
- id: health-resource-manager
  uri: lb://sunshine-resource-manager
  predicates:
    - Path=/health/resource-manager
  filters:
    - RewritePath=/health/resource-manager, /health
```

- [ ] **Step 2: 替换 3 条业务模拟健康路由为 1 条**

```yaml
# 删除：
# - id: health-finance
#   uri: lb://sunshine-finance
# - id: health-oa
#   uri: lb://sunshine-oa
# - id: health-hr
#   uri: lb://sunshine-hr

# 新增：
- id: health-biz-simulator
  uri: lb://sunshine-biz-simulator
  predicates:
    - Path=/health/biz-simulator
  filters:
    - RewritePath=/health/biz-simulator, /health
```

- [ ] **Step 3: 更新 tool-manager 健康路由的 Nacos 服务名**

```yaml
# health-tool-manager
# uri: lb://sunshine-tool-manager → uri: lb://sunshine-tool-service
- id: health-tool-service
  uri: lb://sunshine-tool-service
  predicates:
    - Path=/health/tool-service
  filters:
    - RewritePath=/health/tool-service, /health
```

- [ ] **Step 4: Commit**

```bash
git add docs/nacos/sunshine-gateway.yaml
git commit -m "feat: Gateway 健康路由管理类 4→1、业务模拟 3→1、tool-manager 更名 tool-service"
```

---

### Task 15: 更新 start.py

**Files:**
- Modify: `scripts/start.py`（服务条目 16 → 13）

- [ ] **Step 1: 更新 `SERVICES` 列表**

```python
# 删除 7 个旧条目：
# ('skill-manager', 'skill-manager', 'sunshine-skill-manager', 8225),
# ('agent-manager', 'agent-manager', 'sunshine-agent-manager', 8235),
# ('prompt', 'prompt-manager', 'sunshine-prompt', 8500),
# ('desensitize', 'desensitize', 'sunshine-desensitize', 8600),
# ('finance', 'finance-service', 'sunshine-finance', 8710),
# ('oa', 'oa-service', 'sunshine-oa', 8700),
# ('hr', 'hr-biz-service', 'sunshine-hr', 8720),

# 新增 2 个条目：
# ('resource-manager', 'resource-manager', 'sunshine-resource-manager', 8240),
# ('biz-simulator', 'biz-simulator', 'sunshine-biz-simulator', 8700),

# tool-manager 条目改名 tool-service（jar 名暂不变，因模块目录未改）：
# ('tool-service', 'tool-manager', 'sunshine-tool-manager', 8210),
```

实际修改结构：`SERVICES` 列表中每个条目为 `(service_key, module_dir, artifact_id, port)`。需删除 7 个旧条目，新增 2 个新条目，tool-manager 条目的 `service_key` 改为 `tool-service`。

- [ ] **Step 2: 验证 start.py 语法**

```bash
python3 -c "import py_compile; py_compile.compile('scripts/start.py', doraise=True)"
```

- [ ] **Step 3: Commit**

```bash
git add scripts/start.py
git commit -m "feat: start.py 服务条目 16→13（管理类 4→1、业务模拟 3→1、tool 更名）"
```

---

### Task 16: 更新根 pom.xml 模块列表

**Files:**
- Modify: `pom.xml`（`<modules>` 列表）

- [ ] **Step 1: 替换模块声明**

```xml
<!-- 删除 7 个旧模块 -->
<!-- <module>skill-manager</module> -->
<!-- <module>agent-manager</module> -->
<!-- <module>prompt-manager</module> -->
<!-- <module>desensitize</module> -->
<!-- <module>oa-service</module> -->
<!-- <module>finance-service</module> -->
<!-- <module>hr-biz-service</module> -->

<!-- 新增 2 个新模块 -->
<module>resource-manager</module>
<module>biz-simulator</module>
```

- [ ] **Step 2: 验证全量编译**

```bash
cd /usr/local/gitproj/my-sunshine-agent && mvn compile -q
```

Expected: BUILD SUCCESS（全部 14 个模块编译通过）

- [ ] **Step 3: Commit**

```bash
git add pom.xml
git commit -m "feat: 根 pom.xml 模块列表更新（7 旧→2 新）"
```

---

### Task 17: 更新 CLAUDE.md 和 implementation-plan.md

**Files:**
- Modify: `CLAUDE.md`（服务端口表）
- Modify: `docs/implementation-plan.md`（如有相关条目）

- [ ] **Step 1: 更新 CLAUDE.md 服务端口表**

```markdown
| 服务 | 端口 | 说明 |
|------|:---:|------|
| `resource-manager` | 8240 | 聚合管理服务（Skill/Agent/Prompt/Desensitize） |
| `tool-service` | 8210 | 工具注册与调用（SDK + MCP） |
| `biz-simulator` | 8700 | 业务模拟聚合（OA/Finance/HR） |
```

删除旧表中的 `skill-manager`/`agent-manager`/`prompt-manager`/`desensitize`/`oa-service`/`finance-service`/`hr-biz-service` 行，`tool-manager` 行改名 `tool-service`。

- [ ] **Step 2: 更新 CLAUDE.md 中涉及旧服务名的引用**

搜索并替换：
```bash
rg -n "tool-manager|skill-manager|agent-manager|prompt-manager|desensitize|oa-service|finance-service|hr-biz-service" CLAUDE.md
```

更新所有引用：
- `tool-manager` → `tool-service`
- 管理类 4 服务 → `resource-manager`
- 业务模拟 3 服务 → `biz-simulator`

- [ ] **Step 3: 同步更新 implementation-plan.md**

```bash
rg -n "tool-manager|skill-manager|agent-manager" docs/implementation-plan.md
```

如有引用，同步更新。

- [ ] **Step 4: Commit**

```bash
git add CLAUDE.md docs/implementation-plan.md
git commit -m "docs: 更新 CLAUDE.md 服务端口表，反映合并后服务布局"
```

---

### Task 18: 清理旧模块目录

**Files:**
- Delete: `skill-manager/`、`agent-manager/`、`prompt-manager/`、`desensitize/`、`oa-service/`、`finance-service/`、`hr-biz-service/`

- [ ] **Step 1: 删除旧模块目录**

```bash
cd /usr/local/gitproj/my-sunshine-agent
git rm -r skill-manager/
git rm -r agent-manager/
git rm -r prompt-manager/
git rm -r desensitize/
git rm -r oa-service/
git rm -r finance-service/
git rm -r hr-biz-service/
```

- [ ] **Step 2: 删除旧的 MySQL 种子 SQL（12/15/17）**

```bash
# 这些已被 19-sunshine-resource.sql 替代
git rm docker/mysql/init/12-sunshine-skill-manager.sql
git rm docker/mysql/init/15-sunshine-agent-manager.sql
git rm docker/mysql/init/17-sunshine-prompt-manager.sql
```

- [ ] **Step 3: 验证全量编译仍然通过**

```bash
cd /usr/local/gitproj/my-sunshine-agent && mvn compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "chore: 删除旧模块目录和旧种子 SQL（7 服务 + 3 SQL）"
```

---

### Task 19: 运行 sync_nacos.py 并验收

**Files:**
- Execute: `scripts/sync_nacos.py`（同步 Nacos 配置）
- Execute: 验收脚本（回归验证）

- [ ] **Step 1: 同步 Nacos 配置**

```bash
cd /usr/local/gitproj/my-sunshine-agent && python scripts/sync_nacos.py
```

Expected: 成功同步新配置到 Nacos。

- [ ] **Step 2: 重建数据库**

```bash
# 重新运行 init SQL（需 MySQL 容器运行）
docker exec -i sunshine-mysql mysql -uroot -p<password> < docker/mysql/init/01-init-databases.sql
docker exec -i sunshine-mysql mysql -uroot -p<password> < docker/mysql/init/19-sunshine-resource.sql
```

- [ ] **Step 3: 重新打包并启动全链路**

```bash
python scripts/start.py --restart
```

Expected: 所有 13 个服务启动成功。

- [ ] **Step 4: 运行验收脚本**

```bash
# 工具调用（tool-service 独立验证）
python scripts/verify_tool_integration_live.py

# Skill 管理
python scripts/verify_skills_ui_live.py

# Workflow 含业务工具
python scripts/verify_enterprise_workflow_live.py

# 子智能体白名单
python scripts/verify_spawn_subagent_live.py

# A2A 直连 orchestrator 回归
python scripts/verify_external_agent_live.py
```

Expected: 全部 PASS。

- [ ] **Step 5: Commit 验收结果（如有文档更新）**

```bash
git status
```

---

### Task 20: 归档 spec 文档

**Files:**
- Move: `docs/superpowers/specs/2026-08-03-service-consolidation-design.md` → `docs/superpowers/specs/archive/2026-08-03-service-consolidation-design.md`

- [ ] **Step 1: 归档 spec**

```bash
mv docs/superpowers/specs/2026-08-03-service-consolidation-design.md docs/superpowers/specs/archive/
```

- [ ] **Step 2: 更新 spec 状态**

在文档顶部将 `状态：**待评审**` 改为 `状态：**✅ 已实现**`。

- [ ] **Step 3: Commit**

```bash
git add docs/superpowers/specs/archive/
git commit -m "docs: 归档服务合并 spec（已实现）"
```

---

## 变更影响面总览

| 改造对象 | 合并前 | 合并后 |
|---------|--------|--------|
| Maven 业务模块 | 18 | 13 (-5) |
| 部署进程 | 16 | 13 (-3) |
| MySQL 库 | 14 | 12 (-2) |
| Nacos 配置 | 16 | 11 (-5) |
| Gateway 健康路由 | 15 | 11 (-4) |
| orchestrator 代码变动 | - | 4 个 `@Value` 注解 |
| BFF 代码变动 | - | 3 个 `@Value` 注解 |
| 前端 | - | 零改动 |
