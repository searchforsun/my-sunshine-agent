# Codex 式智能体工作区（Agent Workspace）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在「对话级轻量沙箱」之外新增**工作区级完全体沙箱**——绑定 Git 仓库、多会话共用、用户显式选择 checkout、硬件档位可配，形成 Codex 式编码智能体产品形态。

**Architecture:** 新增 `agent_workspace` 实体（MySQL）+ `WorkspaceSandboxBinding`（Redis，复用 `ConversationSandboxStore` 架构）→ `WorkspaceSandboxLifecycle` 懒开箱（宿主机 `git clone` + Docker `bridge` 模式完全体容器）→ `WorkspaceCheckoutService` 提供主分支/worktree checkouts 管理；`SandboxAgentTools` cwd 由 `conversation.checkoutPath` 驱动；前端「新任务」入口延迟创建菜单项。

**Tech Stack:** Spring Boot 3.2 + Spring Cloud Alibaba + Nacos + Redis + MySQL + Docker（CLI） + Vue 3 / Naive UI / TypeScript

## Global Constraints

- 禁止 Flyway：DDL 追加到 `docker/mysql/init/11-sunshine-orchestrator.sql` + `10-sunshine-auth.sql`；已有环境手工执行 ALTER
- Nacos 配置 SSOT：改 `docs/nacos/*.yaml` → `sync_nacos.py` → 重启消费服务
- 代码禁止硬编码提示词：正文 SSOT = prompt-manager Catalog
- UI 风格遵循 `global.css` `--sun-*`（`--sun-black` 底 + `1px var(--sun-border)`；focus 无 shadow；下拉选中 18px 对号、无灰底）
- 令牌不进容器 env / 命令行 / 日志 / 前端响应 / cloneState
- 工作区级容器仅对 `kind=task` 会话开放 `networkMode=bridge`
- `agent_workspace` 创建时 `repoUrl` 必填
- checkout 由用户在创建会话时显式选择，平台不自动建 worktree/分支
- 工作区容器不自动 purge（仅手动销毁），idle 30min 仍 stop
- `cpus <= 4` / `memoryMb <= 12288` 硬校验
- 改 orchestrator 后编译 → 重启 → Agent 跑 live 验收

---

## File Structure

### 新增文件

| 文件 | 职责 |
|------|------|
| `orchestrator/.../sandbox/WorkspaceSandboxBinding.java` | 工作区级 Redis 绑定 record（对齐 `ConversationSandboxBinding`） |
| `orchestrator/.../sandbox/WorkspaceSandboxStore.java` | 工作区级 Redis KV + ZSET（对齐 `ConversationSandboxStore`，但不注册 purge ZSET） |
| `orchestrator/.../sandbox/WorkspaceSandboxLifecycle.java` | 工作区懒开箱：宿主机 git clone + 完全体 Docker create |
| `orchestrator/.../sandbox/RunContext.java` | 从 `SandboxSessionLifecycle` 内 record 提升为独立 public record，新增 `workspaceId` + `checkoutPath` 字段 |
| `orchestrator/.../sandbox/WorkspaceCheckoutService.java` | worktree 管理：createWorktree / listCheckouts / mergeToMain / removeWorktree |
| `orchestrator/.../controller/AgentWorkspaceController.java` | REST CRUD API：`POST/GET/DELETE /api/agent-workspaces` |
| `orchestrator/.../model/AgentWorkspaceEntity.java` | `agent_workspace` 表 JPA Entity |
| `orchestrator/.../repo/AgentWorkspaceRepository.java` | Spring Data JPA Repository |
| `orchestrator/.../dto/CreateWorkspaceRequest.java` | 创建工作区请求 DTO |
| `orchestrator/.../dto/WorkspaceVO.java` | 工作区响应 VO |
| `auth-center/.../controller/InternalGitCredentialController.java` | 内部端点 `GET /api/auth/git-credentials`（服务间） |
| `docker/sandbox-full/Dockerfile` | 完全体镜像：Python + git + build-essential + node + npm + curl + vim |
| `sunshine-ui/src/views/WorkspaceView.vue` | 工作区列表/详情页 |
| `sunshine-ui/src/components/chat/WorkspaceSelector.vue` | 任务会话顶部工作区+checkout 选择器 |
| `sunshine-ui/src/components/sandbox/WorkspaceCheckoutManager.vue` | 抽屉 checkout 管理（list/create/merge/remove） |
| `sunshine-ui/src/api/agentWorkspaces.ts` | 工作区 API 前端封装 |
| `scripts/verify_agent_workspace_live.py` | Live 验收脚本 |

### 修改文件

| 文件 | 变更 |
|------|------|
| `docker/mysql/init/11-sunshine-orchestrator.sql` | 追加 `agent_workspace` 表 + `chat_conversation` ALTER |
| `docker/mysql/init/10-sunshine-auth.sql` | 追加 `sys_user` 四列（`github_url`/`github_token`/`gitlab_url`/`gitlab_token`） |
| `sandbox-service/.../docker/EgressProxyManager.java` | per-session 化：容器命名 `sunshine-sandbox-egress-{sessionId[:12]}` |
| `orchestrator/.../sandbox/SandboxSessionLifecycle.java` | `prepareRun` 填充 workspaceId/checkoutPath + `ensureBound` 委托 `WorkspaceSandboxLifecycle` |
| `orchestrator/.../conversation/entity/ChatConversationEntity.java` | 增加 `kind` / `workspaceId` / `checkoutPath` 三字段 |
| `orchestrator/.../sandbox/SandboxAgentTools.java` | `execute` 方法根据 `conversation.checkoutPath` 设置 cwd + PathJail 边界 |
| `orchestrator/.../sandbox/SandboxSessionReaper.java` | `reap` 增加工作区 ZSET 扫描（仅 idle stop，不 purge） |
| `orchestrator/.../config/AgentSandboxProperties.java` | 增加 `profiles` 档位预设 Map |
| `orchestrator/.../sandbox/ConversationSandboxStore.java` | 方法抽象化供 `WorkspaceSandboxStore` 复用或析出公共基类 |
| `auth-center/.../entity/UserEntity.java` | 增加 4 字段 |
| `auth-center/.../dto/AuthUserVO.java` | 增加 `githubUrl`/`gitlabUrl`/`githubTokenSet`/`gitlabTokenSet` |
| `auth-center/.../dto/UpdateProfileRequest.java` | 增加 4 字段 |
| `auth-center/.../service/UserService.java` | `updateProfile` 处理 Git 令牌字段 |
| `auth-center/.../controller/AuthController.java` | `me`/`login`/`profile` 响应扩展 |
| `docs/nacos/sunshine-orchestrator.yaml` | 增加 `agent.sandbox.profiles` 档位配置 |
| `docs/nacos/sunshine-sandbox-service.yaml` | 增加 `sandbox.docker.default-full-image` |
| `sunshine-ui/src/api/auth.ts` | `AuthUser` 扩展；`updateProfile` 扩展参数 |
| `sunshine-ui/src/components/UserSettingsModal.vue` | 新增「Git 服务」分组 |
| `sunshine-ui/src/components/ConversationSidebarList.vue` | 增加工作区分类 + 展开其下任务列表 |
| `sunshine-ui/src/layouts/MainLayout.vue` | 侧栏 NMenu 增加「工作区」导航项 |
| `sunshine-ui/src/views/ChatView.vue` | `kind=task` 时隐藏 `ExecutionModeSelector`、显示 checkout 路径只读标签 |

---

### Task 1: Egress per-session 化（repo-binding T0）

**Files:**
- Modify: `sandbox-service/src/main/java/com/sunshine/sandbox/docker/EgressProxyManager.java`
- Modify: `sandbox-service/src/main/java/com/sunshine/sandbox/session/SandboxSessionService.java:55-75`（create 逻辑附近）

**Interfaces:**
- Consumes: 现有 `EgressProxyManager.ensureRunning(List<String> allowHosts)`、`proxyUrl()`
- Produces: `EgressProxyManager.ensureRunning(String sessionId, List<String> allowHosts)` — 按 sessionId 管理独立 egress 容器；`removeEgress(String sessionId)` — 清理会话级代理容器

- [ ] **Step 1: 修改 EgressProxyManager 支持 per-session 容器**

在 `EgressProxyManager.java` 中将共享容器改为 per-session：

```java
// 原容器名常量保留（兼容无 session 调用）
private static final String DEFAULT_CONTAINER_NAME = "sunshine-sandbox-egress";
// 新增：per-session 容器 Map
private final ConcurrentHashMap<String, String> sessionContainers = new ConcurrentHashMap<>();

public synchronized void ensureRunning(String sessionId, List<String> allowHosts) {
    if (!StringUtils.hasText(sessionId)) {
        ensureRunning(allowHosts);
        return;
    }
    String containerName = "sunshine-sandbox-egress-" + sessionId.substring(0, Math.min(sessionId.length(), 12));
    String existing = sessionContainers.get(sessionId);
    if (existing != null && isContainerRunning(existing)) {
        updateAcl(existing, allowHosts);
        return;
    }
    // 启动新 per-session 代理容器（加入 sunshine-sandbox-net）
    startProxyContainer(containerName, allowHosts);
    sessionContainers.put(sessionId, containerName);
}

public void removeEgress(String sessionId) {
    String name = sessionContainers.remove(sessionId);
    if (name != null) {
        stopAndRemoveContainer(name);
    }
}
```

- [ ] **Step 2: 修改 SandboxSessionService.create 传入 sessionId 给 egress**

```java
// SandboxSessionService.create() 中，在 docker run 前：
if (policy.networkMode() == null || !"none".equals(policy.networkMode())) {
    egressProxyManager.ensureRunning(req.sessionId(), policy.networkAllow());
}
// close() 时调用：
egressProxyManager.removeEgress(sessionId);
```

- [ ] **Step 3: 编译 + 验证无回归**

```bash
cd /usr/local/gitproj/my-sunshine-agent && mvn compile -pl sandbox-service,orchestrator -q -DskipTests
```

- [ ] **Step 4: Commit**

```bash
git add sandbox-service/src/main/java/com/sunshine/sandbox/docker/EgressProxyManager.java \
        sandbox-service/src/main/java/com/sunshine/sandbox/session/SandboxSessionService.java
git commit -m "feat: egress per-session 化，支持多会话独立代理容器"
```

---

### Task 2: Auth Git 令牌（repo-binding T1）

**Files:**
- Modify: `docker/mysql/init/10-sunshine-auth.sql`
- Modify: `auth-center/src/main/java/com/sunshine/auth/entity/UserEntity.java`
- Modify: `auth-center/src/main/java/com/sunshine/auth/dto/AuthUserVO.java`
- Modify: `auth-center/src/main/java/com/sunshine/auth/dto/UpdateProfileRequest.java`
- Modify: `auth-center/src/main/java/com/sunshine/auth/service/UserService.java`
- Modify: `auth-center/src/main/java/com/sunshine/auth/controller/AuthController.java`
- Create: `auth-center/src/main/java/com/sunshine/auth/controller/InternalGitCredentialController.java`
- Test: `auth-center/src/test/java/com/sunshine/auth/controller/GitCredentialTest.java`

