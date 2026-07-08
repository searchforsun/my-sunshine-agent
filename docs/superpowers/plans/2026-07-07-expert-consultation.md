# 多专家协作（Expert Consultation）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 peer-collab 从 Nacos 固定 `peer.templates` + 压缩 Timeline + 仲裁 ReAct，演进为 **expert-manager Catalog** 驱动的对等 MsgHub 协作；L0 `$` 绑定、`ConsultationSynthesizer` 终态汇总、按发言逐步 Timeline。

**Architecture:** 新建 `expert-manager :8235`（对称 skill-manager）托管 Expert CRUD + Catalog；orchestrator 扩展 L0 Policy Chain（`#` > `$` > `@`）、`ExpertCoordinatorService` 选人、`ExpertHubEngine` 反应式 MsgHub、逐步 `expert-{id}-s{seq}` Timeline 步；Hub 结束后 `ConsultationSynthesizer` 流式写 `message.content`（无 `generate` Timeline 步）。前端 `/experts` + Chat `$` 补全 + `ExpertStepPanel`。

**Tech Stack:** JDK 21 · Spring Boot 3.2 · JPA · MySQL `sunshine_expert` · Nacos · AgentScope MsgHub · Vue3/Naive UI · Gateway/BFF 透传

**设计 SSOT:** [2026-07-07-expert-consultation-design.md](../specs/2026-07-07-expert-consultation-design.md) · L0 优先级与 `#` 对称：[workflow-studio-design.md](../specs/2026-06-25-workflow-studio-design.md) §3 · 旧 peer 基线：[peer-collab-routing-design.md](../specs/2026-06-24-peer-collab-routing-design.md)

**前置条件:** 设计文档 §13 检查门评审通过后再写业务代码；`executionPreference: peer-collab` 强制路由（本会话已合入 orchestrator）须在本计划 Task 15 一并改为 expert roster 语义。

---

## 文件结构（边界锁定）

| 区域 | 创建 | 修改 | 测试 |
|------|------|------|------|
| **DB** | `docker/mysql/init/15-sunshine-expert-manager.sql` | `docker/mysql/init/01-init-databases.sql` | 手测 `mysql` |
| **expert-manager** | `expert-manager/**`（Application、entity、repo、service、controller、dto） | `pom.xml`（root module） | `ExpertAdminServiceTest` |
| **Nacos/Ops** | `docs/nacos/sunshine-expert-manager.yaml` | `sunshine-orchestrator.yaml`、`sunshine-gateway.yaml`、`sync_nacos.py`、`start.py` | health 200 |
| **L0 路由** | `WorkflowBindingParser`、`WorkflowBindingRoutingPolicy`、`ExpertBindingParser`、`ExpertBindingRoutingPolicy`、`ExpertCollaborationParams` | `ExecutionPlanRouter`、`SkillBindingRoutingPolicy`（仅 order 注释） | `WorkflowBindingParserTest`、`ExpertBindingParserTest`、`RoutingGoldenSetTest` §K |
| **Catalog/Coord** | `ExpertCatalogClient`、`ExpertCoordinatorService` | `OrchestratorErrorCode` | `ExpertCoordinatorServiceTest` |
| **执行引擎** | `ExpertHubEngine`、`ExpertTimelineSupport`、`ConsultationSynthesizer` | `PeerCollaborationExecutor`→`ExpertConsultationExecutor`、`PeerRoundEngine`（删除或内联废弃）、`ExecutionDispatcher` | `ExpertHubEngineTest`、`ConsultationSynthesizerTest` |
| **Timeline** | — | `TimelineStepId`、`IntentLabelService`、`docs/nacos` `agent.timeline.steps` | `ProcessingTimelineSessionTest` |
| **前端** | `api/experts.ts`、`views/ExpertsView.vue`、`composables/useChatExpertMention.ts`、`components/operation/ExpertStepPanel.vue`、`ExpertConvenePanel.vue` | `OperationStack.vue`、`ChatView.vue`、`router`、`MainLayout` | `npx vue-tsc -b` |
| **验收** | `scripts/verify_expert_consultation_live.py` | `docs/routing/routing-golden-set.md` §K、`CLAUDE.md` | live 脚本 PASS |

---

## 迭代排期

```
迭代 0（P0 底座）     T0 → T1 → T2 → T3 → T4 → T5    expert-manager + MySQL + 运维接线
迭代 1（P0 L0）        T6 → T7 → T8                   # / $ / @ 优先级 + 单测
迭代 2（P0 编排）      T9 → T10                        Catalog Client + Coordinator
迭代 3（P0 引擎）      T11 → T12 → T13 → T14          Hub + Timeline + Synthesizer + Executor
迭代 4（P0 迁移）      T15 → T16                       废弃 peer.templates + Nacos 文案
迭代 5（P1 前端）      T17 → T18 → T19                 /experts + $ 补全 + Timeline UI
迭代 6（P0 验收）      T20 → T21                       live 脚本 + 文档索引
```

---

## Task T0: MySQL `sunshine_expert` 库表

**Files:**
- Create: `docker/mysql/init/15-sunshine-expert-manager.sql`
- Modify: `docker/mysql/init/01-init-databases.sql`

- [ ] **Step 1: 建库**

在 `01-init-databases.sql` 末尾追加：

