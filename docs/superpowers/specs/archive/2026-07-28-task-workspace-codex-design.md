# Codex 式智能体工作区（Agent Workspace）设计

> **阶段**：4.5 沙箱 · 增量 · **状态**：✅ 已实现（2026-08-03 核对；实现见 §12 演进差异）
> **日期**：2026-07-28 · **实现核对**：2026-08-03
> **定位**：在「对话级轻量沙箱」（skills 脚本场景）之外，新增**工作区级完全体沙箱**——绑定 Git 项目、多会话共用、用户显式选择 checkout（主分支/worktree，无锁无自动隔离）、硬件档位可配，形成 Codex 式编码智能体产品形态
> **关联**：[sandbox-repo-binding](./2026-07-28-sandbox-repo-binding-design.md)（已归档，粒度升级为本设计的子集）· [sub-agent-sandbox-default](2026-07-17-sub-agent-sandbox-default-design.md) · [sandbox-container-lifecycle](2026-07-17-sandbox-container-lifecycle-design.md) · [archive/4.7.8](./2026-07-28-harness-loop-enhancement-design.md)（已归档；run 内见五层 §4.5）· 索引 [docs/sandbox/README.md](../../../sandbox/README.md)

---

## 1. 背景与动机

当前沙箱定位是「skills 调用脚本」：对话级容器、空目录开箱、`network=none`、硬件固定 0.5C/256M、7 天销毁。适合短平快的脚本任务，但撑不起「给我一个仓库，让 Agent 持续开发」的 Codex 式场景：

| 缺口 | 现状 | 目标 |
|------|------|------|
| 无项目物料 | 空目录 + Skill 只读挂载 | 绑定 Git 仓库 + 分支，clone 进 `/workspace` |
| 网络全断 | `network=none` | 完全体档：任意外网（装依赖、push） |
| 命令受限 | `SandboxExecGuard` 硬拒 + 只读白名单 | 完全体档：任意命令（HITL 语义保留） |
| 硬件单档 | 0.5C/256M 写死 Nacos | 工作区级档位（预设档 + 自定义） |
| 会话级容器 | 一对话一容器，7d 销毁 | 工作区级容器，多会话复用，长期保留 |
| 单任务流 | 天然串行（单会话） | 多会话并发：用户显式选择 checkout（主分支共享 / worktree 隔离），无锁 |

## 2. 核心概念

### 2.1 新实体：`agent_workspace`

一个**智能体工作区** = 一个 Git 仓库 + 分支 + 一个完全体沙箱容器 + N 个会话。

```
agent_workspace 1───N chat_conversation（kind=task，强制 ReAct）
       │
       └─ 1 sandbox 容器（工作区级，完全体档位）
```

| 维度 | 对话级轻量沙箱（现状，保留） | 工作区级完全体沙箱（本设计） |
|------|------------------------------|------------------------------|
| 触发 | 普通 Chat 首次 `sandbox__*` | 创建「新任务」工作区 |
| 粒度 | `conversationId` | `workspaceId` |
| 网络 | `none` / git 白名单 | 完全体（bridge 出网，无 ACL） |
| 命令 | ExecGuard 硬拒 + HITL | 任意命令 + HITL（可配 `writeHitlMode`） |
| 硬件 | 0.5C/256M 固定 | 档位可选（0.5C/256M · 2C/2G · 4C/4G） |
| 镜像 | `sunshine-sandbox-python:3.11-slim` | `sunshine-sandbox-full`（git/build-essential/node 等） |
| 生命周期 | idle 30min 停 / 7d 销毁 | idle 30min 停 / **手动销毁**（默认不自动 purge） |
| 并发 | 单会话天然串行 | 用户显式选择 checkout（主分支共享 / worktree 隔离），无锁 |

### 2.2 会话模式：`kind=task`

`chat_conversation` 增加 `kind` 列（`chat` 默认 / `task`）。「新任务」入口创建的会话 `kind=task`，**强制 `execution_preference=react`**（复用 `ForcedExecutionRouter`，不提供模式选择），且必须挂在某个 `workspaceId` 下。一个任务 = 工作区下的一个会话线程（对齐 Codex 的 thread 概念）。