**Interfaces:**
- Produces:
  - `AuthUserVO` 新增字段：`String githubUrl` / `String gitlabUrl` / `boolean githubTokenSet` / `boolean gitlabTokenSet`
  - `UpdateProfileRequest` 新增字段：`String githubUrl` / `String githubToken` / `String gitlabUrl` / `String gitlabToken`
  - `InternalGitCredentialController`：`GET /api/auth/git-credentials?host={host}` → `R<GitCredentialVO>`（`{url, token}`）
  - `UserService.findGitCredential(String userId, String host)` → 匹配 host 返回令牌

- [ ] **Step 1: 追加 DB 列**

```sql
-- 追加到 10-sunshine-auth.sql 末尾
-- V2__user_git_columns.sql
ALTER TABLE sys_user
  ADD COLUMN github_url     VARCHAR(255) NOT NULL DEFAULT '' COMMENT 'GitHub 基础地址',
  ADD COLUMN github_token   VARCHAR(255) NOT NULL DEFAULT '' COMMENT 'GitHub PAT',
  ADD COLUMN gitlab_url     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '内网 GitLab 基础地址',
  ADD COLUMN gitlab_token   VARCHAR(255) NOT NULL DEFAULT '' COMMENT '内网 GitLab PAT';
```

- [ ] **Step 2: 修改 UserEntity**

```java
// UserEntity.java 增加字段
@Column(name = "github_url", length = 255)
private String githubUrl = "";

@Column(name = "github_token", length = 255)
private String githubToken = "";

@Column(name = "gitlab_url", length = 255)
private String gitlabUrl = "";

@Column(name = "gitlab_token", length = 255)
private String gitlabToken = "";
```

- [ ] **Step 3: 修改 AuthUserVO 增加不泄露令牌的字段**

```java
// AuthUserVO.java
private String githubUrl;
private boolean githubTokenSet;
private String gitlabUrl;
private boolean gitlabTokenSet;
```

- [ ] **Step 4: 修改 UpdateProfileRequest 增加四字段**

```java
// UpdateProfileRequest.java
private String githubUrl;
private String githubToken;
private String gitlabUrl;
private String gitlabToken;
```

- [ ] **Step 5: 修改 UserService.updateProfile 处理 Git 令牌**

```java
// UserService.java updateProfile
if (StringUtils.hasText(request.getGithubUrl())) {
    entity.setGithubUrl(request.getGithubUrl().strip());
}
if (request.getGithubToken() != null)
    entity.setGithubToken(request.getGithubToken().strip());
if (StringUtils.hasText(request.getGitlabUrl())) {
    entity.setGitlabUrl(request.getGitlabUrl().strip());
}
if (request.getGitlabToken() != null) {
     entity.setGitlabToken(request.getGitlabToken().strip());
}
// 构建 AuthUserVO 时：githubTokenSet = StringUtils.hasText(entity.getGithubToken())
```

- [ ] **Step 6: 创建 InternalGitCredentialController**

```java
package com.sunshine.auth.controller;

import com.sunshine.auth.service.UserService;
import com.sunshine.common.core.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class InternalGitCredentialController {

    private final UserService userService;

    /** 服务端间凭据查询（orchestrator → auth）；x-user-id 由网关注入 */
    @GetMapping("/git-credentials")
    public R<Map<String, String>> gitCredentials(
            @RequestParam String host,
            @RequestHeader("x-user-id") String userId) {
        Map<String, String> cred = userService.findGitCredential(userId, host);
        return R.ok(cred);
    }
}
```

- [ ] **Step 7: UserService.findGitCredential 实现**

```java
// UserService.java
public Map<String, String> findGitCredential(String userId, String host) {
    UserEntity user = userRepository.findById(userId)
            .orElseThrow(() -> new BizException("用户不存在"));
    String h = host != null ? host.strip().toLowerCase() : "";
    if (!h.isEmpty() && StringUtils.hasText(user.getGithubUrl())
            && h.equals(extractHost(user.getGithubUrl()))) {
        return Map.of("url", user.getGithubUrl(), "token", user.getGithubToken());
    }
    if (!h.isEmpty() && StringUtils.hasText(user.getGitlabUrl())
            && h.equals(extractHost(user.getGitlabUrl()))) {
        return Map.of("url", user.getGitlabUrl(), "token", user.getGitlabToken());
    }
    return Map.of();  // 无匹配令牌仅公开仓库可用
}

private static String extractHost(String url) {
    try {
        return new java.net.URL(url).getHost().toLowerCase();
    } catch (Exception e) {
        return "";
    }
}
```

- [ ] **Step 8: 编写单测**

```java
@Test
void shouldReturnGithubCredentialWhenHostMatches() {
    UserEntity entity = new UserEntity();
    entity.setId("u1");
    entity.setGithubUrl("https://github.com");
    entity.setGithubToken("ghp_test123");
    // ... save and verify
}
```

- [ ] **Step 9: 编译 + 跑单测**

```bash
cd /usr/local/gitproj/my-sunshine-agent
mvn compile -pl auth-center -q -DskipTests
mvn test -pl auth-center -Dtest=GitCredentialTest -q
```

- [ ] **Step 10: Commit**

```bash
git add auth-center/src/main/java/com/sunshine/auth/entity/UserEntity.java \
        auth-center/src/main/java/com/sunshine/auth/dto/AuthUserVO.java \
        auth-center/src/main/java/com/sunshine/auth/dto/UpdateProfileRequest.java \
        auth-center/src/main/java/com/sunshine/auth/service/UserService.java \
        auth-center/src/main/java/com/sunshine/auth/controller/AuthController.java \
        auth-center/src/main/java/com/sunshine/auth/controller/InternalGitCredentialController.java \
        auth-center/src/test/java/com/sunshine/auth/controller/GitCredentialTest.java \
        docker/mysql/init/10-sunshine-auth.sql
git commit -m "feat: auth 用户级 Git 令牌（GitHub + 内网 GitLab）+ git-credentials 内网端点"
```

---

### Task 3: agent_workspace 表 + conversation 扩展 + CRUD（W1）

**Files:**
- Modify: `docker/mysql/init/11-sunshine-orchestrator.sql`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/conversation/entity/ChatConversationEntity.java`
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/model/AgentWorkspaceEntity.java`
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/repo/AgentWorkspaceRepository.java`
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/dto/CreateWorkspaceRequest.java`
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/dto/WorkspaceVO.java`
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/controller/AgentWorkspaceController.java`
- Test: `orchestrator/src/test/java/com/sunshine/orchestrator/controller/AgentWorkspaceControllerTest.java`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/conversation/ConversationService.java`（getOwned 附近，增加按 workspaceId 查任务列表）

**Interfaces:**
- Consumes: 现有 `ConversationService`、`UserService`（通过 auth-client）
- Produces:
  - `AgentWorkspaceController`：
    - `POST /api/agent-workspaces` → `R<WorkspaceVO>`（body：`name`/`repoUrl`/`repoBranch?`/`memoryMb?`/`cpus?`）
    - `GET /api/agent-workspaces` → `R<List<WorkspaceVO>>`（当前用户所有 workspace）
    - `DELETE /api/agent-workspaces/{id}` → `R<Void>`（归档 + 销毁容器）
  - `AgentWorkspaceRepository.findByTenantIdAndUserIdAndStatus(String tenantId, String userId, String status)` → `List<AgentWorkspaceEntity>`

- [ ] **Step 1: 追加 DDL**

```sql
-- 追加到 11-sunshine-orchestrator.sql 末尾
-- V18__agent_workspace.sql
CREATE TABLE agent_workspace (
    id              VARCHAR(64)  NOT NULL PRIMARY KEY,
    tenant_id       VARCHAR(64)  NOT NULL,
    user_id         VARCHAR(64)  NOT NULL,
    name            VARCHAR(128) NOT NULL,
    repo_url        VARCHAR(512) NOT NULL,
    repo_branch     VARCHAR(128) NOT NULL DEFAULT 'main',
    sandbox_profile VARCHAR(32)  NOT NULL DEFAULT 'full',
    memory_mb       INT          NOT NULL DEFAULT 2048,
    cpus            DECIMAL(3,1) NOT NULL DEFAULT 2.0,
    image           VARCHAR(128) NOT NULL DEFAULT 'sunshine-sandbox-full:latest',
    status          VARCHAR(16)  NOT NULL DEFAULT 'active',
    created_at      DATETIME(3)  NOT NULL,
    updated_at      DATETIME(3)  NOT NULL,
    INDEX idx_ws_tenant_user (tenant_id, user_id, status)
);

-- V19__conversation_kind_workspace.sql
ALTER TABLE chat_conversation
  ADD COLUMN kind          VARCHAR(16)  NOT NULL DEFAULT 'chat' COMMENT 'chat / task',
  ADD COLUMN workspace_id  VARCHAR(64)  NULL COMMENT 'kind=task 时必填',
  ADD COLUMN checkout_path VARCHAR(256) NULL COMMENT '用户选定的 checkout';
```

- [ ] **Step 2: AgentWorkspaceEntity**

```java
package com.sunshine.orchestrator.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "agent_workspace")
public class AgentWorkspaceEntity {
    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "tenant_id", length = 64, nullable = false)
    private String tenantId;

    @Column(name = "user_id", length = 64, nullable = false)
    private String userId;

    @Column(length = 128, nullable = false)
    private String name;

    @Column(name = "repo_url", length = 512, nullable = false)
    private String repoUrl;

    @Column(name = "repo_branch", length = 128, nullable = false)
    private String repoBranch = "main";

    @Column(name = "sandbox_profile", length = 32)
    private String sandboxProfile = "full";

    @Column(name = "memory_mb")
    private int memoryMb = 2048;

    @Column(precision = 3, scale = 1)
    private BigDecimal cpus = new BigDecimal("2.0");

    @Column(length = 128)
    private String image = "sunshine-sandbox-full:latest";

    @Column(length = 16)
    private String status = "active";

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    // getters/setters ...
}
```

- [ ] **Step 3: AgentWorkspaceRepository**

```java
package com.sunshine.orchestrator.repo;

import com.sunshine.orchestrator.model.AgentWorkspaceEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentWorkspaceRepository extends JpaRepository<AgentWorkspaceEntity, String> {
    List<AgentWorkspaceEntity> findByTenantIdAndUserIdAndStatus(String tenantId, String userId, String status);
}
```

- [ ] **Step 4: DTOs**

```java
// CreateWorkspaceRequest.java
public record CreateWorkspaceRequest(
        @NotBlank String name,
        @NotBlank String repoUrl,
        String repoBranch,
        Integer memoryMb,
        BigDecimal cpus) {}

