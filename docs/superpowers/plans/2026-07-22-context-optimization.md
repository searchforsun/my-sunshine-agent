# 三层 Context 状态机 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 用 `ContextAssembler` + `ContextLifecycle` 重建跨轮上下文（L1 Near/Mid/Far、L2 结构化状态、L3 对话 RAG + 定时治理 + Admin），**删除**旧 STM/MTM/LTM 运行时路径；保留 AutoContext（单次 ReAct 工具压缩）。

**Architecture:** `ChatStreamContextFactory` 调 `ContextAssembler` 得到 `AssembledContext`；`PromptComposer` / `ContextMessageBuilder` 按约定注入；assistant completed → `ContextLifecycle` 异步压缩/抽取/ingest；`ContextMaintenanceJob` 定时 GC。开发期不兼容旧记忆。

**Tech Stack:** Spring Boot · JPA · Redis（仅非 STM）· rag-service Milvus · Prompt Catalog · Vue3/Naive UI · Nacos · Live Python

**Spec:** [2026-07-22-context-optimization-design.md](../specs/2026-07-22-context-optimization-design.md)

---

## File map

| 文件 | 职责 |
|------|------|
| `orchestrator/.../context/AssembledContext.java` | 组装结果：L2 块、Far 块、Mid/Near 轮次、L3 块 |
| `orchestrator/.../context/ContextProperties.java` | `agent.context.*`（含 maintenance）；`agent.memory.auto-context` **保留原位** |
| `orchestrator/.../context/ContextAssembler.java` | 读路径组装 + 预算裁剪 |
| `orchestrator/.../context/ContextLifecycle.java` | 写路径：L1 压缩、L2 抽取合并、L3 ingest |
| `orchestrator/.../context/ContextMessageBuilder.java` | 替换 `MemoryMessageBuilder` |
| `orchestrator/.../context/l1/*` | L1 派生表实体/仓库/压缩器 |
| `orchestrator/.../context/l2/*` | `user_context_state` + 冲突合并 + 抽取 |
| `orchestrator/.../context/l3/*` | HistoryRagClient + 召回/ingest 编排 |
| `orchestrator/.../context/job/ContextMaintenanceJob.java` | 定时腐败/向量 GC |
| `orchestrator/.../context/admin/ContextAdminController.java` | Admin API |
| `docker/mysql/init/11-sunshine-orchestrator.sql` | 新表；删旧 MTM/LTM 表定义 |
| `docker/mysql/init/17-sunshine-prompt-manager.sql` | 新 `context.*` Catalog；旧 `memory.*` 除 current-user-marker 可迁 |
| `docs/nacos/sunshine-orchestrator.yaml` | `agent.context.*`；去掉 stm/mtm/ltm |
| `rag-service/.../ChatHistory*` | L3 collection API（`sunshine_chat_history`）；**已删**孤儿 `MemoryController`/`MemoryMilvusService` |
| `sunshine-ui/.../ContextView.vue` + `components/context/*` + `api/contextAdmin.ts` | Admin 可读写页 |
| `scripts/verify_context_layers_live.py` | Live 门禁 |
| **删除** | `memory/stm/*`、`memory/mtm/*`、`memory/ltm/*`、`MemoryComposer`、旧 Redis STM、rag `Memory*`（MTM）等 |

**注入顺序（固定）：** system(L2+usage-rules) → system(Far) → Mid 轮次 → Near 轮次 → system(L3 materials) → 当前提问。

**预算裁剪顺序：** 先丢 L3 → 再丢 Far → **永不丢** L2 `constraint`。

---

### Task 1: `AssembledContext` + `ContextProperties` + Nacos

**Files:**
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/context/AssembledContext.java`
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/context/ContextProperties.java`
- Modify: `docs/nacos/sunshine-orchestrator.yaml`（`agent.memory` 仅留 `auto-context`；新增 `agent.context`）
- Test: `orchestrator/src/test/java/com/sunshine/orchestrator/context/AssembledContextTest.java`

