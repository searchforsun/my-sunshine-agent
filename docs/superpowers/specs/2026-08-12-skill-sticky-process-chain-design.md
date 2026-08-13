# Skill 可发现 / 触发分离 + 绑定保真（行业对齐）

> **状态**：📋 设计评审中 · **v3.1（2026-08-13）**  
> **日期**：2026-08-12（v3 收缩 → **v3.1 加载≠触发**）  
> **编号**：阶段四增量（路由 / Skill 会话态）  
> **前置**：[unified-routing v6](./2026-07-29-unified-routing-design.md) · [unified-context-compression](./2026-07-31-unified-context-compression-design.md) · [task-scene §7 插件/Skill 分层](./2026-08-01-task-scene-context-design.md)（名+描述静态 / 正文按需）  
> **一句话**：**可发现**（Catalog 名+description）与 **触发**（本轮注入 overlay 的 `skillIds`）分离；**物料加载**（sandbox）与触发正交。消息存完整 `RoutingResult` + 上轮**已触发** id 轻 sticky。对齐 Cursor：context discovered, not dumped。

### 相对 v2 / v3

| 口径 | 取舍 |
|------|------|
| v2 Redis ledger / 软链 / processGraph | **不做**（同 v3） |
| v3「租户固定 ∪ 意图召回 → 一律 overlay」 | **纠正**：固定/召回默认只进**可发现集**；overlay 仅对**已触发** `skillIds` |
| 现状 `PromptComposer.resolveSkillOverlay(skillId)` 开场灌全文 | **目标**：仅触发集灌 overlay；目录摘要另层注入 |

---

## 1. 三层语义（行业同构）

| 层 | 含义 | 进 Prompt？ | 典型来源 |
|----|------|-------------|----------|
| **可发现 Discover** | Agent 知道有哪些 skill、何时该用 | **仅** `id + displayName + description`（「Use when…」） | 租户/场景启用 Catalog；可选 L2 提权排序 |
| **触发 Trigger** | 本轮按该 skill **正文**行动 | **全文** `systemOverlay` / SKILL 正文（稳定前缀或 Tier 2） | L0 `/`；上轮已触发 sticky；极少数合规强制；高置信单点（可选） |
| **物料 Load** | 文件在沙箱可读 | 不进 prompt；`mountSkill` → `/skills/{id}/` | 随**触发**懒挂；或工具读路径时挂；**≠** 触发 |

```
Discover ──（用户 / 、高置信、sticky 续）──► Trigger ──► Overlay 注入
                │                                │
                └──── 可选提权排序 ───────────────┘
                                                 │
                                            Sandbox mount（正交）
```

**禁止**：把「L1/L2 召回命中」直接等价为「开场强制触发 overlay」（现状+ v3 初稿的主要偏差）。

---

## 2. 目标与非目标

### 2.1 目标

1. **行业对齐**：静态可发现 = 名+描述；正文按需/按触发注入（对齐 Cursor / task-scene §7）。  
2. **续跑保真**：HITL / reconnect 复用完整 `RoutingResult`，不丢**已触发** `skillIds`。  
3. **轻 Sticky**：粘的是**触发集**，不是可发现全集；无 `/` 续聊时保持 overlay，直到退出/换题/L0 覆盖。  
4. **装配纪律**：`PromptComposer` 只对 `skillIds`（触发集）调用 overlay；可发现集走目录摘要层。

### 2.2 非目标

- 不建 Redis ledger / 软链一等 source / `processGraph`。  
- 不扩展 `RoutingResult` 为双数组（见 §3.1：可发现不进 RoutingResult）。  
- 不在 orchestrator 硬编码技能名。  
- L3 **禁止**改写 `executionMode`；workflow 轨不做 skill sticky。  
- 不做「未发消息后台自动开下一 skill」。  
- 不要求模型必须经工具读 SKILL.md 才触发（允许平台在 Trigger 时直接灌 overlay；与「可读沙箱副本」并存）。

---

## 3. 默认模型（覆盖大多数场景）

