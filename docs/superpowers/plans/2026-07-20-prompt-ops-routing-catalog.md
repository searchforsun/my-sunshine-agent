# Prompt 运营中心 + 统一路由规则引擎 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 落地 4.11：`prompt-manager` DB 为提示词/路由规则唯一 SSOT，统一 Rule Engine 替换 L1/L1b/L2，提供 `/prompts` 运营页与 Live 验收。

**Architecture:** 共享模块 `common/sunshine-routing` 承载匹配/冲突/试跑；`prompt-manager` 管 Catalog CRUD + 发布；orchestrator `PromptCatalogClient` 拉全量 Snapshot 热更新；Policy Chain 固定为 L0 → `UnifiedRuleRoutingPolicy` → L3；前端 `/prompts` 三视图对齐 Experts。

**Tech Stack:** JDK 21 · Spring Boot 3.2 · JPA · MySQL `sunshine_prompt` · WebClient · Vue3/Naive UI · Python Live

**设计 SSOT:** [2026-07-20-prompt-ops-routing-catalog-design.md](../specs/2026-07-20-prompt-ops-routing-catalog-design.md)

**前置:** MySQL / Nacos / 全链路可启；改前确认本计划与 spec 一致。

**matchType 澄清（相对 spec §3.4，锁定 golden-set）:**

| matchType | 语义 | 默认 plan |
|-----------|------|-----------|
| `structural` | multi-step **且** domainGroups≥min（原 L1 一体） | `PLAN_WORKFLOW` |
| `peer_phrase` | peer 句式 | `PEER_COLLAB` |
| `regex` | 原 L2 正则 | 规则内 `plan` |

种子 priority：`structural=100`，`peer_phrase=90`，regex 保持 20/15/10。

---

## 文件结构（边界锁定）

| 区域 | 创建 | 修改 | 测试 |
|------|------|------|------|
| **共享路由** | `common/sunshine-routing/**` | 根 `pom.xml` | `UnifiedRuleEngineTest`、`RoutingConflictDetectorTest` |
| **DB** | `docker/mysql/init/17-sunshine-prompt-manager.sql` | `01-init-databases.sql` | mysql 手测 |
| **prompt-manager** | entity/repo/service/controller/dto | `pom.xml`、`application.yml`、`sunshine-prompt.yaml` | `PromptAdminServiceTest`、`RoutingValidateApiTest` |
| **orchestrator** | `client/PromptCatalogClient`、`prompt/PromptCatalogSnapshot`、`routing/UnifiedRuleEngine` 适配、`policy/UnifiedRuleRoutingPolicy` | 删 Structural/Peer/Golden policies 与 Nacos routing/prompt Properties 绑定；改 `PromptComposer`/`IntentRouter`/timeline | `RoutingGoldenSetTest`、`PromptComposerTest` |
| **BFF** | `PromptsController`、`PromptManagerClient` | gateway 路由如需 | curl |
| **前端** | `api/prompts.ts`、`views/PromptsView.vue`、`components/prompts/*`、`composables/usePromptsPage.ts` | `router/index.ts`、`MainLayout.vue` | `npx vue-tsc -b` |
| **验收/文档** | `scripts/verify_prompt_catalog_live.py` | `routing-golden-set.md`、`CLAUDE.md`、Nacos yaml 退役注释、`implementation-plan.md` | Live |

---

## 迭代排期

```
迭代 0  底座         T0 → T1
迭代 1  prompt-mgr   T2 → T3 → T4 → T5
迭代 2  路由切换     T6 → T7
迭代 3  提示词切换   T8 → T9
迭代 4  BFF+UI       T10 → T11 → T12
迭代 5  验收         T13
```

每迭代结束应可独立验证；T7 完成后 golden-set 必须绿。

---

## Task T0: MySQL `sunshine_prompt`

**Files:**
- Create: `docker/mysql/init/17-sunshine-prompt-manager.sql`
- Modify: `docker/mysql/init/01-init-databases.sql`

- [ ] **Step 1: 建库**

在 `01-init-databases.sql` 追加：

```sql
CREATE DATABASE IF NOT EXISTS sunshine_prompt DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

- [ ] **Step 2: 建表**

创建 `17-sunshine-prompt-manager.sql`：

```sql
-- sunshine-prompt-manager（prompt-manager :8500）
USE sunshine_prompt;

