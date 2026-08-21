# 阶段四 · 4.5 Skills Docker 沙箱（Coding Agent 工具面）

> **阶段**：四 · **任务卡**：**4.5**  
> **状态**：✅ 方案 B 已落地（常驻工具+懒开箱）· 工作区/写确认/时间线展示见 [docs/sandbox/README.md](../../../sandbox/README.md)  
> **日期**：2026-07-15  
> **前置锁定**：D4 Docker 沙箱（[locked-architecture-decisions.md](./2026-06-19-locked-architecture-decisions.md) §D4）  
> **相关**：skill-manager `/skills` · 3.3 HITL · 4.8 特殊工具不进 Catalog（同 `search_knowledge` / `manage_tasks`）  
> **平台索引**：[phase4-platformization-design.md](../phase4-platformization-design.md) §4.5  
> **演进 SSOT**：[conversation-sandbox-permanent-tools-design.md](./2026-07-16-conversation-sandbox-permanent-tools-design.md)

---

## 1. 目标

为声明了沙箱的 Skill 提供 **Coding Agent 工作区能力**：在隔离 Docker 会话内，对挂载目录使用与 Claude Code / Cursor 同构的基础工具面，安全地读改文件并执行命令。

**成功标准（4.5 初版）**：带 `sandbox` 的 Skill 在 ReAct 中可注入并使用六工具；越狱路径与默认出网被拒；写操作走 HITL；会话结束后容器回收且可审计。

**演进（2026-07-16 · 方案 B）**：主 ReAct **始终**注入六工具；容器**懒创建**（首次 `sandbox__*`）；Skill **不**再门控沙箱。详见 [2026-07-16-conversation-sandbox-permanent-tools-design.md](./2026-07-16-conversation-sandbox-permanent-tools-design.md)。

---

## 2. 已确认决策

| 项 | 选择 |
|----|------|
| 形态 | **Coding Agent 工作区**（非纯代码解释器） |
| Workspace | **Skill 材料只读** + **会话可写 workspace** |
| 网络 | **默认 `network=none`**；Skill `sandbox_policy.network_allow` 白名单开网 |
| v1 工具 | `read` / `write` / `edit` / `glob` / `grep` / `exec` |
| 容器生命周期 | **会话级长容器**（同一 Agent run 复用） |
| HITL | 读类免确认；`write`/`edit` 默认确认；`exec` 按只读命令白名单 |
| 服务边界 | **独立 `sandbox-service`（:8226）** |
| 工具注册 | **Orchestrator 内置特殊工具**，**不进** tool-manager DB Catalog |
| 方案 | Orchestrator 注入工具 + RPC sandbox-service（方案 1） |

---

## 3. 架构

> **方案 B（✅）**：MAIN ReAct **常驻**六工具 + 首次工具调用懒开箱。见 [permanent-tools 设计](./2026-07-16-conversation-sandbox-permanent-tools-design.md)。

```text
Chat / Skill 试跑 / Workflow agent(skill)
  → orchestrator AgentRuntime
      → MAIN ReAct：始终注入 6 个 sandbox__* 工具（方案 B）
      → 首次 sandbox__*：ensureConversationSession
      → SandboxClient
            → sandbox-service :8226
                 ├── Session create / tool / close
                 ├── Docker 长容器（每 session 一个）
                 └── Volumes：
                       /skill      ← Skill scripts+references（只读）
                       /workspace  ← 会话工作区（可写）
```

| 组件 | 职责 |
|------|------|
| **orchestrator** | 按 Skill 注入工具、HITL、Timeline、审计编排、`SandboxClient` |
| **sandbox-service** | 会话/容器生命周期、路径 jail、六工具执行、网络白名单 |
| **skill-manager** | Skill 元数据与 `sandbox_policy`；**不**执行 Docker |
| **tool-manager** | **不登记** 六工具 |

修订 D4：保留 Docker 自建与强隔离；网络由「一律 none」扩展为「默认 none + 策略白名单」。

---

## 4. 挂载与路径 Jail

```text
/skill/          # Skill 包 scripts/ + references/（只读）
/workspace/      # 会话工作区（可写；上传/生成文件）
```

