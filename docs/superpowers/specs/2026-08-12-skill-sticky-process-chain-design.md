# Skill 跨轮粘性 + 软链式接续（双写 SSOT）

> **状态**：📋 设计评审中 · **v2（2026-08-12）**  
> **日期**：2026-08-12  
> **编号**：阶段四增量（路由 / Skill 会话态）  
> **前置**：[unified-routing v6](./2026-07-29-unified-routing-design.md) · [unified-context-compression](./2026-07-31-unified-context-compression-design.md)（§5.5 压缩点 / §2.1 状态保真）· [conversation-sandbox-multi-skill](./archive/2026-07-16-conversation-sandbox-multi-skill-design.md)  
> **一句话**：轨 A 下 Skill **跨轮 sticky**（Redis ledger + 消息完整 `RoutingResult` 双写）；**默认软链**（overlay 文案 + L2/L3 上下文召回切下一 skill，对齐 Cursor/Superpowers）；`processGraph` / `advance_skill_phase` 为**可选增强**。绑定态**不进** L1 Near/Mid/Far。

### v2 相对 v1

| v1 | v2 |
|----|-----|
| Catalog `processGraph` + 元工具为接续主路径 | **默认软链**；图与元工具降为可选（§4.3） |
| 管理页必须维护过程图 | **普通/方法包 skill 无需画图**；仅强门控场景可选配图 |
| `skillPhase` 一等字段强依赖图 | 默认可空；仅启用可选增强时使用 |

### 调研结论（Cursor / Claude Code / Superpowers）

| 维度 | 业界做法 | 本设计取舍 |
|------|----------|------------|
| 发现/触发 | description 匹配 + 显式点名；无平台过程图 | L0 `/` + L1/L2/L3；**另增**会话 sticky ledger |
| 跨轮延续 | 对话历史 + 每轮重匹配 | **结构化 sticky**（A+B），不靠 Mid 散文单独撑 |
| 多 skill 链 | 正文 terminal /「invoke X」；模型自觉；**无** Cursor 内核特殊处理 | **同构软链** + L3/召回；可选图仅企业强门控 |
| 压缩后 | 外部文件 ledger / 对话易失 | sticky ledger ∉ L1 折叠；切链信号优先结构化会话态 + description，不只靠散文 |

---

## 1. 目标与非目标

### 1.1 目标

1. **Sticky**：上一轮绑定的 skill，本轮延续时无需再 `/`，自动注入 overlay。  
2. **软链式接续**：类 Superpowers——当前 skill overlay 写明终态下一步；下轮靠 **会话态 + description/L2/L3 召回** 绑上下一 skill（**不强制**管理页维护图）。  
3. **双写 SSOT**：Redis 热态 + 消息级完整 `RoutingResult`；对齐 routing v6 Pre-Routing / 续跑。  
4. **压缩点对齐**：绑定态为独立 L-state；overlay 在 Prompt 稳定前缀。

### 1.2 非目标

- 不在 orchestrator 硬编码技能名或边表。  
- 不要求每个 skill 在管理页配置 `processGraph`。  
- 不恢复 `auto`；L3 **禁止**改写 `executionMode`。  
- 不做「用户未发消息时后台自动开下一 skill 轮」。  
- 不把完整 workflow 摘要塞进 Catalog `description`（SDO：description 只写触发条件）。  
- workflow 轨不做 skill sticky / 链式。  
- 不为 skill 新建平行压缩点 / H1 基建。

---

## 2. 数据模型与双写契约

### 2.1 扩展 `RoutingResult`（轨 A）

在 [unified-routing v6 §4](./2026-07-29-unified-routing-design.md) 基础上增量：

```java
public record RoutingResult(
    ExecutionMode executionMode,   // 用户钉死，路由不改写
    String scene,
    String workflowId,             // 轨 B；轨 A 为 null
    List<String> agentIds,
    List<String> skillIds,         // 本轮生效 skills（可多；含当前主 skill）
    String primarySkillId,         // 可选：本轮主 skill（sticky / 软链焦点）；缺省取 skillIds[0]
    String skillPhase,             // 可选增强：仅 processGraph 启用时
    List<String> skillArtifacts,   // 可选：sandbox 相对路径（spec/plan/progress）
    SkillBindSource skillBindSource,
    Map<String, Object> params,
    String reason
) {
    public enum SkillBindSource {
        L0_EXPLICIT, STICKY, L1_RULE, L2_RECALL, L3, SOFT_CHAIN, PROCESS_ADVANCE, STICKY_MERGED
    }
}
```

- **默认路径**只用 `skillIds` / `primarySkillId` + sticky / `SOFT_CHAIN`；`skillPhase` 可空。  
- v1 的 `processSkillId` **合并进** `primarySkillId`（不再强制区分 process/implementation 两套绑定字段；`skillKind` 仍可在 Catalog 标注供 L3 process-first）。