CREATE TABLE prompt_definition (
    id              VARCHAR(128) PRIMARY KEY,
    kind            VARCHAR(32)  NOT NULL,
    display_name    VARCHAR(128) NOT NULL,
    description     VARCHAR(512) NULL,
    enabled         TINYINT(1)   NOT NULL DEFAULT 1,
    priority        INT          NOT NULL DEFAULT 0,
    active_version  INT          NOT NULL DEFAULT 1,
    catalog_version BIGINT       NOT NULL DEFAULT 1,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_prompt_kind (kind),
    KEY idx_prompt_priority (priority)
);

CREATE TABLE prompt_version (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    prompt_id       VARCHAR(128) NOT NULL,
    version         INT          NOT NULL,
    status          VARCHAR(24)  NOT NULL DEFAULT 'published',
    content_text    MEDIUMTEXT   NULL,
    content_json    MEDIUMTEXT   NULL,
    change_note     VARCHAR(512) NULL,
    maintainer      VARCHAR(64)  NULL,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_prompt_version (prompt_id, version),
    CONSTRAINT fk_prompt_version_def FOREIGN KEY (prompt_id) REFERENCES prompt_definition (id)
);

CREATE TABLE prompt_catalog_meta (
    id              TINYINT PRIMARY KEY DEFAULT 1,
    catalog_version BIGINT NOT NULL DEFAULT 1,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);
INSERT INTO prompt_catalog_meta (id, catalog_version) VALUES (1, 1);
```

- [ ] **Step 3: 种子（最小可跑路由）**

同文件追加（全文提示词种子在 T4 用脚本从 yaml 导入；此处先锁路由三条 regex + structural + peer）：

```sql
INSERT INTO prompt_definition (id, kind, display_name, enabled, priority, active_version) VALUES
('routing-rule.structural-plan', 'routing-rule', '多步跨域→Plan', 1, 100, 1),
('routing-rule.peer-phrase', 'routing-rule', 'Peer句式→协作', 1, 90, 1),
('routing-rule.rule-finance-smart-compliance', 'routing-rule', '财务合规→finance-smart', 1, 20, 1),
('routing-rule.rule-knowledge-budget-travel', 'routing-rule', '预算出差→knowledge-qa', 1, 15, 1),
('routing-rule.rule-finance-list-pending', 'routing-rule', '待审批列表→finance-list', 1, 10, 1);

-- content_json 示例（structural；patterns/domainGroups 与现行 Nacos 一致）
INSERT INTO prompt_version (prompt_id, version, status, content_json) VALUES
('routing-rule.structural-plan', 1, 'published',
 '{"matchType":"structural","minDomainGroups":2,"patterns":["先.+再","再.+(并|然后|接着)","分步","多步","并对.+?(分析|审查|检查|评估)","完整处理","一套.+(分析|流程|处理)"],"domainGroups":{"knowledge":["制度","检索","知识库","政策","差旅办法","报销规定"],"finance":["待审批","报销","财务","付款","单据"],"analysis":["合规","分析","审查","对比","评估","结论"]},"plan":{"mode":"plan-workflow","params":{}}}'),
('routing-rule.peer-phrase', 1, 'published',
 '{"matchType":"peer_phrase","patterns":["互相验证","交叉审查","多专家讨论","分别分析并质疑","两个角度.*审查","专家.*分别.*审查"],"plan":{"mode":"peer-collab","params":{}}}'),
('routing-rule.rule-finance-smart-compliance', 1, 'published',
 '{"matchType":"regex","match":"any","patterns":["是否合规","合规吗","合不合规","对比制度"],"plan":{"mode":"workflow","workflowId":"finance-smart","params":{"status":"pending"}}}'),
('routing-rule.rule-knowledge-budget-travel', 1, 'published',
 '{"matchType":"regex","match":"any","patterns":["预算.*出差","出差.*预算","预算超支","预算不够.*出差"],"plan":{"mode":"workflow","workflowId":"knowledge-qa","params":{}}}'),
