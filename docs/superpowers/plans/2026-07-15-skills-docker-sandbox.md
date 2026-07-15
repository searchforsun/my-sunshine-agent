# Skills Docker 沙箱（4.5）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为声明 `sandbox: docker` 的 Skill 提供会话级 Docker 工作区与六工具 `sandbox__*`（不进 tool-manager Catalog），经独立 `sandbox-service(:8226)` 执行并接 HITL/审计。

**Architecture:** Orchestrator 在 `skill.sandbox != none` 时注入六内置工具（仿 `RagTool`/`ManageTasksTool`）；`ReActAgentRuntime` 开跑 `createSession`、结束 `closeSession`；工具 RPC 到 `sandbox-service` 内长容器（`/skill` 只读 + `/workspace` 可写）。skill-manager 只存 `sandbox` + `sandbox_policy` JSON，不跑 Docker。

**Tech Stack:** JDK 21 · Spring Boot 3.2 · ProcessBuilder/`docker` CLI · WebClient · Micrometer · Vue3（`/skills` 试跑最小入口）· Python Live

**设计 SSOT:** [2026-07-15-skills-docker-sandbox-design.md](../specs/2026-07-15-skills-docker-sandbox-design.md)

**前置:** Docker daemon 可用；镜像可构建；Nacos/`sync_nacos.py`；skill-manager 已有 `sandbox` 列。

---

## 文件结构（边界锁定）

| 区域 | 创建 | 修改 | 测试 |
|------|------|------|------|
| **sandbox-service** | 整模块 `sandbox-service/**` | 根 `pom.xml`、`scripts/start.py` | `PathJailTest`、`SandboxToolExecutorTest`、`SandboxSessionServiceTest` |
| **镜像** | `docker/sandbox/Dockerfile`、`docker/sandbox/egress/tinyproxy.conf` | — | `docker build` 手测 |
| **Nacos** | `docs/nacos/sunshine-sandbox-service.yaml` | `sunshine-orchestrator.yaml`、`sunshine-gateway.yaml` | `sync_nacos.py` |
| **skill-manager** | — | `SkillVersionEntity`、`SkillCatalogEntry`、Admin API、`12-*.sql` 追加列 | `SkillCatalogEntry` 序列化单测 |
| **orchestrator** | `SandboxClient`、`SandboxIds`、`SandboxAgentTool`、`SandboxSessionLifecycle`、`SandboxHitlPolicy` | `DynamicToolkitFactory`、`ToolCatalogService`、`ReActAgentRuntime`、`SkillCatalogEntry` | `DynamicToolkitFactoryTest`、`SandboxHitlPolicyTest`、`Path` 相关 |
| **Nacos prompt** | — | `sunshine-orchestrator.yaml` mode-overlay 一句偏好规则 | — |
| **前端** | — | `skills.ts`、Skills 试跑按钮（最小） | `vue-tsc` |
| **验收** | `scripts/verify_sandbox_live.py` | `implementation-plan.md`、详设状态 | Live G1–G9 |

---

## 迭代排期

```
迭代 0（4.5.1）  T0 → T1 → T2 → T3     模块骨架 + 镜像 + Session 启停
迭代 1（4.5.2）  T4 → T5 → T6 → T7     PathJail + 六工具 + 网络白名单
迭代 2（4.5.4）  T8 → T9               skill sandbox_policy + Catalog 下发
迭代 3（4.5.3）  T10 → T11 → T12 → T13 Client + 注入 + HITL + 生命周期
迭代 4（4.5.5）  T14 → T15 → T16       审计/Grafana + 试跑 UI + Live
```

---

## Task T0: Parent POM + sandbox-service 骨架

**Files:**
- Create: `sandbox-service/pom.xml`
- Create: `sandbox-service/src/main/java/com/sunshine/sandbox/SandboxServiceApplication.java`
- Create: `sandbox-service/src/main/resources/application.yml`
- Create: `docs/nacos/sunshine-sandbox-service.yaml`
- Modify: `pom.xml`（`<module>sandbox-service</module>`，放在 `skill-manager` 后）
- Modify: `scripts/start.py`（SERVICES 增加 sandbox-service :8226）

- [ ] **Step 1: 在根 `pom.xml` 的 `<modules>` 中、`skill-manager` 后插入**

```xml
        <module>sandbox-service</module>
```

- [ ] **Step 2: 创建 `sandbox-service/pom.xml`**

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
    <artifactId>sunshine-sandbox-service</artifactId>
    <dependencies>
        <dependency>
            <groupId>com.sunshine</groupId>
            <artifactId>sunshine-common</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
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
    </dependencies>
    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 3: Application + application.yml**

