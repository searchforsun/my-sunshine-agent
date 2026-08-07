# Task 场景上下文方案（CLAUDE.md 式项目规范 + KV Cache 增量组装）

> **阶段**：四（增量）· **状态**：设计稿（待评审）
> **日期**：2026-08-01 · **v2（2026-08-05）**：确立 chat/task **跨会话记忆隔离边界**（写/读双侧路由，前置必做）；chat **保留** L3 并经 scene 隔离向量通道；task 用户偏好**严格隔离**、由 P0 项目规范显式补位
> **定位**：为 `kind=task` 编码场景建立区别于 chat 的上下文治理体系——对齐 Cursor 的 Dynamic Context Discovery 与 KV Cache 经济学；其中的「项目规范」功能已先行落地 ✅
> **关联**：[unified-context-compression-design](./2026-07-31-unified-context-compression-design.md)（五层管道，本方案是其 task 场景适配；v2 起场景隔离边界已同步其 §5.5.7 差异表）· [task-workspace-codex-design](./2026-07-28-task-workspace-codex-design.md)（`agent_workspace` + `kind=task` 载体）· [harness-loop-enhancement-design](./2026-07-28-harness-loop-enhancement-design.md)（4.7.8 阶段五 AS compaction，run 内 tool 消息流，与本方案正交）

---

## 1. 背景与动机

### 1.1 现状：chat/task 共用同一套上下文

当前所有会话（`kind=chat` 与 `kind=task`）走完全相同的五层管道（L1 Near/Mid/Far + L2 结构化状态 + L3 向量召回 + Budget 裁剪）。task 场景的工程特性未被利用：

| 缺口 | 现状 | task 场景危害 |
|------|------|--------------|
| 无项目级记忆 | L2 仅 `(tenant_id, user_id)` 维度 | 工作区内会话之间不共享项目索引/方案/约束，每个会话重新探索 |
| 无任务级摘要 | L1 只压缩对话轮次 | 「目标/已改文件/验证结果/待办」无载体，任务上下文增长全量靠轮次压缩 |
| KV Cache 全 miss | 每轮基于全量 history 重新 partition 固定 `near=8` | Near 块每轮整体右移，messages 前缀每轮变 → prefix caching 全量重建 |
| 无项目规范 | — | 用户无法像 CLAUDE.md 那样维护项目级约定 |

### 1.2 行业调研：Cursor 编码场景上下文机制