- [ ] **Step 1: 写失败单测**

```java
package com.sunshine.orchestrator.context;

import com.sunshine.orchestrator.conversation.ChatTurn;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class AssembledContextTest {
    @Test
    void empty_hasNoLayers() {
        assertThat(AssembledContext.empty().hasAnyLayer()).isFalse();
    }

    @Test
    void forSubAgent_isEmpty() {
        assertThat(AssembledContext.forSubAgent()).isEqualTo(AssembledContext.empty());
    }

    @Test
    void hasAnyLayer_trueWhenNearPresent() {
        var ctx = new AssembledContext("", "", List.of(), List.of(new ChatTurn("user", "hi")), "");
        assertThat(ctx.hasAnyLayer()).isTrue();
    }
}
```

- [ ] **Step 2: 运行确认失败**

```bash
cd /usr/local/gitproj/my-sunshine-agent
mvn -pl orchestrator -Dtest=AssembledContextTest test
```

Expected: FAIL（类不存在）

- [ ] **Step 3: 实现 record + properties**

```java
package com.sunshine.orchestrator.context;

import com.sunshine.orchestrator.conversation.ChatTurn;
import java.util.List;

/** L1 Mid/Near 轮次 + L2/Far/L3 system 块。SUB/PLANNER 用 empty/forSubAgent。 */
public record AssembledContext(
        String l2SystemBlock,
        String farSummaryBlock,
        List<ChatTurn> midTurns,
        List<ChatTurn> nearTurns,
        String l3MaterialBlock
) {
    public static AssembledContext empty() {
        return new AssembledContext("", "", List.of(), List.of(), "");
    }

    public static AssembledContext forSubAgent() {
        return empty();
    }

    public boolean hasAnyLayer() {
        return hasText(l2SystemBlock) || hasText(farSummaryBlock) || hasText(l3MaterialBlock)
                || (midTurns != null && !midTurns.isEmpty())
                || (nearTurns != null && !nearTurns.isEmpty());
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
```

```java
package com.sunshine.orchestrator.context;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

@Data
@Component
@RefreshScope
@ConfigurationProperties(prefix = "agent.context")
public class ContextProperties {
    private boolean enabled = true;
    private L1 l1 = new L1();
    private L2 l2 = new L2();
    private L3 l3 = new L3();
    private Maintenance maintenance = new Maintenance();

    @Data
    public static class L1 {
        private int nearTurns = 8;
        private int midTurns = 8;
        private int maxChars = 12000;
    }

    @Data
    public static class L2 {
        private double minConfidence = 0.75;
        private double constraintOverwriteConfidence = 0.9;
        private int preferenceTtlDays = 365;
        private int agreementTtlDays = 365;
        private int goalTtlDays = 90;
        private int decisionTtlDays = 90;
        private int factTtlDays = 30;
        private int constraintTtlDays = 30;
    }

    @Data
    public static class L3 {
        private String collection = "sunshine_chat_history";
        private int topK = 5;
        private double minScore = 0.55;
        private boolean timeDecay = true;
    }

    @Data
    public static class Maintenance {
        /** ms；仿 SandboxSessionReaper */
        private long intervalMs = 3_600_000L;
    }
}
```

Nacos（在 `agent:` 下）：**删除** `memory.stm` / `mtm` / `ltm`；**保留** `memory.auto-context`；新增：

```yaml
  context:
    enabled: true
    l1:
      near-turns: 8
      mid-turns: 8
      max-chars: 12000
    l2:
      min-confidence: 0.75
      constraint-overwrite-confidence: 0.9
      preference-ttl-days: 365
      agreement-ttl-days: 365
      goal-ttl-days: 90
      decision-ttl-days: 90
      fact-ttl-days: 30
      constraint-ttl-days: 30
    l3:
      collection: sunshine_chat_history
      top-k: 5
      min-score: 0.55
      time-decay: true
    maintenance:
      interval-ms: 3600000
  memory:
    # 仅 AutoContext（4.6.4）；跨轮上下文见 agent.context
    auto-context:
      enabled: true
      # …原有字段不变
```