### 2.2 Redis `SkillSessionLedger`（热态）

- Key：`skill:ledger:{tenantId}:{conversationId}`  
- TTL：与会话沙箱同量级（建议 7d）  
- 字段：`skillIds`、`primarySkillId`、可选 `skillPhase` / `skillArtifacts`、`updatedAt`、`sourceMessageId`

与沙箱 `loadedSkillIds` **解耦**：ledger = 绑定焦点；sandbox = 文件物料累积。

### 2.3 消息级完整 `RoutingResult`

- `chat_message` 存完整 JSON（新建列或 `execution_plan_json`）。  
- **修复**现状：仅写 `intent=react` 导致续跑丢 `params.skill`。

### 2.4 双写表

| 事件 | Redis ledger | 消息 RoutingResult |
|------|--------------|-------------------|
| 新消息路由定稿 | upsert | 写入本轮 assistant |
| HITL / 同消息续跑 | 不变 | 复用已存 |
| L0 / 软链切换 / 可选 advance | upsert | 新轮写新结果 |
| Redis miss | 从上条 assistant 回填 | 权威回退源 |

### 2.5 与压缩点的边界

| 状态 | 存放 | 进 L1 Near/Mid/Far？ |
|------|------|----------------------|
| skill overlay | PromptComposer 稳定前缀 | ❌ |
| sticky 焦点 | Redis + 消息 RoutingResult | ❌ |
| Mid schema 可选路标 | `primarySkillId=…` | ✅ 仅路标，非 SSOT |

软链召回时：L3 **必须先读 ledger 会话态**，再结合用户话与候选 description；**禁止**在压缩后只靠 Mid 散文猜「还在用哪个 skill」。

---

## 3. 跨轮 Sticky 规则（轨 A · L0–L3）

仅 `executionMode ∈ {fast, pro}`。workflow **禁用**。

### 3.1 每轮输入

| 输入 | 来源 | 用途 |
|------|------|------|
| `clientSkillIds` / `/skill` | L0 | 最高优先级覆盖 |
| `ledger` | Redis；miss → 上条 assistant | 默认继承种子 |
| L1 / L2 | 规则 / embedding 召回 | 累积候选；软链切换的主要候选源 |
| L3 | 合并裁决 | 保留 sticky **或**软切换到召回命中的下一 skill |
| `recentHistory` | 深层兜底 | 辅助语义；**不**单独当 sticky SSOT |

### 3.2 合并算法

```
acc ← ledger（可空）

L0 命中 → 替换；source = L0_EXPLICIT

L1/L2 → add 候选（含 description「Use when…」匹配）

L3 →
  无强切换且用户在延续 → 保留 sticky（可追加 implementation）
  用户话/候选表明进入下一方法 skill（软链）→ 替换 primarySkillId；source = SOFT_CHAIN
  强切换/否定/换题 → 替换或清空；reason 必填
  禁止因低置信清空 sticky
```

### 3.3 继承 / 清除

同 v1 精神：同会话、非 workflow、无 L0/清除、L3 未强切换 → `STICKY`。  
「退出技能」、另选 `/`、换题无关 → 覆盖或清空（sandbox 可不 umount）。

### 3.4 L3 输入契约

```text
【会话 skill 态】primarySkillId=…; skillIds=…; artifacts=…
【软链提示】若当前 skill overlay 已达终态且用户在推进，可在候选中选用其「下一步」skill（见 overlay，勿发明未在 Catalog 的 id）
```

---

## 4. 接续模型：默认软链 + 可选强图

### 4.1 默认：软链（对齐 Cursor / Superpowers）

**谁定义「下一个」？** Skill **作者写在 overlay 正文**（terminal state / 「完成后使用 writing-plans」），**不是**管理页过程图。

**怎么启用下一个？**

| 时刻 | 行为 |
|------|------|
| 本轮 | 模型按 overlay 做完；可在回复/工具结果中留下产物路径（写入 `skillArtifacts` 更佳） |
| **下一轮** | sticky 仍带当前 skill **或** L2/L3 根据用户意图 + description 命中「下一步」skill → `source=SOFT_CHAIN`，注入新 overlay |
| 用户 `/` 点名 | L0 覆盖 |

同一时刻仍以 **一个 primarySkillId** 为主 overlay；同轮可额外挂若干 implementation `skillIds`。

**管理页**：只需维护 skill 条目、`description`（触发条件）、`systemOverlay`（含软链文案）。**无需**画 `processGraph`。

### 4.2 软链可靠性底线（相对纯上下文）

仅靠对话召回在压缩后不可靠。默认软链仍要求：

1. **sticky ledger** 保住「当前焦点」；  
2. L3 输入带 **结构化会话态**；  
3. 候选来自 **Catalog description / L2**，禁止模型编造未启用 skill id；  
4. overlay 内 next 名称与 Catalog id **一致**（导入 Superpowers 时做一次 id 映射）。