### 2.3 并发模型：用户显式选择 checkout（无锁、无自动 worktree）

工作区级容器被多会话复用后，**不引入任何应用层锁、也不自动创建 worktree**——**checkout（主分支 / worktree 分支）由用户在创建/进入会话时显式选择**，Agent 只在用户给定的 checkout 内工作：

| 方案 | 结论 |
|------|------|
| 应用层读写锁 | **否决**：粒度大、阻塞排队体验差、重新发明 Git 已解决的问题 |
| Agent 自动建 worktree | **否决**：分支策略是用户的开发决策（要不要隔离、隔离到哪、合并到哪），Agent 不应替用户决定 |
| **用户显式选择 checkout（采用）** | 创建会话时用户选「直接在主分支工作」或「新建/复用一个 worktree 分支」；冲突与否由用户的分支策略决定 |

**业界依据**（2026-07-28 调研）：Cursor `/worktree` 是用户显式命令、Codex App 线程需用户在 composer 选 Worktree 模式——**隔离都是用户的主动选择，不是平台自动行为**。Codex 官方也明确「threads 不自动隔离，都选 Local 就是都改同一 checkout」。

**本设计机制**：

- 工作区 clone 主仓库到 `/workspace/main`（基线 checkout）；
- **创建 `kind=task` 会话时**，用户二选一：
  - **主分支**：cwd = `/workspace/main`，直接在基线 checkout 工作（适合单会话独占工作区、或用户明确要"就在主干上改"）；
  - **worktree 分支**：用户指定分支名（新建 `agent/xxx` 或复用已有分支）→ 容器内 `git worktree add /workspace/branches/{branch} {branch}`，cwd = 该 worktree；
- **用户决定冲突语义**：
  - 两个会话都选主分支 → **共用同一 checkout，second-write-wins**（对齐 Cursor `/multitask` 语义，用户自己承担）；
  - 各选各的 worktree → 目录级隔离，零冲突；
- **合并**：用户在 worktree 会话的抽屉点「合并到主分支」→ `git -C /workspace/main merge {branch}`；**冲突时返回冲突文件清单，由用户决定**（让 Agent 修 / 自己手动 / 放弃），平台不自动解冲突；
- **清理**：worktree 分支由用户在抽屉显式删除（`git worktree remove` + 删分支）；**不自动清**——分支是用户的生产资料，删会话不删 worktree。

**为什么不需要锁也不需要自动隔离**：Git 的 checkout/分支模型本身就是用户表达「我要隔离还是共享」的原生语义。平台要做的是把这个语义暴露给用户选择，而不是在中间加一层用户看不见、也控制不了的锁或自动分支。两个会话共用主分支时 second-write-wins 是 Git 用户的常识预期；要隔离就自己开 worktree，一个命令的事。

**exec 命令的 cwd**：`sandbox__exec` 在会话选定的 checkout 下执行（主分支或 worktree）；`npm install` 等依赖装在该 checkout（worktree 共享 `.git` 对象库，源码不占双份）。

## 3. 数据模型

### 3.1 `agent_workspace`（orchestrator DB，`11-sunshine-orchestrator.sql` 追加，禁 Flyway）

```sql
CREATE TABLE agent_workspace (
    id              VARCHAR(64)  NOT NULL PRIMARY KEY,
    tenant_id       VARCHAR(64)  NOT NULL,
    user_id         VARCHAR(64)  NOT NULL,
    name            VARCHAR(128) NOT NULL,
    repo_url        VARCHAR(512) NOT NULL,
    repo_branch     VARCHAR(128) NOT NULL DEFAULT 'main',
    sandbox_profile VARCHAR(32)  NOT NULL DEFAULT 'full',   -- full=完全体（v1 仅此档）
    memory_mb       INT          NOT NULL DEFAULT 2048,
    cpus            DECIMAL(3,1) NOT NULL DEFAULT 2.0,
    image           VARCHAR(128) NOT NULL DEFAULT 'sunshine-sandbox-full:latest',
    status          VARCHAR(16)  NOT NULL DEFAULT 'active',  -- active / archived
    created_at      DATETIME(3)  NOT NULL,
    updated_at      DATETIME(3)  NOT NULL,
    INDEX idx_ws_tenant_user (tenant_id, user_id, status)
);
```