```java
package com.sunshine.sandbox;

import com.sunshine.common.core.exception.GlobalExceptionHandler;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@Import(GlobalExceptionHandler.class)
public class SandboxServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(SandboxServiceApplication.class, args);
    }
}
```

```yaml
spring:
  application:
    name: sunshine-sandbox-service
  config:
    import:
      - optional:nacos:sunshine-sandbox-service.yaml
  cloud:
    nacos:
      discovery:
        server-addr: ${NACOS_ADDR:ecs4c16g:8848}
        group: SUNSHINE_V2
      config:
        server-addr: ${NACOS_ADDR:ecs4c16g:8848}
        file-extension: yaml
        group: DEFAULT_GROUP
```

- [ ] **Step 4: Nacos SSOT `docs/nacos/sunshine-sandbox-service.yaml`**

```yaml
server:
  port: 8226

sandbox:
  docker:
    binary: docker
    host-data-root: /var/lib/sunshine-sandbox
    default-image: sunshine-sandbox-python:3.11-slim
    default-memory-mb: 256
    default-cpus: "0.5"
    default-timeout-sec: 30
  egress:
    proxy-image: sunshine-sandbox-egress:1.0
    proxy-port: 8888

management:
  endpoints:
    web:
      exposure:
        include: health,prometheus,metrics

logging:
  level:
    com.sunshine: debug
```

- [ ] **Step 5: `scripts/start.py` 在 skill-manager 行后增加**

```python
    ("sandbox-service", "sandbox-service", "sunshine-sandbox-service", 8226),
```

并更新帮助文案中的端口列表。

- [ ] **Step 6: 编译**

Run: `mvn -pl sandbox-service -am -DskipTests package -q`  
Expected: BUILD SUCCESS

- [ ] **Step 7: Commit**

```bash
git add pom.xml sandbox-service docs/nacos/sunshine-sandbox-service.yaml scripts/start.py
git commit -m "$(cat <<'EOF'
feat(sandbox): add sandbox-service module skeleton on :8226

EOF
)"
```

---

## Task T1: Python 沙箱镜像

**Files:**
- Create: `docker/sandbox/Dockerfile`
- Create: `docker/sandbox/requirements.txt`
- Create: `scripts/build_sandbox_image.py`

- [ ] **Step 1: Dockerfile**

```dockerfile
FROM python:3.11-slim
RUN useradd -m -u 10001 sandbox \
 && mkdir -p /skill /workspace \
 && chown -R sandbox:sandbox /workspace
WORKDIR /workspace
COPY requirements.txt /tmp/requirements.txt
RUN pip install --no-cache-dir -r /tmp/requirements.txt \
 && rm /tmp/requirements.txt
USER sandbox
CMD ["sleep", "infinity"]
```

```text
pandas==2.2.3
regex==2024.11.6
```

- [ ] **Step 2: 构建脚本 `scripts/build_sandbox_image.py`**

```python
#!/usr/bin/env python3
"""Build sunshine-sandbox-python:3.11-slim from docker/sandbox."""
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ctx = ROOT / "docker" / "sandbox"
cmd = ["docker", "build", "-t", "sunshine-sandbox-python:3.11-slim", str(ctx)]
print("+", " ".join(cmd), flush=True)
sys.exit(subprocess.call(cmd))
```

- [ ] **Step 3: 构建镜像**

Run: `python scripts/build_sandbox_image.py`  
Expected: exit 0；`docker images | grep sunshine-sandbox-python` 有行

- [ ] **Step 4: Commit**

```bash
git add docker/sandbox scripts/build_sandbox_image.py
git commit -m "$(cat <<'EOF'
feat(sandbox): add sunshine-sandbox-python image build

EOF
)"
```

---

## Task T2: PathJail（纯函数，先测）

**Files:**
- Create: `sandbox-service/src/main/java/com/sunshine/sandbox/jail/PathJail.java`
- Create: `sandbox-service/src/test/java/com/sunshine/sandbox/jail/PathJailTest.java`

- [ ] **Step 1: 写失败单测**

```java
package com.sunshine.sandbox.jail;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class PathJailTest {
    @Test
    void resolvesUnderSkillAndWorkspace() {
        assertThat(PathJail.resolveRead("/skill/scripts/a.py").toString())
                .isEqualTo("/skill/scripts/a.py");
        assertThat(PathJail.resolveWrite("/workspace/out.txt").toString())
                .isEqualTo("/workspace/out.txt");
    }

    @Test
    void rejectsEscapeAndSkillWrite() {
        assertThatThrownBy(() -> PathJail.resolveRead("/skill/../etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PathJail.resolveWrite("/skill/x.py"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PathJail.resolveRead("/tmp/x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void resolveCwdDefaultsWorkspace() {
        assertThat(PathJail.resolveCwd(null).toString()).isEqualTo("/workspace");
        assertThat(PathJail.resolveCwd("/skill/scripts").toString()).isEqualTo("/skill/scripts");
    }
}
```