- 所有路径规范化后必须落在 `/skill` 或 `/workspace` 下；禁止 `..` 逃逸。
- 默认 cwd：`/workspace`。
- `read` / `glob` / `grep`：可访问 `/skill` + `/workspace`。
- `write` / `edit`：**仅** `/workspace`。
- `exec` 的 `cwd` 必须在 jail 内（默认 `/workspace`）。

工作区种子（v1）：Chat 附件或试跑上传写入 `/workspace`；无上传则为空目录（仍可读 `/skill`）。

---

## 5. 工具契约（v1）

LLM `tool_call.name` / Timeline 与 Catalog 风格一致，使用固定 ID：

| Tool ID | 参数 | 行为 |
|---------|------|------|
| `sandbox__read` | `path`；可选 `offset` / `limit` | 读文本；过大截断并注明 |
| `sandbox__write` | `path`, `content` | 创建或整文件覆盖（仅 workspace） |
| `sandbox__edit` | `path`, `old_string`, `new_string` | 精确替换；`old_string` 不唯一则失败 |
| `sandbox__glob` | `pattern`；可选 `path` | jail 内 glob，返回路径列表 |
| `sandbox__grep` | `pattern`；可选 `path` / `glob` | 内容搜索；路径 + 行号 + 摘录 |
| `sandbox__exec` | `command`；可选 `cwd` / `timeout_sec` | 会话容器内 shell；默认超时 30s |

**偏好规则（写入 mode-overlay / skill overlay）**：文件读写搜索优先专用工具，避免用 `exec` 代替 `read`/`grep`/`glob`/`edit`。

---

## 6. sandbox-service API

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/api/sandbox/sessions` | 创建会话：skill 材料快照 + policy → 启动长容器；返回 `sessionId` |
| `POST` | `/api/sandbox/sessions/{id}/tools/{name}` | 执行单次工具；body 为工具参数 |
| `DELETE` | `/api/sandbox/sessions/{id}` | 停止并移除容器；volume 按策略清理或短期保留便于调试 |

鉴权：服务间调用（与现有 manager 客户端一致）；请求携带 `userId` / `tenantId` / `skillId` / `runId` 供审计。

---

## 7. 安全策略

### 7.1 容器基线

| 项 | 默认 |
|----|------|
| 镜像 | `sunshine-sandbox-python:3.11-slim` |
| 内存 / CPU | 256MB / 0.5（policy 可调） |
| 单次 exec 超时 | 30s（policy 可调） |
| capabilities | `cap_drop=ALL`；非 root 执行 |
| 可写面 | `read_only_rootfs=true`，仅挂载可写 `/workspace` + 必要 tmpfs；禁止 Docker socket |

### 7.2 网络

- 默认：`network=none`。
- `sandbox_policy.network_allow` 非空时：容器接入专用 bridge，出网经 **egress 白名单代理**（域名/CIDR）；**空列表 = `network=none`**。
- v1 **不**做宿主机级 iptables 定制；白名单外连接必须失败（G6）。

### 7.3 HITL（3.3）

| 工具 | 确认 |
|------|------|
| `sandbox__read` / `glob` / `grep` | 否 |
| `sandbox__write` / `edit` | **是**（默认） |
| `sandbox__exec` | 命中只读命令白名单则否；否则 **是** |

只读 exec 白名单由 Nacos/policy 配置（如 `ls`、`pwd`、`python -m pytest *` 等）；未命中一律确认。

### 7.4 审计与指标

- 每次工具调用审计：`sessionId`、tool、参数摘要（全文/代码 **hash**，不全量落库）、exit code、耗时、user/tenant/skill/run。
- Grafana：活跃会话、工具 QPS、exec 失败率、HITL 拒绝率、会话回收延迟。

---

## 8. 编排接入

1. Skill：`sandbox: docker` + `sandbox_policy`（image、timeout_sec、memory_mb、cpus、network_allow、exec_readonly_allow）。
2. `AgentRuntime` 开始：`createSession`；`doFinally`：`closeSession`。
3. Toolkit：在 Skill 原有工具子集上 **追加** 六工具。
4. Timeline：`tool-sandbox__*` 步；label 中文（读文件 / 写文件 / 编辑文件 / 查找文件 / 搜索内容 / 执行命令）。
5. 入口：ReAct `@skill`、Workflow `agent` 节点绑定该 skill、`/skills` 试跑。

未声明 sandbox 的 Skill：**不注入** 六工具。

---

## 9. `sandbox_policy` 示例

```yaml
sandbox: docker
sandbox_policy:
  runtime: docker
  image: sunshine-sandbox-python:3.11-slim
  timeout_sec: 30
  memory_mb: 256
  cpus: 0.5
  network_allow: []          # 空 = 无网
  # network_allow: ["pypi.org", "files.pythonhosted.org"]
  exec_readonly_allow:
    - "ls *"
    - "pwd"
    - "python -m pytest *"