### 3.2 `chat_conversation` 追加列

```sql
ALTER TABLE chat_conversation
  ADD COLUMN kind          VARCHAR(16)  NOT NULL DEFAULT 'chat' COMMENT 'chat / agent',
  ADD COLUMN workspace_id  VARCHAR(64)  NULL COMMENT 'kind=task 时必填',
  ADD COLUMN checkout_path VARCHAR(256) NULL COMMENT 'kind=task 时必填：用户选定的 checkout（/workspace/main 或 /workspace/branches/{branch}）';
```

### 3.3 Redis binding 粒度迁移

新增工作区级 binding（与对话级并存）：

```
sandbox:ws:{tenant}:{workspaceId}  →  工作区级容器 binding
sandbox:conv:{tenant}:{conversationId}  →  对话级容器 binding（现状，不动）
```

`WorkspaceSandboxBinding` record（对齐 `ConversationSandboxBinding` 结构）：

```java
record WorkspaceSandboxBinding(
        String sessionId, String userId, String tenantId,
        String workspaceId, String state, Long lastActiveAt,
        String repoUrl, String repoBranch, String cloneState,
        int memoryMb, double cpus, String image) {}
```

`cloneState`：`pending` / `done` / `failed:<摘要>`（同 repo-binding spec）。

## 4. 核心链路

### 4.1 创建工作区（强制绑定 repo）

`POST /api/agent-workspaces`（orchestrator，body：`name` / `repoUrl` / `repoBranch?` / `memoryMb?` / `cpus?`）：

1. 复用 repo-binding spec 的 host 白名单校验 + `git-credentials` 令牌解析；
2. 落 `agent_workspace` 行（`status=active`）；
3. **不立即开箱**：首个会话进入或首次 `sandbox__*` 调用时 `ensureWorkspaceSession` 懒开箱（clone + create 容器）。

**强制绑定**：创建时 `repoUrl` 必填（400 校验）；这是与对话级沙箱的本质差异——完全体沙箱的存在前提是有项目物料。

### 4.2 懒开箱 + clone（`WorkspaceSandboxLifecycle.ensureWorkspaceSession`）

复用 repo-binding spec §4.2 的宿主机 clone 链路，差异仅在粒度：

```
ensureWorkspaceSession(workspaceId):
  binding = redis.get(sandbox:ws:{tenant}:{workspaceId})
  if binding exists and alive: touch + return
  workspace = db.get(workspaceId)
  creds = authClient.gitCredentials(userId, hostOf(workspace.repoUrl))
  hostGitClone(repoUrl, repoBranch, creds, hostWorkspaceRoot)  // 容器外 clone
  sandboxService.createSession(image=workspace.image,
      memoryMb=workspace.memoryMb, cpus=workspace.cpus,
      networkMode='bridge',  // 完全体：不挂 egress，直接出网
      hostWorkspace=hostWorkspaceRoot)
  save binding (state=running, cloneState=done)
```

**网络差异**：完全体档 `networkMode=bridge`（Docker 默认桥接，直接出网，不经 egress 代理）。对话级沙箱的 `none`/白名单档不受影响。**安全边界**：完全体档仅对 `kind=task` 工作区开放，普通 Chat 沙箱保持 `none`。

### 4.3 worktree 隔离链路（改造 `SandboxAgentTools` + 新增 `WorkspaceWorktreeService`）

```
SandboxAgentTools.execute(toolId, params):
  workspaceId = resolveWorkspaceId(conversationId)   // kind=task 会话 → 其 workspaceId
  if workspaceId == null:  // 对话级沙箱，现状不动
      ... 现有逻辑（PathJail /workspace） ...
  // 工作区级：cwd = 会话选定的 checkout（用户创建会话时选择）
  checkoutPath = conversation.checkoutPath   // /workspace/main 或 /workspace/branches/{branch}
  params.cwd = checkoutPath   // PathJail 边界 = checkoutPath
  ... HITL + RPC 调用（现有逻辑，无锁） ...
```