```sql
CREATE DATABASE IF NOT EXISTS sunshine_expert DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

- [ ] **Step 2: 表结构 + 种子**

创建 `15-sunshine-expert-manager.sql`：

```sql
-- sunshine-expert-manager（expert-manager :8235）
USE sunshine_expert;

CREATE TABLE expert_definition (
    id              VARCHAR(64) PRIMARY KEY,
    display_name    VARCHAR(128) NOT NULL,
    description     VARCHAR(512),
    system_prompt   MEDIUMTEXT NOT NULL,
    enabled         TINYINT(1) NOT NULL DEFAULT 1,
    tags_json       VARCHAR(512) NOT NULL DEFAULT '[]',
    tools_json      VARCHAR(512) NOT NULL DEFAULT '["*"]',
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE expert_skill_link (
    expert_id       VARCHAR(64) NOT NULL,
    skill_id        VARCHAR(64) NOT NULL,
    PRIMARY KEY (expert_id, skill_id),
    CONSTRAINT fk_expert_skill_def FOREIGN KEY (expert_id) REFERENCES expert_definition (id)
);

INSERT INTO expert_definition (id, display_name, description, system_prompt, enabled, tags_json) VALUES
('policy-expert', '制度专家', '企业制度检索与条款解读', '你是制度专家。仅基于检索到的制度材料做专业分析；可质疑其他专家观点，但不对用户直接致辞。', 1, '["knowledge"]'),
('finance-expert', '财务专家', '待审批单据与财务合规分析', '你是财务专家。基于待办/单据材料做合规分析；可回应制度专家的质疑。', 1, '["finance"]');

INSERT INTO expert_skill_link (expert_id, skill_id) VALUES
('policy-expert', 'policy-review'),
('finance-expert', 'finance-analysis');
```

- [ ] **Step 3: 应用 SQL（ecs4c16g 已有 MySQL 时手动执行）**

```bash
mysql -h ecs4c16g -uroot -p < docker/mysql/init/01-init-databases.sql
mysql -h ecs4c16g -uroot -p < docker/mysql/init/15-sunshine-expert-manager.sql
```

Expected: `USE sunshine_expert; SHOW TABLES;` 返回 `expert_definition`、`expert_skill_link`；种子 2 行。

- [ ] **Step 4: Commit**

```bash
git add docker/mysql/init/01-init-databases.sql docker/mysql/init/15-sunshine-expert-manager.sql
git commit -m "feat(expert): add sunshine_expert schema and seed experts"
```

---

## Task T1: expert-manager Maven 模块骨架

**Files:**
- Create: `expert-manager/pom.xml`
- Create: `expert-manager/src/main/java/com/sunshine/expert/ExpertManagerApplication.java`
- Create: `expert-manager/src/main/resources/application.yml`
- Modify: `pom.xml`（root `<modules>`）

- [ ] **Step 1: 根 pom 增加 module**

```xml
<module>expert-manager</module>
```

- [ ] **Step 2: expert-manager/pom.xml**（复制 skill-manager，改 artifactId）

```xml
<artifactId>sunshine-expert-manager</artifactId>
```

依赖保留：`sunshine-common`、`spring-boot-starter-web`、`spring-boot-starter-data-jpa`、`mysql-connector-j`、Nacos discovery/config、test。**不**引入 MinIO（MVP 无包文件）。

- [ ] **Step 3: Application + bootstrap**

`ExpertManagerApplication.java`:

```java
package com.sunshine.expert;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ExpertManagerApplication {
    public static void main(String[] args) {
        SpringApplication.run(ExpertManagerApplication.class, args);
    }
}
```

`application.yml`:

```yaml
spring:
  application:
    name: sunshine-expert-manager
  config:
    import: optional:nacos:sunshine-expert-manager.yaml
```

- [ ] **Step 4: 编译**

```bash
mvn -pl expert-manager -am compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add pom.xml expert-manager/
git commit -m "feat(expert): scaffold expert-manager module"
```

---

## Task T2: Entity + Repository

**Files:**
- Create: `expert-manager/src/main/java/com/sunshine/expert/entity/ExpertDefinitionEntity.java`
- Create: `expert-manager/src/main/java/com/sunshine/expert/entity/ExpertSkillLinkEntity.java`
- Create: `expert-manager/src/main/java/com/sunshine/expert/entity/ExpertSkillLinkId.java`
- Create: `expert-manager/src/main/java/com/sunshine/expert/repo/ExpertDefinitionRepository.java`
- Create: `expert-manager/src/main/java/com/sunshine/expert/repo/ExpertSkillLinkRepository.java`
- Test: `expert-manager/src/test/java/com/sunshine/expert/repo/ExpertDefinitionRepositoryTest.java`

- [ ] **Step 1: 写失败单测**

```java
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ExpertDefinitionRepositoryTest {
    @Autowired ExpertDefinitionRepository repo;
    @Test
    void findById_returnsSeed() {
        assertThat(repo.findById("policy-expert")).isPresent();
    }
}
```

- [ ] **Step 2: 运行确认失败**

```bash
mvn test -pl expert-manager -Dtest=ExpertDefinitionRepositoryTest -q
```

Expected: FAIL（类/表不存在或测试库未配置 — 属预期）

- [ ] **Step 3: 实现 Entity/Repo**

`ExpertDefinitionEntity` 字段对齐 SQL；`ExpertSkillLinkEntity` 用 `@EmbeddedId ExpertSkillLinkId`。

- [ ] **Step 4: Nacos 数据源**（`docs/nacos/sunshine-expert-manager.yaml`，Task T5 会 sync；本地 test 可用 H2 或 Testcontainers — **优先** `@DataJpaTest` + test `application.yml` 指向 `sunshine_expert`）

- [ ] **Step 5: 单测通过 + Commit**

```bash
mvn test -pl expert-manager -Dtest=ExpertDefinitionRepositoryTest
git add expert-manager/
git commit -m "feat(expert): JPA entities and repositories"
```

---

## Task T3: ExpertAdminService CRUD

**Files:**
- Create: `expert-manager/src/main/java/com/sunshine/expert/dto/*.java`（Create/Update/Enable/Catalog DTO）
- Create: `expert-manager/src/main/java/com/sunshine/expert/service/ExpertAdminService.java`
- Create: `expert-manager/src/main/java/com/sunshine/expert/exception/ExpertErrorCode.java`
- Test: `expert-manager/src/test/java/com/sunshine/expert/service/ExpertAdminServiceTest.java`

- [ ] **Step 1: 失败单测 — 创建专家并关联 skill**

```java
@Test
void createExpert_withSkillLinks() {
    ExpertCreateRequest req = new ExpertCreateRequest(
            "legal-expert", "法务专家", "合同审查", "你是法务专家…", List.of("policy-review"));
    expertAdminService.create(req);
    ExpertCatalogEntry entry = expertAdminService.findCatalogEntry("legal-expert").orElseThrow();
    assertThat(entry.skillIds()).containsExactly("policy-review");
}
```

- [ ] **Step 2: 实现 `ExpertAdminService`**

方法：`create`、`update`、`setEnabled`、`listCatalogIndex`、`findCatalogEntry`、`listAdmin`（分页可 YAGNI 全量）。`skillIds` 全量替换 `expert_skill_link`。ID 冲突 → `ExpertErrorCode.EXPERT_ALREADY_EXISTS`；未知 skill → `SKILL_LINK_INVALID`（调 skill-manager `GET /api/skills/{id}/catalog` 或 MVP 仅校验非空字符串）。

- [ ] **Step 3: 单测通过**

```bash
mvn test -pl expert-manager -Dtest=ExpertAdminServiceTest
```

- [ ] **Step 4: Commit**

```bash
git commit -m "feat(expert): admin service CRUD and skill links"
```

---

## Task T4: Catalog + Admin HTTP API

**Files:**
- Create: `expert-manager/src/main/java/com/sunshine/expert/controller/ExpertCatalogController.java`
- Create: `expert-manager/src/main/java/com/sunshine/expert/controller/ExpertAdminController.java`
- Test: `expert-manager/src/test/java/com/sunshine/expert/controller/ExpertCatalogControllerTest.java`

- [ ] **Step 1: 失败 WebMvc 测试**

```java
@WebMvcTest(ExpertCatalogController.class)
class ExpertCatalogControllerTest {
    @MockBean ExpertAdminService adminService;
    @Autowired MockMvc mvc;
    @Test
    void catalogIndex_returnsEnabledExperts() throws Exception {
        when(adminService.listCatalogIndex()).thenReturn(List.of(
                new ExpertCatalogIndexEntry("policy-expert", "制度专家", "企业制度…")));
        mvc.perform(get("/api/experts/catalog/index"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value("policy-expert"));
    }
}
```

- [ ] **Step 2: 实现 Controller**

| 方法 | 路径 |
|------|------|
| GET | `/api/experts/catalog/index` |
| GET | `/api/experts/{id}/catalog` |
| GET/POST/PUT/PATCH | `/api/experts/admin/**`（对称 `/api/skills/admin`） |

- [ ] **Step 3: 单测 PASS + Commit**

```bash
mvn test -pl expert-manager -Dtest=ExpertCatalogControllerTest
git commit -m "feat(expert): catalog and admin REST API"
```

---

## Task T5: Nacos · Gateway · start.py 接线

**Files:**
- Create: `docs/nacos/sunshine-expert-manager.yaml`
- Modify: `docs/nacos/sunshine-gateway.yaml`、`docs/nacos/sunshine-orchestrator.yaml`、`scripts/sync_nacos.py`、`scripts/start.py`

- [ ] **Step 1: Nacos 配置**

`sunshine-expert-manager.yaml`:

```yaml
server:
  port: 8235
spring:
  datasource:
    url: jdbc:mysql://ecs4c16g:3306/sunshine_expert?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai
    username: root
    password: ${MYSQL_PASSWORD}
  jpa:
    hibernate:
      ddl-auto: none
```

`sunshine-orchestrator.yaml` 追加：

```yaml
expert-manager:
  base-url: http://localhost:8235
```

Gateway 增加 `/health/expert-manager` → `lb://sunshine-expert-manager`（对称 skill-manager）。

`sync_nacos.py` DATA_IDS 增加 `sunshine-expert-manager.yaml`。

`start.py` SERVICES 元组增加：

```python
("expert-manager", "expert-manager", "sunshine-expert-manager", 8235),
```

插在 workflow-manager 之后、orchestrator 之前。

- [ ] **Step 2: sync + 启动验证**

```bash
python scripts/sync_nacos.py
mvn -pl expert-manager -am package -DskipTests -q
python scripts/start.py --only expert-manager
curl -s http://127.0.0.1:8235/health | jq .
curl -s http://127.0.0.1:8235/api/experts/catalog/index | jq '.data | length'
```

Expected: health `UP`；catalog index ≥ 2

- [ ] **Step 3: Commit**

```bash
git add docs/nacos/ scripts/
git commit -m "chore(expert): wire expert-manager into nacos gateway and start"
```

---

## Task T6: WorkflowBindingParser + WorkflowBindingRoutingPolicy（L0 `#`）

**Files:**
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/workflow/WorkflowBindingParser.java`
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/workflow/WorkflowBindingOutcome.java`
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/routing/policy/WorkflowBindingRoutingPolicy.java`
- Test: `orchestrator/src/test/java/com/sunshine/orchestrator/workflow/WorkflowBindingParserTest.java`

- [ ] **Step 1: 失败单测**

```java
@Test
void hashMention_bindsWorkflow() {
    when(workflowCatalog.isKnownWorkflow("knowledge-qa")).thenReturn(true);
    WorkflowBindingOutcome o = parser.parse("#knowledge-qa 年假可以请几天");
    assertThat(o.bound()).isTrue();
    assertThat(o.workflowId()).isEqualTo("knowledge-qa");
    assertThat(o.effectiveQuery()).isEqualTo("年假可以请几天");
}
```

- [ ] **Step 2: 实现 Parser**

```java
private static final Pattern HASH_PATTERN = Pattern.compile(
        "^#([\\w\\u4e00-\\u9fff-]+)(?:\\s+(.*)|\\s*)$", Pattern.DOTALL);
```

`resolveWorkflowId` → 注入 `WorkflowCatalog.isKnownWorkflow`；未知 → `WorkflowBindingOutcome.unknown`。

- [ ] **Step 3: RoutingPolicy order = -20**

```java
@Override
public int order() { return -20; }

@Override
public Mono<Optional<ExecutionPlan>> tryRoute(RoutingContext ctx) {
    WorkflowBindingOutcome binding = workflowBindingParser.parse(ctx.userMessage());
    if (binding.unknown()) {
        return Mono.error(new BizException(OrchestratorErrorCode.WORKFLOW_NOT_FOUND));
    }
    if (!binding.bound()) {
        return Mono.just(Optional.empty());
    }
    Map<String, String> params = Map.of("effectiveQuery", binding.effectiveQuery());
    return Mono.just(Optional.of(new ExecutionPlan(
            ExecutionMode.WORKFLOW, binding.workflowId(), params, "workflow:#mention")));
}
```

- [ ] **Step 4: 注册到 `ExecutionPlanRouter` policy 列表（`-20` 最先）**

- [ ] **Step 5: 单测 + golden §I1 本地跑**

```bash
mvn test -pl orchestrator -Dtest=WorkflowBindingParserTest
```

- [ ] **Step 6: Commit**

```bash
git commit -m "feat(routing): L0 workflow # binding policy"
```

---

## Task T7: ExpertBindingParser + ExpertBindingRoutingPolicy（L0 `$`）

**Files:**
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/expert/ExpertBindingParser.java`
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/expert/ExpertBindingOutcome.java`
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/expert/ExpertCollaborationParams.java`
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/routing/policy/ExpertBindingRoutingPolicy.java`
- Test: `orchestrator/src/test/java/com/sunshine/orchestrator/expert/ExpertBindingParserTest.java`

- [ ] **Step 1: 失败单测**

```java
@Test
void dollarMention_bindsMultipleExperts() {
    when(expertCatalogClient.isKnownExpert("policy-expert")).thenReturn(true);
    when(expertCatalogClient.isKnownExpert("finance-expert")).thenReturn(true);
    ExpertBindingOutcome o = parser.parse("$policy-expert $finance-expert 是否合规");
    assertThat(o.bound()).isTrue();
    assertThat(o.expertIds()).containsExactly("policy-expert", "finance-expert");
    assertThat(o.effectiveQuery()).isEqualTo("是否合规");
}
```

- [ ] **Step 2: Parser 实现**

对称 `SkillBindingParser`：

```java
private static final Pattern DOLLAR_PATTERN = Pattern.compile(
        "^\\$([\\w\\u4e00-\\u9fff-]+)(?:\\s+(.*)|\\s*)$", Pattern.DOTALL);
private static final Pattern INLINE_DOLLAR = Pattern.compile(
        "\\$([\\w\\u4e00-\\u9fff-]+)(?=[\\s，。！？,.!?;；：:]|$)");
```

收集全部 `$id`（去重、保序）；任一 unknown → `EXPERT_NOT_FOUND`；`stripExpertMentions` 生成 effectiveQuery。

`ExpertCollaborationParams`:

```java
public static final String EXPERT_IDS = "expertIds";       // 逗号分隔
public static final String EFFECTIVE_QUERY = "effectiveQuery";
public static final String COORDINATOR_REASON = "coordinatorReason";
```

- [ ] **Step 3: RoutingPolicy order = -10**

若 `ctx.userMessage().strip().startsWith("#")` → `Optional.empty()`（让 Workflow 已处理；双保险）。

产出：

```java
ExecutionPlan(ExecutionMode.PEER_COLLAB, null, params, "expert:$mention")
```

`params`: `expertIds=policy-expert,finance-expert`、`effectiveQuery=…`

- [ ] **Step 4: 单测 PASS**

```bash
mvn test -pl orchestrator -Dtest=ExpertBindingParserTest
```

- [ ] **Step 5: Commit**

```bash
git commit -m "feat(routing): L0 expert $ binding policy"
```

---

## Task T8: L0 优先级集成测试 + golden §K 骨架

**Files:**
- Modify: `orchestrator/src/test/java/com/sunshine/orchestrator/routing/RoutingGoldenSetTest.java`
- Modify: `docs/routing/routing-golden-set.md`（新增 §K 表）
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/exception/OrchestratorErrorCode.java`（`EXPERT_NOT_FOUND`、`WORKFLOW_NOT_FOUND` 若缺）

- [ ] **Step 1: 写失败 golden 用例 K1–K6**

```java
@Test
void k1_dollarExperts_routePeerCollab() {
  when(expertBindingParser.parse("$policy-expert $finance-expert 是否合规"))
      .thenReturn(ExpertBindingOutcome.bound(List.of("policy-expert","finance-expert"), "是否合规"));
  ExecutionPlan plan = router.route(ctx("$policy-expert $finance-expert 是否合规")).block();
  assertThat(plan.mode()).isEqualTo(ExecutionMode.PEER_COLLAB);
  assertThat(plan.params()).containsEntry(ExpertCollaborationParams.EXPERT_IDS, "policy-expert,finance-expert");
}

@Test
void k2_hashBeatsDollar() {
  ExecutionPlan plan = router.route(ctx("#finance-smart $policy-expert 是否合规")).block();
  assertThat(plan.mode()).isEqualTo(ExecutionMode.WORKFLOW);
  assertThat(plan.workflowId()).isEqualTo("finance-smart");
}

@Test
void k3_dollarBeatsAtSkill() {
  ExecutionPlan plan = router.route(ctx("$policy-expert @finance-analysis 是否合规")).block();
  assertThat(plan.mode()).isEqualTo(ExecutionMode.PEER_COLLAB);
}
```

- [ ] **Step 2: 跑测试至 PASS**

```bash
mvn test -pl orchestrator -Dtest=RoutingGoldenSetTest#k1_dollarExperts_routePeerCollab
mvn test -pl orchestrator -Dtest=RoutingGoldenSetTest
```

- [ ] **Step 3: 文档 §K**（6 行用例表，对齐 spec D6）

- [ ] **Step 4: Commit**

```bash
git add orchestrator/ docs/routing/routing-golden-set.md
git commit -m "test(routing): golden set section K for expert L0 binding"
```

---

## Task T9: ExpertCatalogClient（orchestrator）

**Files:**
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/client/ExpertCatalogClient.java`
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/catalog/ExpertCatalogService.java`
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/catalog/ExpertCatalogEntry.java`
- Test: `orchestrator/src/test/java/com/sunshine/orchestrator/catalog/ExpertCatalogServiceTest.java`

- [ ] **Step 1: 失败单测（MockWebServer）**

```java
@Test
void loadIndexCachesExperts() {
    server.enqueue(new MockResponse().setBody("""
        {"code":200,"data":[{"id":"policy-expert","displayName":"制度专家"}]}"""));
    List<ExpertCatalogIndexEntry> list = service.listIndex();
    assertThat(list).extracting(ExpertCatalogIndexEntry::id).contains("policy-expert");
}
```

- [ ] **Step 2: 实现**（对称 `SkillCatalogService`：`GET {base}/api/experts/catalog/index`、`GET .../{id}/catalog`；内存缓存 + 启动预热）

- [ ] **Step 3: `ExpertBindingParser` 改注入 `ExpertCatalogService`**

- [ ] **Step 4: PASS + Commit**

```bash
mvn test -pl orchestrator -Dtest=ExpertCatalogServiceTest
git commit -m "feat(orchestrator): expert catalog client"
```

---

## Task T10: ExpertCoordinatorService

**Files:**
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/expert/ExpertCoordinatorService.java`
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/expert/ExpertRoster.java`
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/config/ExpertCoordinatorProperties.java`
- Modify: `docs/nacos/sunshine-orchestrator.yaml`（`agent.expert.coordinator-prompt`）
- Test: `orchestrator/src/test/java/com/sunshine/orchestrator/expert/ExpertCoordinatorServiceTest.java`

- [ ] **Step 1: 失败单测 — 显式 roster 直通**

```java
@Test
void explicitIds_skipLlm() {
    ExpertRoster roster = coordinator.resolve(List.of("policy-expert", "finance-expert"), "是否合规", null);
    assertThat(roster.expertIds()).containsExactly("policy-expert", "finance-expert");
    assertThat(roster.reason()).isNull();
}
```

- [ ] **Step 2: 失败单测 — 无 `$` 时 LLM 选人（mock LlmGateway）**

返回 JSON：`{"expertIds":["policy-expert","finance-expert"],"reason":"制度+财务交叉"}`；校验 2–4 人、enabled、去重。

- [ ] **Step 3: 实现 `resolve(List<String> explicitIds, String query, String tenantId)`**

- `<2` enabled → `OrchestratorErrorCode.EXPERT_ROSTER_TOO_SMALL` 或降级单专家 ReAct（spec §8.1：MVP **抛 BizException** 提示用户 `$` 多选）

- [ ] **Step 4: Nacos prompt + sync**

- [ ] **Step 5: PASS + Commit**

```bash
mvn test -pl orchestrator -Dtest=ExpertCoordinatorServiceTest
git commit -m "feat(expert): coordinator selects 2-4 experts"
```

---

## Task T11: ExpertHubEngine（反应式 MsgHub）

**Files:**
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/expert/ExpertHubEngine.java`
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/expert/ExpertSpeakCallback.java`
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/expert/ExpertTranscriptEntry.java`
- Test: `orchestrator/src/test/java/com/sunshine/orchestrator/expert/ExpertHubEngineTest.java`

- [ ] **Step 1: 定义回调接口**

```java
@FunctionalInterface
public interface ExpertSpeakCallback {
    /** 每次专家发言前/后由引擎回调，用于 Timeline SSE */
    void onSpeak(ExpertTranscriptEntry entry, String lifecycle); // running | done
}
```

- [ ] **Step 2: 失败单测 — 2 专家各发言 1 次**

Mock `ReActAgentFactory` 返回固定文本；断言 `transcript.size() >= 2`；**无** moderator 分支。

- [ ] **Step 3: 实现 `ExpertHubEngine.run`**

输入：`List<ExpertCatalogEntry> roster`、`userQuery`、`maxRounds`（`agent.peer.max-rounds`）、`ExpertSpeakCallback`。

逻辑：

1. 为每位专家 `AgentRunRequest(SUB, …, skillId from link[0], systemOverlay=expert.systemPrompt)`
2. `MsgHub.builder().participants(all).enableAutoBroadcast(true)`
3. **反应式**：每轮由 Hub 内 agent 读历史后 `call`；**禁止**双重 `for (round) for (peer)` 固定轮转（删除 `PeerRoundEngine` 该结构）
4. MVP 可行实现：`RoundRobinScheduler` 仅作 **fallback** 当 agent 未主动发言 — 文档注释标明；优先尝试 `hub.listen` + 第一个 `call` 返回非空者（AgentScope API 以现有 `PeerRoundEngine` 可编译子集为准）
5. 每次发言 → `transcript` + `callback.onSpeak`

- [ ] **Step 4: PASS + Commit**

```bash
mvn test -pl orchestrator -Dtest=ExpertHubEngineTest
git commit -m "feat(expert): reactive MsgHub engine without moderator"
```

---

## Task T12: ExpertTimelineSupport（逐步 expert 步）

**Files:**
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/expert/ExpertTimelineSupport.java`
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/expert/ExpertStepLabels.java`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/processing/TimelineStepId.java`
- Modify: `docs/nacos/sunshine-orchestrator.yaml`（`agent.timeline.steps.expert-convene` / `expert`）
- Test: `orchestrator/src/test/java/com/sunshine/orchestrator/expert/ExpertTimelineSupportTest.java`