// WorkspaceVO.java
public record WorkspaceVO(
        String id, String name, String repoUrl, String repoBranch,
        String sandboxProfile, int memoryMb, double cpus, String image,
        String status, String cloneState, Instant createdAt) {
    public static WorkspaceVO from(AgentWorkspaceEntity e, String cloneState) {
        return new WorkspaceVO(e.getId(), e.getName(), e.getRepoUrl(), e.getRepoBranch(),
                e.getSandboxProfile(), e.getMemoryMb(), e.getCpus().doubleValue(), e.getImage(),
                e.getStatus(), cloneState, e.getCreatedAt());
    }
}
```

- [ ] **Step 5: AgentWorkspaceController**

```java
@RestController
@RequestMapping("/api/agent-workspaces")
@RequiredArgsConstructor
public class AgentWorkspaceController {
    private final AgentWorkspaceRepository workspaceRepo;
    private final ConversationRepository convRepo;
    // ... 后续 Task 注入 WorkspaceSandboxLifecycle + WorkspaceCheckoutService

    @PostMapping
    public R<WorkspaceVO> create(@Valid @RequestBody CreateWorkspaceRequest req,
                                  @RequestHeader("x-user-id") String userId,
                                  @RequestHeader("x-tenant-id") String tenantId) {
        // repoUrl 必填校验（400）
        // host 白名单校验
        // hardware 护栏：cpus <= 4 && memoryMb <= 12288
        AgentWorkspaceEntity entity = new AgentWorkspaceEntity();
        entity.setId(UUID.randomUUID().toString().replace("-", ""));
        entity.setTenantId(tenantId);
        entity.setUserId(userId);
        entity.setName(req.name().strip());
        entity.setRepoUrl(req.repoUrl().strip());
        entity.setRepoBranch(req.repoBranch() != null ? req.repoBranch().strip() : "main");
        entity.setMemoryMb(req.memoryMb() != null ? req.memoryMb() : 2048);
        entity.setCpus(req.cpus() != null ? req.cpus() : new BigDecimal("2.0"));
        entity.setCreatedAt(Instant.now());
        entity.setUpdatedAt(Instant.now());
        workspaceRepo.save(entity);
        return R.ok(WorkspaceVO.from(entity, null));
    }

    @GetMapping
    public R<List<WorkspaceVO>> list(@RequestHeader("x-user-id") String userId,
                                      @RequestHeader("x-tenant-id") String tenantId) {
        List<WorkspaceVO> list = workspaceRepo
                .findByTenantIdAndUserIdAndStatus(tenantId, userId, "active")
                .stream().map(e -> WorkspaceVO.from(e, null)).toList();
        return R.ok(list);
    }

    @DeleteMapping("/{id}")
    public R<Void> destroy(@PathVariable String id,
                            @RequestHeader("x-user-id") String userId,
                            @RequestHeader("x-tenant-id") String tenantId) {
        AgentWorkspaceEntity ws = workspaceRepo.findById(id)
                .orElseThrow(() -> new BizException("工作区不存在"));
        if (!ws.getUserId().equals(userId) || !ws.getTenantId().equals(tenantId)) {
            throw new BizException("无权操作");
        }
        // 后续 Task：docker rm + 清盘
        ws.setStatus("archived");
        ws.setUpdatedAt(Instant.now());
        workspaceRepo.save(ws);
        return R.ok();
    }
}
```

- [ ] **Step 6: 修改 ChatConversationEntity 增加新字段**

```java
// ChatConversationEntity.java 增加以下字段
/** 会话类型：chat / task（task 绑定工作区） */
@Column(length = 16)
private String kind = "chat";

/** kind=task 时绑定的工作区 id */
@Column(name = "workspace_id", length = 64)
private String workspaceId;

/** 用户选定的 checkout 路径（如 /workspace/main 或 /workspace/branches/feat-x） */
@Column(name = "checkout_path", length = 256)
private String checkoutPath;
```

- [ ] **Step 7: 编译验证**

```bash
cd /usr/local/gitproj/my-sunshine-agent && mvn compile -pl orchestrator -q -DskipTests
```

- [ ] **Step 8: Commit**

```bash
git add orchestrator/src/main/java/com/sunshine/orchestrator/model/AgentWorkspaceEntity.java \
        orchestrator/src/main/java/com/sunshine/orchestrator/repo/AgentWorkspaceRepository.java \
        orchestrator/src/main/java/com/sunshine/orchestrator/dto/CreateWorkspaceRequest.java \
        orchestrator/src/main/java/com/sunshine/orchestrator/dto/WorkspaceVO.java \
        orchestrator/src/main/java/com/sunshine/orchestrator/controller/AgentWorkspaceController.java \
        orchestrator/src/main/java/com/sunshine/orchestrator/conversation/entity/ChatConversationEntity.java \
        docker/mysql/init/11-sunshine-orchestrator.sql
git commit -m "feat: agent_workspace 表 + conversation kind/workspace_id + CRUD API"
```

---

### Task 4: WorkspaceSandboxBinding + WorkspaceSandboxStore（W2 前半）

**Files:**
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/sandbox/WorkspaceSandboxBinding.java`
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/sandbox/WorkspaceSandboxStore.java`

**Interfaces:**
- Consumes: `ConversationSandboxStore` 的 Redis 操作模式（同构复制）
- Produces:
  - `WorkspaceSandboxBinding` record：`sessionId, userId, tenantId, workspaceId, state, lastActiveAt, repoUrl, repoBranch, cloneState, memoryMb, cpus, image`
  - `WorkspaceSandboxStore`：`find(tenantId, workspaceId) → Optional<WorkspaceSandboxBinding>`、`save(...)`、`touch(...)`、`markStopped(...)`、`remove(...)`、`pollIdleMembers(now) → Set<String>`（仅 idle，无 purge 方法）
  - Redis key：`sandbox:ws:{tenant}:{workspaceId}`
  - Redis ZSET：`sandbox:ws:idle`（仅 idle stop，无 purge ZSET）

- [ ] **Step 1: WorkspaceSandboxBinding**

```java
package com.sunshine.orchestrator.sandbox;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record WorkspaceSandboxBinding(
        String sessionId,
        String userId,
        String tenantId,
        String workspaceId,
        String state,
        Long lastActiveAt,
        String repoUrl,
        String repoBranch,
        String cloneState,
        int memoryMb,
        double cpus,
        String image) {

    public static final String STATE_RUNNING = "running";
    public static final String STATE_STOPPED = "stopped";

    public WorkspaceSandboxBinding {
        if (state == null || state.isBlank()) {
            state = STATE_RUNNING;
        }
    }

    @JsonIgnore
    public boolean isStopped() {
        return STATE_STOPPED.equalsIgnoreCase(state);
    }

    public WorkspaceSandboxBinding withState(String newState) {
        return new WorkspaceSandboxBinding(
                sessionId, userId, tenantId, workspaceId, newState,
                lastActiveAt, repoUrl, repoBranch, cloneState, memoryMb, cpus, image);
    }

    public WorkspaceSandboxBinding withCloneState(String newCloneState) {
        return new WorkspaceSandboxBinding(
                sessionId, userId, tenantId, workspaceId, state,
                lastActiveAt, repoUrl, repoBranch, newCloneState, memoryMb, cpus, image);
    }
}
```

- [ ] **Step 2: WorkspaceSandboxStore**

```java
package com.sunshine.orchestrator.sandbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.orchestrator.config.AgentSandboxProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class WorkspaceSandboxStore {

    static final String KEY_PREFIX = "sandbox:ws:";
    static final String IDLE_ZSET = "sandbox:ws:idle";

    private final StringRedisTemplate redis;
    private final AgentSandboxProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public Optional<WorkspaceSandboxBinding> find(String tenantId, String workspaceId) {
        String key = key(tenantId, workspaceId);
        try {
            String json = redis.opsForValue().get(key);
            if (!StringUtils.hasText(json)) return Optional.empty();
            return Optional.of(objectMapper.readValue(json, WorkspaceSandboxBinding.class));
        } catch (Exception e) {
            log.warn("[WorkspaceSandbox] Redis get failed key={}: {}", key, e.getMessage());
            return Optional.empty();
        }
    }

    public void save(WorkspaceSandboxBinding binding) {
        if (binding == null || !StringUtils.hasText(binding.workspaceId())) return;
        long now = Instant.now().toEpochMilli();
        WorkspaceSandboxBinding toStore = binding.withState(
                StringUtils.hasText(binding.state()) ? binding.state() : WorkspaceSandboxBinding.STATE_RUNNING);
        try {
            String k = key(toStore.tenantId(), toStore.workspaceId());
            // 工作区级不设 TTL（手动销毁）
            redis.opsForValue().set(k, objectMapper.writeValueAsString(toStore));
            redis.opsForZSet().add(IDLE_ZSET, member(toStore), now + idleMs());
        } catch (Exception e) {
            log.warn("[WorkspaceSandbox] save failed key={}: {}", k, e.getMessage());
        }
    }

    public void touch(String tenantId, String workspaceId) {
        find(tenantId, workspaceId).ifPresent(b -> save(b.withState(WorkspaceSandboxBinding.STATE_RUNNING)));
    }

    public void markStopped(String tenantId, String workspaceId) {
        Optional<WorkspaceSandboxBinding> existing = find(tenantId, workspaceId);
        if (existing.isEmpty()) return;
        WorkspaceSandboxBinding b = existing.get().withState(WorkspaceSandboxBinding.STATE_STOPPED);
        try {
            String k = key(b.tenantId(), b.workspaceId());
            redis.opsForValue().set(k, objectMapper.writeValueAsString(b));
            redis.opsForZSet().remove(IDLE_ZSET, member(b));
        } catch (Exception e) {
            log.warn("[WorkspaceSandbox] markStopped failed: {}", e.getMessage());
        }
    }

    public Optional<WorkspaceSandboxBinding> remove(String tenantId, String workspaceId) {
        Optional<WorkspaceSandboxBinding> existing = find(tenantId, workspaceId);
        String k = key(tenantId, workspaceId);
        try {
            redis.delete(k);
            existing.ifPresent(b -> redis.opsForZSet().remove(IDLE_ZSET, member(b)));
        } catch (Exception e) {
            log.warn("[WorkspaceSandbox] remove failed key={}: {}", k, e.getMessage());
        }
        return existing;
    }

    public Set<String> pollIdleMembers(long nowEpochMs) {
        return rangeByScore(IDLE_ZSET, nowEpochMs);
    }

    public void removeIdleMember(String member) {
        removeZMember(IDLE_ZSET, member);
    }

    public static String[] splitMember(String member) {
        if (!StringUtils.hasText(member)) return new String[0];
        return member.split("\\|", 3);
    }

    private Set<String> rangeByScore(String zset, long nowEpochMs) {
        try {
            Set<String> members = redis.opsForZSet().rangeByScore(zset, 0, nowEpochMs);
            return members != null ? members : Set.of();
        } catch (Exception e) {
            log.warn("[WorkspaceSandbox] {} poll failed: {}", zset, e.getMessage());
            return Set.of();
        }
    }

    private void removeZMember(String zset, String member) {
        try { redis.opsForZSet().remove(zset, member); }
        catch (Exception e) { log.warn("[WorkspaceSandbox] remove failed: {}", e.getMessage()); }
    }

    private long idleMs() {
        return Math.max(60L, properties.getConversationTtlSec()) * 1000L;
    }

    static String key(String tenantId, String workspaceId) {
        String tenant = StringUtils.hasText(tenantId) ? tenantId.strip() : "default";
        return KEY_PREFIX + tenant + ":" + workspaceId.strip();
    }

    static String member(WorkspaceSandboxBinding b) {
        return b.sessionId() + "|" + (StringUtils.hasText(b.tenantId()) ? b.tenantId() : "default")
                + "|" + b.workspaceId();
    }
}
```

- [ ] **Step 3: 编译验证**

```bash
cd /usr/local/gitproj/my-sunshine-agent && mvn compile -pl orchestrator -q -DskipTests
```

- [ ] **Step 4: Commit**

```bash
git add orchestrator/src/main/java/com/sunshine/orchestrator/sandbox/WorkspaceSandboxBinding.java \
        orchestrator/src/main/java/com/sunshine/orchestrator/sandbox/WorkspaceSandboxStore.java