**`WorkspaceCheckoutService`**（orchestrator 新增，**不提供自动建 worktree**，只执行用户显式操作）：

| 方法 | 触发 | 行为 |
|------|------|------|
| `createWorktree(workspaceId, branch, fromRef?)` | 用户创建会话时选「新建 worktree 分支」 | 容器内 `git -C /workspace/main worktree add /workspace/branches/{branch} -b {branch} {fromRef\|HEAD}`；返回 checkout 路径 |
| `listCheckouts(workspaceId)` | 创建会话弹窗 / 抽屉 | 返回主分支 + 所有活跃 worktree 分支列表（`git worktree list`）供用户选择 |
| `mergeToMain(workspaceId, branch)` | 用户在抽屉点「合并到主分支」 | `git -C /workspace/main merge {branch}`；冲突返回冲突文件清单（**由用户决定如何处理**） |
| `removeWorktree(workspaceId, branch)` | 用户在抽屉显式删除 | `git worktree remove` + 删分支；**仅用户手动触发，不自动清** |

**PathJail 边界**：工作区级会话的 jail = 会话选定的 checkout 路径（主分支 `/workspace/main` 或 worktree `/workspace/branches/{branch}`）；`read`/`grep`/`glob` 允许读 `/workspace/main`（基线代码）+ 会话 checkout；**不禁止**读其他 worktree（用户显式选择的共享语义下，读是安全的；对齐本地 Git 用户可 `cd` 到任何 worktree 看代码的常识）。`exec` cwd = 会话 checkout。

### 4.4 硬件档位

`AgentSandboxProperties.Runtime` 扩展档位预设（Nacos `agent.sandbox.profiles`）：