- [ ] **Step 4: 单测通过 + sync**

```bash
mvn -pl orchestrator -Dtest=AssembledContextTest test
python scripts/sync_nacos.py
```

- [ ] **Step 5: Commit**

```bash
git add orchestrator/src/main/java/com/sunshine/orchestrator/context/ \
  orchestrator/src/test/java/com/sunshine/orchestrator/context/AssembledContextTest.java \
  docs/nacos/sunshine-orchestrator.yaml
git commit -m "$(cat <<'EOF'
feat(context): add AssembledContext and agent.context properties

EOF
)"
```

---

### Task 2: Schema — 新表替换旧 MTM/LTM

**Files:**
- Modify: `docker/mysql/init/11-sunshine-orchestrator.sql`（替换 `conversation_memory_mtm` / `user_memory_profile` 段）
- Create: `orchestrator/.../context/l1/ConversationContextL1Entity.java` + `Repository`
- Create: `orchestrator/.../context/l2/UserContextStateEntity.java` + `Repository`
- Test: 实体字段映射单测或 Repository `@DataJpaTest`（若模块已有模式则跟随）

- [ ] **Step 1: SQL 替换为**

```sql
-- 上下文优化：L1 派生 + L2 状态（废止 conversation_memory_mtm / user_memory_profile）
CREATE TABLE conversation_context_l1 (
    conv_id       VARCHAR(32)  NOT NULL PRIMARY KEY,
    user_id       VARCHAR(64)  NOT NULL,
    tenant_id     VARCHAR(32)  NOT NULL DEFAULT 'default',
    mid_answers   MEDIUMTEXT   NULL COMMENT 'JSON map msgId -> answer summary',
    far_summary   MEDIUMTEXT   NULL,
    near_n        INT          NOT NULL DEFAULT 8,
    mid_n         INT          NOT NULL DEFAULT 8,
    updated_at    TIMESTAMP(3) NOT NULL,
    INDEX idx_l1_user (user_id, tenant_id)
);

CREATE TABLE user_context_state (
    id              VARCHAR(32)  NOT NULL PRIMARY KEY,
    user_id         VARCHAR(64)  NOT NULL,
    tenant_id       VARCHAR(32)  NOT NULL DEFAULT 'default',
    kind            VARCHAR(32)  NOT NULL,
    state_key       VARCHAR(128) NOT NULL,
    state_value     TEXT         NOT NULL,
    confidence      DOUBLE       NOT NULL DEFAULT 0,
    status          VARCHAR(16)  NOT NULL DEFAULT 'active',
    expires_at      TIMESTAMP(3) NULL,
    source_msg_id   VARCHAR(64)  NULL,
    created_at      TIMESTAMP(3) NOT NULL,
    updated_at      TIMESTAMP(3) NOT NULL,
    UNIQUE KEY uk_ctx_user_kind_key (user_id, tenant_id, kind, state_key),
    INDEX idx_ctx_user_status (user_id, tenant_id, status),
    INDEX idx_ctx_expires (expires_at)
);
```

开发环境若库已存在：另写一次性清理脚本说明（`scripts/` 或注释）：`DROP TABLE IF EXISTS conversation_memory_mtm, user_memory_profile;` 并建新表。禁止 Flyway。

- [ ] **Step 2: JPA 实体**（字段名与上表一致；`kind`/`status` 用 String 或 enum）

- [ ] **Step 3: Commit**

```bash
git commit -m "$(cat <<'EOF'
feat(context): add L1/L2 tables, drop legacy MTM/LTM schema

EOF
)"
```

---

### Task 3: `ContextMessageBuilder` + 接入 `PromptComposer`