- [ ] **Step 2: Run — expect FAIL**

Run: `mvn -pl sandbox-service -Dtest=PathJailTest test`  
Expected: 编译失败 / 类不存在

- [ ] **Step 3: 实现**

```java
package com.sunshine.sandbox.jail;

import java.nio.file.Path;
import java.nio.file.Paths;

public final class PathJail {
    public static final Path SKILL = Paths.get("/skill").toAbsolutePath().normalize();
    public static final Path WORKSPACE = Paths.get("/workspace").toAbsolutePath().normalize();

    private PathJail() {}

    public static Path resolveRead(String raw) {
        return mustBeUnder(normalize(raw), SKILL, WORKSPACE);
    }

    public static Path resolveWrite(String raw) {
        return mustBeUnder(normalize(raw), WORKSPACE);
    }

    public static Path resolveCwd(String raw) {
        if (raw == null || raw.isBlank()) {
            return WORKSPACE;
        }
        return mustBeUnder(normalize(raw), SKILL, WORKSPACE);
    }

    private static Path normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("path required");
        }
        Path p = Paths.get(raw).toAbsolutePath().normalize();
        return p;
    }

    private static Path mustBeUnder(Path p, Path... roots) {
        for (Path root : roots) {
            if (p.startsWith(root)) {
                return p;
            }
        }
        throw new IllegalArgumentException("path escapes jail: " + p);
    }
}
```

- [ ] **Step 4: Run — expect PASS**

Run: `mvn -pl sandbox-service -Dtest=PathJailTest test`  
Expected: Tests run: 3, Failures: 0

- [ ] **Step 5: Commit**

```bash
git add sandbox-service/src/main/java/com/sunshine/sandbox/jail \
        sandbox-service/src/test/java/com/sunshine/sandbox/jail
git commit -m "$(cat <<'EOF'
feat(sandbox): add PathJail for /skill and /workspace

EOF
)"
```

---

## Task T3: Docker Session 启停（长容器）

**Files:**
- Create: `sandbox-service/.../docker/DockerCli.java`
- Create: `sandbox-service/.../session/SandboxSession.java`
- Create: `sandbox-service/.../session/SandboxSessionStore.java`
- Create: `sandbox-service/.../session/SandboxSessionService.java`
- Create: `sandbox-service/.../api/SandboxSessionController.java`
- Create: `sandbox-service/.../api/CreateSessionRequest.java` 等 DTO
- Create: `sandbox-service/.../session/SandboxSessionServiceTest.java`（可用 mock DockerCli）

**约定：**
- 宿主机目录：`{host-data-root}/{sessionId}/skill`、`.../workspace`
- `docker run -d --name sunshine-sb-{id} --network none --read-only --tmpfs /tmp --memory --cpus --user 10001:10001 -v skill:/skill:ro -v workspace:/workspace --cap-drop ALL {image} sleep infinity`
- `network_allow` 非空时：`--network sunshine-sandbox-bridge` 并注入 `HTTP_PROXY`/`HTTPS_PROXY` 指向 egress（T7 完成；本任务先只实现 `none`，非空时暂抛 `501` 或同样 `none` 并打 warn — **选：非空暂拒绝 create，T7 再开**）

- [ ] **Step 1: DTO**

```java
// CreateSessionRequest
public record CreateSessionRequest(
        String userId,
        String tenantId,
        String skillId,
        String runId,
        SandboxPolicyDto policy,
        Map<String, String> skillFiles,      // path → text content
        Map<String, String> workspaceFiles    // optional seed
) {}

public record SandboxPolicyDto(
        String runtime,
        String image,
        Integer timeoutSec,
        Integer memoryMb,
        Double cpus,
        List<String> networkAllow,
        List<String> execReadonlyAllow
) {}

public record CreateSessionResponse(String sessionId) {}
```

- [ ] **Step 2: `DockerCli` 封装 `run`/`exec`/`rm -f`/`inspect`**（`ProcessBuilder`，超时读 stdout/stderr）

关键方法签名：

```java
public String runDetached(List<String> args); // returns containerId
public ExecResult exec(String containerId, List<String> cmd, Duration timeout);
public void removeForce(String containerIdOrName);
public boolean isRunning(String containerIdOrName);
```