- [ ] **Step 1: 失败单测 — step id 格式**

```java
@Test
void speakStepId_usesExpertAndSeq() {
    ProcessingStep step = ExpertTimelineSupport.speakRunning(
            "policy-expert", "制度专家", 2, false);
    assertThat(step.id()).isEqualTo("expert-policy-expert-s2");
    assertThat(step.phase()).isEqualTo("expert");
}
```

- [ ] **Step 2: 实现 convene + speak running/complete**

- `expert-convene`：`phase=expert-convene`（或 `expert` 子类型，与前端约定一致）
- `active` vs `active-responding`：该专家本场 `speakSeq==1` → `active`；否则且 transcript 已有其他专家 → `active-responding`（文案读 Nacos）
- `metadata`: `expertId`, `displayName`, `speakSeq`（**不下发** round 字段）
- **禁止** `detail` 含「第 N 轮」

- [ ] **Step 3: 删除/废弃 `PeerTimelineSupport` 压缩单步**（`phase=peer-collab` 主行改为可选兼容 1 个 convene 步）

- [ ] **Step 4: PASS + sync nacos + Commit**

```bash
python scripts/sync_nacos.py --data-id sunshine-orchestrator.yaml
mvn test -pl orchestrator -Dtest=ExpertTimelineSupportTest
git commit -m "feat(timeline): per-expert speak steps for consultation"
```