**Files:**
- Create: `orchestrator/.../context/ContextMessageBuilder.java`
- Modify: `orchestrator/.../prompt/PromptComposeRequest.java` — `memory` → `AssembledContext context`（或双字段过渡一期直接替换）
- Modify: `orchestrator/.../prompt/PromptComposer.java` — 改用 `ContextMessageBuilder`；Catalog id 改为 `context.*`
- Modify: `ChatStreamContext.java` / `AgentRunRequest.java` — `MemoryContext` → `AssembledContext`
- Test: `ContextMessageBuilderTest.java`；更新 `PromptComposerTest.java`

- [ ] **Step 1: 失败单测 — Mid 为正常轮次、L2 在 system**

```java
@Test
void append_ordersL2_Far_Mid_Near_L3() {
    var ctx = new AssembledContext(
            "[用户状态 · L2]\n- preference: 简洁",
            "[更早对话 · Far]\n曾讨论差旅",
            List.of(new ChatTurn("user", "Q1"), new ChatTurn("assistant", "A1摘要")),
            List.of(new ChatTurn("user", "Q2"), new ChatTurn("assistant", "A2全文")),
            "[历史材料 · L3 · 可能过期]\n- …");
    List<Map<String, Object>> msgs = new ArrayList<>();
    ContextMessageBuilder.appendAll(msgs, ctx, "分层说明", "仅供指代");
    assertThat(msgs.get(0).get("role")).isEqualTo("system");
    assertThat(msgs.get(0).get("content").toString()).contains("L2");
    // …断言 Far → Mid user/assistant → Near → L3
}
```

- [ ] **Step 2: 实现 `appendAll` / `formatCurrentUser`**（`current-user-marker` Catalog id 可保留 `memory.current-user-marker` 或迁为 `context.current-user-marker`，计划统一迁到 `context.current-user-marker`）

- [ ] **Step 3: 全仓替换 `MemoryContext` 引用为 `AssembledContext`**（`forSubAgent`/`empty` 同名方法）。编译：

```bash
mvn -pl orchestrator -am -DskipTests compile
```

- [ ] **Step 4: Commit**

```bash
git commit -m "$(cat <<'EOF'
feat(context): wire ContextMessageBuilder into PromptComposer

EOF
)"
```

---

### Task 4: `ContextAssembler` 最小读路径（Near only）替换 `MemoryComposer`

**Files:**
- Create: `orchestrator/.../context/ContextAssembler.java`
- Modify: `ChatStreamContextFactory.java` — `memoryComposer.compose` → `contextAssembler.assemble`
- Delete/停用: `MemoryComposer`（本 Task 末或 Task 8 统一删）
- Test: `ContextAssemblerTest.java`

- [ ] **Step 1: 单测 — 仅 Near，超 `nearTurns` 从头部丢掉（暂无 Mid）**

```java
@Test
void assemble_keepsLastNearTurns() {
    // history 20 轮 user/assistant；nearTurns=2 → 仅最后 2 条消息（1 轮）
    AssembledContext ctx = assembler.assemble(req);
    assertThat(ctx.nearTurns()).hasSize(2);
    assertThat(ctx.midTurns()).isEmpty();
    assertThat(ctx.l2SystemBlock()).isBlank(); // Task 6 前为空
}
```

- [ ] **Step 2: 实现** `assemble(AssembleRequest)`：从 history 取尾部 `nearTurns` 条（成对优先，与旧 `StmWindowPolicy` 类似：超 `maxChars` 从头整轮丢弃）。L2/L3/Far 先返回空。

- [ ] **Step 3: Factory 接入；跑相关单测**

```bash
mvn -pl orchestrator -Dtest=ContextAssemblerTest,PromptComposerTest,ConversationIntegrationTest test
```

- [ ] **Step 4: Commit**

```bash
git commit -m "$(cat <<'EOF'
feat(context): ContextAssembler Near-only read path

EOF
)"
```

---

### Task 5: L1 Mid/Far 压缩写路径