- [ ] **Step 3: `SandboxSessionService.create`**
  1. 校验 `policy.networkAllow` 为空（T3）；非空 → `400`「网络白名单将在 T7 启用」或直接进入 T7 分支（实现时若 T7 已合并则调用）
  2. `sessionId = UUID`
  3. 写 skillFiles / workspaceFiles 到宿主机目录（相对路径必须在 `scripts/` 或 `references/` 下映射到 `/skill/...`；workspace 相对路径映射 `/workspace/...`）
  4. `docker run` 长容器，存入 `SandboxSessionStore`
  5. 返回 `sessionId`

- [ ] **Step 4: `DELETE /api/sandbox/sessions/{id}`** → `docker rm -f` + 删除宿主机目录（可配置 `retain-hours`，v1 立即删）

- [ ] **Step 5: Controller**

```java
@RestController
@RequestMapping("/api/sandbox/sessions")
@RequiredArgsConstructor
public class SandboxSessionController {
    private final SandboxSessionService sessions;

    @PostMapping
    public R<CreateSessionResponse> create(@RequestBody CreateSessionRequest req) {
        return R.ok(new CreateSessionResponse(sessions.create(req)));
    }

    @DeleteMapping("/{id}")
    public R<Void> close(@PathVariable String id) {
        sessions.close(id);
        return R.ok(null);
    }
}
```

- [ ] **Step 6: 单测用 FakeDockerCli** — create 写目录 + close 调 remove；不要求本机 Docker（CI 友好）

- [ ] **Step 7: 本机有 Docker 时手测**

```bash
curl -s -X POST http://localhost:8226/api/sandbox/sessions \
  -H 'Content-Type: application/json' \
  -d '{"skillId":"demo","runId":"r1","policy":{"image":"sunshine-sandbox-python:3.11-slim","networkAllow":[]},"skillFiles":{"scripts/hello.py":"print(1)"},"workspaceFiles":{}}'
```

Expected: `{"code":0,"data":{"sessionId":"..."}}`；`docker ps` 见 `sunshine-sb-*`

- [ ] **Step 8: Commit**

```bash
git add sandbox-service
git commit -m "$(cat <<'EOF'
feat(sandbox): session create/close with long-lived docker container

EOF
)"
```

---

## Task T4: 工具执行 API + read/write/edit

**Files:**
- Create: `sandbox-service/.../tool/SandboxToolExecutor.java`
- Create: `sandbox-service/.../tool/SandboxToolNames.java`
- Modify: `SandboxSessionController` 增加 `POST .../tools/{name}`
- Test: `SandboxToolExecutorTest`（临时目录模拟容器内 FS，或 FakeDockerCli.exec）

**工具名（URL path，无前缀）：** `read` | `write` | `edit` | `glob` | `grep` | `exec`  
（Orchestrator 侧 LLM 名是 `sandbox__read` 等，RPC 时剥前缀。）

- [ ] **Step 1: 单测 — edit 精确替换与不唯一失败**

```java
@Test
void editReplacesOnce() {
    // 在 session workspace 写 "aaa\nbbb\naaa\n"
    // edit old=bbb new=CCC → ok
    // edit old=aaa → IllegalArgumentException not unique
}
```

- [ ] **Step 2: 实现 `read`/`write`/`edit`**
  - **推荐 v1**：宿主机直接读写 bind 目录（与容器内路径一致），**不必**每次 `docker exec` 做文件 IO（更快、易测）；`exec`/`grep`/`glob` 仍可在宿主机对 workspace+skill 目录操作，或 `docker exec`。统一在宿主机 Path 上操作（jail 用容器路径语义，映射到 host root）。

Host 映射：

```text
hostSkill = dataRoot/sessionId/skill
hostWorkspace = dataRoot/sessionId/workspace
container /skill/x → hostSkill/x
```

- [ ] **Step 3: `read` 支持 offset/limit；超大文件截断并在结果注明 `truncated=true`**

- [ ] **Step 4: Controller**

```java
@PostMapping("/{id}/tools/{name}")
public R<ToolInvokeResponse> invoke(
        @PathVariable String id,
        @PathVariable String name,
        @RequestBody Map<String, Object> body) {
    return R.ok(executor.invoke(id, name, body));
}
```

```java
public record ToolInvokeResponse(
        boolean ok,
        String output,
        Integer exitCode,
        Map<String, Object> meta
) {}
```

- [ ] **Step 5: 测试通过后 Commit**

```bash
git commit -m "$(cat <<'EOF'
feat(sandbox): implement read/write/edit tools with path jail

EOF
)"
```

---

## Task T5: glob / grep

**Files:**
- Modify: `SandboxToolExecutor.java`
- Test: `SandboxToolExecutorGlobGrepTest.java`

- [ ] **Step 1: 单测** — glob `**/*.py` 只返回 jail 内路径；grep 返回 `path:line:excerpt`；pattern 非法抛 400