Cursor 的核心哲学是 **Dynamic Context Discovery**（[官方博客](https://cursor.com/blog/dynamic-context-discovery)）：**context should be discovered, not dumped**——文件系统即记忆基板，静态只注入最少引导信息，深细节按需拉取。

| Cursor 机制 | 做法 | 对 task 场景的意义 |
|------------|------|-------------------|
| 长工具输出 → 文件 | 长 shell/MCP 结果不截断，写文件 + `tail`/渐进读取 | 防数据丢失，减少不必要摘要 |
| chat history 作为文件 | 上下文满 → 自动摘要，原始 history 存可检索文件；缺细节 grep 恢复 | 防「摘要后有损压缩」的信息丢失 |
| Skills / Rules 标准 | 只静态注入名字+描述；正文按需 grep/semantic search | 一 A/B 测试降 46.9% token |
| MCP 工具描述 → 文件夹 | 静态只给工具名列表，全描述按需读 | 长描述不常驻上下文 |
| 终端会话 → 文件 | 终端输出同步本地文件系统，`grep` 按需查 | 「为什么命令失败了」类问题按需定位 |

上下文组装相关两条：
- **Rules 分层作用域**（`.cursor/rules/*.mdc` frontmatter）：`alwaysApply`（每会话注入）/ `globs`（命中文件才注入）/ `description`（agent 按相关性自取）/ 手动 `@`。模块化 + 作用域化是 token 预算关键。
- **KV Cache 经济学**：缓存命中完全依赖 **prefix 稳定**。timestamp 等动态注入、上下文重排、早期文件编辑都会让整个 cache 失效。Cursor 把静态指令保持在上下文最前，动态内容放尾部。

---

## 2. 设计目标

| 做 | 不做 |
|----|------|
| chat **保留** L3（scene 隔离向量通道 + kind 门禁，见 §6.3） | L3 语义提取层升级（五层 spec §7.4 P1） |
| **写/读双侧场景路由（防跨会话污染，前置必做，见 §2.1）** | — |
| 工作区级项目记忆（W0，会话公有） | 新建独立 context 微服务 |
| 任务级进度摘要（T0） | 对最终答案二次加工 |
| KV Cache 增量组装（压缩点模式，chat/task 统一启用） | 兼容/双写旧 STM Redis |
| 项目规范（CLAUDE.md 式，用户手动维护） | 自动生成/覆盖用户规范 |
| 项目索引 + 按需发现工具 | 全量注入项目文件 |

**核心原则**（对齐 Cursor）：
- **静态层稳定 → prefix 缓存命中**：低频变更的规范/状态放最前，逐轮只 append 尾部
- **先轻后重**：tail 增量零代价每轮跑，跨轮压缩一次性集中触发（对齐五层 spec §4.4「三阶段一次」；压缩点模式已回写五层 spec §5.5，chat/task 统一）
- **按需发现，不全量注入**：项目细节存索引，Agent 经工具按需拉取

### 2.1 场景隔离边界（v2 前置 · chat/task 跨会话记忆互不污染）

**决策记录（2026-08-05 拍板）**：
- chat **保留** L3 跨会话历史召回，经 **scene 隔离向量通道**（同一 collection + `scene` 字段过滤，见 §6.3）
- task 会话发现的**用户级偏好严格隔离**，不回流用户 L2；用户显式偏好经 **P0 项目规范**（CLAUDE.md 式）补位

**隔离矩阵**（记忆层 × 场景 × 读写）：

| 记忆层 | 作用域 | chat 读 | chat 写 | task 读 | task 写 |
|--------|--------|:---:|:---:|:---:|:---:|
| L1 Near/Mid/Far | conversation | ✅ | ✅ | ✅ | ✅ |
| L2 用户状态 | (user, tenant) | ✅ | ✅ | ❌ | ❌ |
| W0 工作区记忆 | (tenant, workspace) | ❌ | ❌ | ✅ | ✅ |
| T0 任务进度 | conversation | — | — | ✅ | ✅ |
| L3 chat 历史 | (user, tenant) | ✅ | ✅（scene=chat） | ❌ | ❌ |
| P0 项目规范 | workspace | ❌ | — | ✅ | — |

> L1 按 `convId` 天然会话内隔离，本方案只补跨会话层（L2/W0/L3/T0）的场景闸门。

**写路由（防污染闸门）**——`ContextWritePath.runAsync` 按 `conversation.kind` 分流：

```
task 会话（kind=task）:
  L2 抽取   → 跳过（不写用户 L2）
  L1 压缩   → 执行，但压缩上下文读 W0/T0（不读用户 L2，修弱串通道）
  W0 抽取   → 执行（§5.2）
  T0 刷新   → 随压缩点推进（§6.1）
  L3 ingest → 跳过（不写向量库）

chat 会话（kind=chat）:
  L2 抽取   → 执行（现状）
  L1 压缩   → 执行（现状）
  L3 ingest → 执行（scene=chat，§6.3）
```

**读路由（防串闸门）**——`ContextAssembler.assemble` 按 `AssembleRequest.scene` 选源：

```
task 场景:
  注入 W0 + T0 + P0 项目规范
  不注入用户 L2；不召回 L3
chat 场景:
  注入用户 L2
  不注入 W0/T0/P0；L3 召回 scene=chat
```

**现状污染证据（改造前）**：
- 写路径无条件 L2+L3：`ContextWritePath.runAsync` 对 chat/task 一视同仁（L2 抽取 + L1 压缩 + L3 ingest 全部执行）
- 读路径无条件用户 L2：`ContextAssembler.assemble` 恒读 `l2StateStore.assembleSystemBlock(userId, tenantId)`；`AssembleRequest` 无 `scene`/`workspaceId` 维度
- L3 共享向量库：`sunshine_chat_history` 按 `(user_id, tenant_id)` 召回，无 scene 过滤
- 弱串通道：`L1Compressor.compress` 压缩 task 历史时也读用户 L2 作为压缩上下文

**隔离原则**：执行链路（`AgentRuntime`/ReAct/压缩点机制）完全共用；只对「读写哪份记忆」按场景路由。写/读路由是**两处唯一闸门**，禁止在业务代码里用临时 if 打补丁——防污染靠写侧，防串靠读侧，双侧缺一不可。

---

## 3. 架构总览

```
┌─ Tier 0 · 绝对静态核（字节恒定 → 双层缓存内层命中）────────────┐
│  tools（确定性序列化）+ System base + overlay.task + mode.react  │
│  + [P0] 项目规范（用户维护 CLAUDE.md 式）→ system  ✅ 已实现       │
├─ Tier 1 · 低频记忆（content-hash 幂等，真变才失效一次）─────────│
│  + L2 用户状态 → system                                          │
│  + [W0] 工作区记忆（索引/方案/确认项/事实/约束/摘要）→ system     │
│  + [T0] 任务进度（降频：随压缩点刷新）→ system                   │
│  + L1 Far 折叠 + L1 Mid 压缩 → system                            │
├─ Tier 2 · 动态段（每轮 append，只 miss 尾部）───────────────────│
│  + L1 Near 原文（从「压缩点」开始，逐轮增长）                    │
│  + L3 召回（U 形排序）· 意图/模式注入（尾部 system 消息）         │
│  + 当前 user query（tail 末尾）                                  │
└─────────────────────────────────────────────────────────────────┘
  动态发现（工具，不入前缀）：
  + [P1] ws_index / ws_read / ws_grep（复用沙箱工具集）
  + AS 原生 session_search（压缩后历史按需 grep 恢复，对应 history-as-files）
```

> **v3 分层依据**：意图识别为路由决策（控制流），结果不注入 prefix；W0/L2 靠幂等 upsert 保证字节稳定；T0 降频随压缩点刷新。业界证据与约束见五层 spec §5.5.5。

与五层管道的映射：
- `W0` = 新增跨会话层（工作区维度），Tier 1
- `T0` = 新增会话级摘要（任务态优先于对话态），Tier 1 · 降频
- `L1/L2/Budget` = 保留改造（压缩点模式 + Tier 0/1/2 分层）
- `L3` = task 场景不读不写（kind 门禁，见 §6.3）；chat 场景保留，经 scene 隔离向量通道（`scene=chat` 过滤），Tier 2 尾部
- Layer 1 / 4.7.8 阶段五 AS compaction = 正交不变（管单次 run 内 tool 消息流）

---

## 4. 核心设计 A：KV Cache 增量组装（压缩点模式）

### 4.1 问题

当前 `ContextAssembler.assemble` 每轮基于**全量 history 重新 partition**：

```46:49:orchestrator/src/main/java/com/sunshine/orchestrator/context/ContextAssembler.java
        int nearN = Math.max(1, l1.getNearTurns());
        int midN = Math.max(0, l1.getMidTurns());
        L1Compressor.WindowBands bands = L1Compressor.partition(source, nearN, midN);
```

固定 `near=8` 滑动窗意味着每轮 Near 块整体右移，messages 前缀每轮都变 → KV Cache 每轮全 miss、全量 prefill。这与五层 spec §4.3「跨轮压缩改 prefix 是 KV Cache 敌对操作」相矛盾——现有实现每轮都在做这个敌对操作。

### 4.2 方案：压缩点前移，tail 只 append

复用现有 `conversation_context_l1.far_folded_msg_ids`（已折叠 msgId 集合）作为**压缩点**，把 L1 从「固定滑动窗」改为「压缩点前进」。同时按**变化频率分层**（对齐五层 spec §5.5.3 v3，业界调研见五层 spec §5.5.5）：

```
每轮 prepareNewMessage 组装：
  Tier 0 = tools(确定性序列化；规模大时名列表静态 + schema 尾部，见下) + System base + overlay.task + P0 项目规范
  Tier 1 = L2 + W0 + T0(降频) + Far + Mid      ← content-hash 幂等，真变才失效一次
  Tier 2 = Near 原文 + L3(尾部) + 意图/模式注入(尾部 system) + 当前 user
  KV cache：DeepSeek prefix caching 命中 ✅（Tier 0 内层稳定核）

  若 tail token > modelWindow × 0.8（一次性触发）：
    L1Compressor 压缩：Near→Mid 摘要、Mid+旧Far→新Far 折叠
    压缩点前移 → prefix 重建一次（唯一一次 KV cache 全 miss）
    → 对齐五层 spec §4.4「三阶段一次原则」，落地到跨轮组装层
```

> **v6 注记（tools 分层注入）**：工具规模 > 阈值（默认 20）时，Tier 0 `tools` 从「全量 schema」改为「**全量工具名列表**」+ Tier 2 尾部注入 Top-K 完整 schema（对齐 [phase5 §5.5](./phase5-operation-openness-design.md) 工具检索；本 spec 依赖其 `retrieval` 模式，不重复实现）。小工具集保持 `full` 模式全量 schema 进 Tier 0。

> **v3 修正**：原稿把 W0/L2/T0 全部置于「静态层」是错误的——它们每轮可能 upsert。已按频率分层：W0/L2 依赖 content-hash 幂等 upsert（见 §5.2）、T0 降频随压缩点刷新（见 §6.1），才允许留在 Tier 1。

### 4.3 改动点

| 文件 | 操作 |
|------|------|
| `ContextAssembler` | Near 起点从 `history.size() - nearN` 改为**最后一个折叠 msgId 之后**；`trimByTokens` 不再从头部丢轮次（避免破坏 prefix），溢出时走压缩而非裁剪；**组装按 Tier 0/1/2 分层渲染** |
| `L1Compressor.partition` | 以压缩点为界划分，而非固定 near/mid 轮数 |
| `conversation_context_l1` | 已具备载体（`far_folded_msg_ids`），无需新表 |
| `ContextMessageBuilder` | 顺序确认：Tier 0（tools+base+overlay+P0）→ Tier 1（L2+W0+T0+Far+Mid）→ Tier 2（Near→L3→意图/模式 system→当前 user） |
| **`WorkspaceContextExtractService`** | **content-hash 幂等 upsert**：产出块 sha256 与库中 `content_hash` 比对，未变化跳过写库（保证 Tier 1 字节稳定） |
| **注入序列化** | 所有注入块确定性序列化：JSON 键排序、无时间戳/session id、固定字段顺序；意图/模式经尾部 system 消息注入，不进 prefix |

### 4.4 实施范围

**压缩点模式作为 L1 通用机制统一启用**（chat/task 同走 ReAct，无 DIRECT 直答，见五层 spec §5.5.4 ④）；差异为 Tier 0/1 内容（P0/W0/T0 仅 task）与 L3 通道（chat 保留 scene=chat、task 不读不写，§6.3）。实施顺序 **P1/P2 场景隔离前置**（`AssembleRequest` 透传 `scene`/`workspaceId`，task 先行验证），隔离验收通过后 chat 跟随切换；切换期 chat 可保留现滑动窗（不回归）。

---

## 5. 核心设计 B：W0 工作区记忆（`workspace_context_state`）

### 5.1 数据模型

复用 L2 成熟模型（kind/key/value/confidence/status/TTL/source），作用域从 `(user_id, tenant_id)` 提升为 `(tenant_id, workspace_id)`，**工作区内所有 `kind=task` 会话公有**：

```sql
CREATE TABLE workspace_context_state (
    id           VARCHAR(64)  NOT NULL PRIMARY KEY,
    tenant_id    VARCHAR(64)  NOT NULL DEFAULT 'default',
    workspace_id VARCHAR(64)  NOT NULL,
    kind         VARCHAR(32)  NOT NULL,   -- project_index / scheme / agreement / fact / constraint / summary
    state_key    VARCHAR(128) NOT NULL,
    state_value  TEXT         NOT NULL,
    content_hash VARCHAR(64)  NOT NULL,   -- sha256(state_value)，幂等 upsert 载体（v3）
    confidence   DOUBLE       NOT NULL DEFAULT 0,
    status       VARCHAR(16)  NOT NULL DEFAULT 'active',
    expires_at   DATETIME(3)  NULL,
    source_msg_id VARCHAR(64) NULL,
    created_at   DATETIME(3)  NOT NULL,
    updated_at   DATETIME(3)  NOT NULL,
    KEY idx_ws_ctx (tenant_id, workspace_id, status)
);
```

> `content_hash` 用于幂等 upsert（§5.5.6）：LLM 每轮抽取结果若 hash 与库中一致则**跳过写库**，保证 Tier 1 组装字节不变、KV 缓存不失效。

### 5.2 读写链路

- **注入**：`ContextAssembler.assemble` 依据 `conv.workspaceId` 组装成 system 块，放 **Tier 1**（L2 之后、T0 之前）
- **写入**：`ContextWritePath` 按 kind 分流（§2.1 写路由）——task 会话**跳过用户 L2 抽取**、独立执行 workspace 抽取（LLM 按 Catalog `context.ws.extract` 抽项目级信息）；chat 会话不执行 W0 抽取；**content-hash 幂等 upsert**：产出块 sha256 与库中 `content_hash` 比对，未变化跳过写库（对齐五层 spec §5.5.6，保证 Tier 1 字节稳定）
- **冲突合并**：复用 `L2ConflictMerger` 逻辑（时间优先覆盖，旧条 superseded 审计保留）
- **语义冲突识别（v7 预置）**：W0 写路径**直接内置语义候选判定**（对齐五层 spec §6.4）——新 candidate 入库前对同 kind active 条目做语义判定（LLM · Catalog `context.ws.merge`），动作 NOOP/MERGE/UPDATE/CONFLICT 与 L2 一致。**这是 2026-08-01 L2 线上 bug 的前置防护**：工作区摘要/约束/事实更易出现语义相似 key（"项目用 Java17" vs "项目 JDK=17"），若不在 W0 落地时内置，将复现「相似的 key、value 相反也无法判矛盾」
- **清理**：`ContextMaintenanceJob` 扩展扫 workspace 维度（过期/矛盾）

### 5.3 与 L2/L3 的关系

| 层 | 作用域 | 内容 | 关系 |
|----|--------|------|------|
| L2 | 用户 | 画像/偏好/约定 | **仅 chat 读写**（写路由闸门，§2.1）；task 不读不写，task 发现的用户偏好由 P0 项目规范显式补位 |
| **W0** | 工作区 | 项目索引/方案/确认项/事实/约束/摘要 | 新增；**仅 task 读写**，chat 不读不写 |
| L3 | 用户/会话 | chat 语义历史段落 | **仅 chat 读写**（kind 门禁 + `scene=chat` 通道，§6.3）；task 不 ingest 不召回 |

---

## 6. 核心设计 C：T0 任务进度摘要 + 项目索引

### 6.1 T0 任务进度摘要

`conversation_context_l1` 增加 `task_progress` 字段（或独立表），assistant completed 后由 LLM **增量刷新**「任务目标 / 已改文件 / 验证结果 / 待办」。

> **v3 修正（降频）**：原稿「每轮刷新」会破坏 Tier 1 稳定性（每轮失效）。改为**随压缩点推进刷新**——`L1Compressor` 触发跨轮压缩时同步更新 task_progress（有界块 + content-hash 幂等），非压缩期保持不变。注入位置在 Far 之前（任务态 > 对话历史），属 **Tier 1 低频层**。

- 有界性：task_progress 限长（如 512 token），LLM 用旧块 + 新增变化增量合并，不无限增长
- 兜底：若任务中途发生非压缩性重大变更（用户改需求），通过尾部 system 消息注入当前状态，不触碰 Tier 1（对齐五层 spec §5.5.5 约束 2）

### 6.2 项目索引

工作区首次开箱后构建 repo 结构索引（模块树 / 关键文件 / 依赖 / 构建命令），存 W0 `kind=project_index`；提供 `ws_index` / `ws_read` / `ws_grep` 工具（或复用现有 `sandbox__glob`/`grep`）让 Agent **按需发现**，而非全量注入。对齐 Cursor「小索引引导 + 按需拉取」。

### 6.3 chat L3 scene 隔离向量通道（v2）

> **决策（2026-08-05）**：chat **保留** L3 跨会话历史召回。task 消息**不写入**向量库（kind 门禁）；chat 消息写入带 `scene=chat`，召回按 `scene=chat` 过滤——即使历史遗留 task 数据（改造前混入）也不会被 chat 召回。task 侧项目级记忆不建向量库，走 W0 + T0 + 文件系统按需发现（§6.2），避免双份存储。

**数据模型**：复用 `sunshine_chat_history` collection，schema 增加 `scene` 字段（VarChar/16，chat 恒为 `"chat"`）：

```java
.addFieldType(FieldType.newBuilder()
        .withName("scene")
        .withDataType(DataType.VarChar)
        .withMaxLength(16)
        .build())
```

**读写链路**：

| 环节 | 改动 |
|------|------|
| `ChatHistoryMilvusService` | `ensureCollection` schema 加 `scene`；`insertChunks` 增参 scene 写库；`search` expr 追加 `&& scene == "chat"` |
| `ChatHistoryRetrievalService` / `ChatHistoryController` | upsert / search 透传 scene |
| `HistoryRagClient.upsert` | 增参 scene |
| `L3IngestService.ingest` | 增参 scene；由写路由传 `"chat"` |
| `L3RecallService.recall` | 固定 `scene=chat`（chat 场景专用，读路由决定是否调用） |
| `ContextWritePath` | task 会话跳过 `ingestTurnPair`（kind 门禁，§2.1） |

**上线迁移**：collection 重建（drop + recreate + 重建索引 + reload），旧向量一次性清除——改造前 collection 已混入 task 消息，无法用缺省值兜底；重建后从 chat 历史重新 ingest。与五层 spec §9 GC 机制一致，无残留。

**防御性隔离**：`scene` 字段是双保险。即使未来写路径遗漏 kind 门禁误 ingest 了 task 消息，search 的 `scene == "chat"` 过滤仍保证其不被 chat 召回；task 侧如需向量能力，再以独立 `scene=task`（或独立 collection）扩展，不复用 chat 通道。

---

## 7. 已落地：项目规范（CLAUDE.md 式） ✅

本次会话已先行实现「用户手动维护项目级规范」，是本方案 P0 部分：

### 7.1 后端

| 文件 | 说明 |
|------|------|
| `docker/mysql/init/11-sunshine-orchestrator.sql` V20 | `workspace_project_guide` 表（一工作区一行，MEDIUMTEXT） |
| `workspace/entity/WorkspaceProjectGuideEntity.java` | 实体 |
| `workspace/repo/WorkspaceProjectGuideRepository.java` | Repository |
| `AgentWorkspaceController` | `GET/PUT /api/agent-workspaces/{id}/project-guide`（64KB 上限，`requireOwned` 校验，删除工作区级联清理） |
| `context/AssembledContext.java` | 新增 `projectGuideBlock` 字段（保留 5 参便捷构造兼容） |
| `context/ContextAssembler.java` | `conversationId → workspaceId → guide` 解析，读失败降级空串 |
| `context/ContextMessageBuilder.java` | 渲染为最前 system 块（静态层，L2 之前） |

### 7.2 前端

| 文件 | 说明 |
|------|------|
| `api/workspaces.ts` | `getProjectGuide` / `saveProjectGuide` |
| `components/sandbox/ProjectGuideModal.vue` | 弹窗：编辑/预览 tab（复用 `StaticMarkdown`）+ 插入示例模板 |
| `layouts/MainLayout.vue` | 工作区「更多」菜单新增「项目规范」项 |

### 7.3 语义

- 工作区内所有 `kind=task` 会话自动注入（系统块），类 CLAUDE.md 项目级文件
- 低频维护 → 注入最前静态层，对 KV prefix 缓存几乎无影响
- 已通过编译 + `vue-tsc` 类型检查

### 7.4 插件菜单：Skills / MCP 官方与个人分层（设计稿）

承接前端「插件」菜单需求——对话层新增菜单，两个 Tab：**Skills** 与 **MCP**。选型对上下文分层的影响：

| 维度 | 官方市场 **system 级** | 官方市场 **user 级**（个人选配） | 用户**自定义** |
|------|----------------------|------------------------------|----------------|
| 管理视角 | 后台统一开启，全局共有 | 市场可见，个人开启 | 个人创建 |
| 数据载体 | `SkillCatalogService`（目录索引 + 详情缓存） | `user_skill_binding`（幂等 upsert） | 同左 + 个人 workspace |
| 注入层级 | Planner **Tier 0 目录摘要**（仅名字+描述，字节稳定） | Planner **Tier 1** 幂等块 | Planner **Tier 1** 幂等块 |
| 命中正文 | — | Tier 2 尾部 `skill-overlay`（动态） | 同左 |
| MCP 工具 | 全局工具（`mcp__*`）进 tools Tier 0 | 个人绑定进 Tier 1 | 个人自定义 |

**关键约束**：

1. **目录摘要只静态注入「名字+描述」**（对齐 §1.2 调研：静态注入名+描述、正文按需读，一 A/B 测试降 46.9% token）；正文经 `SkillCatalogService` 详情缓存按需取，命中才进 Tier 2。
2. **system 级 = 后台统一、字节稳定 → 才有资格进 Tier 0 前缀**；user 级 = 个人低频变更（content-hash 幂等 upsert）→ Tier 1。**禁止**把个人配置混入 Tier 0。
3. 业务工具 / MCP 经 `DynamicToolkitFactory` 注入，只作用于 Worker 工具集 / spawn 子 Agent（子会话无前缀包袱），不占 Planner 前缀预算。
4. 前端「插件」菜单的 system/user 标识即对应 **Tier 0 / Tier 1** 划分；Skill 详情抽屉复用阶段四已有卡片样式。
5. **工具规模膨胀联动 tool RAG**：插件菜单启用工具数量超过阈值（默认 20）时，Tier 0 `tools` 自动降级为「全量名列表 + Tier 2 Top-K schema」（对齐 [phase5 §5.5](./phase5-operation-openness-design.md) 分层注入，见 §4.2 v6 注记）；MCP 工具描述同样只静态进名列表，schema 按需读——插件菜单是工具/技能数量的入口，也是触发 `full`→`retrieval` 模式切换的信号源。

**落点**：`skill-manager`（Catalog CRUD + system/user 标记）+ `user_skill_binding` 表 + `ContextMessageBuilder` Tier 0/1 渲染。

---

## 8. 实施清单

| # | 任务 | 文件 | 依赖 |
|---|------|------|------|
| P0 | 项目规范（CLAUDE.md 式） | 已落地 ✅ | — |
| **P1** | **写路由（防污染闸门，§2.1）**：`ContextWritePath` 按 `conversation.kind` 分流——task 跳过用户 L2 抽取与 L3 ingest、改走 W0+T0；chat 走用户 L2 + L3(scene=chat)；`L1Compressor` 压缩上下文 task 读 W0/T0、不读用户 L2（修弱串通道） | `ContextLifecycle`、`ContextWritePath`、`L1Compressor` | — |
| **P2** | **读路由（防串闸门，§2.1）**：`AssembleRequest` 加 `scene`/`workspaceId`；task 注入 W0+T0+guide、不注入用户 L2；chat 注入用户 L2、不注入 W0/T0/guide | `ContextAssembler`、`AssembleRequest` | P1 |
| 1 | W0 表 + `WorkspaceContextStore` + `WorkspaceContextExtractService` | orchestrator context/ | P2 |
| 2 | `ContextAssembler` 增量组装（压缩点模式 + Tier 0/1/2 分层，对齐五层 spec §5.5） | `ContextAssembler`、`L1Compressor` | 1 |
| 3 | `ContextMessageBuilder` 顺序调整（Tier 0/1/2 定序 + 意图/模式尾部 system 注入 + 确定性序列化） | `ContextMessageBuilder` | 1、2 |
| 4 | T0 任务进度摘要（**降频随压缩点刷新** + 有界块） | `L1Compressor`/`ContextWritePath` | 2 |
| 4a | **幂等 upsert**：W0/L2 抽取加 content-hash 比对，未变化跳过写库 | `WorkspaceContextExtractService`、`L2ExtractService` | 1、2 |
| 4b | **W0 语义冲突识别（v7）**：写路径语义候选判定（NOOP/MERGE/UPDATE/CONFLICT · Catalog `context.ws.merge`，复用五层 §6.4 设计）+ Nacos `semantic-merge` 开关 | `WorkspaceContextStore`、`WorkspaceContextExtractService` | 4a |
| 5 | 项目索引构建 + `ws_index` 工具 | orchestrator workspace/ | 1 |
| 5a | **chat L3 scene 隔离通道（§6.3）**：`sunshine_chat_history` 重建加 `scene` 字段；chat ingest `scene=chat`；search 过滤 `scene=chat`；旧向量一次性清除（含改造前混入的 task 数据） | rag-service `ChatHistoryMilvusService`/`ChatHistoryRetrievalService`/`ChatHistoryController`、orchestrator `L3IngestService`/`L3RecallService`/`HistoryRagClient` | P1 |
| 6 | Nacos：chat/task 场景开关（L3 按 scene、W0 启用）、Catalog `context.ws.extract` | Nacos + prompt-manager | 全部 |
| 7 | 验收脚本 `verify_task_context_live.py`（KV 命中、W0 会话公有、进度摘要、**幂等字节稳定**、**场景隔离回归**：task 不写用户 L2 / chat 不读 W0 / L3 scene 过滤） | scripts/ | 全部 |
| 8 | 插件菜单分层渲染（§7.4）：skill-manager 加 system/user 标记 + `user_skill_binding` + Tier 0 目录摘要 / Tier 1 幂等块 + 前端双 Tab | skill-manager、orchestrator context/、sunshine-ui | 2、3 |

**建议顺序**：P0（已完成）→ **P1 → P2（隔离前置，先行落地）** → 1 → 2 → 3 → 6 → 4/4a/5a/5 → 7 → 8。

---

## 9. 风险与取舍

| 风险/取舍 | 结论 |
|-----------|------|
| KV 增量组装依赖 DeepSeek prefix caching 服务端支持 | 验收需透传并统计 `prompt_cache_hit_tokens`（llm-gateway 透传）；服务端不支持则退化为「正常组装，零收益」，不阻塞 |
| chat 保留 L3 的隔离成本（v2） | 同一 collection + `scene` 字段过滤（§6.3），非新建独立库；kind 门禁防 task 数据混入，`scene=chat` 双保险兜底 |
| **task 用户偏好严格隔离（v2）** | task 不写用户 L2，任务内发现的用户级偏好不回流；用户显式偏好走 **P0 项目规范**（CLAUDE.md 式）——用户主动声明、可覆盖、不可被自动抽取污染，天然补位 |
| **改造前跨会话记忆已污染（v2）** | 写/读路由闸门上线即止住新增污染；存量用户 L2 由腐败审计 `auditL2` 清理；存量 L3 向量经 §6.3 重建 collection 一次性清除 |
| 项目规范与 W0 `scheme/agreement` 语义重叠 | 分工：P0 是用户权威手写规范（不可自动覆盖）；W0 是系统自动抽取的补充状态（可被用户规范压制/冲突时以用户为准） |
| 压缩点前进导致 Near 在压缩后变短 | 一次性压缩的语义损失由 T0 任务进度摘要兜底（任务关键信息在摘要层，不依赖 Near 原文） |
| W0 表膨胀 | 复用 L2 治理：类型化 TTL（`summary` 30 天、`constraint` 30 天等）+ `superseded` 审计 + 定时 GC |
| **W0/L2 每轮 upsert 破坏 Tier 1 稳定性（v3 新增）** | content-hash 幂等 upsert + 确定性序列化；真变才失效一次，且 Tier 0 内层仍命中（双层缓存） |
| **T0 每轮刷新破坏前缀（v3 新增）** | T0 降频随压缩点推进刷新 + 有界块；非压缩期重大变更经尾部 system 消息注入 |
| **意图识别结果注入 prefix（v3 新增）** | 路由决策为控制流不进 prefix；模型需知模式时用尾部 system 消息（Anthropic mode-switch） |
| **skill 目录摘要混入前缀 / 个人配置混入 Tier 0（v6 新增）** | 目录摘要只注入名字+描述进 Tier 0（字节稳定）；个人绑定进 Tier 1 幂等块；命中正文进 Tier 2 尾部（§7.4） |
| **W0 语义相似 key 矛盾（v7 新增）** | W0 写路径内置语义判定（Catalog `context.ws.merge`，对齐五层 §6.4），防「相似 key / value 相反」独立成条；与 L2 共用判定标准，不重复实现 |

---

## 10. 验收

| 项 | 预期 | 脚本 |
|----|------|------|
| 项目规范 CRUD | GET/PUT 保存，task 会话组装出现 guide system 块 | `verify_task_context_live.py` |
| W0 会话公有 | 工作区两个会话对 `scheme/constraint` 均可见 | 同上 |
| W0 语义矛盾识别（v7） | 工作区出现语义相似 key（如 "项目用 Java17" vs "项目 JDK=17"）、value 相反时：判定 CONFLICT 双标不注入 / MERGE 归一，`active` 中不并存相反事实 | 同上 |
| 压缩点增量组装 | task 会话连续多轮，prefix 稳定（组装日志 Near 起点不动） | 同上 |
| 压缩一次性触发 | tail 超阈值才触发一次 Near→Mid/Far，`compacted` 标记生效 | 同上 |
| **场景隔离·写路由（v2）** | task 会话结束后 `user_context_state` 无新增行；chat 会话结束后 W0 无新增行 | 同上 |
| **场景隔离·读路由（v2）** | task 组装块不含 L2 用户状态，含 W0+T0+guide；chat 组装块含 L2、不含 W0/T0/guide | 同上 |
| **场景隔离·L3（v2）** | task 消息不 ingest 向量库；chat query 检索结果 scene 恒为 chat（Milvus expr 带 `scene == "chat"`） | 同上 |
| chat L3 scene 隔离回归 | chat 场景 L3 块正常召回且过滤后不含 task 内容，L1+L2 正常 | 回归 `verify_context_layers_live.py` |
| 编译绿 + 前端类型检查 | orchestrator `mvn compile`、UI `vue-tsc` | CI |

---

## 11. 文档关系

| 文件 | 关系 |
|------|------|
| `2026-07-31-unified-context-compression-design.md` | 五层管道 SSOT；压缩点模式已回写其 §5.5（chat/task 统一），本方案是其 task 场景适配，落地后同步其 §10 配置；**W0 语义冲突识别对齐其 §6.4**（写路径语义判定，`context.ws.merge`）；**v2 场景隔离边界已同步其 §5.5.7 差异表**（chat 保留 L3 / task 不读不写） |
| `2026-07-28-task-workspace-codex-design.md` | 工作区/`kind=task` 载体 |
| `2026-07-31-harness-loop-enhancement-design.md` | 4.7.8 阶段五 AS compaction 管 run 内，本方案管跨轮组装，正交 |
| `2026-07-31-planner-harness-loop-design.md` | Planner-Worker 场景；其 §2.4 落地本 spec 的 Tier 0/1/2 分层与插件映射到 Planner/Worker 上下文 |
| `phase5-operation-openness-design.md` | 其 5.5 工具分层注入被本 spec §4.2/§7.4 引用（tools 名列表 + Top-K schema）；`call_scene` 命名与路由 `scene` 隔离 |
| `docs/implementation-plan.md` | 落地后同步进度 |