```yaml
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

- 创建工作区时可选预设档或自定义（上限受宿主机 4C16G 护栏，orchestrator 校验 `cpus <= 4` 且 `memoryMb <= 12288`）；
- `SandboxSessionService.createSession` 已支持 `memoryMb`/`cpus` 从 policy 透传（现状已就绪，见 `SandboxSessionService:69-72`），本设计只是把 policy 来源从 Nacos 默认改为工作区配置；
- **新镜像** `sunshine-sandbox-full`：在 Python 基座上追加 git / build-essential / node / npm / curl / vim（Dockerfile 放 `docker/sandbox-full/`，`docker-compose` 构建）。

### 4.5 生命周期调整

| 时机 | 对话级（现状） | 工作区级（本设计） |
|------|----------------|---------------------|
| idle 30min | `docker stop` | 同（复用 reaper） |
| 再进 | `docker start` | 同 |
| 销毁 | 7d 自动 purge | **不自动 purge**；仅手动 `DELETE /api/agent-workspaces/{id}`（确认后 `docker rm` + 清盘 + 归档 workspace 行 `status=archived`） |
| 删对话 | 立即销毁容器 | **不销毁**（容器属工作区，删会话只删 `chat_conversation` 行） |

Reaper 的 ZSET 扫描对工作区 binding 只做 idle stop，不注册 purge ZSET。

## 5. 前端

### 5.1 「新任务」入口 + 延迟菜单创建

「新任务」采用**延迟创建**模式，与「新对话」的交互流程不同：

| 交互 | 「新对话」（现状） | 「新任务」（本设计） |
|------|--------------------|----------------------|
| 点击入口 | 立即建会话 + 立即在左侧菜单创建行 | **只开一个空白任务会话页**，左侧菜单**不创建任何条目** |
| 菜单创建时机 | 点击即创建 | **发送首条消息后**才动态创建到指定工作区菜单下 |
| 工作区/分支选择 | 无 | 会话页顶部选择器（发送前可改，发送后只读） |

**会话页顶部的选择器**（发送前可改，发送后只读）：

| 场景 | 工作区 | checkout（分支） |
|------|--------|-------------------|
| 当前已选中某工作区的任务 | **默认沿用该工作区** | **默认沿用当前任务的 checkout**（用户当前所在分支） |
| 否则（普通对话页 / 无选中） | 下拉选择已有工作区 / 「+ 新建工作区」 | 选完工作区后：主分支单选 / worktree 下拉 / 新建分支输入 |

**「+ 新建工作区」**：在选择器内嵌弹窗（name + repo URL + 分支 + 硬件档位），创建后回填到工作区下拉。

**发送首条消息时**：

1. 校验工作区 + checkout 已选（未选阻断发送，提示「请先选择工作区与分支」）；
2. `POST /api/chat/sessions`（`kind=task` + `workspaceId` + `checkoutPath`）落 `chat_conversation`；
3. **动态创建左侧菜单项**：在该工作区节点下插入新任务行（标题取首条消息摘要，同普通会话）；
4. 触发 `ensureWorkspaceSession` 懒开箱（clone + create 容器）。

**左侧菜单结构**：新增「工作区」分类（在会话列表上方或独立分组）——列出当前用户的 `agent_workspace`（name + repo host/org/repo@branch 芯片 + 状态点），展开显示其下任务列表；普通「对话」分类保持现状（`kind=chat`）。

**未发送就离开**：空白任务会话页不落库、不留菜单（与普通「新对话」的草稿行为一致）。

### 5.2 工作区页/抽屉增强

- 工作区抽屉顶栏：repo 芯片 + 分支 + 硬件档位 + 「沙箱配置」入口（弹窗改 memoryMb/cpus/档位，改后下次开箱生效，运行中容器提示重启生效）；
- `cloneState=failed` 红色态 + 重试（复用 repo-binding spec §5 降级）；
- checkout 管理：抽屉列出工作区所有 checkout（主分支 + 各 worktree 分支，`git worktree list`），每个 worktree 行显示分支名 + 关联会话 + 「合并到主分支」/「删除」按钮（**用户显式触发**）；

### 5.3 会话内

- `kind=task` 会话：底栏隐藏 `executionPreference` 选择器（强制 ReAct，显示只读「ReAct 模式」标签）；工作区抽屉默认展开；
- **创建会话时选 checkout**：新建 `kind=task` 会话弹窗（或工作区下「+ 任务」）提供 checkout 选择——「主分支（`/workspace/main`）」单选 /「worktree 分支」单选 + 下拉（`WorkspaceCheckoutService.listCheckouts` 已有分支）+「新建分支」输入框；选择落 `chat_conversation.checkout_path`，会话内只读展示；
- 两个会话同选主分支时，会话内给一句轻提示「另一会话也在主分支工作，改动互相可见」（不阻断，对齐 second-write-wins 语义）。

## 6. 与 4.7.7 / 4.7.8 的协同

Codex 式长任务体验高度依赖 harness loop 能力（**本设计不重复，仅标注依赖**）：

- **4.7.7** 目标对齐 + 失败预算：防多轮开发漂移；
- **4.7.8 阶段一** CompletionGuard「写后必验证」：写代码不跑测试不收束——完全体沙箱的核心价值场景；
- **4.7.8 阶段五** compaction 栈：长任务上下文治理，`ToolResultEviction` 正好消化 `sandbox__exec` 的大输出（编译日志）。

**建议实施顺序**：本设计的沙箱基础设施（工作区实体 + 完全体档 + checkout 选择）可与 4.7.7/4.7.8 并行；「新任务」产品入口在 4.7.8 阶段一（CompletionGuard）落地后再开放，否则体验撑不住（5 轮 max-iters + 无验证闭环）。

## 7. 安全清单

1. **完全体出网边界**：`networkMode=bridge` 仅对 `kind=task` 工作区生效；普通 Chat 沙箱 `network=none` 不回归。orchestrator 在 `ensureWorkspaceSession` 强制校验会话 `kind`。
2. **任意命令边界**：`SandboxExecGuard` 硬拒规则（`rm -rf /` 等）对完全体档**仍生效**（防容器内自毁）；放宽的是「只读白名单免 HITL」的档位（完全体档默认 `writeHitlMode=smart`，用户可改 `never`）。
3. **令牌**：复用 repo-binding spec §7 令牌流转约束（不落 env/命令行/日志/前端）。
4. **worktree 合并冲突**：「应用到主分支」时冲突返回冲突文件清单，Agent 在 worktree 内修复后重试；**不自动解冲突**（对齐 Git 语义，冲突解决权在用户/Agent 协商）。
5. **硬件护栏**：`cpus <= 4` / `memoryMb <= 12288` 硬校验（ecs4c16g 总量）；多工作区并存时 sandbox-service 不做超卖控制（Docker 自身调度），但创建时告警提示当前已分配总量。

## 8. 任务分解（实现顺序）

| # | 任务 | 依赖 |
|---|------|------|
| W0 | repo-binding spec 的 T0–T4 落地（egress per-session、令牌、clone、绑定 API） | — |
| W1 | `agent_workspace` 表 + `chat_conversation.kind/workspace_id` 列 + CRUD API | W0 |
| W2 | `WorkspaceSandboxBinding` + `WorkspaceSandboxLifecycle`（ensureWorkspaceSession + clone + 完全体 create） | W1 |
| W3 | `WorkspaceCheckoutService`（createWorktree/listCheckouts/mergeToMain/removeWorktree）+ `SandboxAgentTools` cwd 重定向 + PathJail 边界 | W2 |
| W4 | 硬件档位（Nacos profiles + 创建/配置校验 + `sunshine-sandbox-full` 镜像） | W2 |
| W5 | 生命周期调整（工作区 binding 不注册 purge + 手动销毁 API） | W2 |
| W6 | 前端：「新任务」入口 + 工作区分类 + 沙箱配置弹窗 + kind=task 会话底栏 | W1、W2 |
| W7 | 验收：`verify_agent_workspace_live.py`（创建工作区→clone→两会话分别选主分支/worktree→并行写互不干扰→合并到主分支→冲突返回清单→硬件档位生效→手动销毁） | 全部 |

**与 repo-binding spec 的关系**：W0 直接落地该 spec 的 T0/T1（粒度无关部分）；T2–T4 在本设计中被 W2/W6 取代（绑定粒度从会话升级为工作区）。**repo-binding spec 需同步修订**（见 §9）。

## 9. 对既有 spec 的修订（本设计生效后同步改）

| spec | 修订点 |
|------|--------|
| `2026-07-28-sandbox-repo-binding-design.md` | 粒度从「会话级绑定」升级为「工作区级绑定」；绑定从可选增强变为工作区创建的强制前置；`ConversationSandboxBinding` 增 repo 字段改为 `WorkspaceSandboxBinding` 独立实体；分支成为一等字段（工作区列表展示）；T2/T4 任务由本设计 W2/W6 取代 |
| `2026-07-17-sub-agent-sandbox-default-design.md` | 增加说明：SUB 复用粒度在 `kind=task` 会话下从 `conversationId` 升级为 `workspaceId`（经 `resolveWorkspaceId`）；`kind=chat` 保持 conversationId 不变 |
| `2026-07-17-sandbox-container-lifecycle-design.md` | 增加工作区级生命周期段落：idle stop 复用，purge 不自动（手动销毁），删会话不销毁工作区容器 |
| `docs/sandbox/README.md` | 索引补本设计；「当前产品行为」表增加工作区级完全体沙箱行 |

## 10. 风险与缓解

| 风险 | 缓解 |
|------|------|
| 两会话同选主分支 → second-write-wins | 创建会话时明确提示 checkout 语义；同选主分支的会话给轻提示「另一会话也在主分支」；用户自主选择承担 |
| 合并冲突（worktree → 主分支） | 冲突返回文件清单，**由用户决定**（Agent 修 / 手动 / 放弃）；平台不自动解冲突，对齐 Git 语义 |
| 完全体出网被滥用（挖矿/攻击） | 仅 `kind=task` 开放 + 强制 repo 绑定（物料前提）+ 全量审计（现有 `sandbox__exec` 审计链路）+ 租户级配额（后续接 5.2） |
| 多工作区硬件超卖 | 创建/改配时校验当前已分配总量（扫 active workspace 的 cpus/memoryMb 求和），超 80% 告警 |
| worktree 磁盘膨胀（多分支各占依赖目录） | worktree 共享 `.git` 对象库（源码不占双份）；依赖目录（node_modules/target）随用户手动 remove 清理；抽屉显示各 worktree 磁盘占用 |
| 工作区级容器长期不销毁占资源 | idle 30min 仍 stop（不占 CPU/内存，只占盘）；列表页显示磁盘占用；手动销毁入口明显 |

## 11. 明确不做

- **应用层读写锁**（业界三家验证：锁是错误抽象——粒度大、阻塞体验差、重新发明 Git 已解决的问题）
- **Agent 自动建 worktree/分支**（分支策略是用户的开发决策；checkout 由用户在创建会话时显式选择）
- 自动合并冲突（冲突返回清单，**用户决定**如何处理）
- 自动清理 worktree 分支（分支是用户生产资料；仅用户显式删除）
- 跨会话执行中断/抢占（各会话在自己的 checkout 内，天然不受其他会话影响）
- 工作区级多容器（一工作区一容器 + 容器内多 checkout；多容器编排属后续 K8s 化议题）
- 工作区协作（多人共享同一工作区；v1 `user_id` 单属主）
- 自动 purge 工作区（手动销毁，防误删生产资料）

---

## 12. 实现演进差异（2026-08-03 核对）

> 本 spec 原始设计为评审稿，落地时在几个关键点上做了**简化与演进**。下方对照实现代码 SSOT，标注差异与原因；以实现为准，spec 原文保留作为决策记录。

### 12.1 已按设计落地（一致项）

| 设计点 | 实现位置 |
|--------|----------|
| `agent_workspace` 表 + CRUD（repoUrl 必填、`cpus<=4`/`mem<=12288` 护栏） | `AgentWorkspaceController` · `AgentWorkspaceEntity/Repository` · SQL `V18` |
| `chat_conversation` 加 `kind`/`workspace_id`/`checkout_path` | SQL `V19` · `ChatConversationEntity` |
| `WorkspaceSandboxBinding`（Redis `sandbox:ws:{tenant}:{wsId}`，无 purge ZSET） | `WorkspaceSandboxStore`（仅 idle ZSET） |
| 工作区容器 `bridge` 直出网；普通 Chat 仍 `none` 不回归 | `SandboxSessionService` `externalWorkspace` -> `--network bridge` |
| ExecGuard 硬拒防自毁仍生效（task 走精简 TASK_RULES） | `SandboxExecGuard.denyReason(cmd, kind)` |
| Reaper 工作区仅 idle stop、不自动 purge；DELETE 手动销毁+归档 | `SandboxSessionReaper.reapWorkspaceIdleStop` · `destroyWorkspaceSession` |
| 完全体镜像 `sunshine-sandbox-full`（git/build-essential/node/npm/curl/vim） | `docker/sandbox-full/Dockerfile` |
| 用户级 Git 令牌 + `GET /api/auth/git-credentials`，GIT_ASKPASS 注入不落盘 | `WorkspaceSandboxLifecycle.cloneMirrorRepo` · auth `InternalGitCredentialController` |
| 前端「新任务」入口（延迟建菜单）+ `WorkspaceView` + 工作区分类 | `ChatView` `newTaskMode`/`pendingWorkspace` · `WorkspaceView.vue` |

### 12.2 关键差异（实现已偏离原设计）

1. **clone/存储模型：普通 clone -> 共享裸镜像库**
   - 原设计 §4.2：宿主机普通 clone 到 `/workspace/main`，主 checkout 直接挂载给 AI。
   - 实现：宿主机 `git clone --mirror` 成**共享裸镜像库** `repos/{wsId}.git`，挂容器 `/opt/git`（PathJail 外，AI 读不到）；会话工作目录均为容器内懒建 worktree。
   - 收益：分支对象全量共享、凭据落 `/opt/git` 不暴露给模型、push 走常规 refspec（已 `--unset remote.origin.mirror`）。

2. **「主分支 checkout」概念取消**
   - 原设计 §2.3/§4.3：用户创建会话二选一「主分支 `/workspace/main` / worktree `/workspace/branches/{branch}`」，并给「两会话同选主分支」second-write-wins 轻提示。
   - 实现：**无主 checkout**，一律 worktree `/workspace/{checkoutId}`（`wt-{uuid}`），分支↔目录一一对应；`WorkspaceCheckoutService.ensureCheckout` 按分支幂等复用已有 worktree。
   - 随之「同主分支共用 checkout + 轻提示」整套语义**未落地**（无主分支则无共享语义）。

3. **`mergeToMain` 未做，换为完整 Git 工作流**
   - 原设计 §4.3：抽屉「合并到主分支」-> 冲突返回文件清单，由用户决定。
   - 实现：全代码库无 merge；取而代之是 `WorkspaceGitService` 的 `status/stage/commit/push/pull` + `/sync`（fetch --all / 重新 clone），前端 `GitBranchSelector` + 会话内 git 操作按钮。
   - 合并语义变为：worktree 内 commit -> push 远程 -> 用户自行处理（对齐 Git 原生工作流，平台不介入合并）。

4. **懒开箱 -> 创建即开箱 + 手动同步**
   - 原设计 §4.1/§4.2：创建时不立即开箱，首会话/首工具才 `ensureWorkspaceSession`。
   - 实现：`POST /api/agent-workspaces` 创建后**后台线程立即开箱**（clone + create 容器）；另加 `POST /{id}/sync` 手动刷新、clone 失败自动重试。
   - 原因：避免首条消息才 clone 的长等待，前置到创建时后台进行。

5. **「强制 ReAct」仅前端隐藏，后端未锁死**
   - 原设计 §2.2：`kind=task` 强制 `execution_preference=react`（复用 `ForcedExecutionRouter`）。
   - 实现：前端 task 会话隐藏 `ExecutionModeSelector`，但 `useExecutionPreference` 默认仍 `auto`/本地存储值，`ConversationService.create` 未写死 `react`。
   - **缺口**：后端未真正锁死模式，task 会话理论上仍可走其他模式。

6. **硬件档位名存实亡**
   - 原设计 §4.4：Nacos `agent.sandbox.profiles.full.allowed-presets` 三档 + `validateAndResolve` 校验。
   - 实现：`AgentSandboxProperties.ProfilePreset` 已定义，但 **Nacos `sunshine-orchestrator.yaml` 无 `profiles` 配置**；实际是工作区落库用户自定义 `memory_mb`/`cpus` + Controller 硬编码上限。`sandbox_profile` 列存了但未走档位解析。
   - **缺口**：档位预设未生效，仅硬护栏生效。

### 12.3 实现新增（spec 未覆盖）

- `WorkspaceGitService`：完整 git 操作（`safe.directory` 豁免、askpass 注入 push/pull/fetch）。
- `WorkspaceProjectGuideEntity`：项目规范（类 CLAUDE.md，注入 task 场景上下文，有独立 spec 跟进）。
- `SandboxWorkspaceService`：工作区文件浏览 API（`/sandbox/workspace`，无需 conversationId）。
- `PromptComposer` scene-overlay（`resolveSceneOverlay(kind)`）+ `workspaceCheckout` overlay（提示 AI 当前工作目录）。
- `POST /{id}/checkouts/ensure` 幂等端点、`ReactiveBlocking` 规避 reactor 线程阻塞。

### 12.4 明确不做 / 已补齐

| 项 | 结论 | 说明 |
|----|------|------|
| 强制 ReAct 锁死 | **明确不做** | task 会话前端已隐藏模式选择器；后端不锁死 mode，保留用户在 task 会话内切其他模式的能力（与「不强制」产品决策一致） |
| 硬件档位 Nacos 配置 + 校验 | **✅ 已补齐** | Nacos `agent.sandbox.profiles.full.allowed-presets` 三档；`AgentSandboxProperties.validateAndResolve` 接入 `AgentWorkspaceController.create`；前端 WorkspaceView 档位下拉；Live `verify_agent_workspace_live.py` 覆盖非法档位 400 + 合法档位落库 |
| `verify_agent_workspace_live.py` 覆盖增强 | **✅ 已补齐** | 现 17 项断言：档位校验 + checkout list/ensure 幂等 + clone 真实成功 + task 会话绑定 + 归档 |
