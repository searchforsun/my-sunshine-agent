# 工作区项目绑定（Git 仓库 + 用户令牌）

> **阶段**：4.5 沙箱 · **状态**：待评审（评审通过后再动工）  
> **触发**：对话级工作区目前只有「空目录 + Skill 只读挂载」一档；需支持**绑定 Git 项目**：clone 进 `/workspace`、按白名单开网，形成「项目工作区」完整形态  
> **关联**：索引 [docs/sandbox/README.md](../../sandbox/README.md) · 基座 [skills-docker-sandbox-design](./2026-07-15-skills-docker-sandbox-design.md) · 用户级配置范式 [user-default-write-hitl-design](./2026-07-16-user-default-write-hitl-design.md) · 出网 [EgressProxyManager](../../../sandbox-service/src/main/java/com/sunshine/sandbox/docker/EgressProxyManager.java)

---

## 1. 目标与非目标

| 项 | 行为 |
|----|------|
| **用户级 Git 凭据** | 账号设置可配 **GitHub + 内网 GitLab** 两个服务：各「基础地址 + 访问令牌」；落 auth `sys_user` |
| **工作区绑定项目** | 对话级绑定 repo URL（+ 分支）；首次开箱时 clone 到 `/workspace`（容器外落盘，挂卷即生效） |
| **绑定即开网** | 绑定项目 → 该会话 `networkAllow = 默认 git 白名单 + repo host`；未绑定 → 维持 `network=none` |
| **默认 git 白名单** | Nacos `agent.sandbox.runtime.default-git-allow`：`github.com` + 内网 GitLab 域名（运维可改） |

**非目标（明确不做）**：

- ❌ **绑定项目 ≠ 放开危险操作 / 免确认**：写与危险 exec 仍走 `writeHitlMode` HITL（用户想少被打扰，自己在账号设置显式改 `smart/never`）。绑定只影响**网络与物料**两个维度。
- ❌ 包管理外网（pypi/npm/maven）：默认白名单仅 git 域名；装依赖需运维在 `default-git-allow` 追加或走 Skill `network_allow`。
- ❌ SSH 协议 clone：v1 仅 HTTPS + PAT。
- ❌ 多仓库：一会话绑一个项目。

---

## 2. 总体语义

| 场景 | 网络 | `/workspace` | HITL |
|------|------|-------------|------|
| 未绑定项目（现状） | `network=none` | 空目录 + 用户产物 | `writeHitlMode`（不变） |
| 绑定项目 + 已配对应令牌 | `default-git-allow + repo host` 白名单 | clone 仓库内容 | 不变 |
| 绑定项目 + 未配令牌 | 同上（仅公开仓库可成功） | 公开仓库 clone 成功；私有失败见 §5 降级 | 不变 |

**凭据匹配**：repo URL 的 host 匹配用户 `githubUrl`/`gitlabUrl` 的 host → 取对应令牌；都不匹配 → 无令牌（仅公开仓库可用）。

---

## 3. 数据与 API

### 3.1 DB（SSOT：`docker/mysql/init/10-sunshine-auth.sql`）

```sql
ALTER TABLE sys_user
  ADD COLUMN github_url     VARCHAR(255) NOT NULL DEFAULT '' COMMENT 'GitHub 基础地址，如 https://github.com',
  ADD COLUMN github_token   VARCHAR(255) NOT NULL DEFAULT '' COMMENT 'GitHub PAT（v1 明文；演进见 §8）',
  ADD COLUMN gitlab_url     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '内网 GitLab 基础地址',
  ADD COLUMN gitlab_token   VARCHAR(255) NOT NULL DEFAULT '' COMMENT '内网 GitLab PAT';
```

禁止 Flyway；已有环境手工执行同等 `ALTER`（或重建 init）。

> **令牌存储（v1 决策）**：对齐本阶段其他密钥的明文现状，v1 明文存列。约束：任何 API / 日志 / 时间线**不回传**令牌（见 §3.2、§7）。§8 列出演进项（加密列 / 独立 secret 服务）。

### 3.2 Auth 契约

| 端点 | 变更 |
|------|------|
| `PATCH /api/auth/profile` | 请求增可选 `githubUrl` / `githubToken` / `gitlabUrl` / `gitlabToken` |
| `login` / `me` / `profile` 响应 | 增 `githubUrl` / `gitlabUrl` + `githubTokenSet` / `gitlabTokenSet`（boolean，仅表示已配置）；**禁止**回传令牌明文 |
| 新端点 `GET /api/auth/git-credentials?host={host}` | **服务端间**凭据查询（orchestrator → auth）：返回匹配 host 的 `{url, token}`；`x-user-id` 由网关注入、auth 校验与 JWT 一致。令牌只允许经此内网通道流动 |