- [ ] **Step 2: 实现** — 使用 `Files.walk` + `PathMatcher`（glob）；grep 用 `Pattern` 逐行（限制最大匹配数 200，防止爆炸；**不**截断单行模型内容，仅限制条数并在 meta 注明 `hitLimit`）

- [ ] **Step 3: Commit**

```bash
git commit -m "$(cat <<'EOF'
feat(sandbox): add glob and grep tools

EOF
)"
```

---

## Task T6: exec（会话共享状态 + 超时）

**Files:**
- Modify: `DockerCli` / `SandboxToolExecutor`
- Test: `SandboxExecTest`（`@EnabledIf` Docker 可用，或 Fake 记录命令）

- [ ] **Step 1: `exec` 经 `docker exec -w {cwd} {container} sh -lc {command}`**
  - cwd 经 `PathJail.resolveCwd`
  - timeout 默认 policy.timeoutSec，body 可覆盖；超时 `docker exec` 进程 destroyForcibly，返回 `ok=false, exitCode=-1, output=timeout`

- [ ] **Step 2: 共享状态手测** — 同 session `echo hi > /workspace/a.txt` 再 `cat /workspace/a.txt` 可见

- [ ] **Step 3: Commit**

```bash
git commit -m "$(cat <<'EOF'
feat(sandbox): add exec tool with timeout and shared fs

EOF
)"
```

---

## Task T7: 网络白名单（egress 代理）

**Files:**
- Create: `docker/sandbox/egress/Dockerfile`
- Create: `docker/sandbox/egress/tinyproxy.conf.template`
- Create: `sandbox-service/.../docker/EgressProxyManager.java`
- Modify: `SandboxSessionService.create`

**行为：**
- `network_allow` 空 → `--network none`，无代理 env
- 非空 → 确保 docker network `sunshine-sandbox-net` 存在；启动/复用 egress 容器（tinyproxy + ACL 由 allow 列表生成）；sandbox 容器 `--network sunshine-sandbox-net -e HTTP_PROXY=http://egress:8888 -e HTTPS_PROXY=... -e NO_PROXY=localhost`

- [ ] **Step 1: egress 镜像** — `alpine` + `tinyproxy`；入口脚本把 `ALLOW` 环境变量写成 ACL

- [ ] **Step 2: create 分支接好**；单测 Fake：非空 allow 时 DockerCli 收到的 args 含 `sunshine-sandbox-net` 与 `HTTP_PROXY`

- [ ] **Step 3: Live 手测 G6** — allow 空时 `docker exec ... curl https://example.com` 失败；allow `example.com` 时经代理成功（若环境无外网，至少断言 none 下失败）

- [ ] **Step 4: Commit**

```bash
git commit -m "$(cat <<'EOF'
feat(sandbox): egress allowlist proxy for network_allow

EOF
)"
```

---

## Task T8: skill-manager `sandbox_policy` 列 + Catalog

**Files:**
- Modify: `docker/mysql/init/12-sunshine-skill-manager.sql`（追加 ALTER；**禁止 Flyway**）
- Modify: `SkillVersionEntity.java` — `sandboxPolicyJson` / `@Column(name="sandbox_policy_json")`
- Modify: `SkillCatalogEntry`（skill-manager + orchestrator 镜像 record）加 `String sandbox`, `SandboxPolicy policy`（或 raw JSON string）
- Modify: `SkillCatalogRegistry` / Admin create-update 读写
- Modify: `sunshine-ui/src/api/skills.ts` 类型

**SQL 追加（文件末尾）：**

```sql
ALTER TABLE skill_version
    ADD COLUMN sandbox_policy_json JSON NULL COMMENT 'sandbox_policy' AFTER sandbox;
```

已有库手工执行同语句。

- [ ] **Step 1: 默认** — `sandbox=none` 时 `sandbox_policy_json` 可为 NULL；`sandbox=docker` 时 Admin 保存须带合法 JSON（image 等可缺省用服务默认）

- [ ] **Step 2: Catalog 详情 API 返回**

```json
{
  "id": "...",
  "sandbox": "docker",
  "sandboxPolicy": {
    "runtime": "docker",
    "image": "sunshine-sandbox-python:3.11-slim",
    "timeoutSec": 30,
    "memoryMb": 256,
    "cpus": 0.5,
    "networkAllow": [],
    "execReadonlyAllow": ["ls *", "pwd", "python -m pytest *"]
  }
}
```

- [ ] **Step 3: skill-manager 增加 **内部** 材料导出（供 orchestrator 拉文件）

`GET /api/skills/{id}/material` → `{ "files": { "scripts/a.py": "...", "references/b.md": "..." } }`  
（仅 scripts/ + references/；鉴权与现有 catalog 同级服务间调用）