**Files:**
- Create: `orchestrator/.../context/l1/L1Compressor.java`
- Create: `orchestrator/.../context/l1/ConversationContextL1Store.java`
- Modify: `ContextAssembler` — 读 mid_answers / far_summary 拼 Mid/Far
- Modify: `ContextLifecycle`（本 Task 创建骨架）— `onTurnCompleted` 调 L1
- Modify: `GenerationJob` / `ChatStreamExecutor` — `MemoryLifecycleService` → `ContextLifecycle`
- Catalog: `17-sunshine-prompt-manager.sql` 增加 `context.l1.mid-compress` / `context.l1.far-fold`
- Test: `L1CompressorTest`、`ContextAssemblerL1Test`

- [x] **Step 1: 单测 — Mid 形态**

```java
@Test
void midTurn_keepsFullUser_andSummarizedAssistant() {
    // 给定 mid_answers["msg-a"]="摘要A"
    // history 含 user Q + assistant(msg-a) 长文
    // 落入 Mid 带时：assistant content == "摘要A"
}
```

- [x] **Step 2: 单测 — 触发条件（预算或轮次）**

```java
@Test
void shouldCompress_whenOverMaxCharsEvenIfUnderTurnCap() { … }

@Test
void shouldCompress_whenOverTurnCapEvenIfUnderChars() { … }
```

- [x] **Step 3: 实现压缩**：对将落入 Mid 的 assistant 原文调 LLM（Catalog `context.l1.mid-compress`）写入 `mid_answers`；对将落入 Far 的区间调 `context.l1.far-fold` 更新 `far_summary`。异步；失败打日志不抛到用户。

- [x] **Step 4: Assembler 读派生表拼装 Mid/Far（Far 为 system 块文本）**

- [x] **Step 5: Commit**

```bash
git commit -m "$(cat <<'EOF'
feat(context): L1 Mid/Far compression and assembly

EOF
)"
```

---

### Task 6: L2 抽取 · 冲突 · TTL · system 注入

**Files:**
- Create: `orchestrator/.../context/l2/L2StateStore.java`
- Create: `orchestrator/.../context/l2/L2ConflictMerger.java`
- Create: `orchestrator/.../context/l2/L2ExtractService.java`
- Modify: `ContextAssembler` — 渲染 L2 system 块
- Modify: `ContextLifecycle` — 完成后抽取
- Catalog: `context.l2.extract`、`context.usage-rules`、`context.layer-prompt`
- Test: `L2ConflictMergerTest`、`L2StateStoreFilterTest`

- [ ] **Step 1: 冲突单测**

```java
@Test
void preference_newerHighConfidence_supersedesOld() {
    // old preference key=style value=详细 conf=0.8
    // new conf=0.85 → old status=superseded, new active
}

@Test
void constraint_requiresHigherOverwriteConfidence() {
    // old constraint conf=0.9；new conf=0.8 < 0.9 → 拒绝覆盖，仍 active 旧条
}
```

- [ ] **Step 2: TTL 过滤单测** — `expires_at` 已过不注入；`constraint` 未过期必注入

- [ ] **Step 3: 实现 Merger + Store + Extract**  
  抽取输出 JSON 数组：`[{kind,key,value,confidence}]`；`< minConfidence` 丢弃；同 key 走 Merger；写入时按 kind 设 `expires_at`。

- [ ] **Step 4: Assembler 渲染**

```text
[用户状态 · L2]
- preference/style: 简洁
- constraint/budget: 单次不超过500
```

前置 Catalog `context.usage-rules`（如何使用、冲突以何为准）。

- [ ] **Step 5: Commit**

```bash
git commit -m "$(cat <<'EOF'
feat(context): L2 extract, conflict merge, and system inject

EOF
)"
```

---

### Task 7: L3 chat-history RAG + Far 回填

