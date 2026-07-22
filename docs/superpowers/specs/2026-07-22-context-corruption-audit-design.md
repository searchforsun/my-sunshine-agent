# 上下文腐败 / 矛盾审计设计

**日期**：2026-07-22  
**状态**：已落地  
**范围**：L2 用户状态 + L1 会话派生（不含 L3）  
**关联**：`2026-07-22-context-optimization-design.md` §5；现有 `ContextMaintenanceJob`

## 1. 目标

在定时治理之外，于 **每次 L2 抽取完成后**对本用户做一次**轻量**矛盾/腐败检查，及时清理错误信息；暧昧情况打标不注入，避免误伤。

## 2. 已确认决策

| 项 | 选择 |
|----|------|
| 范围 | L2 + L1 派生（不碰 L3 向量） |
| 处置 | **混合**：明确冲突自动 void/清派生；暧昧 → `conflict` 打标 |
| 检测 | **规则 + LLM**（Catalog 审阅提示词） |
| 触发 | **小时维护任务** + **每次 L2 抽取后轻量检查** |

## 3. 状态与注入

### 3.1 L2 `status` 扩展

现有：`active` | `superseded` | `void`  
新增：`conflict` — 语义矛盾或内容可疑，**不注入**（与 void 同等排除），Admin 可见，可手动恢复 `active` 或改 `void`。

注入路径 `L2StateStore.listInjectable` / `assembleSystemBlock`：仅 `active` 且未过期（现状已过滤非 active）。

### 3.2 L1 派生

无新 status 列亦可：冲突 mid 键从 `mid_answers` 删除；`far_summary` 冲突句剔除或整段重写（LLM 输出 `farSummary` 替换 / `removeMidKeys`）。可选列 `audit_note`（TEXT）记最近一次审计摘要，便于 Admin（**本期可选**，优先日志 + mid/far 就地修正）。

## 4. 检测流水线

统一入口：`ContextAuditService`（失败仅日志，不抛、不阻断聊天）。

### 4.1 规则快扫（无 LLM，抽取后与维护任务均执行）

对 `(userId, tenantId)`：

1. 同 `(kind, state_key)` 多条 `active`：保留置信最高者（平手取 `updated_at` 新）→ 其余 **`void`**。  
2. （可选）空 value / 超短垃圾串 → `void`。

### 4.2 轻量 LLM 审阅（抽取后）

**时机**：`L2ExtractService.extract` 成功 upsert 至少一条（或本轮有候选）之后 `@Async` 调用 `auditUserLight(userId, tenantId)`。

**输入**：该用户当前全部 `active` L2（id/kind/key/value/confidence）；若存在任意 L1 派生行，附带各会话 `far_summary` + `mid_answers` 摘要列表（截断预算，如合计 ≤ 4k 字）。

**Catalog**：

- `context.l2.audit` — 审阅 L2，输出 JSON：  
  `{ "voidIds": [], "conflictIds": [], "reasons": { "id": "…" } }`  
  - `voidIds`：明确错误/互斥且应作废  
  - `conflictIds`：暧昧，仅打标  
- `context.l1.audit` — 仅当存在 L1 派生时调用；输出：  
  `{ "removeMidKeys": { "convId": ["msgId",…] }, "farSummaryByConv": { "convId": "修订后或空" }, "notes": "…" }`

**门禁**：单次 LLM 超时与失败 → skip；`voidIds`/`conflictIds` 必须属于本用户 active 集合，否则忽略（防幻觉 id）。

### 4.3 全量审阅（小时维护）

`ContextMaintenanceService.runOnce` 在现有 void/superseded/L3/L1 孤儿清理之后：

1. 分页选取近期有 L2 更新的用户（`audit-max-users-per-tick`，默认如 50）。  
2. 每用户：`规则快扫` → `auditUserLight`（与抽取后同路径，可复用）。  
3. 指标/日志：`voided` / `conflicted` / `l1Patched`。

Admin「清理过期索引」继续调 `runOnce`（含本审计）。

## 5. 与抽取热路径的关系

```
onTurnCompleted
  → L1 compressAsync
  → L2 extractAsync
       → extract → upsert…
       → auditUserLightAsync(user, tenant)   // 新增，独立 @Async
  → L3 ingestAsync
```

轻量检查**不阻塞**用户 SSE；与 compress/ingest 并行可接受。同一用户可用短 TTL 内存锁（如 30s）合并抖动，避免连发两轮抽取得出双次 LLM。

## 6. Admin / UI

- L2 状态选项增加「矛盾」(`conflict`)；列表 tag 用 warning。  
- 已有作废/保存可把 `conflict` → `active`（人工确认）或 `void`。  
- 不必新增独立审计页（本期）。

## 7. 配置（Nacos `agent.context.maintenance`）

```yaml
maintenance:
  interval-ms: 3600000
  superseded-retention-days: 180
  audit-enabled: true
  audit-on-extract: true          # 抽取后轻量检查
  audit-max-users-per-tick: 50    # 小时任务批量上限
  audit-extract-debounce-ms: 30000
```

## 8. Catalog / SQL

- Prompt：`context.l2.audit`、`context.l1.audit`（`docker/mysql/init/17-…` 种子 + `/prompts`）。  
- `user_context_state.status` 注释/校验允许 `conflict`（VARCHAR 已够用，**无需改列宽**）。  
- L1 `audit_note`：**可选**，本期可不加列。

## 9. 非目标

- L3 向量语义去腐  
- 对用户可见最终 content 二次加工  
- 抽取同步阻塞等待审计结果  

## 10. 验收要点

1. 同 key 双 active → 抽取或维护后仅一条 active，其余 void。  
2. LLM 返回 voidId → 该条不再出现在 system L2 块。  
3. conflict 条 Admin 可见、不注入；手动改回 active 后可注入。  
4. L1 mid 与现行 L2 明确冲突 → 对应 mid 键被移除。  
5. 审计 LLM 失败不影响聊天 completed 主路径。