- [ ] **Step 4: 单测 + Commit**

```bash
git commit -m "$(cat <<'EOF'
feat(skill): persist sandbox_policy and expose in catalog/material

EOF
)"
```

---

## Task T9: `/skills` UI — sandbox 开关与试跑入口（最小）

**Files:**
- Modify: `sunshine-ui/src/views/SkillsView.vue`（或详情子组件）
- Modify: `sunshine-ui/src/api/skills.ts`

- [ ] **Step 1: 版本详情展示** `sandbox` 下拉（`none` | `docker`）+ `sandbox_policy` JSON 编辑（v1 用 textarea + 校验 JSON；勿灰底，遵循 `--sun-black`）

- [ ] **Step 2: 「试跑」按钮** — 跳转 Chat 并预填 `@ {skillId} 请用沙箱工具：读取 /skill 下脚本，在 /workspace 写 test.txt，再 ls`  
  （复用现有 Chat 路由 query；**不**新建独立试跑后端，除非已有 — 当前无试跑 API 则走 Chat）

- [ ] **Step 3: `npx vue-tsc -b` PASS + Commit**

```bash
git commit -m "$(cat <<'EOF'
feat(ui): skills sandbox policy editor and try-run deep link

EOF
)"
```

---

## Task T10: Orchestrator `SandboxClient` + Nacos base-url

**Files:**
- Create: `orchestrator/.../client/SandboxClient.java`
- Modify: `docs/nacos/sunshine-orchestrator.yaml` — `sandbox-service.base-url: http://localhost:8226`
- Test: `SandboxClientTest`（MockWebServer 可选；或轻量契约测试）

```java
@Component
public class SandboxClient {
    @Value("${sandbox-service.base-url:http://localhost:8226}")
    private String baseUrl;

    public String createSession(CreateSessionRequest req) { ... }
    public ToolInvokeResponse invoke(String sessionId, String toolName, Map<String, Object> body) { ... }
    public void closeSession(String sessionId) { ... }
}
```

- [ ] **Step 1: 实现 + sync_nacos**

Run: `python scripts/sync_nacos.py`  
Expected: sunshine-orchestrator / sunshine-sandbox-service 已同步

- [ ] **Step 2: Commit**

```bash
git commit -m "$(cat <<'EOF'
feat(orchestrator): add SandboxClient for sandbox-service RPC

EOF
)"
```

---

## Task T11: `SandboxIds` + 六工具 AgentTool + HITL

**Files:**
- Create: `orchestrator/.../sandbox/SandboxIds.java`
- Create: `orchestrator/.../sandbox/SandboxHitlPolicy.java`
- Create: `orchestrator/.../sandbox/SandboxSessionHolder.java`
- Create: `orchestrator/.../sandbox/SandboxAgentTools.java`（一个 Component 注册 6 个 AgentTool，或 6 个小类）
- Modify: `ToolCatalogService.displayName` / `requiresConfirmation`
- Test: `SandboxHitlPolicyTest`

- [ ] **Step 1: IDs**

```java
public final class SandboxIds {
    public static final String READ = "sandbox__read";
    public static final String WRITE = "sandbox__write";
    public static final String EDIT = "sandbox__edit";
    public static final String GLOB = "sandbox__glob";
    public static final String GREP = "sandbox__grep";
    public static final String EXEC = "sandbox__exec";
    public static final List<String> ALL = List.of(READ, WRITE, EDIT, GLOB, GREP, EXEC);

    public static String rpcName(String toolId) {
        return toolId.startsWith("sandbox__") ? toolId.substring("sandbox__".length()) : toolId;
    }

    private SandboxIds() {}
}
```

- [ ] **Step 2: HITL 策略单测**

```java
assertThat(SandboxHitlPolicy.requiresConfirmation(SandboxIds.READ, Map.of())).isFalse();
assertThat(SandboxHitlPolicy.requiresConfirmation(SandboxIds.WRITE, Map.of())).isTrue();
assertThat(SandboxHitlPolicy.requiresConfirmation(SandboxIds.EXEC, Map.of("command", "ls"))).isFalse();
assertThat(SandboxHitlPolicy.requiresConfirmation(SandboxIds.EXEC, Map.of("command", "rm -rf /workspace"))).isTrue();
```

`execReadonlyAllow` 使用简单 glob：`*` → `.*` 正则，整命令匹配；来源：当前 session 的 policy（`SandboxSessionHolder.current().policy()`）。

- [ ] **Step 3: `ToolCatalogService`**