```

---

## 10. 非目标（v1 明确不做）

- `WebFetch` / `WebSearch`、`NotebookEdit`、容器内子 Agent / Task 工具
- 任意宿主机目录 bind、Docker-out-of-Docker
- 将六工具登记进 tool-manager Catalog / `/tools` 启停（可后续加说明文档）
- SQL 只读沙箱（原 L2）、E2B/Modal 托管沙箱
- 多镜像矩阵（先 Python slim；Node 等后续）

---

## 11. 检查门

| # | 项 |
|---|-----|
| G1 | 无 sandbox 的 Skill 工具列表 **不含** `sandbox__*` |
| G2 | 有 sandbox：可读 `/skill`，可写仅 `/workspace` |
| G3 | `edit` 精确替换；路径越狱被拒 |
| G4 | `glob` / `grep` 结果不越出 jail |
| G5 | 同一 session 内多次 `exec` 共享文件系统状态；超时强杀 |
| G6 | 默认无网；白名单外连接失败 |
| G7 | `write` / `edit` 与非只读 `exec` 触发 HITL |
| G8 | 审计可查；`closeSession` 后容器不残留 |
| G9 | `/skills` 试跑：read → edit → exec 最小闭环 |

Live 脚本（计划阶段命名）：如 `scripts/verify_sandbox_live.py`。

---

## 11. 演进：对话级常驻工具（方案 B）

**SSOT**：[2026-07-16-conversation-sandbox-permanent-tools-design.md](./2026-07-16-conversation-sandbox-permanent-tools-design.md)

| 原约束（§2–§3） | 方案 B |
|----------------|--------|
| 仅 `skill.sandbox != none` 注入六工具 | MAIN ReAct **始终**注入 |
| `openIfNeeded` 在 run 开始 | **首次 `sandbox__*`** 时 `ensureSession` |
| 无 Skill 则无沙箱 | 无 Skill 仍可用 `/workspace`；有 Skill 时懒挂载 `/skills/{id}/` |

初版验收 G1「无 sandbox skill 不出现 sandbox__」在方案 B 后**作废**；以 permanent-tools 文档 §7 为准。

---

## 12. 实现任务拆分（供 writing-plans）

| 编号 | 内容 |
|------|------|
| **4.5.1** | `sandbox-service` 骨架 + Docker Session + 镜像构建 |
| **4.5.2** | 六工具执行器 + 路径 jail + 网络白名单 |
| **4.5.3** | orchestrator `SandboxClient` + 工具注入 + HITL 接线 |
| **4.5.4** | Skill `sandbox_policy` 模型 / API / `/skills` 试跑入口 |
| **4.5.5** | 审计 + Grafana + Live 检查门 |

---

## 13. 修订记录

| 日期 | 说明 |
|------|------|
| 2026-07-15 | 初稿：Coding Agent 六工具面 + 独立 sandbox-service + 会话长容器；修订 D4 网络白名单 |
| 2026-07-16 | 方案 B：常驻工具 + 懒开箱；Skill 不门控（见 permanent-tools 设计） |
| 2026-07-16 | 工作区抽屉 / `writeHitlMode` / 时间线相对路径展示；索引 `docs/sandbox/README.md` |