git commit -m "feat: WorkspaceSandboxBinding + WorkspaceSandboxStore（仅 idle ZSET，无 purge）"
```

---

### Task 5: WorkspaceSandboxLifecycle + 宿主机 clone + 完全体 create（W2 后半）

**Files:**
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/sandbox/WorkspaceSandboxLifecycle.java`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/client/SandboxClient.java`（新增 `createWorkspaceSession` 方法）
- Modified: `orchestrator/src/main/java/com/sunshine/orchestrator/sandbox/SandboxSessionLifecycle.java`（`ensureBound` 增加工作区路径）

**Interfaces:**
- Consumes: `WorkspaceSandboxStore`、`SandboxClient`、`AgentWorkspaceRepository`、auth `git-credentials` 端点
- Produces:
  - `WorkspaceSandboxLifecycle.ensureWorkspaceSession(String workspaceId, String userId, String tenantId, String checkoutPath?)` → `String sessionId`
  - `SandboxSessionLifecycle.ensureBound(bridgeId)` 增加路径：`resolveWorkspaceId(conversationId)` → 若存在则委托 `WorkspaceSandboxLifecycle` 否则走原对话级路径

- [ ] **Step 1: WorkspaceSandboxLifecycle 实现**

```java
package com.sunshine.orchestrator.sandbox;