('routing-rule.rule-finance-list-pending', 1, 'published',
 '{"matchType":"regex","match":"any","patterns":["有哪些待审批","查询待审批","列出待审批","待审批的.*报销","待审批.*付款"],"plan":{"mode":"workflow","workflowId":"finance-list","params":{"status":"pending"}}}');
```

已有库手工执行同 SQL。

- [ ] **Step 4: Commit**

```bash
git add docker/mysql/init/01-init-databases.sql docker/mysql/init/17-sunshine-prompt-manager.sql
git commit -m "chore(db): add sunshine_prompt schema and routing rule seeds"
```

---

## Task T1: 共享模块 `common/sunshine-routing`

**Files:**
- Create: `common/sunshine-routing/pom.xml`
- Create: `common/sunshine-routing/src/main/java/com/sunshine/routing/RoutingRuleDef.java`
- Create: `common/sunshine-routing/src/main/java/com/sunshine/routing/RoutingPlanSpec.java`
- Create: `common/sunshine-routing/src/main/java/com/sunshine/routing/UnifiedRuleEngine.java`
- Create: `common/sunshine-routing/src/main/java/com/sunshine/routing/RoutingConflictDetector.java`
- Create: `common/sunshine-routing/src/main/java/com/sunshine/routing/RoutingDryRunResult.java`
- Create: `common/sunshine-routing/src/test/java/com/sunshine/routing/UnifiedRuleEngineTest.java`
- Modify: 根 `pom.xml`（modules）

- [ ] **Step 1: 写失败单测**

```java
package com.sunshine.routing;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

class UnifiedRuleEngineTest {
    @Test
    void structuralBeatsLowerPriorityRegex() {
        RoutingRuleDef structural = rule("structural-plan", 100, "structural",
                List.of("先.+再"), Map.of("knowledge", List.of("制度"), "finance", List.of("报销"), "analysis", List.of("分析")),
                "plan-workflow", null);
        RoutingRuleDef regex = rule("finance-list", 10, "regex",
                List.of("待审批"), Map.of(), "workflow", "finance-list");
        UnifiedRuleEngine engine = new UnifiedRuleEngine(List.of(regex, structural));
        Optional<UnifiedRuleEngine.Hit> hit = engine.match("先检索制度再分析报销待审批");
        assertTrue(hit.isPresent());
        assertEquals("structural-plan", hit.get().ruleId());
        assertEquals("plan-workflow", hit.get().plan().mode());
    }

    @Test
    void regexFirstByPriority() {
        RoutingRuleDef a = rule("a", 20, "regex", List.of("是否合规"), Map.of(), "workflow", "finance-smart");
        RoutingRuleDef b = rule("b", 10, "regex", List.of("是否合规"), Map.of(), "workflow", "other");
        Optional<UnifiedRuleEngine.Hit> hit = new UnifiedRuleEngine(List.of(b, a)).match("这样是否合规");
        assertEquals("a", hit.get().ruleId());
    }

    private static RoutingRuleDef rule(String id, int priority, String matchType,
                                       List<String> patterns, Map<String, List<String>> groups,
                                       String mode, String workflowId) {
        return new RoutingRuleDef(id, priority, true, matchType, "any", patterns, groups, 2,
                new RoutingPlanSpec(mode, workflowId, Map.of()));
    }
}
```

- [ ] **Step 2: 跑测确认失败**

```bash
mvn -pl common/sunshine-routing test -Dtest=UnifiedRuleEngineTest -q
```

Expected: 模块不存在或编译失败。

- [ ] **Step 3: 实现模块**

`pom.xml`：parent `my-sunshine-agent`，artifact `sunshine-routing`，依赖 `junit-jupiter`（test）。

核心类要点：

```java
public record RoutingPlanSpec(String mode, String workflowId, Map<String, String> params) {}

public record RoutingRuleDef(
        String id, int priority, boolean enabled, String matchType, String match,
        List<String> patterns, Map<String, List<String>> domainGroups, int minDomainGroups,
        RoutingPlanSpec plan) {}

public final class UnifiedRuleEngine {
    public record Hit(String ruleId, RoutingPlanSpec plan, String reason) {}
    private final List<RoutingRuleDef> rules; // 构造时按 priority desc, id asc 排序并过滤 !enabled

    public Optional<Hit> match(String userQuery) { /* matchType 分派 */ }