---

## Task T13: ConsultationSynthesizer

**Files:**
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/expert/ConsultationSynthesizer.java`
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/config/PeerSynthesisProperties.java`
- Modify: `docs/nacos/sunshine-orchestrator.yaml`（`agent.peer.synthesis-prompt`）
- Test: `orchestrator/src/test/java/com/sunshine/orchestrator/expert/ConsultationSynthesizerTest.java`

- [ ] **Step 1: 失败单测 — 流式 token**

Mock `LlmStreamClient`；输入 2 条 transcript；断言 `Flux<StreamToken>` 含 `type=content` 且 **无** `type=step` phase=generate。

- [ ] **Step 2: 实现**

```java
public Flux<StreamToken> synthesize(String userQuery, List<ExpertTranscriptEntry> transcript, ExecutionStreamContext ctx) {
    String prompt = properties.getSynthesisPrompt()
            .replace("{userQuery}", userQuery)
            .replace("{transcript}", formatTranscript(transcript));
    return llmStreamClient.streamChat(systemPrompt, prompt, ctx)
            .map(StreamToken::content);
}
```

Nacos `agent.peer.synthesis-prompt` 模板（禁止 Java 硬编码正文）。

- [ ] **Step 3: PASS + Commit**

```bash
mvn test -pl orchestrator -Dtest=ConsultationSynthesizerTest
git commit -m "feat(expert): consultation synthesizer streams final answer"
```