校验：`githubUrl` / `gitlabUrl` 须 `https?://` + 合法 host，非法存空串；令牌去空白原样存。

### 3.3 会话绑定存储（orchestrator Redis）

复用 `ConversationSandboxBinding`，增字段：

```java
record ConversationSandboxBinding(
        String sessionId, List<String> loadedSkillIds, String userId, String tenantId,
        String conversationId, String state, Long lastActiveAt,
        // 新增 ↓
        String repoUrl, String repoBranch, String cloneState) {}
```

`cloneState`：`pending` / `done` / `failed:<原因摘要>`（供工作区抽屉展示；**勿**含令牌/完整命令）。绑定信息随会话 7d TTL 销毁，不落 MySQL（与现有 binding 同生命周期）。

---

## 4. 核心链路

### 4.1 绑定入口（工作区抽屉）

`POST /api/sandbox/workspace/repo`（orchestrator，body：`conversationId` / `repoUrl` / `branch?`）：

1. 解析 host → 调 auth `git-credentials` 取令牌；
2. 校验：host 必须在 `default-git-allow` 或用户已配 git 服务内，否则 400「该 git 服务未在白名单，请联系运维」；
3. 写 binding（`cloneState=pending`）；
4. **幂等**：同 conv 重复绑定同 repo → no-op；换 repo → 若会话已存在且已 clone → 400 提示「先重置工作区」（v1 不支持原地换仓库）。

### 4.2 开箱 clone（`SandboxSessionLifecycle.ensureSession` 扩展）

现有 `ensureSession` 在 create 容器**之前**准备宿主机目录，clone 天然落在同一位置（**容器外执行**，挂卷即见，无需镜像预装 git）：

```
createEmpty():
  hostWorkspace = hostRoot/workspace
  if binding.repoUrl != null:
      creds = authClient.gitCredentials(userId, hostOf(repoUrl))
      allowHosts = defaultGitAllow + hostOf(repoUrl)
      policy = policy.withNetworkAllow(allowHosts)        // ← 唯一改 networkAllow 的位置
      hostGitClone(repoUrl, branch, creds, hostWorkspace) // 宿主机 git clone --depth 1
      cloneState = done | failed:<摘要>
```

- **宿主机 clone 的网络边界**：orchestrator 主机本就能访问内网 GitLab；访问 github.com 依赖部署机出网能力（部署运维职责，同 egress 镜像构建时的外网依赖）。若需收敛，可演进为 clone 也走 egress 代理（§8）。
- **令牌注入**：`https://oauth2:{token}@host/org/repo.git`（GitLab）/ `https://x-access-token:{token}@github.com/...`；clone 后**删除** hostWorkspace 下 `.git/config` 中的凭据段（或 clone 时用 `-c http.extraheader` + 不写回），保证容器内 `git remote -v` 不可见令牌。
- 超时：宿主机 clone 默认 120s；深度 `--depth 1`；失败 → 不阻塞 create（`cloneState=failed`，workspace 为空目录）。

### 4.3 令牌进容器（Agent `git push/pull` 场景）

clone 之后容器内对**同一 host** 的 git 操作需二次认证。方案：

- sandbox-service 新增内部端点 `POST /sessions/{id}/git-credential`：orchestrator 把 `{host, token}` 下发给 sandbox-service（内存持有，随会话销毁，不落盘、不进容器 env）；
- `sandbox__exec` 执行 `git *` 命令时，sandbox-service 为该次 exec 注入 `GIT_ASKPASS` 应答脚本（`/tmp/.git-askpass-{invocationId}`，exec 结束即删）；
- 非 git 命令不注入；令牌不进命令行参数（防 `ps` 泄露）。

> v1 简化可选项：仅支持 clone（拉），容器内 push 提示用户在 HITL 确认后由 Agent 走 `git -c http.extraheader`（令牌经 askpass 注入）。push 属危险写，天然走 HITL 确认，与「绑定不解锁危险操作」一致。

### 4.4 Egress per-session（前置依赖 T0）

现状 `EgressProxyManager` 全局单容器、共享 ALLOW（并发会话互相覆盖）。改为：

| 项 | 设计 |
|----|------|
| 容器命名 | `sunshine-sandbox-egress-{sessionId[:12]}` |
| 网络 | 仍共享 bridge `sunshine-sandbox-net`；per-session 代理容器加入该网络 |
| 生命周期 | 随会话 create 启动、stop 停、close/purge 删；代理容器无状态、镜像极小，成本可忽略 |
| 兼容 | 无网络白名单的会话仍 `--network none`，**不启动** egress（现状） |

