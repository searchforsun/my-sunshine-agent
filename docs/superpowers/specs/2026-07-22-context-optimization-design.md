# 上下文优化（三层 Context 状态机）设计

> 状态：已确认 · 2026-07-22  
> 归属：对话上下文 / 记忆体系重建模（替换旧 STM/MTM/LTM）  
> 约束：开发期 **不兼容** 旧记忆逻辑；直接删除替换

## 1. 问题

长会话窗口外事实丢失；跨会话偏好/约定/方案无可靠状态；历史细节无法按需召回。旧方案 C（STM 滑动窗 + MTM 整会话摘要 + LTM 空壳画像）与「同会话 mid/far + 跨会话结构化状态 + 对话 RAG」语义不对齐，开发期不做兼容桥。

## 2. 目标与非目标

| 做 | 不做 |
|----|------|
| 统一 `ContextAssembler` + `ContextLifecycle` 状态机 | 兼容/双写旧 STM Redis、MTM、`user_memory_profile` |
| L1 同会话 Near/Mid/Far（Mid 仍为 user/assistant 轮次） | 对最终答案截断/摘要二次加工 |
| L2 六类跨会话状态，静默置信写入 | 用户侧 HITL 确认记忆（本期无感知） |
| L3 对话历史 chunk 入 rag-service，按需召回 | 与企业知识库混用同一 collection |
| 冲突/过期/定时 GC 防上下文腐败 | 新建独立 context 微服务 |
| Admin 可读写纠错 | 改 AutoContext（单次 ReAct 工具压缩，正交保留） |
| SUB/PLANNER 仍无跨轮记忆 | 前端维护本地记忆 Map |

## 3. 三层模型

### 3.1 L1 — 会话内窗口

| 带 | 范围 | 注入形态 |
|----|------|----------|
| **Near** | 最近约 `[0, N]` | 完整 `user` / `assistant` 轮次 |
| **Mid** | 约 `(N, 2N]` | 仍为正常轮次：完整 `user` + **压缩后的答案** `assistant` |
| **Far** | `> 2N` | 默认折叠为一条边界摘要块；当前 query 命中时由 **L3 回填**相关细节 |

- **原文 SSOT**：MySQL `chat_message`（压缩不删原文）
- **派生**：`conversation_context_l1`（`mid_answers` 映射、`far_summary`、窗口元数据）
- **触发压缩（混合）**：逼近 token/字符预算时压缩；预算未满但轮次超阈值也压缩

### 3.2 L2 — 跨会话结构化状态

表 `user_context_state`（按 `tenant_id` + `user_id`），条目化：

| 字段 | 说明 |
|------|------|
| `kind` | `profile` / `preference` / `goal` / `agreement` / `constraint` / `fact` / `decision` |
| `key` / `value` | 稳定键与文本/JSON 值 |
| `confidence` | 抽取置信；低于门禁不进跨会话库 |
| `status` | `active` / `superseded` / `void` |
| `expires_at` | 类型化软/硬过期 |
| `source_msg_id` / `updated_at` | 溯源与时间优先 |

**写入**：每轮 assistant completed 后后台抽取；高置信静默入库；低置信 **丢弃**（不落跨会话库；不做用户可见确认）。用户无感知。

**注入**：仅 `active` 且通过过期过滤的条目 → **system** 结构化块。

### 3.3 L3 — 跨会话历史细节（RAG）

- rag-service **独立 collection**（如 `sunshine_chat_history`）
- 源：`chat_message`，按现有 chunk 策略分块；metadata：`userId` / `tenantId` / `convId` / `msgId` / `time`
- 召回：排除本会话已在 L1 近窗的内容；topK + **时间衰减**；注入为「历史材料·可能过期」块（非指令）
- Far 命中回填走同一检索通道

## 4. 架构

```text
读：ChatStreamContextFactory
  → ContextAssembler.assemble(user, conv, query)
       ├─ L2StateStore        → system 稳定状态
       ├─ L1（ConversationContextL1Store + Near 窗）→ Mid/Near 轮次 + Far 折叠块
       └─ L3 HistoryRagClient → 按需 chunk（可回填 Far）
  → AssembledContext → PromptComposer → LLM

写：assistant completed
  → ContextLifecycle.onTurnCompleted → ContextWritePath（异步，顺序固定）
       ├─ L2 抽取 → 置信门禁 → 冲突合并 → upsert
       ├─ L1 压缩 / Far 折叠（可读本轮 L2）
       └─ L3 chunk ingest（失败可重试，不阻塞主路径）

治：ContextMaintenanceJob（定时）
       ├─ L2 过期 void / superseded 归档 / 低置信清扫 / 矛盾打标
       ├─ L3 向量 GC（过期、作废、孤儿）+ 健康检查
       └─ L1 无主会话派生行清理
```