---

## Task T14: ExpertConsultationExecutor 串联

**Files:**
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/execution/PeerCollaborationExecutor.java`（重命名为 `ExpertConsultationExecutor` 或保留类名改实现）
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/execution/ExecutionDispatcher.java`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/peer/PeerRunAuditService.java`（改读 `ExpertTranscriptEntry`）
- Test: `orchestrator/src/test/java/com/sunshine/orchestrator/execution/ExpertConsultationExecutorTest.java`

- [ ] **Step 1: 失败单测 — 无 templateId 依赖**

```java
@Test
void execute_withExpertIds_emitsConveneAndSpeakSteps() {
    ExecutionPlan plan = new ExecutionPlan(PEER_COLLAB, null,
            Map.of(ExpertCollaborationParams.EXPERT_IDS, "policy-expert,finance-expert",
                   ExpertCollaborationParams.EFFECTIVE_QUERY, "是否合规"),
            "expert:$mention");
    StepRecorder rec = new StepRecorder();
    executor.execute(ctx.withPlan(plan)).doOnNext(rec::add).blockLast();
    assertThat(rec.phases()).contains("expert-convene", "expert");
    assertThat(rec.phases()).doesNotContain("generate", "peer-collab");
}
```

- [ ] **Step 2: 实现 execute 流程**

```
Flux.just(convene running)
  → coordinator.resolve(explicitIds, query)
  → Flux.just(convene done)
  → hubEngine.run(..., callback → speak steps)
  → audit.persist(transcript)
  → synthesizer.synthesize(...)   // 无额外 Timeline 步
  → Flux.empty()  // 正文由 content token 写入 GenerationJob