```java
public String displayName(String toolId) {
    if (RagTool.NAME.equals(toolId)) return "检索知识库";
    return switch (toolId) {
        case SandboxIds.READ -> "读文件";
        case SandboxIds.WRITE -> "写文件";
        case SandboxIds.EDIT -> "编辑文件";
        case SandboxIds.GLOB -> "查找文件";
        case SandboxIds.GREP -> "搜索内容";
        case SandboxIds.EXEC -> "执行命令";
        default -> find(toolId).map(ToolCatalogEntry::displayName).orElse(toolId);
    };
}

public boolean requiresConfirmation(String toolId) {
    if (SandboxIds.ALL.contains(toolId)) {
        // exec 依赖参数，Catalog 层对 EXEC 返回 true；具体白名单在工具内再判
        return SandboxHitlPolicy.catalogDefault(toolId);
    }
    return find(toolId).map(ToolCatalogEntry::requireConfirmation).orElse(false);
}
```

**注意：** `shouldConfirmForBridge` 只看 toolId。对 `sandbox__exec`：`catalogDefault` 返回 `true`，工具内若命中只读白名单则**跳过** `awaitConfirmation`；未命中则走 HITL。对 read/glob/grep：`catalogDefault=false`。

- [ ] **Step 4: 工具实现骨架（每个工具）**

```java
// callAsync → boundedElastic
String sessionId = SandboxSessionHolder.requireSessionId();
Map<String, Object> body = extractParams(param);
if (hitlConfirmationService.shouldConfirmForBridge(NAME, bridgeId)) {
    // EXEC：若 SandboxHitlPolicy.isReadonlyExec(command, allow) 则跳过
    boolean approved = hitlConfirmationService.awaitConfirmation(...);
    if (!approved) return denyResult(...);
}
ToolInvokeResponse resp = sandboxClient.invoke(sessionId, SandboxIds.rpcName(NAME), body);
audit(...); // T14
return ToolResultBlock.of(..., TextBlock.builder().text(resp.output()).build());
```

参数 schema 与详设 §5 一致（JSON Schema map）。

- [ ] **Step 5: Commit**

```bash
git commit -m "$(cat <<'EOF'
feat(orchestrator): sandbox__* AgentTools with HITL policy

EOF
)"
```

---

## Task T12: DynamicToolkitFactory 条件注入

**Files:**
- Modify: `DynamicToolkitFactory.java`
- Modify: `ReActAgentFactory` / toolkit 构建入参（需能读到当前 skill 的 sandbox 标志）
- Test: `DynamicToolkitFactoryTest` — sandbox=none 不注册；sandbox=docker 注册 6 个

**注入规则：**
- 当 `SkillCatalogService.find(skillId).map(e -> !"none".equalsIgnoreCase(e.sandbox())).orElse(false)` 为 true 时，在 MAIN **与** SUB 均 `registerAgentTool` 六个工具（子 Agent 绑定沙箱 Skill 时同样需要）。
- 无 skillId → 不注入。

- [ ] **Step 1: 扩展 orchestrator `SkillCatalogEntry` record** 增加 `sandbox`、`sandboxPolicy`

- [ ] **Step 2: Factory 注册**

```java
if (shouldAttachSandbox(skillId)) {
    for (AgentTool t : sandboxAgentTools.all()) {
        tk.registerAgentTool(t);
        registered.add(t.getName());
    }
}
```

- [ ] **Step 3: 测试 PASS + Commit**

```bash
git commit -m "$(cat <<'EOF'
feat(orchestrator): inject sandbox tools when skill.sandbox=docker

EOF
)"
```

---

## Task T13: Session 生命周期绑 `ReActAgentRuntime`

**Files:**
- Create: `orchestrator/.../sandbox/SandboxSessionLifecycle.java`
- Modify: `ReActAgentRuntime.java`（`run` 开头 create、`doFinally` close）
- Modify: skill material 拉取 — `SkillCatalogClient.fetchMaterial(skillId)`

```java
public final class SandboxSessionLifecycle {
    public void openIfNeeded(AgentRunRequest req) {
        if (!needsSandbox(req.skillId())) return;
        var detail = skillCatalogService.find(req.skillId()).orElseThrow();
        Map<String, String> files = skillCatalogClient.fetchMaterial(req.skillId());
        String sid = sandboxClient.createSession(...);
        SandboxSessionHolder.bind(sid, detail.sandboxPolicy());
    }
    public void closeQuietly() {
        String sid = SandboxSessionHolder.unbind();
        if (sid != null) sandboxClient.closeSession(sid);
    }
}
```

- [ ] **Step 1: `ReActAgentRuntime.run`**

```java
sandboxSessionLifecycle.openIfNeeded(request);
try {
    // existing agent call
} finally {
    sandboxSessionLifecycle.closeQuietly();
}
```

（若现有为 Reactor 链，用 `doFinally`；保证取消/错误也 close。）