    private boolean matchStructural(String q, RoutingRuleDef r) {
        // 任一 multi-step pattern find AND domainGroupHitCount >= minDomainGroups
    }
    private boolean matchRegex(String q, RoutingRuleDef r) { /* any/all 同 RuleBasedRouter */ }
    private boolean matchPeer(String q, RoutingRuleDef r) { /* patterns find */ }
}
```

`RoutingConflictDetector.detect(List<RoutingRuleDef>)` → `List<Warning>`（同 priority；regex 互相包含用 `Pattern` 启发式字符串包含警告）。

`dryRun(String query, List<RoutingRuleDef> rules)` → `RoutingDryRunResult(matchedRuleId|null, wouldLlm, stage)`。

- [ ] **Step 4: 跑测通过 + Commit**

```bash
mvn -pl common/sunshine-routing test -q
git add common/sunshine-routing pom.xml
git commit -m "feat(routing): add shared UnifiedRuleEngine module"
```

---

## Task T2: prompt-manager JPA 骨架

**Files:**
- Modify: `prompt-manager/pom.xml`（加 jpa、mysql、sunshine-routing、test）
- Modify: `docs/nacos/sunshine-prompt.yaml`（datasource）
- Create: `prompt-manager/src/main/java/com/sunshine/prompt/entity/PromptDefinitionEntity.java`
- Create: `prompt-manager/src/main/java/com/sunshine/prompt/entity/PromptVersionEntity.java`
- Create: `prompt-manager/src/main/java/com/sunshine/prompt/entity/PromptCatalogMetaEntity.java`
- Create: `prompt-manager/src/main/java/com/sunshine/prompt/repo/*.java`
- Modify: `PromptApplication.java`（`@EnableJpaRepositories`、`@EntityScan`）

- [ ] **Step 1: 依赖与数据源**

`pom.xml` 追加（对齐 expert-manager）：

```xml
<dependency>
  <groupId>com.sunshine</groupId>
  <artifactId>sunshine-routing</artifactId>
  <version>${project.version}</version>
</dependency>
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-data-jpa</artifactId>
</dependency>
<dependency>
  <groupId>com.mysql</groupId>
  <artifactId>mysql-connector-j</artifactId>
</dependency>
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-test</artifactId>
  <scope>test</scope>
</dependency>
```

`sunshine-prompt.yaml` 追加（凭据与其它 manager 同 ecs4c16g）：

```yaml
spring:
  datasource:
    url: jdbc:mysql://ecs4c16g:3306/sunshine_prompt?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai
    username: root
    password: <与其它 manager 一致>
  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false
```

- [ ] **Step 2: Entity 字段对齐 T0 表**（`kind` 用 String；`contentJson`/`contentText` 映射 MEDIUMTEXT）

- [ ] **Step 3: 编译**

```bash
python scripts/sync_nacos.py  # 若改了 sunshine-prompt.yaml
mvn -pl prompt-manager -am compile -q
```

- [ ] **Step 4: Commit**

```bash
git commit -am "feat(prompt-manager): JPA entities and datasource for prompt catalog"
```

---

## Task T3: Admin CRUD + 版本发布

**Files:**
- Create: `prompt-manager/.../dto/*Request*.java`、`PromptDetailResponse.java`、`PromptListItem.java`
- Create: `prompt-manager/.../service/PromptAdminService.java`
- Create: `prompt-manager/.../controller/PromptAdminController.java`
- Create: `prompt-manager/src/test/java/.../PromptAdminServiceTest.java`

- [ ] **Step 1: 写服务单测（Mockito + 内存逻辑）**

覆盖：`create` → `addVersion(draft)` → `publish` 切换 `activeVersion` 且 `bumpCatalogVersion`；`rollback(version)` 仅允许 published；并发用 `updatedAt` 乐观校验返回冲突。

- [ ] **Step 2: 实现 API**

```
GET    /api/prompts?kind=&enabled=
GET    /api/prompts/{id}
POST   /api/prompts
PUT    /api/prompts/{id}
POST   /api/prompts/{id}/versions          body: {status, contentText, contentJson, changeNote}
POST   /api/prompts/{id}/publish           body: {version?}  # 缺省最新 draft
POST   /api/prompts/{id}/rollback          body: {version}
GET    /api/prompts/{id}/versions
PUT    /api/prompts/{id}/enable            body: {enabled}
```

发布与元数据变更后：`UPDATE prompt_catalog_meta SET catalog_version = catalog_version + 1`。

返回统一 `R<T>`（sunshine-common）。

- [ ] **Step 3: 测试通过 + Commit**

```bash
mvn -pl prompt-manager test -Dtest=PromptAdminServiceTest -q
git commit -am "feat(prompt-manager): prompt CRUD publish and rollback"
```

---

## Task T4: Catalog API + 提示词种子导入

**Files:**
- Create: `prompt-manager/.../controller/PromptCatalogController.java`
- Create: `prompt-manager/.../dto/PromptCatalogEntry.java`、`PromptCatalogResponse.java`
- Create: `prompt-manager/.../service/PromptCatalogService.java`
- Create: `scripts/migrate_nacos_prompts_to_db.py`（一次性导入 system/mode-overlay/intent/timeline/…）
- Modify: `15-*.sql` 或脚本输出追加 INSERT（长文本）

- [ ] **Step 1: Catalog 契约**

```json
{
  "catalogVersion": 12,
  "entries": [
    {
      "id": "mode-overlay.react",
      "kind": "mode-overlay",
      "displayName": "...",
      "enabled": true,
      "priority": 0,
      "version": 1,
      "contentText": "...",
      "contentJson": null
    }
  ]
}
```

`GET /api/prompts/catalog` — 仅 `enabled=true` 且 active published 内容。

- [ ] **Step 2: 导入脚本**

从 `docs/nacos/sunshine-orchestrator.yaml` 解析键写入 DB（或生成 SQL）。至少覆盖：

- `agent.system-prompt` → `system-prompt`
- `agent.prompt.mode-overlays.*` → `mode-overlay.*`
- `agent.intent.classifier-prompt` → `intent.classifier`
- `agent.planner.prompt` → `planner.prompt`
- `agent.prompt.answer-template` / `answer-overlay`
- `agent.timeline.*` → `timeline.*`（json）
- `agent.rewrite.*`、`agent.hitl.agent-prompt`、`agent.memory.layer-prompt`、`agent.prompt.scope-prompt`

ReAct 大段可先整段进 `mode-overlay.react`；fragment 拆分可在 T8/T12 再拆种子。

- [ ] **Step 3: 手测**

```bash
curl -s http://127.0.0.1:8500/api/prompts/catalog | head
```

Expected: `catalogVersion>=1` 且含 routing-rule 与 system-prompt。

- [ ] **Step 4: Commit**

```bash
git commit -am "feat(prompt-manager): catalog API and nacos prompt seed import"
```

---

## Task T5: validate + dry-run API

**Files:**
- Create: `prompt-manager/.../controller/PromptRoutingController.java`
- Create: `prompt-manager/.../service/PromptRoutingSupport.java`（Def → RoutingRuleDef）
- Create: `prompt-manager/src/test/java/.../RoutingValidateTest.java`

- [ ] **Step 1: 单测冲突与试跑**

```java
@Test
void dryRunHitsStructural() {
  // 加载与 T0 相同的 structural+regex defs
  RoutingDryRunResult r = engine.dryRun("先检索制度再分析报销合规");
  assertEquals("structural-plan", r.matchedRuleId());
  assertFalse(r.wouldLlm());
}
```

- [ ] **Step 2: API**

```
POST /api/prompts/routing/validate  body: { rules?: [...] }  # 缺省用 DB enabled rules
POST /api/prompts/routing/dry-run   body: { query: "...", includeL0Hints?: false }
```

响应：`warnings[]` / `{ stage, matchedRuleId, plan, wouldLlm }`。首期 **不** 解析 `#/$/@`（`includeL0Hints` 预留 false）。

- [ ] **Step 3: Commit**

```bash
git commit -am "feat(prompt-manager): routing validate and dry-run APIs"
```

---

## Task T6: orchestrator Catalog 客户端与 Snapshot

**Files:**
- Modify: `orchestrator/pom.xml`（依赖 `sunshine-routing`）
- Create: `orchestrator/.../client/PromptCatalogClient.java`
- Create: `orchestrator/.../prompt/PromptCatalogSnapshot.java`
- Create: `orchestrator/.../prompt/PromptCatalogHolder.java`
- Create: `orchestrator/.../prompt/PromptCatalogRefreshScheduler.java`
- Create: `orchestrator/src/test/java/.../PromptCatalogHolderTest.java`
- Modify: Nacos `sunshine-orchestrator.yaml` 增加 `prompt-manager` 服务发现名/刷新间隔（**非**提示词正文）

- [ ] **Step 1: Holder 行为单测**

- 首次 `replace(snapshot)` 成功  
- `refresh` 抛错时 **保留旧 snapshot**  
- 启动时 snapshot==null → `IllegalStateException`（供 fail-fast）

- [ ] **Step 2: Client**

对齐 `SkillCatalogClient`：WebClient → `http://sunshine-prompt/api/prompts/catalog`（经 LoadBalancer）。解析 entries → `PromptCatalogSnapshot`（按 kind 索引 + `List<RoutingRuleDef> routingRules()` + `String text(id)`）。

- [ ] **Step 3: Scheduler**

启动 `@PostConstruct` 同步拉取（失败则抛，阻止 ready）；其后每 N 秒比对 `catalogVersion`，变化则全量替换。

- [ ] **Step 4: Commit**

```bash
git commit -am "feat(orchestrator): PromptCatalogClient snapshot and hot refresh"
```

---

## Task T7: 切换 Policy Chain 到 UnifiedRuleEngine

**Files:**
- Create: `orchestrator/.../routing/policy/UnifiedRuleRoutingPolicy.java`
- Modify: `RoutingPolicyChain` 注册（若自动收集 `@Component` 则靠 order）
- Delete 或停用: `StructuralRoutingPolicy`、`PeerStructuralRoutingPolicy`、`GoldenRuleRoutingPolicy`
- Delete/停用: `RuleBasedRouter`、`StructuralPlanMatcher`、`PeerPatternMatcher` 对 Nacos Properties 的依赖（逻辑已在 sunshine-routing）
- Modify: `RoutingRuleProperties` — **删除** structural/rules/peer 绑定（或整类删除）
- Modify: `docs/nacos/sunshine-orchestrator.yaml` — 删除 `agent.routing.structural/peer/rules` 正文（留注释指向 Catalog）
- Modify: `docs/routing/routing-golden-set.md` — 配置源改为 Catalog
- Modify: 相关单测改用 Snapshot fixture

- [ ] **Step 1: 实现 Policy**

```java
@Component
@RequiredArgsConstructor
public class UnifiedRuleRoutingPolicy implements RoutingPolicy {
    private final PromptCatalogHolder holder;
    @Override public int order() { return 10; }
    @Override
    public Mono<Optional<ExecutionPlan>> tryRoute(RoutingContext ctx) {
        return Mono.fromCallable(() -> {
            var engine = new UnifiedRuleEngine(holder.snapshot().routingRules());
            return engine.match(ctx.userMessage()).map(hit -> toExecutionPlan(hit));
        });
    }
}
```

`toExecutionPlan`：mode 解析同现 `RuleBasedRouter.parseMode`；`reason` = `rule:{id}`。

- [ ] **Step 2: 跑 golden-set**

```bash
mvn -pl orchestrator test -Dtest=RoutingGoldenSetTest,StructuralPlanMatcherTest,RuleBasedRouterTest,ExecutionPlanRouterTest -q
```

Expected：`RoutingGoldenSetTest` PASS；旧 Matcher/Router 单测删除或改为 `UnifiedRuleEngineTest` 委托。

- [ ] **Step 3: 删除 Nacos 路由段 + sync**

```bash
python scripts/sync_nacos.py
# 重启 orchestrator + prompt-manager
```

- [ ] **Step 4: Commit**

```bash
git commit -am "feat(orchestrator): replace L1/L2 policies with UnifiedRuleRoutingPolicy"
```

---

## Task T8: PromptComposer / ReAct 切 Snapshot

**Files:**
- Modify: `PromptComposer.java`、`PromptOverlayProperties.java`（删除或改为从 Holder 读）
- Modify: `ReActAgentFactory` / `AgentPromptProperties`（system-prompt 从 Catalog `system-prompt`）
- Create: fragment 拼接辅助 `ReactOverlayAssembler.java`
- Modify: `PromptComposerTest.java`

- [ ] **Step 1: 失败单测 — fragment 顺序**

```java
@Test
void appendsEnabledFragmentsInSortOrder() {
    // snapshot: mode-overlay.react = "BASE"
    // fragments attachTo=mode-overlay.react sortOrder 2 then 1 → "BASE\nF1\nF2"
}
```

- [ ] **Step 2: 实现**

`composeReactInputs`：mode overlay 文本 = `text("mode-overlay.react") + join(fragments)`；`react-restart` / hitl / scope / memory 同理从 id 读取。  
**删除** Nacos `agent.prompt.mode-overlays` 等已迁键绑定。

- [ ] **Step 3: 测试 + Commit**

```bash
mvn -pl orchestrator test -Dtest=PromptComposerTest -q
git commit -am "feat(orchestrator): PromptComposer reads Catalog snapshot with react fragments"
```

---

## Task T9: Intent / Timeline / Rewrite / Planner / Answer 切 Snapshot

**Files:**
- Modify: `IntentRouter`、timeline 文案装配类、`QueryRewriteService`（orchestrator 侧）、Planner prompt 读取、`PlanAnswerPromptAssembler`
- 删除对应 `@ConfigurationProperties` 字段
- 清理 `sunshine-orchestrator.yaml` 已迁键（保留与提示词无关配置）
- 更新受影响单测 fixture

- [ ] **Step 1: 逐个替换读取点为 `holder.snapshot().text(id)` / `json(id)`**

映射表：

| 原 Nacos | Catalog id |
|----------|------------|
| `agent.intent.classifier-prompt` | `intent.classifier` |
| `agent.planner.prompt` | `planner.prompt` |
| `agent.prompt.answer-template` | `answer.template` |
| `agent.timeline.intent` | `timeline.intent` |
| `agent.timeline.steps.*` | `timeline.steps.{name}` |
| `agent.rewrite.intent/planner` | `rewrite.intent` / `rewrite.planner` |

- [ ] **Step 2: 回归单测**

```bash
mvn -pl orchestrator test -q
```

- [ ] **Step 3: Commit**

```bash
git commit -am "feat(orchestrator): migrate intent timeline rewrite planner prompts to Catalog"
```

---

## Task T10: BFF 透传

**Files:**
- Create: `bff/.../client/PromptManagerClient.java`
- Create: `bff/.../controller/PromptsController.java`
- Modify: BFF 路由/网关配置（若按服务名发现 `sunshine-prompt`）

- [ ] **Step 1: 透传与 SkillsController 同构**

对外仍 `/api/prompts/**`；校验登录（现有 Sa-Token 拦截）。

- [ ] **Step 2: curl 经 BFF**

```bash
curl -s -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8100/api/prompts | head
```

- [ ] **Step 3: Commit**

```bash
git commit -am "feat(bff): proxy prompt-manager admin and catalog APIs"
```

---

## Task T11: 前端 `/prompts` 壳（全部视图）

**Files:**
- Create: `sunshine-ui/src/api/prompts.ts`
- Create: `sunshine-ui/src/composables/usePromptsPage.ts`
- Create: `sunshine-ui/src/views/PromptsView.vue`
- Create: `sunshine-ui/src/components/prompts/PromptsListPanel.vue`
- Create: `sunshine-ui/src/components/prompts/PromptDetailPanel.vue`
- Modify: `sunshine-ui/src/router/index.ts`
- Modify: `sunshine-ui/src/layouts/MainLayout.vue`

- [ ] **Step 1: API 类型与方法**（list/get/create/update/versions/publish/rollback/enable）

- [ ] **Step 2: 页面同构 Experts**

- 顶栏 Tabs：`全部 | 路由规则 | ReAct 拼装`（本 Task 只实现「全部」可编辑 text/json + 发布）  
- 左列表按 kind 分组；右栏版本条 + textarea + 发布/回滚  
- 视觉：`--sun-black`、`sun-field`

- [ ] **Step 3: 路由与侧栏**

```ts
{ path: 'prompts', name: 'prompts', component: () => import('../views/PromptsView.vue') }
```

`FILL_CONTENT_ROUTES` 加 `prompts`；平台菜单「提示词」图标可用 `DocumentTextOutline`。

- [ ] **Step 4: 类型检查**

```bash
cd sunshine-ui && npx vue-tsc -b
```

- [ ] **Step 5: Commit**

```bash
git commit -am "feat(ui): add /prompts catalog shell aligned with Experts"
```

---

## Task T12: 路由规则视图 + ReAct 拼装视图

**Files:**
- Create: `sunshine-ui/src/components/prompts/RoutingRuleEditor.vue`
- Create: `sunshine-ui/src/components/prompts/RoutingDryRunPanel.vue`
- Create: `sunshine-ui/src/components/prompts/ReactComposePanel.vue`
- Modify: `PromptsView.vue` / `usePromptsPage.ts`
- Modify: `api/prompts.ts`（validate/dry-run）

- [ ] **Step 1: 路由规则编辑器**

结构化表单字段对齐 `content_json`；保存前调 `validate`，有 warnings 黄条仍可保存；priority 可改。

- [ ] **Step 2: 试跑面板**

输入框 + 按钮 → 展示 `stage` / `matchedRuleId` / `wouldLlm`。

- [ ] **Step 3: ReAct 面板**

只读层顺序；编辑 `mode-overlay.react` / `react-restart`；列表 fragments（启停、sortOrder、正文）。无 fragment 时仍可只编整段 overlay。

- [ ] **Step 4: vue-tsc + Commit**

```bash
cd sunshine-ui && npx vue-tsc -b
git commit -am "feat(ui): prompts routing dry-run and react compose panels"
```

---

## Task T13: Live 脚本与文档收口

**Files:**
- Create: `scripts/verify_prompt_catalog_live.py`
- Modify: `docs/routing/routing-golden-set.md`（配置变更指引）
- Modify: `CLAUDE.md`（运维表加脚本；Nacos 提示词 SSOT 说明改 Catalog）
- Modify: `docs/implementation-plan.md` 4.11 状态
- Modify: `docs/nacos/sunshine-orchestrator.yaml` 顶部注释「提示词/规则已迁 prompt-manager」
- Modify: spec 状态 → 实施中/完成（收口时）

- [x] **Step 1: Live 脚本检查门**

| 门 | 断言 |
|----|------|
| P1 | `GET catalog` 含 routing-rule 与 system-prompt |
| P2 | dry-run 样例命中 `structural-plan` 或已知 regex |
| P3 | 调低/调高某 regex priority → 刷新后 dry-run 命中变化 |
| P4 | rollback active_version → 行为恢复 |
| P5 | orchestrator 热更新后（等 refresh）新请求生效 |

```bash
python scripts/verify_prompt_catalog_live.py
```

Expected: 全 PASS。

- [x] **Step 2: 文档与 CLAUDE 运维表**

- [x] **Step 3: Commit**

```bash
git commit -am "test(4.11): prompt catalog live gates and docs cutover"
```

---

## Spec 覆盖对照（自审）

| Spec 项 | Task |
|---------|------|
| D2 DB SSOT 无 Nacos 规则/提示词 | T0–T4, T7, T9 |
| D3 本地缓存热更新 | T6 |
| D4 冲突+试跑 | T1, T5, T12 |
| D5 ReAct 层+fragment | T8, T12 |
| D6/D7 固定链+统一引擎 | T1, T7 |
| D8 draft/published/回滚 | T3 |
| D10 fail-fast / 保留旧 Snapshot | T6 |
| `/prompts` 三视图 | T11, T12 |
| Catalog 全 kind 壳 | T4, T11 |
| golden-set | T7 |
| Live | T13 |
| BFF | T10 |

**不做（本计划外）:** 链拖拽、dry-run 真 LLM、审核流、Skill/Expert/Workflow 节点迁入、Nacos 双写。

---

## 执行交接

Plan complete and saved to `docs/superpowers/plans/2026-07-20-prompt-ops-routing-catalog.md`.

**两种执行方式：**

1. **Subagent-Driven（推荐）** — 每任务新开子代理，任务间复查，迭代快  
2. **Inline Execution** — 本会话按 executing-plans 批量推进并设检查点  

选哪种？