```

**删除** `reactExecutor.executeWithInjected` 仲裁路径。

- [ ] **Step 3: PASS + 编译全模块**

```bash
mvn test -pl orchestrator -Dtest=ExpertConsultationExecutorTest
mvn -pl orchestrator -am test -q
```

- [ ] **Step 4: Commit**

```bash
git commit -m "feat(expert): consultation executor with hub and synthesizer"
```

---

## Task T15: 迁移 — 废弃 peer.templates + 强制路由

**Files:**
- Modify: `docs/nacos/sunshine-orchestrator.yaml`（`agent.routing.peer` / `agent.peer.templates` 标 `@deprecated` 后删除）
- Modify: `orchestrator/.../ForcedExecutionRouter.java`（`peer-collab` 不再注入 `templateId`）
- Modify: `orchestrator/.../PeerStructuralRoutingPolicy.java`、`GoldenRuleRoutingPolicy.java`（L1/L2 命中 peer 时 **无 templateId**，仅 `PEER_COLLAB` + 空 roster → Coordinator）
- Delete or deprecate: `PeerTemplateCatalog.java`、`PeerTemplate.java`（若无引用则删）
- Test: 更新 `ForcedExecutionRouterTest`、`PeerCollaborationRoutingTest`

- [ ] **Step 1: 更新测试期望**

`forced peer-collab` → `params` **不含** `templateId`；L2 peer 语义 query → `PEER_COLLAB` without `finance-smart`。

- [ ] **Step 2: 删 Nacos templates 块；保留 `agent.peer.max-rounds`**

- [ ] **Step 3: sync + 重启 orchestrator**

```bash
python scripts/sync_nacos.py --data-id sunshine-orchestrator.yaml
mvn -pl orchestrator -am package -DskipTests -q
python scripts/start.py --only orchestrator
```

- [ ] **Step 4: Commit**

```bash
git commit -m "refactor(peer): remove template roster in favor of expert catalog"
```

---

## Task T16: Intent 文案 + orchestrator 错误码

**Files:**
- Modify: `orchestrator/.../IntentLabelService.java`
- Modify: `docs/nacos/sunshine-orchestrator.yaml`（`agent.timeline.intent` peer 文案 →「多专家协作」）
- Modify: `sunshine-ui/src/api/executionModes.ts`（description 对齐）

- [ ] **Step 1: PEER_COLLAB intent after 模板**

`$` 绑定：`已指定专家：{expertNames}`；Coordinator：`将召集多专家协作`；统一 **「多专家协作」**，禁用「专家会诊」「第 N 轮」。

- [ ] **Step 2: 手测 SSE intent 行**

- [ ] **Step 3: Commit**

```bash
git commit -m "chore(copy): unify peer-collab timeline intent labels"
```

---

## Task T17: 前端 `/experts` 管理页

**Files:**
- Create: `sunshine-ui/src/api/experts.ts`
- Create: `sunshine-ui/src/views/ExpertsView.vue`
- Modify: `sunshine-ui/src/router/index.ts`、`MainLayout.vue`（侧栏入口，对称 `/skills`）

- [ ] **Step 1: API 封装**

```typescript
export interface ExpertIndexEntry { id: string; displayName: string; description?: string }
export function fetchExpertCatalogIndex(): Promise<ExpertIndexEntry[]>
export function createExpert(body: ExpertCreateRequest): Promise<void>
// update, setEnabled, listSkillOptions 复用 skills API
```

- [ ] **Step 2: ExpertsView — 列表 + 抽屉 CRUD**

Codex 风格：`--sun-black` 底 + 边框；关联 Skill 多选；工具列只读「全部工具」。

- [ ] **Step 3: 类型检查**

```bash
cd sunshine-ui && npx vue-tsc -b
```

- [ ] **Step 4: Commit**

```bash
git commit -m "feat(ui): experts management page"
```

---

## Task T18: Chat `$` 补全

**Files:**
- Create: `sunshine-ui/src/utils/expertMention.ts`
- Create: `sunshine-ui/src/composables/useChatExpertMention.ts`
- Modify: `sunshine-ui/src/views/ChatView.vue`、`ComposerSkillInput.vue`（或新建 `ComposerMentionInput` 共用逻辑）
- Modify: `sunshine-ui/src/api/executionModes.ts`（`peer-collab` 增加 `allowsExpertMention: true`）

- [ ] **Step 1: 分段解析 `$expertId`**（对称 `skillMention.ts`）

- [ ] **Step 2: 输入 `@` 仍走 skill；`$` 在 `peer-collab` / `auto` 下可用**

优先级 UI 提示：`#` > `$` > `@`（help text 一行即可）