- [ ] **Step 2: 单测 mock SandboxClient — open/close 各一次**

- [ ] **Step 3: Commit**

```bash
git commit -m "$(cat <<'EOF'
feat(orchestrator): bind sandbox session to AgentRuntime run lifecycle

EOF
)"
```

---

## Task T14: 审计 + Micrometer + Grafana 面板

**Files:**
- Modify: sandbox 工具调用处 → `ToolAuditService.toolCall`（params 对 `content`/`new_string` 存 sha256，不存全文）
- Modify: `sandbox-service` — `sandbox.session.active` Gauge、`sandbox.tool.invoke` Counter/Timer
- Create: `docs/grafana/sandbox-dashboard.json`（可简：活跃会话、工具 QPS、exec 失败）
- Optional: `docker/grafana/provisioning/...` 挂载

- [ ] **Step 1: 审计 payload 字段** — `sessionId`, `toolId`, `paramDigest`, `exitCode`, `durationMs`, `skillId`, `runId`

- [ ] **Step 2: Commit**

```bash
git commit -m "$(cat <<'EOF'
feat(sandbox): tool.call audit digests and grafana metrics

EOF
)"
```

---

## Task T15: Nacos 提示词偏好（禁止硬编码业务 prompt）

**Files:**
- Modify: `docs/nacos/sunshine-orchestrator.yaml` — `agent.prompt.mode-overlays.react` 或 skill overlay 说明追加一句：

```text
若可用 sandbox__* 工具：读写与搜索优先 sandbox__read/write/edit/glob/grep，避免用 sandbox__exec 代替。
```

- [ ] **Step 1: sync_nacos + 重启 orchestrator**

- [ ] **Step 2: Commit**

```bash
git commit -m "$(cat <<'EOF'
docs(nacos): prefer sandbox file tools over exec

EOF
)"
```

---

## Task T16: Live 验收脚本 G1–G9

**Files:**
- Create: `scripts/verify_sandbox_live.py`
- Modify: `docs/superpowers/specs/2026-07-15-skills-docker-sandbox-design.md` 状态 → 实施中/验收
- Modify: `CLAUDE.md` 运维表增加一行 `verify_sandbox_live.py`
- Modify: `docs/implementation-plan.md` 4.5 行指向本计划

**脚本结构：**

```python
# 环境: GATEWAY_URL, SANDBOX_URL=http://ecs4c16g:8226, SKILL_ID=...
# G1: catalog/toolkit 探测 — 无 sandbox skill 的 react 不应出现 sandbox__（可通过 debug API 或 SSE tool 步断言）
# G2-G5: 直连 sandbox-service create → read/write/edit/glob/grep/exec → close；越狱 path 期望 4xx
# G6: network none 下 exec curl 失败
# G7: Chat SSE：write 出现 HITL pending（若 HITL 开）
# G8: close 后 docker ps 无容器；审计 recent 可查
# G9: Chat @skill 试跑 read→edit→exec 闭环（需预置 sandbox=docker 测试 Skill）
```

- [ ] **Step 1: 预置测试 Skill**（Admin API 或 SQL）：`sandbox=docker`，含 `scripts/sample.py`

- [ ] **Step 2: Run**

```bash
python scripts/verify_sandbox_live.py --suite all
```

Expected: 各 G 项 PASS

- [ ] **Step 3: Commit**

```bash
git commit -m "$(cat <<'EOF'
test(sandbox): add verify_sandbox_live G1-G9 gate

EOF
)"
```

---

## 自检（对照详设）

| 详设项 | 任务 |
|--------|------|
| 独立 :8226 + 会话长容器 | T0 T3 |
| 六工具契约 | T4–T6 T11 |
| Path jail / 只写 workspace | T2 T4 |
| network_allow + egress | T7 |
| 不进 Catalog、Factory 注入 | T12 |
| HITL 读免写确认 / exec 白名单 | T11 |
| AgentRuntime create/close | T13 |
| skill sandbox_policy | T8 T9 |
| 审计 / Grafana | T14 |
| Live G1–G9 | T16 |
| 非目标（WebFetch、Catalog 登记等） | 未列入 — OK |

**类型一致性：** Tool ID 一律 `sandbox__*`；RPC path 短名；`SandboxPolicyDto` 字段与 Catalog JSON camelCase 对齐（Java record + Jackson）。

---

## 风险与注意

1. **Windows 开发机**：需 Docker Desktop；`host-data-root` 路径在 Nacos 可改。
2. **禁止**把六工具写入 tool-manager DB。
3. **禁止**对模型/工具输出做截断兜底（read 的 truncated 标记是工具契约内声明，不是二次加工答案）。
4. 改 Nacos 后必须 `sync_nacos.py` 并重启消费服务。