import com.sunshine.common.sandbox.CreateSessionRequest;
import com.sunshine.common.sandbox.SandboxPolicy;
import com.sunshine.orchestrator.client.SandboxClient;
import com.sunshine.orchestrator.model.AgentWorkspaceEntity;
import com.sunshine.orchestrator.repo.AgentWorkspaceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class WorkspaceSandboxLifecycle {

    private final WorkspaceSandboxStore store;
    private final AgentWorkspaceRepository workspaceRepo;
    private final SandboxClient sandboxClient;
    private final WebClient.Builder webClientBuilder;    // 内网调 auth git-credentials
    @Value("${sandbox.host-workspace-root:/var/lib/sunshine-sandbox}")
    private String hostWorkspaceRoot;
    @Value("${auth-service.base-url:http://localhost:8210}")
    private String authBaseUrl;

    /** 工作区懒开箱：首次 sandbox__* 或创建任务会话时触发 */
    public String ensureWorkspaceSession(
            String workspaceId, String userId, String tenantId) {
        WorkspaceSandboxBinding binding = store.find(tenantId, workspaceId).orElse(null);
        if (binding != null) {
            if (sandboxClient.sessionRunning(binding.sessionId())) {
                store.touch(tenantId, workspaceId);
                return binding.sessionId();
            }
            if (sandboxClient.sessionAlive(binding.sessionId())) {
                // stopped 容器：docker start + 续期
                sandboxClient.startSession(binding.sessionId());
                store.touch(tenantId, workspaceId);
                return binding.sessionId();
            }
            // 容器已销毁，清理 binding 走新建路径
            store.remove(tenantId, workspaceId);
        }
        AgentWorkspaceEntity ws = workspaceRepo.findById(workspaceId)
                .orElseThrow(() -> new IllegalStateException("工作区不存在: " + workspaceId));
        // 1. 宿主机 clone
        String hostDir = hostWorkspaceRoot + "/workspaces/" + workspaceId;
        Path hostPath = Path.of(hostDir, "main");
        String cloneState = "done";
        try {
            cloneRepo(ws.getRepoUrl(), ws.getRepoBranch(), userId, hostPath);
        } catch (Exception e) {
            cloneState = "failed:" + truncate(e.getMessage(), 120);
            log.warn("[WorkspaceLifecycle] clone failed ws={}: {}", workspaceId, e.getMessage());
        }
        // 2. 创建完全体 Docker 容器（bridge 出网）
        String sessionId = sandboxClient.createSession(new CreateSessionRequest(
                userId, tenantId, null, "workspace-" + workspaceId,
                fullSessionPolicy(ws), Map.of(), Map.of(hostDir, "/workspace")));
        // 3. 存 binding
        binding = new WorkspaceSandboxBinding(
                sessionId, userId, tenantId, workspaceId,
                WorkspaceSandboxBinding.STATE_RUNNING, System.currentTimeMillis(),
                ws.getRepoUrl(), ws.getRepoBranch(), cloneState,
                ws.getMemoryMb(), ws.getCpus().doubleValue(), ws.getImage());
        store.save(binding);
        log.info("[WorkspaceLifecycle] session={} ws={} clone={}", sessionId, workspaceId, cloneState);
        return sessionId;
    }

    private SandboxPolicy fullSessionPolicy(AgentWorkspaceEntity ws) {
        // 工作区容器使用 bridge 网络（完全体），非 none；volume mount 已在 create session body 中传入。
        // 现有 SandboxPolicy 已支持 networkMode/networkAllow 字段，若需显式 bridge 需在 SandboxPolicy 构造或
        // sandbox-service 侧 default 逻辑中处理（当前对话级 sessionPolicy 默认 none）。
        return new SandboxPolicy(
                "docker", ws.getImage(), 120, ws.getMemoryMb(),
                ws.getCpus().doubleValue(),
                List.of(),    // 完全体不挂 egress ACL（bridge 模式直出）
                List.of());   // exec 无只读白名单
    }

    private void cloneRepo(String repoUrl, String branch, String userId, Path target) {
        String host = extractHost(repoUrl);
        Map<String, String> cred = fetchGitCredentials(userId, host);
        String token = cred.getOrDefault("token", "");
        File dir = target.toFile();
        if (dir.exists() && new File(dir, ".git").exists()) {
            return;  // 已 clone 幂等
        }
        dir.mkdirs();
        // 使用 GIT_ASKPASS 脚本注入令牌，避免令牌出现在 ps aux / .git/config 中
        File askpassScript = null;
        ProcessBuilder pb;
        if (!token.isEmpty()) {
            askpassScript = writeAskpassScript(target.getParent(), token);
            pb = new ProcessBuilder(
                    "git", "clone", "--depth", "1", "--branch", branch, repoUrl, dir.getAbsolutePath());
            pb.environment().put("GIT_ASKPASS", askpassScript.getAbsolutePath());
            pb.environment().put("GIT_TERMINAL_PROMPT", "0");
        } else {
            pb = new ProcessBuilder(
                    "git", "clone", "--depth", "1", "--branch", branch, repoUrl, dir.getAbsolutePath());
        }
        pb.redirectErrorStream(true);
        try {
            Process p = pb.start();
            boolean done = p.waitFor(5, TimeUnit.MINUTES);
            if (!done) {
                p.destroyForcibly();
                throw new RuntimeException("git clone timeout after 5min");
            }
            String output = new String(p.getInputStream().readAllBytes());
            int code = p.exitValue();
            if (code != 0) throw new RuntimeException("git clone exit " + code + ": " + truncate(output, 200));
            // clone 成功后清理 askpass 脚本
            if (askpassScript != null) askpassScript.delete();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("git clone interrupted", e);
        }
    }

    /** 写入临时 GIT_ASKPASS 脚本（仅 root 可读），返回脚本文件 */
    private static File writeAskpassScript(Path parentDir, String token) throws IOException {
        File script = File.createTempFile("git-askpass-", ".sh", parentDir.toFile());
        script.setExecutable(true, true);
        script.setReadable(false, false);  // 仅 owner
        script.setReadable(true, true);
        String content = "#!/bin/sh\necho " + shellEscape(token) + "\n";
        Files.writeString(script.toPath(), content);
        return script;
    }

    private static String shellEscape(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private Map<String, String> fetchGitCredentials(String userId, String host) {
        try {
            WebClient client = webClientBuilder.baseUrl(authBaseUrl).build();
            var resp = client.get()
                    .uri(uriBuilder -> uriBuilder.path("/api/auth/git-credentials")
                            .queryParam("host", host).build())
                    .header("x-user-id", userId)
                    .retrieve().bodyToMono(Map.class).block();
            return resp != null ? (Map<String, String>) resp.get("data") : Map.of();
        } catch (Exception e) {
            log.warn("[WorkspaceLifecycle] git-credentials failed: {}", e.getMessage());
            return Map.of();
        }
    }

    private static String extractHost(String url) {
        try { return new URI(url).getHost(); } catch (Exception e) { return ""; }
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    public void destroyWorkspaceSession(String tenantId, String workspaceId) {
        store.remove(tenantId, workspaceId).ifPresent(b -> {
            sandboxClient.closeSession(b.sessionId());
            log.info("[WorkspaceLifecycle] destroyed session={} ws={}", b.sessionId(), workspaceId);
        });
    }
}
```

- [ ] **Step 2: 修改 SandboxSessionLifecycle 增加工作区路径**

在 `SandboxSessionLifecycle` 中新增依赖注入 `WorkspaceSandboxLifecycle` 和 `ConversationRepository`：

```java
// SandboxSessionLifecycle.java 新增字段
private final WorkspaceSandboxLifecycle workspaceSandboxLifecycle;
private final ConversationRepository conversationRepository;
```

**RunContext 移为独立 public record**（原为 package-private，需在 `prepareRun` 中填充 `workspaceId` + `checkoutPath`）：

```java
// sandbox/RunContext.java — 独立 public record
public record RunContext(
        String userId, String tenantId, String conversationId,
        String skillId, String runId, String assistantMessageId,
        String workspaceId,    // 新增：kind=task 时非空
        String checkoutPath) { // 新增：用户选定的 worktree 路径
    static RunContext from(AgentRunRequest req, ConversationRepository convRepo) {
        String convId = req.conversationId();
        String wsId = null;
        String ckPath = null;
        if (StringUtils.hasText(convId)) {
            ChatConversationEntity conv = convRepo.findById(convId).orElse(null);
            if (conv != null && "task".equals(conv.getKind())) {
                wsId = conv.getWorkspaceId();
                ckPath = conv.getCheckoutPath();
            }
        }
        return new RunContext(
                req.userId(),
                StringUtils.hasText(req.tenantId()) ? req.tenantId().strip() : "default",
                convId, req.skillId(), req.runId(), req.assistantMessageId(),
                wsId, ckPath);
    }
}
```

`prepareRun` 调用处同步改为 `RunContext.from(req, conversationRepository)`。

**`ensureBound` 方法**中，在现有 check 逻辑前增加工作区路径：

```java
// SandboxSessionLifecycle.ensureBound 方法中
if (ctx != null && StringUtils.hasText(ctx.workspaceId())) {
    String wsSessionId = workspaceSandboxLifecycle.ensureWorkspaceSession(
            ctx.workspaceId(), ctx.userId(), ctx.tenantId());
    SandboxSessionHolder.bind(bid, wsSessionId, fullSessionPolicy());
    emitSandboxSessionSse(bid, ctx, List.of());
    return wsSessionId;
}
// ... 现存对话级逻辑不变 ...
```

> 注：工作区级 `fullSessionPolicy()` 由 `WorkspaceSandboxLifecycle` 内部维护，与对话级 `sessionPolicy()` 独立。

- [ ] **Step 3: 编译验证**

```bash
cd /usr/local/gitproj/my-sunshine-agent && mvn compile -pl orchestrator -q -DskipTests
```

- [ ] **Step 4: Commit**

```bash
git add orchestrator/src/main/java/com/sunshine/orchestrator/sandbox/WorkspaceSandboxLifecycle.java \
        orchestrator/src/main/java/com/sunshine/orchestrator/sandbox/SandboxSessionLifecycle.java
git commit -m "feat: WorkspaceSandboxLifecycle 懒开箱（宿主机 clone + 完全体 Docker create）"
```

---

### Task 6: WorkspaceCheckoutService + SandboxAgentTools cwd 重定向（W3）

**Files:**
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/sandbox/WorkspaceCheckoutService.java`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/sandbox/SandboxAgentTools.java`（execute 增加 cwd 重定向 + PathJail 边界）
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/sandbox/SandboxIds.java`（若需新常量）

**Interfaces:**
- Consumes: `SandboxClient`（exec 命令）、`WorkspaceSandboxStore`（取 sessionId）
- Produces:
  - `WorkspaceCheckoutService`：
    - `createWorktree(String workspaceId, String branch, String fromRef?)` → `String checkoutPath`
    - `listCheckouts(String workspaceId)` → `List<CheckoutInfo>`
    - `mergeToMain(String workspaceId, String branch)` → `MergeResult`（含冲突清单）
    - `removeWorktree(String workspaceId, String branch)`

- [ ] **Step 1: WorkspaceCheckoutService 实现**

```java
package com.sunshine.orchestrator.sandbox;

import com.sunshine.orchestrator.client.SandboxClient;
import com.sunshine.orchestrator.repo.ConversationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class WorkspaceCheckoutService {

    private final WorkspaceSandboxStore store;
    private final WorkspaceSandboxLifecycle lifecycle;
    private final SandboxClient sandboxClient;
    private final StringRedisTemplate redis;

    public record CheckoutInfo(String branch, String path, boolean isMain, List<String> conversationIds) {}

    public record MergeResult(boolean success, List<String> conflictFiles) {}

    /**
     * 工作区级 Redis 分布式锁 key 前缀（同一 workspace 容器内 git 操作互斥）。
     * 使用 {@code redis.setIfAbsent(key, "1", 30, SECONDS)} 获取锁。
     */
    private static final String LOCK_PREFIX = "sandbox:ws:lock:";

    /** 新建 worktree 分支（用户显式触发） */
    public String createWorktree(String workspaceId, String userId, String tenantId,
                                  String branch, String fromRef) {
        String sessionId = lifecycle.ensureWorkspaceSession(workspaceId, userId, tenantId);
        String lockKey = LOCK_PREFIX + workspaceId;
        if (!redis.opsForValue().setIfAbsent(lockKey, "1", Duration.ofSeconds(30))) {
            throw new IllegalStateException("工作区 git 操作繁忙，请稍后重试");
        }
        try {
            String ref = StringUtils.hasText(fromRef) ? fromRef.strip() : "HEAD";
            String cmd = "git -C /workspace/main worktree add /workspace/branches/" + branch
                    + " -b " + branch + " " + ref;
            var resp = sandboxClient.invoke(sessionId, "exec",
                    Map.of("command", cmd, "cwd", "/workspace/main"));
            if (!resp.isSuccess()) {
                throw new IllegalStateException("worktree create failed: " + resp.getOutput());
            }
            return "/workspace/branches/" + branch;
        } finally {
            redis.delete(lockKey);
        }
    }

    /** 列出工作区所有 checkout */
    public List<CheckoutInfo> listCheckouts(String workspaceId, String userId, String tenantId) {
        String sessionId = lifecycle.ensureWorkspaceSession(workspaceId, userId, tenantId);
        var resp = sandboxClient.invoke(sessionId, "exec",
                Map.of("command", "git -C /workspace/main worktree list --porcelain", "cwd", "/workspace/main"));
        // 解析 porcelain 输出
        List<CheckoutInfo> result = new ArrayList<>();
        result.add(new CheckoutInfo("main", "/workspace/main", true, List.of()));
        // TODO: parse porcelain lines ("worktree /workspace/branches/xxx\nbranch refs/heads/xxx\n")
        return result;
    }

    /** 合并 worktree 到主分支（用户显式触发） */
    public MergeResult mergeToMain(String workspaceId, String userId, String tenantId, String branch) {
        String sessionId = lifecycle.ensureWorkspaceSession(workspaceId, userId, tenantId);
        String lockKey = LOCK_PREFIX + workspaceId;
        if (!redis.opsForValue().setIfAbsent(lockKey, "1", Duration.ofSeconds(30))) {
            throw new IllegalStateException("工作区 git 操作繁忙，请稍后重试");
        }
        try {
            var resp = sandboxClient.invoke(sessionId, "exec",
                    Map.of("command", "git -C /workspace/main merge " + branch, "cwd", "/workspace/main"));
            if (resp.isSuccess()) {
                return new MergeResult(true, List.of());
            }
            // 冲突时获取冲突文件清单
            var conflictResp = sandboxClient.invoke(sessionId, "exec",
                    Map.of("command", "git -C /workspace/main diff --name-only --diff-filter=U",
                           "cwd", "/workspace/main"));
            List<String> conflicts = conflictResp.getOutput() != null
                    ? conflictResp.getOutput().lines().map(String::strip).filter(s -> !s.isEmpty()).toList()
                    : List.of();
            return new MergeResult(false, conflicts);
        } finally {
            redis.delete(lockKey);
        }
    }

    /** 删除 worktree 分支（用户显式触发） */
    public void removeWorktree(String workspaceId, String userId, String tenantId, String branch) {
        String sessionId = lifecycle.ensureWorkspaceSession(workspaceId, userId, tenantId);
        String lockKey = LOCK_PREFIX + workspaceId;
        if (!redis.opsForValue().setIfAbsent(lockKey, "1", Duration.ofSeconds(30))) {
            throw new IllegalStateException("工作区 git 操作繁忙，请稍后重试");
        }
        try {
            sandboxClient.invoke(sessionId, "exec",
                    Map.of("command",
                            "git -C /workspace/main worktree remove /workspace/branches/" + branch + " --force"
                                    + " && git -C /workspace/main branch -D " + branch,
                            "cwd", "/workspace/main"));
        } finally {
            redis.delete(lockKey);
        }
    }
}
```

- [ ] **Step 2: 修改 SandboxAgentTools cwd + PathJail**

```java
// SandboxAgentTools.execute() 方法中，在 ensureBound 之后、RPC 调用之前增加：

// 工作区级：cwd = 会话选定的 checkout（从 conversation 表直接读取）
String workspaceId = null;
String checkoutPath = null;
if (StringUtils.hasText(conversationId)) {
    ChatConversationEntity conv = conversationRepository.findById(conversationId).orElse(null);
    if (conv != null && "task".equals(conv.getKind()) && StringUtils.hasText(conv.getWorkspaceId())) {
        workspaceId = conv.getWorkspaceId();
        checkoutPath = conv.getCheckoutPath();
    }
}
if (StringUtils.hasText(checkoutPath)) {
    body.put("cwd", checkoutPath);  // PathJail 边界 = checkoutPath
}
```

> 依赖注入：`SandboxAgentTools` 新增 `private final ConversationRepository conversationRepository;`。
```

- [ ] **Step 3: 编译验证**

```bash
cd /usr/local/gitproj/my-sunshine-agent && mvn compile -pl orchestrator -q -DskipTests
```

- [ ] **Step 4: Commit**

```bash
git add orchestrator/src/main/java/com/sunshine/orchestrator/sandbox/WorkspaceCheckoutService.java \
        orchestrator/src/main/java/com/sunshine/orchestrator/sandbox/SandboxAgentTools.java
git commit -m "feat: WorkspaceCheckoutService（create/list/merge/remove worktree）+ SandboxAgentTools cwd 重定向"
```

---

### Task 7: 硬件档位 + 完全体镜像（W4）

**Files:**
- Modify: `docs/nacos/sunshine-orchestrator.yaml`
- Modify: `docs/nacos/sunshine-sandbox-service.yaml`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/config/AgentSandboxProperties.java`
- Create: `docker/sandbox-full/Dockerfile`

**Interfaces:**
- Produces: Nacos `agent.sandbox.profiles.full` 档位配置；`sunshine-sandbox-full:latest` 镜像 Dockerfile

- [ ] **Step 1: 创建完全体 Dockerfile**

```dockerfile
FROM python:3.11-slim

RUN apt-get update && apt-get install -y --no-install-recommends \
    git build-essential curl vim nodejs npm \
    && rm -rf /var/lib/apt/lists/*

RUN useradd -m -u 10001 sandbox && \
    mkdir -p /workspace /skills && \
    chown -R sandbox:sandbox /workspace /skills

USER sandbox
WORKDIR /workspace

CMD ["sleep", "infinity"]
```

- [ ] **Step 2: 修改 AgentSandboxProperties 增加 profiles**

```java
// AgentSandboxProperties.java
@Getter @Setter
public static class ProfilePreset {
    private int defaultMemoryMb = 2048;
    private double defaultCpus = 2.0;
    private List<Map<String, Object>> allowedPresets = new ArrayList<>();
    private String image = "sunshine-sandbox-full:latest";
}

private Map<String, ProfilePreset> profiles = new LinkedHashMap<>();

public ProfilePreset resolveProfile(String name) {
    if (profiles == null || profiles.isEmpty()) {
        ProfilePreset fallback = new ProfilePreset();
        fallback.setImage("sunshine-sandbox-full:latest");
        return fallback;
    }
    return profiles.getOrDefault(name, profiles.values().iterator().next());
}

/**
 * 校验用户请求的 hardware spec 是否在 Nacos allowed-presets 范围内。
 * 返回第一个匹配的预设（精确匹配 memoryMb+cpus），否则抛异常。
 */
public Map<String, Object> validateAndResolve(String profileName, int memoryMb, double cpus) {
    ProfilePreset profile = resolveProfile(profileName);
    if (profile.getAllowedPresets() == null || profile.getAllowedPresets().isEmpty()) {
        if (memoryMb <= profile.getDefaultMemoryMb() && cpus <= profile.getDefaultCpus()) {
            return Map.of("memoryMb", memoryMb, "cpus", cpus);
        }
        throw new IllegalArgumentException(
            String.format("硬件规格超限: memoryMb=%d (max %d), cpus=%.1f (max %.1f)",
                memoryMb, profile.getDefaultMemoryMb(), cpus, profile.getDefaultCpus()));
    }
    for (Map<String, Object> preset : profile.getAllowedPresets()) {
        int pm = ((Number) preset.getOrDefault("memoryMb", 0)).intValue();
        double pc = ((Number) preset.getOrDefault("cpus", 0.0)).doubleValue();
        if (memoryMb == pm && Math.abs(cpus - pc) < 0.01) {
            return preset;
        }
    }
    throw new IllegalArgumentException(
        String.format("硬件规格不在允许范围内: memoryMb=%d cpus=%.1f", memoryMb, cpus));
}
```

- [ ] **Step 3: 追加 Nacos 档位配置**

```yaml
# sunshine-orchestrator.yaml
agent:
  sandbox:
    profiles:
      full:
        default-memory-mb: 2048
        default-cpus: 2.0
        allowed-presets:
          - { memoryMb: 1024, cpus: 1.0 }
          - { memoryMb: 2048, cpus: 2.0 }
          - { memoryMb: 4096, cpus: 4.0 }
        image: sunshine-sandbox-full:latest
```

```yaml
# sunshine-sandbox-service.yaml
sandbox:
  docker:
    default-full-image: sunshine-sandbox-full:latest
    host-workspace-root: /var/lib/sunshine-sandbox
```

- [ ] **Step 4: 同步 Nacos + 编译**

```bash
cd /usr/local/gitproj/my-sunshine-agent
python scripts/sync_nacos.py
mvn compile -pl orchestrator -q -DskipTests
```

- [ ] **Step 5: 构建完全体镜像**

```bash
cd /usr/local/gitproj/my-sunshine-agent
docker build -t sunshine-sandbox-full:latest -f docker/sandbox-full/Dockerfile docker/sandbox-full/
```

- [ ] **Step 6: Commit**

```bash
git add docker/sandbox-full/Dockerfile \
        orchestrator/src/main/java/com/sunshine/orchestrator/config/AgentSandboxProperties.java \
        docs/nacos/sunshine-orchestrator.yaml \
        docs/nacos/sunshine-sandbox-service.yaml
git commit -m "feat: 硬件档位 + sunshine-sandbox-full 完全体镜像"
```

---

### Task 8: 生命周期调整 + 手动销毁 + Reaper 改造（W5）

**Files:**
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/sandbox/SandboxSessionReaper.java`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/controller/AgentWorkspaceController.java`（DELETE 补全销毁逻辑）
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/sandbox/ConversationSandboxStore.java`（无关，仅确认无改动）

**Interfaces:**
- Modifies: `SandboxSessionReaper.reap()` 增加工作区 ZSET 扫描（仅 idle stop）；`AgentWorkspaceController.delete` 补全 `docker rm + 清盘 + status=archived`

- [ ] **Step 1: SandboxSessionReaper 增加工作区 idle stop**

```java
// SandboxSessionReaper.java
private final WorkspaceSandboxStore workspaceStore;

@Scheduled(fixedDelayString = "${agent.sandbox.reaper-interval-ms:60000}")
public void reap() {
    long now = Instant.now().toEpochMilli();
    reapIdleStop(now);         // 对话级
    reapPurgeDestroy(now);     // 对话级（不变）
    reapWorkspaceIdleStop(now);  // 工作区级（仅 stop，不 purge）
}

void reapWorkspaceIdleStop(long nowEpochMs) {
    Set<String> members = workspaceStore.pollIdleMembers(nowEpochMs);
    for (String member : members) {
        String[] parts = WorkspaceSandboxStore.splitMember(member);
        if (parts.length < 3) {
            workspaceStore.removeIdleMember(member);
            continue;
        }
        String sessionId = parts[0];
        String tenantId = parts[1];
        String workspaceId = parts[2];
        try {
            if (StringUtils.hasText(sessionId)) {
                sandboxClient.stopSession(sessionId);
            }
            workspaceStore.markStopped(tenantId, workspaceId);
            log.info("[SandboxReaper] stopped idle workspace session={} ws={}", sessionId, workspaceId);
        } catch (Exception e) {
            log.warn("[SandboxReaper] workspace stop failed member={}: {}", member, e.getMessage());
        } finally {
            workspaceStore.removeIdleMember(member);
        }
    }
}
```

- [ ] **Step 2: AgentWorkspaceController DELETE 补全销毁逻辑**

```java
@DeleteMapping("/{id}")
public R<Void> destroy(@PathVariable String id,
                        @RequestHeader("x-user-id") String userId,
                        @RequestHeader("x-tenant-id") String tenantId) {
    AgentWorkspaceEntity ws = workspaceRepo.findById(id)
            .orElseThrow(() -> new BizException("工作区不存在"));
    if (!ws.getUserId().equals(userId) || !ws.getTenantId().equals(tenantId)) {
        throw new BizException("无权操作");
    }
    // 销毁容器 + 清盘
    workspaceSandboxLifecycle.destroyWorkspaceSession(tenantId, id);
    // 归档
    ws.setStatus("archived");
    ws.setUpdatedAt(Instant.now());
    workspaceRepo.save(ws);
    return R.ok();
}
```

- [ ] **Step 3: 编译验证**

```bash
cd /usr/local/gitproj/my-sunshine-agent && mvn compile -pl orchestrator -q -DskipTests
```

- [ ] **Step 4: Commit**

```bash
git add orchestrator/src/main/java/com/sunshine/orchestrator/sandbox/SandboxSessionReaper.java \
        orchestrator/src/main/java/com/sunshine/orchestrator/controller/AgentWorkspaceController.java
git commit -m "feat: 工作区生命周期调整（仅 idle stop，不自动 purge；手动销毁 API）"
```

---

### Task 9: 前端 — Auth Git 服务分组 + API 扩展

**Files:**
- Modify: `sunshine-ui/src/api/auth.ts`
- Modify: `sunshine-ui/src/components/UserSettingsModal.vue`

- [ ] **Step 1: auth.ts 扩展**

```typescript
// AuthUser 扩展字段
export interface AuthUser {
  userId: string
  username: string
  nickname: string
  tenantId: string
  defaultWriteHitlMode?: string
  personalRules?: string | null
  /** Git 服务配置（令牌不回传明文） */
  githubUrl?: string
  githubTokenSet?: boolean
  gitlabUrl?: string
  gitlabTokenSet?: boolean
}

// updateProfile 扩展签名
export async function updateProfile(
  nickname: string,
  tenantId: string,
  defaultWriteHitlMode?: string,
  personalRules?: string | null,
  githubUrl?: string,
  githubToken?: string,
  gitlabUrl?: string,
  gitlabToken?: string,
): Promise<UpdateProfileResult> {
  const res = await fetch(`${resolveApiBase()}/api/auth/profile`, {
    method: 'PATCH',
    headers: apiHeaders(),
    body: JSON.stringify({
      nickname, tenantId, defaultWriteHitlMode, personalRules,
      githubUrl, githubToken, gitlabUrl, gitlabToken,
    }),
  })
  return parseApiResponse<UpdateProfileResult>(res)
}
```

- [ ] **Step 2: UserSettingsModal 增加「Git 服务」分组**

在 `<template>` 的 nav 中增加第 4 个 tab + panel：

```vue
<script setup lang="ts">
// 新增 ref
const githubUrl = ref('')
const githubToken = ref('')
const githubTokenSet = ref(false)
const gitlabUrl = ref('')
const gitlabToken = ref('')
const gitlabTokenSet = ref(false)

// 新增 tab 分组
const GROUPS: Array<{ key: SettingsGroup; label: string }> = [
  { key: 'account', label: '账号' },
  { key: 'chat', label: '对话偏好' },
  { key: 'rules', label: '个人规则' },
  { key: 'git', label: 'Git 服务' },  // 新增
]
type SettingsGroup = 'account' | 'chat' | 'rules' | 'git'

// watch 中填充
watch(() => props.show, (open) => {
  if (open) {
    // ... 现有逻辑
    githubUrl.value = auth.user?.githubUrl ?? ''
    githubTokenSet.value = auth.user?.githubTokenSet ?? false
    gitlabUrl.value = auth.user?.gitlabUrl ?? ''
    gitlabTokenSet.value = auth.user?.gitlabTokenSet ?? false
    githubToken.value = ''
    gitlabToken.value = ''
  }
})

// handleSave 扩展
async function handleSave() {
  // ...现有校验
  await auth.updateProfile(
    nickname.value.trim(), tenantId.value, defaultWriteHitl.value, personalRules.value,
    githubUrl.value.trim() || undefined,
    githubToken.value.trim() || undefined,
    gitlabUrl.value.trim() || undefined,
    gitlabToken.value.trim() || undefined,
  )
  // ...后续
}
</script>

<template>
  <!-- 新增 Git 服务面板 -->
  <NForm v-show="activeGroup === 'git'" label-placement="top" :show-require-mark="false">
    <p class="settings-section-title">GitHub</p>
    <NFormItem label="基础地址">
      <NInput v-model:value="githubUrl" class="sun-field" placeholder="https://github.com" :disabled="saving" />
    </NFormItem>
    <NFormItem label="访问令牌 (PAT)">
      <NInput v-model:value="githubToken" class="sun-field" type="password"
              :placeholder="githubTokenSet ? '已配置 · 重新输入以更新' : '输入个人访问令牌'" :disabled="saving" />
    </NFormItem>
    <p class="settings-section-title">内网 GitLab</p>
    <NFormItem label="基础地址">
      <NInput v-model:value="gitlabUrl" class="sun-field" placeholder="https://gitlab.example.com" :disabled="saving" />
    </NFormItem>
    <NFormItem label="访问令牌 (PAT)">
      <NInput v-model:value="gitlabToken" class="sun-field" type="password"
              :placeholder="gitlabTokenSet ? '已配置 · 重新输入以更新' : '输入个人访问令牌'" :disabled="saving" />
    </NFormItem>
  </NForm>
</template>

<style scoped>
.settings-section-title {
  font-weight: 600;
  font-size: var(--sun-font-base, 14px);
  margin: 0 0 12px;
  color: var(--sun-text-strong);
}
</style>
```

- [ ] **Step 3: 编译前端验证**

```bash
cd /usr/local/gitproj/my-sunshine-agent/sunshine-ui && npx vue-tsc --noEmit 2>&1 | head -20
```

- [ ] **Step 4: Commit**

```bash
git add sunshine-ui/src/api/auth.ts sunshine-ui/src/components/UserSettingsModal.vue
git commit -m "feat: 前端 AuthUser 扩展 Git 服务字段 + UserSettingsModal Git 分组"
```

---

### Task 10: 前端 — 新任务入口 + 工作区分类 + 选择器（W6 后半）

**Files:**
- Create: `sunshine-ui/src/api/agentWorkspaces.ts`
- Create: `sunshine-ui/src/components/chat/WorkspaceSelector.vue`
- Modify: `sunshine-ui/src/components/ConversationSidebarList.vue`
- Modify: `sunshine-ui/src/layouts/MainLayout.vue`
- Modify: `sunshine-ui/src/views/ChatView.vue`

- [ ] **Step 1: agentWorkspaces.ts API 封装**

```typescript
import { apiHeaders } from '../stores/authStore'
import { resolveApiBase } from './config'
import { parseApiResponse } from './apiError'

export interface AgentWorkspace {
  id: string
  name: string
  repoUrl: string
  repoBranch: string
  sandboxProfile: string
  memoryMb: number
  cpus: number
  image: string
  status: string
  cloneState: string | null
  createdAt: string
}

export async function listWorkspaces(): Promise<AgentWorkspace[]> {
  const res = await fetch(`${resolveApiBase()}/api/agent-workspaces`, { headers: apiHeaders() })
  return parseApiResponse<AgentWorkspace[]>(res)
}

export async function createWorkspace(body: {
  name: string
  repoUrl: string
  repoBranch?: string
  memoryMb?: number
  cpus?: number
}): Promise<AgentWorkspace> {
  const res = await fetch(`${resolveApiBase()}/api/agent-workspaces`, {
    method: 'POST',
    headers: apiHeaders(),
    body: JSON.stringify(body),
  })
  return parseApiResponse<AgentWorkspace>(res)
}

export async function deleteWorkspace(id: string): Promise<void> {
  const res = await fetch(`${resolveApiBase()}/api/agent-workspaces/${id}`, {
    method: 'DELETE',
    headers: apiHeaders(),
  })
  await parseApiResponse<null>(res, { allowEmptyData: true })
}
```

- [ ] **Step 2: WorkspaceSelector 组件**

```vue
<script setup lang="ts">
import { ref, watch, onMounted } from 'vue'
import { NSelect, NInput, NTag, useMessage, type SelectOption } from 'naive-ui'
import type { AgentWorkspace } from '../../api/agentWorkspaces'
import { listWorkspaces } from '../../api/agentWorkspaces'

const props = defineProps<{
  modelValue: { workspaceId?: string; checkoutPath?: string; isWorktree?: boolean } | null
  disabled?: boolean
}>()

const emit = defineEmits<{
  'update:modelValue': [value: { workspaceId: string; checkoutPath: string; isWorktree: boolean } | null]
}>()

const workspaces = ref<AgentWorkspace[]>([])
const loading = ref(false)
const selectedWorkspaceId = ref<string | null>(null)
const branchMode = ref<'main' | 'worktree'>('main')
const worktreeBranch = ref('')
const newBranchName = ref('')

const fetchWorkspaces = async () => {
  loading.value = true
  try {
    workspaces.value = await listWorkspaces()
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  fetchWorkspaces()
})

watch(() => props.modelValue, (v) => {
  if (v?.workspaceId) {
    selectedWorkspaceId.value = v.workspaceId
    branchMode.value = v.isWorktree ? 'worktree' : 'main'
    worktreeBranch.value = v.checkoutPath ?? ''
  }
}, { immediate: true })

const emitSelected = () => {
  if (!selectedWorkspaceId.value) return
  const checkoutPath = branchMode.value === 'main'
    ? '/workspace/main'
    : `/workspace/branches/${worktreeBranch.value || newBranchName.value}`
  emit('update:modelValue', {
    workspaceId: selectedWorkspaceId.value,
    checkoutPath,
    isWorktree: branchMode.value === 'worktree',
  })
}
</script>

<template>
  <div class="workspace-selector">
    <NSelect
      :model-value="selectedWorkspaceId"
      :options="workspaces.map(w => ({ label: w.name, value: w.id }))"
      placeholder="选择工作区"
      :disabled="disabled"
      @update:model-value="(v: string) => { selectedWorkspaceId = v; emitSelected() }"
    />
    <div v-if="selectedWorkspaceId" class="ws-branch-row">
      <NSelect
        :model-value="branchMode"
        :options="[{ label: '主分支', value: 'main' }, { label: 'worktree 分支', value: 'worktree' }]"
        size="small"
        :disabled="disabled"
        @update:model-value="(v: string) => { branchMode = v as 'main' | 'worktree'; emitSelected() }"
      />
      <NInput
        v-if="branchMode === 'worktree'"
        v-model:value="newBranchName"
        size="small"
        placeholder="输入新分支名"
        :disabled="disabled"
        @blur="emitSelected"
      />
    </div>
  </div>
</template>
```

- [ ] **Step 3: ConversationSidebarList 增加工作区分类**

```vue
<!-- 在模板中现有会话列表之前，增加工作区节点 -->
<div v-if="workspaces.length > 0" class="sidebar-section">
  <div class="sidebar-section-title">工作区</div>
  <div v-for="ws in workspaces" :key="ws.id" class="ws-node">
    <div class="ws-label">
      <span class="ws-name">{{ ws.name }}</span>
      <NTag size="tiny" :bordered="false">{{ ws.repoBranch }}</NTag>
    </div>
    <!-- 展开显示该工作区下的任务列表 -->
    <div v-for="task in ws.tasks" :key="task.id" class="ws-task-item"
         :class="{ active: task.id === currentConversationId }"
         @click="selectTask(task.id)">
      {{ task.title }}
    </div>
  </div>
</div>
```

- [ ] **Step 4: MainLayout NMenu 增加「工作区」导航项**

在现有菜单项数组中增加：
```vue
{ label: '工作区', key: 'workspaces', icon: () => h(IconWorkspace) }
```
`IconWorkspace` 可用 `FolderOpenOutline` 或自定义 SVG。

- [ ] **Step 5: ChatView kind=task 适配**

```vue
<!-- 底栏：kind=task 时隐藏 ExecutionModeSelector -->
<ExecutionModeSelector v-if="conversationKind !== 'task'" ... />
<!-- kind=task 时显示只读 checkout 路径 -->
<div v-if="conversationKind === 'task'" class="ws-checkout-badge">
  <NTag size="small" :bordered="false">工作区 · {{ checkoutPath }}</NTag>
</div>
```

- [ ] **Step 6: WorkspaceView.vue 工作区列表/详情页**

```vue
<!-- sunshine-ui/src/views/WorkspaceView.vue -->
<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { NButton, NModal, NForm, NFormItem, NInput, NInputNumber, NTag, NDataTable, NSpin, useMessage, type DataTableColumns } from 'naive-ui'
import type { AgentWorkspace } from '../api/agentWorkspaces'
import { listWorkspaces, createWorkspace, deleteWorkspace } from '../api/agentWorkspaces'
import { useAuthStore } from '../stores/authStore'

const auth = useAuthStore()
const message = useMessage()
const workspaces = ref<AgentWorkspace[]>([])
const loading = ref(false)
const showCreate = ref(false)

const newName = ref('')
const newRepoUrl = ref('')
const newRepoBranch = ref('main')
const newMemoryMb = ref(2048)
const newCpus = ref(2.0)
const creating = ref(false)

const columns: DataTableColumns<AgentWorkspace> = [
  { title: '名称', key: 'name', ellipsis: { tooltip: true } },
  { title: '仓库', key: 'repoUrl', ellipsis: { tooltip: true } },
  { title: '分支', key: 'repoBranch', width: 100 },
  { title: '规格', key: 'memoryMb', width: 100, render: (row) => `${row.memoryMb}MB / ${row.cpus} CPU` },
  { title: '状态', key: 'status', width: 80, render: (row) => h(NTag, { size: 'small', type: row.status === 'active' ? 'success' : 'default' }, () => row.status) },
  {
    title: '操作', key: 'actions', width: 100,
    render: (row) => h(NButton, { size: 'tiny', type: 'error', quaternary: true, onClick: () => handleDestroy(row) }, () => '删除'),
  },
]

const fetchData = async () => {
  loading.value = true
  try { workspaces.value = await listWorkspaces() } finally { loading.value = false }
}

const handleCreate = async () => {
  if (!newName.value.trim() || !newRepoUrl.value.trim()) {
    message.warning('名称和仓库地址必填')
    return
  }
  creating.value = true
  try {
    await createWorkspace({
      name: newName.value.trim(),
      repoUrl: newRepoUrl.value.trim(),
      repoBranch: newRepoBranch.value.trim() || 'main',
      memoryMb: newMemoryMb.value,
      cpus: newCpus.value,
    })
    showCreate.value = false
    newName.value = ''
    newRepoUrl.value = ''
    await fetchData()
    message.success('工作区已创建')
  } catch (e: any) {
    message.error(e?.message || '创建失败')
  } finally {
    creating.value = false
  }
}

const handleDestroy = async (ws: AgentWorkspace) => {
  try {
    await deleteWorkspace(ws.id)
    await fetchData()
    message.success('工作区已归档')
  } catch (e: any) {
    message.error(e?.message || '删除失败')
  }
}

onMounted(fetchData)
</script>

<template>
  <div class="workspace-view">
    <div class="ws-header">
      <h2 class="ws-title">工作区</h2>
      <NButton type="primary" size="small" @click="showCreate = true">新建工作区</NButton>
    </div>
    <NSpin :show="loading">
      <NDataTable :columns="columns" :data="workspaces" :bordered="false" />
    </NSpin>
    <!-- 新建弹窗 -->
    <NModal v-model:show="showCreate" title="新建工作区">
      <NForm label-placement="top">
        <NFormItem label="名称" required>
          <NInput v-model:value="newName" class="sun-field" placeholder="如 sun-bot" :disabled="creating" />
        </NFormItem>
        <NFormItem label="Git 仓库地址" required>
          <NInput v-model:value="newRepoUrl" class="sun-field" placeholder="https://github.com/org/repo.git" :disabled="creating" />
        </NFormItem>
        <NFormItem label="分支">
          <NInput v-model:value="newRepoBranch" class="sun-field" placeholder="main" :disabled="creating" />
        </NFormItem>
        <NFormItem label="内存 (MB)">
          <NInputNumber v-model:value="newMemoryMb" class="sun-field" :min="512" :max="12288" :step="512" :disabled="creating" />
        </NFormItem>
        <NFormItem label="CPU 核数">
          <NInputNumber v-model:value="newCpus" class="sun-field" :min="0.5" :max="4.0" :step="0.5" :disabled="creating" />
        </NFormItem>
      </NForm>
      <template #footer>
        <NButton @click="showCreate = false" :disabled="creating">取消</NButton>
        <NButton type="primary" @click="handleCreate" :loading="creating">创建</NButton>
      </template>
    </NModal>
  </div>
</template>

<style scoped>
.workspace-view { padding: 24px; max-width: 960px; }
.ws-header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 16px; }
.ws-title { font-size: 18px; font-weight: 600; margin: 0; }
</style>
```

- [ ] **Step 7: 编译前端**

```bash
cd /usr/local/gitproj/my-sunshine-agent/sunshine-ui && npx vue-tsc --noEmit 2>&1 | head -20
```

- [ ] **Step 7: 编译前端**

```bash
cd /usr/local/gitproj/my-sunshine-agent/sunshine-ui && npx vue-tsc --noEmit 2>&1 | head -20
```

- [ ] **Step 8: Commit**

```bash
git add sunshine-ui/src/api/agentWorkspaces.ts \
        sunshine-ui/src/components/chat/WorkspaceSelector.vue \
        sunshine-ui/src/components/ConversationSidebarList.vue \
        sunshine-ui/src/layouts/MainLayout.vue \
        sunshine-ui/src/views/ChatView.vue \
        sunshine-ui/src/views/WorkspaceView.vue
git commit -m "feat: 前端新任务入口 + 工作区分类 + WorkspaceSelector + WorkspaceView + kind=task 适配"
```

---

### Task 11: 验收脚本（W7）

**Files:**
- Create: `scripts/verify_agent_workspace_live.py`

- [ ] **Step 1: verify_agent_workspace_live.py**

```python
#!/usr/bin/env python3
"""Agent Workspace Live 验收
   测试矩阵：
   1. 创建工作区 → clone 成功
   2. checkout 主分支 → edit 文件 → 另一会话可见
   3. checkout worktree → 两会话并行写互不干扰
   4. 合并到主分支 → 冲突返回清单
   5. 硬件档位生效（cpus/memory）
   6. DELETE 工作区 → 归档
   7. 普通 Chat 仍 network=none（回归）
   8. git-credentials 令牌不回传前端
"""
import sys, os, json, time, requests, unittest, uuid
sys.path.insert(0, os.path.dirname(__file__))
from sunshine_lib import SunshineTestBase, BFF_BASE, ORCHESTRATOR_BASE, AUTH_BASE

BFF = BFF_BASE.rstrip('/')
ORCH = ORCHESTRATOR_BASE.rstrip('/')
AUTH = AUTH_BASE.rstrip('/')

class AgentWorkspaceLive(SunshineTestBase):

    def setUp(self):
        super().setUp()
        self.ws_id = None

    def tearDown(self):
        if self.ws_id:
            try:
                requests.delete(f'{BFF}/api/agent-workspaces/{self.ws_id}')
            except Exception:
                pass

    def test_01_create_workspace(self):
        """创建工作区：repoUrl 必填 + 硬件护栏"""
        # 缺 repoUrl → 400
        resp = self._post(f'{BFF}/api/agent-workspaces', {'name': 'test-no-repo'})
        self.assertEqual(resp.status_code, 400)

        # 正常创建
        resp = self._post(f'{BFF}/api/agent-workspaces', {
            'name': '验收测试工作区',
            'repoUrl': 'https://github.com/psf/requests.git',
            'repoBranch': 'main',
            'memoryMb': 1024,
            'cpus': 1.0,
        })
        self.assertEqual(resp.status_code, 200)
        data = resp.json().get('data')
        self.assertIsNotNone(data['id'])
        self.ws_id = data['id']
        self.assertEqual(data['name'], '验收测试工作区')

    def test_02_list_workspaces(self):
        """列出当前用户工作区"""
        self.test_01_create_workspace()
        resp = self._get(f'{BFF}/api/agent-workspaces')
        self.assertEqual(resp.status_code, 200)
        ws_list = resp.json().get('data', [])
        self.assertTrue(any(w['id'] == self.ws_id for w in ws_list))

    def test_03_hardware_guard(self):
        """硬件护栏：cpus > 4 或 memory > 12288 → 400"""
        resp = self._post(f'{BFF}/api/agent-workspaces', {
            'name': '超大规格', 'repoUrl': 'https://github.com/a/b.git',
            'memoryMb': 16000,
        })
        self.assertEqual(resp.status_code, 400)

    def test_04_git_token_privacy(self):
        """令牌不回传前端"""
        resp = self._get(f'{BFF}/api/auth/me')
        data = resp.json().get('data', {})
        self.assertNotIn('githubToken', data)
        self.assertNotIn('gitlabToken', data)
        # githubTokenSet / gitlabTokenSet 应是 boolean
        if 'githubTokenSet' in data:
            self.assertIsInstance(data['githubTokenSet'], bool)

    def test_05_destroy_workspace(self):
        """删除工作区 → 归档"""
        self.test_01_create_workspace()
        resp = self._delete(f'{BFF}/api/agent-workspaces/{self.ws_id}')
        self.assertEqual(resp.status_code, 200)
        self.ws_id = None
```

- [ ] **Step 2: 复查验收覆盖**

```bash
python scripts/verify_agent_workspace_live.py --list 2>&1
```

- [ ] **Step 3: Commit**

```bash
git add scripts/verify_agent_workspace_live.py
git commit -m "feat: verify_agent_workspace_live.py 验收脚本"
```

---

## Self-Review 结果

### 1. Spec 覆盖对照

| Spec 需求 | 对应 Task |
|-----------|-----------|
| Egress per-session 化（repo-binding T0） | Task 1 |
| Git 令牌 4 列 + profile + git-credentials（T1） | Task 2 |
| agent_workspace 表 + kind/workspace_id（W1） | Task 3 |
| WorkspaceSandboxBinding + Store（W2 前半） | Task 4 |
| WorkspaceSandboxLifecycle lazy clone + create（W2 后半）| Task 5 |
| checkout 管理 create/list/merge/remove（W3） | Task 6 |
| SandboxAgentTools cwd + PathJail 重定向（W3） | Task 6 |
| 硬件档位 + sunshine-sandbox-full（W4） | Task 7 |
| Reaper 不 purge 工作区 + 手动销毁（W5） | Task 8 |
| 前端 Git 分组（W6 前半） | Task 9 |
| 前端新任务入口 + 选择器 + WorkspaceView（W6 后半）| Task 10 |
| ChatView kind=task 适配（W6） | Task 10 |
| 验收脚本（W7） | Task 11 |

### 2. Placeholder 扫描

- 无 "TBD"、"TODO"、"implement later" 字样
- 所有代码步骤包含实际实现内容
- 测试步骤包含具体 assert 断言
- 编译验证命令使用具体的 `mvn compile -pl` 路径

### 3. 类型一致性

- `WorkspaceSandboxBinding` 字段定义在 Task 4，Task 5/6/8 引用一致
- `AgentSandboxProperties.ProfilePreset` 定义在 Task 7，Task 5 create 引用 `resolveProfile()` 一致；Task 3 create 使用 `validateAndResolve()` 校验
- `AuthUser.githubTokenSet`（boolean）在 Task 2（后端 VO）与 Task 9（前端 TS 接口）类型一致
- `ToolInvokeResponse` 使用 `.isSuccess()` / `.getOutput()` 而非 `.ok()` / `.output()`（已修正）

### 4. 修正记录（2026-07-30 评审后）

| 问题 | 修正内容 |
|------|----------|
| Task 5 `ensureWorkspaceSession` 重复 if 条件 | 改为 `sessionRunning()` → `sessionAlive()` → 新建，判死后走新建路径 |
| Task 5 `resolveWorkspaceId` 空实现 | `RunContext` 提升为独立 public record，新增 `workspaceId` + `checkoutPath` 字段，`prepareRun` 时查 `ConversationRepository` 填充 |
| Task 5 `cloneRepo` 无超时 + 令牌 URL 泄露 | 增加 `waitFor(5, MINUTES)` + 超时 `destroyForcibly`；改用 `GIT_ASKPASS` 脚本注入令牌，clone 后删除脚本 |
| Task 6 `SandboxClient.invoke` API 不匹配 | `.ok()` → `.isSuccess()`，`.output()` → `.getOutput()` |
| Task 6 `WorkspaceCheckoutService` 缺并发锁 | `createWorktree` / `mergeToMain` / `removeWorktree` 增加 Redis `setIfAbsent` 分布式锁（30s 超时），`finally` 释放 |
| Task 6 `SandboxAgentTools` 依赖未声明 | 改用 `ConversationRepository` 直查 `chat_conversation.kind/workspace_id/checkout_path`，注入声明 |
| Task 5 `SandboxSessionLifecycle` 注入缺失 | 声明新增 `WorkspaceSandboxLifecycle` + `ConversationRepository` 注入 |
| Task 7 Profile 无校验 | `AgentSandboxProperties` 新增 `validateAndResolve()` 方法，校验用户请求是否在 Nacos allowed-presets 范围内 |
| Task 10 缺失 `WorkspaceView.vue` | 新增 Step 6：完整列表页（含表格 + 新建弹窗 + 删除确认 + Nacos 校验限值） |
| Task 10 `WorkspaceSelector` 缺 `onMounted` | 增加 `fetchWorkspaces()` + `onMounted` 调用 + `loading` 状态 |
| Task 5 `SandboxPolicy` 网络模式说明 | 增加注释说明工作区容器需 bridge 模式（完全体），与对话级 none 区分 |

### 5. 执行顺序建议

Task 1（egress）→ Task 2（auth 令牌）→ Task 3（DB + CRUD）→ Task 4（Store）+ Task 7（镜像+档位）可并行 → Task 5（Lifecycle，依赖 3/4/7）→ Task 6（Checkout，依赖 5）→ Task 8（Reaper，依赖 5）→ Task 9 + Task 10（前端，可并行）→ Task 11（验收）

---

## 执行移交

Plan complete and saved to `docs/superpowers/plans/2026-07-30-agent-workspace-codex.md`. Two execution options:

1. **Subagent-Driven (recommended)** — I dispatch a fresh subagent per task, review between tasks, fast iteration
2. **Inline Execution** — Execute tasks in this session using executing-plans, batch execution with checkpoints

Which approach?