- [ ] **Step 3: `vue-tsc` PASS + Commit**

```bash
git commit -m "feat(ui): chat dollar-expert mention autocomplete"
```

---

## Task T19: Timeline UI — ExpertStepPanel

**Files:**
- Create: `sunshine-ui/src/components/operation/ExpertConvenePanel.vue`
- Create: `sunshine-ui/src/components/operation/ExpertStepPanel.vue`
- Modify: `sunshine-ui/src/components/operation/OperationStack.vue`
- Modify: `sunshine-ui/src/api/processingStepsDisplay.ts`

- [ ] **Step 1: phase 路由**

| phase | 组件 |
|-------|------|
| `expert-convene` | `ExpertConvenePanel` |
| `expert` | `ExpertStepPanel` |
| `peer-collab` | **移除**或仅历史消息兼容 |

- [ ] **Step 2: ExpertStepPanel**

主行：`step.label` + `resolveStepHeaderText`；展开 `step.result`；**不渲染** `metadata.speakSeq` / round。

- [ ] **Step 3: 删除 `PeerCollabPanel` 按轮分组 UI**（文件可删）

- [ ] **Step 4: Commit**

```bash
git commit -m "feat(ui): expert consultation timeline panels"
```

---

## Task T20: Live 验收脚本 + golden §K 完整