### 4.3 可选增强：`processGraph` + `advance_skill_phase`

仅当产品需要 **可校验相位 / 强制用户批准门 / 合规审计「必须过某 gate」** 时启用：

- Catalog 可选字段 `processGraph`（phases / gate / next）  
- 元工具 `advance_skill_phase` 改 ledger，`source=PROCESS_ADVANCE`  
- `gate=user_approval` → 复用 `request_decision`  

**默认关闭**；未配置图的 skill 走 §4.1，行为与 Cursor 同构。  
实施上 **S-3 可选、可延期**，不阻塞 sticky / 软链。

### 4.4 SUB 隔离

- 主 Agent 持 sticky / 软链焦点。  
- SUB `forSubAgent()` 不继承父会话 process/软链 ledger（对齐 `<SUBAGENT-STOP>`）。  
- Spawn 可显式带单个 `skillId`。

---

## 5. 装配与写路径

### 5.1 读路径（轨 A）

```
RoutingResult
  → PromptComposer 稳定前缀：
       mode-overlay → react/planner
       → primarySkill overlay
       → 其余 skillIds overlays（克制数量）
       → scene / L1–L3
  → Sandbox：skillIds ∪ loadedSkillIds 懒挂载
  → AgentRuntime.run(MAIN|PLANNER)
```

### 5.2 写路径（assistant 终态）

1. 落盘完整 `RoutingResult`  
2. Upsert Redis ledger  
3. L1 压缩照旧；**禁止**把 ledger 折进 `far_summary`

---

## 6. 相对 unified-routing v6 的增量

| v6 | 本设计（v2） |
|----|----------------|
| `skillIds[]` 收集 | + sticky 种子 + `primarySkillId` + 软链切换 |
| Pre-Routing 复用 RoutingResult | 消息必须存完整结果 |
| skill 挂载 | 多 overlay（主 + 可选附加） |
| — | Redis `SkillSessionLedger` |
| — | **可选** `processGraph` / `advance_skill_phase` |

---

## 7. 风险与对策

| 风险 | 对策 |
|------|------|
| 软链漏切 / 跳步 | overlay 写清终态；L3 带会话态；live 抽检；必要再开 §4.3 |
| L3 误清 sticky | 禁止低置信清空；切换必填 reason |
| Redis / 消息不一致 | 先消息后 Redis；miss 回填 |
| overlay 过大 | 控制正文体积；大物料走 sandbox 文件 |
| 与压缩点混淆 | ledger ∉ L1；文档标明 |

---

## 8. 实施切片

| 阶段 | 内容 | 出口 |
|------|------|------|
| **S-0** | 消息存完整 RoutingResult；续跑不丢 skill | 单测 + 续跑 live |
| **S-1** | Redis ledger + §3 sticky | 无 `/` 次轮仍有 overlay |
| **S-2** | PromptComposer 多 `skillIds`；主 overlay = primary | 双 skill 冒烟 |
| **S-2.5** | 软链：L3 + L2 召回切下一 skill（`SOFT_CHAIN`） | Superpowers 风格 E2E（无图） |
| **S-3** | （可选）`processGraph` + `advance_skill_phase` | 强门控 E2E |
| **S-4** | 压缩点后 sticky / 软链回归 | 压缩后仍可续 |

---

## 9. 验收标准

| # | 场景 | 预期 |
|---|------|------|
| V1 | 上轮 `/skill-A`，本轮「继续」无 `/` | overlay=A，source=STICKY |
| V2 | 本轮 `/skill-B` | 覆盖为 B |
| V3 | 「退出技能」 | skillIds 空 |
| V4 | overlay 终态后用户推进；Catalog 有下一 skill | 下轮可 `SOFT_CHAIN` 绑上下一 skill（**无** processGraph） |
| V5 | 压缩点前移后续聊 | sticky 仍在 |
| V6 | HITL 续跑 | 不重绑、不丢 skill |
| V7 | workflow 模式 | 无 skill sticky |
| V8 | SUB | 不继承父软链 ledger |
| V9 | （可选）配置了 graph 的 skill | advance + gate 行为符合图 |

---

## 10. 关联文档

| 文档 | 关系 |
|------|------|
| [unified-routing v6](./2026-07-29-unified-routing-design.md) | 轨 A 收集；本设计为 sticky / 软链增量 |
| [unified-context-compression](./2026-07-31-unified-context-compression-design.md) | 压缩点；ledger 为 L-state 外部载体 |
| [multi-agent-unified](./2026-07-29-multi-agent-unified-design.md) | spawn / SUB 隔离 |
| [request_decision / 4.7.9](../implementation-plan.md) | 仅可选 §4.3 gate |
| [sandbox multi-skill](./archive/2026-07-16-conversation-sandbox-multi-skill-design.md) | loadedSkillIds 物料层 |