**Files:**
- Create: `rag-service/.../ChatHistoryController.java` + `ChatHistoryMilvusService`（**已落地**；孤儿 `Memory*` 已删）
- Create: `orchestrator/.../context/l3/HistoryRagClient.java`
- Create: `orchestrator/.../context/l3/L3IngestService.java` / `L3RecallService.java`
- Modify: Assembler — query 召回；排除本会话 Near/Mid 已覆盖 msgId；时间衰减
- Catalog: `context.l3.material-header`
- Test: `L3RecallServiceTest`（mock client）；rag-service 分块单测若有则扩展

- [ ] **Step 1: 单测 — 排除近窗 msgId**

```java
@Test
void recall_excludesMessageIdsAlreadyInL1Window() {
    when(client.search(...)).thenReturn(hits including msg-near and msg-old);
    var block = recall.recall(user, tenant, conv, query, Set.of("msg-near"));
    assertThat(block).doesNotContain("msg-near");
    assertThat(block).contains("msg-old");
}
```

- [ ] **Step 2: ingest** — Lifecycle 对新 completed 消息按现有 chunk 策略切分后 upsert（metadata: userId, tenantId, convId, msgId, time）

- [ ] **Step 3: Far 回填** — 若 `far_summary` 非空且召回 hit 的 msg 落在 Far 区间，把 hit 文本并入 L3 材料块（仍标可能过期）

- [ ] **Step 4: 预算裁剪单测** — 超 `maxChars` 时先清空 `l3MaterialBlock`，再清空 `farSummaryBlock`，保留 `l2` constraint 行

- [ ] **Step 5: Commit**

```bash
git commit -m "$(cat <<'EOF'
feat(context): L3 chat-history RAG ingest and recall

EOF
)"
```

---

### Task 8: 删除旧记忆运行时 + Catalog 迁移

**Files:**
- Delete: `memory/stm/*`、`memory/mtm/*`、`memory/ltm/*`、`MemoryComposer`、`MemoryLifecycleService`、`MemoryMessageBuilder`（若仍存在）、旧测试
- Keep: `agent.memory.auto-context` 绑定类（可从 `MemoryProperties` 瘦身为 `AutoContextProperties`，或 `MemoryProperties` 仅含 auto-context）
- Modify: `17-sunshine-prompt-manager.sql` — INSERT 新 `context.*`；旧 `memory.layer-prompt` / `stm.*` / `mtm.*` 标废弃或删除种子（开发期可删）
- Modify: `scripts/clear_session_cache.py` — 去掉 MTM truncate，改为新表
- Test: 全量 `mvn -pl orchestrator test` 相关模块绿

- [ ] **Step 1: 编译期零引用旧类**

```bash
rg -n "MemoryComposer|StmStore|MtmService|LtmProfileService|MemoryLifecycleService" orchestrator/
# Expected: 无业务引用（仅 CHANGELOG/废止文档可提）
```

- [ ] **Step 2: 跑测试并修编译错误**

```bash
mvn -pl orchestrator,rag-service -am test
```

- [ ] **Step 3: Commit**

```bash
git commit -m "$(cat <<'EOF'
refactor(context): remove legacy STM/MTM/LTM runtime

EOF
)"
```

---

### Task 9: `ContextMaintenanceJob`

**Files:**
- Create: `orchestrator/.../context/job/ContextMaintenanceJob.java`
- Create: `orchestrator/.../context/job/ContextMaintenanceService.java`
- Test: `ContextMaintenanceServiceTest.java`

- [ ] **Step 1: 单测**

```java
@Test
void voidExpiredL2_andDeleteCorrespondingVectors() {
    // given expired active row → status void；verify historyRagClient.deleteBySourceMsgIds or deleteByStateIds
}

@Test
void gcOrphanL1_whenConversationMissing() { … }
```

- [ ] **Step 2: 实现 Job**

```java
@Component
public class ContextMaintenanceJob {
    private final ContextMaintenanceService service;

    @Scheduled(fixedDelayString = "${agent.context.maintenance.interval-ms:3600000}")
    public void tick() {
        service.runOnce(); // 内部 try/catch 吞掉，只 log
    }
}
```