**Files:**
- Create: `scripts/verify_expert_consultation_live.py`
- Modify: `docs/routing/routing-golden-set.md` §K
- Modify: `CLAUDE.md` 运维表

- [ ] **Step 1: 脚本用例（对称 verify_peer_collab_live.py）**

| Case | 请求 | 断言 |
|------|------|------|
| K-L1 | `$policy-expert $finance-expert 是否合规` | mode peer；≥2 `expert-*` 步；无 `plan`；无 `generate` |
| K-L2 | `#finance-smart $policy-expert …` | workflow finance-smart |
| K-L3 | `executionPreference=peer-collab` + 合规问句 | peer；Coordinator 召集；无 finance-smart DAG |
| K-L4 | GET `/api/experts/catalog/index` | ≥2 |

```bash
python scripts/verify_expert_consultation_live.py
```

Expected: 全部 PASS 并写 `logs/verify_expert_consultation_*.json`

- [ ] **Step 2: CLAUDE.md 增加脚本行**

- [ ] **Step 3: Commit**

```bash
git commit -m "test(expert): live verification script and golden set K"
```

---

## Task T21: 文档与 implementation-plan 索引

**Files:**
- Modify: `docs/implementation-plan.md`（4.7.3 演进勾选、expert-manager 行）
- Modify: `docs/superpowers/specs/2026-07-07-expert-consultation-design.md`（状态 → 实施中）
- Modify: `CLAUDE.md`（进度、`15-sunshine-expert-manager.sql`、`:8235`）

- [ ] **Step 1: 更新索引与检查门勾选说明**

- [ ] **Step 2: Commit**

```bash
git commit -m "docs: index expert consultation implementation"
```

---

## 自审（Spec coverage）

| Spec § | Task |
|--------|------|
| D1 独立 Expert 实体 | T0–T5 |
| D2 `$` L0 | T7–T8 |
| D3 Coordinator | T10 |
| D4 对等 Hub | T11 |
| D5 Synthesizer 无 generate 步 | T13–T14 |
| D6 `#` > `$` > `@` | T6–T8 |
| D7 Timeline 无轮次 | T12、T19 |
| D8 无仲裁 Expert | T11、T13（删 moderator） |
| §6 数据模型 | T0、T2 |
| §7 `/experts` | T17 |
| §9 Timeline V2 | T12、T16、T19 |
| §11 迁移 peer.templates | T15 |
| §13 检查门 | T20 |

**Placeholder scan:** 无 TBD / 实现后补。

**类型一致性:** `ExpertCollaborationParams.EXPERT_IDS` 全链路逗号分隔；`ExpertTranscriptEntry` 替代 `PeerTranscriptEntry` 字段：`expertId`, `displayName`, `speakSeq`, `content`（无 `round` 对外）。

---

## 执行交接

Plan complete and saved to `docs/superpowers/plans/2026-07-07-expert-consultation.md`. Two execution options:

**1. Subagent-Driven (recommended)** — 每个 Task 派发独立 subagent，Task 间人工/自动 review，迭代快

**2. Inline Execution** — 本会话用 executing-plans 按迭代批量执行，检查点复盘

Which approach?