- 新包：`orchestrator/.../context/`（Assembler · Lifecycle · stores · job）
- **删除**：旧 `MemoryComposer` 路径、Redis STM 窗、`conversation_memory_mtm`、`user_memory_profile` 注入、旧 Catalog `memory.mtm.*` 等（改挂 `context.*`）
- **保留**：`AutoContextMemory`（单次 ReAct 工具轨迹）；MAIN 才完整组装；SUB/PLANNER `empty`

## 5. 冲突 · 过期 · 防腐败

### 5.1 冲突

- **时间优先**：新高置信同 key 覆盖旧条，旧条 `superseded`（保留审计）
- **类型门槛**：`constraint` / `fact` 覆盖需更高置信或多次印证；`preference` / `goal` / `decision` 相对易覆盖

### 5.2 过期（按类型）

| 类型倾向 | 策略 |
|----------|------|
| preference / agreement | 较长寿 |
| goal / decision | 中等 TTL + 衰减 |
| fact / 临时 constraint | 短寿 + 衰减 |
| 硬过期 | 定时任务 → `void`，不再注入 |

软过期：仍可注入时须能被衰减分数/边界文案体现「可能过期」。

### 5.3 读时预算裁剪顺序

先砍 L3 → 再砍 Far → **不砍** L2 的 `constraint`（及同类硬限制）。

### 5.4 原则

- 摘要/抽取只发生在记忆层，禁止对用户可见最终 content 二次加工
- L3 / Far 材料始终带 Catalog 边界头，避免被当成不可违背指令
- 定时任务与请求解耦；失败仅日志 + 指标

## 6. Prompt / Catalog

| 位置 | 内容 |
|------|------|
| **system** | L2 状态块；`context.usage-rules`；Far / L3 边界头 |
| **对话 messages** | L1 Near 全文轮次；L1 Mid（完整 user + 摘要 assistant）；当前提问 |
| **仅后台** | `context.l1.mid-compress` / `context.l1.far-fold` / `context.l2.extract` |

新 Catalog id（替换旧 memory 文案）：

- `context.layer-prompt`
- `context.usage-rules`
- `context.l1.mid-compress`
- `context.l1.far-fold`
- `context.l3.material-header`
- `context.l2.extract`
- Far 边界头（可并入 layer-prompt 或独立 id）

正文 SSOT = prompt-manager；Nacos 仅保留非提示词运行参数（窗口 N、预算、置信门禁、TTL、cron、topK 等）。

## 7. Admin

用户无感知；运维可纠错：

- L2：按用户列表；作废 / 编辑 / 强制覆盖 / 调置信
- L1：查看会话 Mid/Far 压缩快照
- L3：索引状态；手动触发 GC / 重 ingest

UI 与 Skills/Experts 同构即可（`--sun-black` + 边框分区），非本期视觉重点。

## 8. 配置（Nacos 示意）

```yaml
agent:
  context:
    enabled: true
    l1:
      near-turns: 8          # N 的默认
      mid-turns: 8           # 再 N 轮 Mid
      max-chars: 120000     # 预算（与轮次双触发）；SSOT=Nacos，Java 默认对齐
    l2:
      min-confidence: 0.75
      constraint-overwrite-confidence: 0.9
      # 各 kind TTL / 衰减系数
    l3:
      collection: sunshine_chat_history
      top-k: 5
      min-score: 0.55
      time-decay: true
    maintenance:
      cron: "0 0 3 * * ?"    # 示例：每日 03:00
```

具体键名实现期与 `docs/nacos/sunshine-orchestrator.yaml` 对齐；改后 `sync_nacos.py` + 重启。

## 9. 验收

- **单测**：Assembler 预算裁剪顺序；冲突合并门槛；TTL 过滤；Mid 轮次形态（user 全文 + assistant 摘要）
- **Live**：`scripts/verify_context_layers_live.py`
  - 长会话触发 Mid/Far
  - 跨会话 L2 静默写入与注入
  - L3 召回（排除近窗）与 Far 回填
  - 定时维护干跑（过期 void + 向量 GC）
- **回归**：SUB 无记忆；AutoContext 行为不变；企业 KB RAG 不受影响

## 10. 落地节奏（同一详设，可分 PR）

1. **骨架**：`AssembledContext` + Assembler 替换旧 Memory 注入；删旧路径  
2. **L1**：Mid/Far 压缩与派生表  
3. **L2**：抽取 + 冲突/TTL + system 注入  
4. **L3**：chunk ingest/search + Far 回填  
5. **治理 + Admin**：`ContextMaintenanceJob` + 可读写页 + Live 脚本  

## 11. 与旧文档关系

- `docs/superpowers/specs/archive/2026-06-17-agent-memory-design.md`（方案 C）：**已归档**；本文件取而代之  
- `2026-07-17-autocontext-memory-design.md`（4.6.4）：**仍然有效**（单次 run 内工具压缩）