### 3.1 可发现集（每轮，轻）

```
discoverable =
  租户启用 Catalog ∩ 场景可见
  （可选）L2 Top-K 提到摘要前列，仍只暴露名+描述
```

- **租户/场景「固定」= 固定可发现**，不是固定触发。  
- 合规红线若必须每轮约束行为 → 标为 **force-trigger**（极少数），与「方法包常驻可发现」分开配置；禁止把整个方法包 Catalog 当 force-trigger。

### 3.2 触发集 `skillIds`（进 RoutingResult）

```
triggered skillIds =
  L0 显式（/skill、clientSkillIds）        // 最高优先，可整表替换
  ∪ 上轮 triggered sticky（无退出/换题/L0）
  ∪ （可选）L3 高置信「本轮唯一应执行」的 ≤1 个 skill
  ∪ force-trigger（租户合规例外）

L1/L2 召回 → 默认只影响 discoverable 排序 / 给 L3 候选
           → 禁止无门槛写入 triggered
```

| 来源 | 进 discover | 进 triggered（overlay） |
|------|:-----------:|:----------------------:|
| 租户/场景启用 Catalog | ✅ | ❌（除非 force-trigger） |
| L0 `/` / 客户端点名 | ✅ | ✅ |
| L1 规则 / L2 embedding | 排序/候选 | ❌ 默认；经 L3 高置信才可 ✅ |
| 轻 sticky（上轮 triggered） | — | ✅ 继承 |
| 沙箱已 mount 的历史文件 | 物料层 | ❌ 不单独构成触发 |

**软链（产品语义）**：overlay 可写「完成后使用 X」；下轮靠 **description 可发现 + 用户推进/L0/高置信** 再触发 X——平台不建 `SOFT_CHAIN`。

### 3.3 装配（相对现状的根因修正）

| 步骤 | 现状（偏差） | 目标（行业） |
|------|--------------|--------------|
| 路由 | 召回 id ≈ 绑定 | 产出 discover 上下文 + **triggered** `skillIds` |
| Prompt | `resolveSkillOverlay(skillId)` 开场灌全文 | 目录摘要（名+描述）+ **仅 triggered** overlay |
| 沙箱 | 绑定时 mount | 触发时懒 mount；与 loadedSkillIds 累积解耦 |

对齐 [task-scene §7](./2026-08-01-task-scene-context-design.md)：目录摘要稳定前缀；命中正文进动态段 / skill-overlay。

---

## 4. 数据契约

### 4.1 `RoutingResult.skillIds` = **本轮已触发**

```java
List<String> skillIds;   // triggered only；轨 A；可空
// 不把 discoverable 全量写入 RoutingResult（避免续跑把「目录」当成「触发」）
```

可发现集由 Catalog + 租户策略 **运行时解析**，不落消息 SSOT（除非日后要审计「当时可见集」，另开字段，非本版）。

### 4.2 消息完整 `RoutingResult`（S-0）

- 存完整 JSON；修续跑丢 skill。  
- HITL / 同消息续跑：复用已存 **triggered** `skillIds`，不重跑收集、不重触发决策。

### 4.3 轻 Sticky（S-1）— 只粘触发

```
seed ← 上条 assistant.RoutingResult.skillIds   // 已触发

L0 → 替换 triggered
退出技能 / 明确换题 → 清空 seed
否则 → triggered 至少含 seed；L3 可追加高置信，禁止低置信清空 seed
discoverable ← 本轮按 §3.1 重算（与 sticky 无关）
```

- SSOT = 消息 RoutingResult；无 Redis。  
- triggered **不进** L1 Near/Mid/Far；Mid 最多路标。  
- Overlay 仅 triggered，落 Prompt 稳定前缀 / Tier 2（与压缩点一致）。

### 4.4 沙箱

- `skillIds`（triggered）→ 懒 `mountSkill`。  
- `loadedSkillIds` = 物料累积；sticky 不强制 umount。  
- **仅 mount 不注入 overlay** ≠ 已触发。