---

## 5. 降级与错误

| 场景 | 行为 |
|------|------|
| clone 失败（网络 / 认证 / 非空目录） | 会话照常创建；`cloneState=failed:<摘要>`；抽屉显示「仓库拉取失败」+ 重试按钮（重调绑定接口） |
| 未配令牌 + 私有仓库 | clone 401 → 同上 failed；抽屉引导「去账号设置配置 {host} 令牌」 |
| auth `git-credentials` 不可用 | 按无令牌处理（不阻塞开箱） |
| egress 启动失败 | create 失败抛错（与现状一致），绑定信息保留，下次 ensure 重试 |

**原则**：clone 是增强不是门槛——任何凭据/网络问题都不阻塞「空工作区」路径。

---

## 6. 前端

| 位置 | 变更 |
|------|------|
| `UserSettingsModal` | 新增「Git 服务」分组：GitHub（地址 + 令牌）、内网 GitLab（地址 + 令牌）；令牌 input `type=password`，已配置显示「已配置 · 重新输入以更新」；保存走 `updateProfile` 扩展字段 |
| 工作区抽屉顶栏 | 「绑定项目」入口：输入 repo URL + 分支 → 调 §4.1；已绑定显示 repo 芯片（host/org/repo@branch）；`cloneState=failed` 红色态 + 重试 |
| `api/auth.ts` | `AuthUser` 增 `githubUrl/gitlabUrl/githubTokenSet/gitlabTokenSet`；`updateProfile` 增四参 |

UI 风格遵循 `global.css` `--sun-*`（输入用 `sun-field` 覆写，同账号设置现有项）。

---

## 7. 安全清单

1. 令牌只出现在：`sys_user` 列、auth→orchestrator 内网响应、sandbox-service 内存、askpass 临时文件（exec 期间存在）。**禁止**：进容器 env、进 exec command 参数、进 `cloneState` / 时间线 / 日志 / 前端响应。
2. repo URL host 白名单校验在 orchestrator 绑定入口（防 SSRF：任意内网 host 借 egress 出网）。
3. clone 在宿主机以固定非交互模式执行，repo URL 经 `SAFE_URL` 正则校验（防参数注入，同 `validateImage` 范式）。
4. `git-credentials` 端点仅内网服务调用，auth 侧校验 `x-user-id` 与 JWT 一致，防越权取他人令牌。
5. HITL 语义不变：`git push` 等写远端的 exec 仍按 `writeHitlMode` 确认。

---

## 8. 任务分解（实现顺序）

| # | 任务 | 依赖 |
|---|------|------|
| T0 | Egress per-session 化（`EgressProxyManager` + `SandboxSessionService`） | — |
| T1 | auth：4 列 + profile 读写 + `git-credentials` 内网端点 + 单测 | — |
| T2 | orchestrator：binding 增 repo 字段 + `POST /workspace/repo` + `ensureSession` clone 分支 | T0、T1 |
| T3 | sandbox-service：`git-credential` 端点 + exec askpass 注入（push 支持） | T2 |
| T4 | 前端：账号设置 Git 分组 + 抽屉绑定入口/状态 | T1、T2 |
| T5 | Nacos：`agent.sandbox.runtime.default-git-allow`（`sync_nacos.py`） | — |
| T6 | 验收：`verify_sandbox_repo_live.py`（绑定→clone→read/edit→push HITL；未绑定仍断网） | 全部 |

**演进项（不在本版）**：令牌加密列 / 独立 secret 服务；clone 走 egress 代理；SSH 协议；多仓库；包管理白名单预设档位。

---

## 9. 验收标准

1. 未绑定会话：`docker inspect` 网络为 `none`（回归不破）。
2. 绑定 github 公开仓库：`/workspace` 出现仓库内容；容器内 `curl https://pypi.org` 被 egress ACL 拒绝，`git ls-remote origin` 通。
3. 私有 GitLab 仓库 + 已配令牌：clone 成功；未配令牌：cloneState failed + 抽屉引导文案。
4. 两个会话绑定不同 host 的项目：各自 egress 白名单互不干扰（T0 回归）。
5. `git push`：弹 HITL 确认；确认后成功且容器内不可见令牌（`env` / `git remote -v` / `/tmp` 无残留）。
6. 全链路日志 / SSE / DB 中检索不到令牌明文（自动化断言）。