`runOnce`：L2 硬过期→void；可选清理长期 superseded；L3 删 void/过期源向量与孤儿；L1 无主会话行删除。

- [ ] **Step 3: Commit**

```bash
git commit -m "$(cat <<'EOF'
feat(context): scheduled maintenance for stale state and vectors

EOF
)"
```

---

### Task 10: Admin API + UI

**Files:**
- Create: `orchestrator/.../context/admin/ContextAdminController.java`（`/api/admin/context/...`）
- Create: BFF 透传（仿 `ExpertsController`）
- Create: `sunshine-ui/src/api/contextAdmin.ts`
- Create: `sunshine-ui/src/views/ContextView.vue`（左用户/条目列表 + 右详情编辑；同构 Experts）
- Modify: `router/index.ts`、`MainLayout.vue` 加入口（如 `/context`）
- Test: Controller WebMvcTest 或 Live 脚本覆盖写接口

- [x] **Step 1: API 契约**

```text
GET  /api/admin/context/l2?userId=&tenantId=
PUT  /api/admin/context/l2/{id}          # value/confidence/status
POST /api/admin/context/l2/{id}/void
GET  /api/admin/context/l1?convId=
GET  /api/admin/context/l3/status?userId=
POST /api/admin/context/l3/gc
POST /api/admin/context/l3/reingest?convId=
```

- [x] **Step 2: 前端最小可读写**（黑底边框；不作视觉专项）

- [x] **Step 3: Commit**

```bash
git commit -m "$(cat <<'EOF'
feat(context): admin read/write UI for L1/L2/L3

EOF
)"
```

---

### Task 11: Live 脚本 + 文档收口

**Files:**
- Create: `scripts/verify_context_layers_live.py`
- Modify: `CLAUDE.md` 运维表增加该脚本一行
- Modify: `docs/superpowers/specs/2026-06-17-agent-memory-design.md` 顶部加废止声明指向新详设
- Modify: `README.md` 若有记忆章节则改链接

- [x] **Step 1: Live 覆盖**

| Case | 断言 |
|------|------|
| L1 | 造 >2N 轮短对话 → DB 有 mid_answers / far_summary；下一请求 messages 含摘要 assistant |
| L2 | 用户明确说偏好 → completed 后 `user_context_state` active；新会话 system 含该条 |
| L2 conflict | 先约束后低置信反转 → 不覆盖 |
| L3 | 旧会话细节可被新会话召回；近窗 msg 不重复 |
| Job | 调 `runOnce` 或 Admin GC → 过期 void + 向量减少 |
| 回归 | SUB 无 L2/L1；AutoContext 开关仍生效 |

- [x] **Step 2: 跑 Live（实施机）**

```bash
python scripts/verify_context_layers_live.py
```

Expected: 全 PASS

- [x] **Step 3: Commit**

```bash
git commit -m "$(cat <<'EOF'
test(context): add verify_context_layers_live and docs closure

EOF
)"
```

---

## Self-review（对照 Spec）

| Spec 要求 | Task |
|-----------|------|
| AssembledContext + Assembler/Lifecycle | 1,4,5,6,7 |
| L1 Near/Mid/Far（Mid 仍轮次） | 5 |
| 压缩触发预算+轮次 | 5 |
| L2 六类 + 静默置信 | 6 |
| 冲突时间优先+类型门槛 | 6 |
| 按类型 TTL | 6,9 |
| L3 chunk RAG + Far 回填 | 7 |
| 预算裁剪顺序 | 7 Step 4 |
| Catalog `context.*` | 3,5,6,7,8 |
| 删旧记忆 / 不兼容 | 2,8 |
| 保留 AutoContext | 1 Nacos、8 |
| SUB empty | 1,3 |
| MaintenanceJob | 9 |
| Admin 可读写 | 10 |
| Live 脚本 | 11 |

无 TBD；类型名统一 `AssembledContext` / `ContextAssembler` / `ContextLifecycle` / `ContextProperties`。