### 4.5 SUB

- SUB 不继承父 triggered sticky。  
- Spawn 可显式带一个 `skillId`（视为该 SUB 的 triggered）。

---

## 5. 相对 unified-routing v6

| v6 | 本设计（v3.1） |
|----|----------------|
| 轨 A 收集 `skillIds[]` | 语义收窄为 **triggered**；装配禁止「召回即 overlay」 |
| §10「skillIds → overlays + 沙箱」 | 改为：discover 摘要 + triggered overlays + 触发时 mount |
| Pre-Routing 复用 | S-0 保真 |
| — | S-1 轻 sticky（触发集） |
| — | 不做 ledger / 软链 / processGraph |

---

## 6. 实施切片

| 阶段 | 内容 | 出口 |
|------|------|------|
| **S-0** | 消息存完整 `RoutingResult`；续跑复用 triggered | live：不丢 skill |
| **S-D** | 可发现层：Prompt 注入租户可见 **名+描述**目录；**召回默认不灌 overlay** | 无 L0 时不应出现无关 skill 全文 |
| **S-T** | 触发：仅 L0 / sticky / force-trigger /（可选）L3 高置信 → `resolveSkillOverlay` | `/` 与续聊行为正确；L2 命中 alone 不触发 |
| **S-1** | 上轮 triggered 轻 sticky（依赖 S-0） | 「继续」无 `/` 仍有 overlay |
| **延期** | Redis、SOFT_CHAIN、processGraph、模型强制读 SKILL.md 才触发 | YAGNI |

**顺序建议**：S-0 → S-D + S-T（可同 PR 纪律）→ S-1。S-D/S-T 是相对 v3 的**根因修正**，优先于加厚 sticky 算法。

---

## 7. 验收标准

| # | 场景 | 预期 |
|---|------|------|
| V0 | 仅租户启用、无 `/`、无高置信 | Prompt 有目录名+描述；**无**任意 skill 全文 overlay |
| V1 | `/skill-A` | triggered=A，全文 overlay；可 mount |
| V2 | 上轮已触发 A，本轮「继续」无 `/` | 仍 triggered=A（sticky） |
| V3 | L2 召回 B 但未 L0/未高置信 | B 可出现在目录/排序；**不**自动全文 overlay |
| V4 | 「退出技能」/ 换题 | 清空 triggered；目录仍可发现 |
| V5 | HITL / 续跑 | 复用 triggered，不丢 |
| V6 | workflow / SUB | 无父 sticky；SUB 不继承 |
| V7 | 软链自动切下一 skill | **不验收** |

---

## 8. 风险与对策

| 风险 | 对策 |
|------|------|
| 目录过长占前缀 | Top-N + 「更多经 / 或检索」；对齐 task-scene 字节稳定 |
| L3 乱触发 | 默认关高置信自动触发；先 L0+sticky |
| 误以 mount=触发 | 文档+单测：仅 mount 无 overlay |
| 压缩后丢触发态 | S-0 消息 SSOT；∉ L1 折叠 |
| 与旧「召回即绑定」习惯冲突 | 验收 V0/V3；改 routing §10 文案 |

---

## 9. 关联文档

| 文档 | 关系 |
|------|------|
| [unified-routing v6](./2026-07-29-unified-routing-design.md) | 轨 A；`skillIds`=triggered；装配改 discover/trigger |
| [task-scene](./2026-08-01-task-scene-context-design.md) §7 | 名+描述 / 正文按需的直接依据 |
| [unified-context-compression](./2026-07-31-unified-context-compression-design.md) | 触发态不进 L1 |
| [business-context-authority](./2026-08-13-business-context-authority-design.md) | 触发稳定有助于 biz_scene；可发现≠ scene 乱跳 |
| [sandbox multi-skill](./archive/2026-07-16-conversation-sandbox-multi-skill-design.md) | 物料层 |

### 归档备注

v1 过程图、v2 软链+ledger、v3「固定∪召回→一律 overlay」均废弃；以本 v3.1 为准。
