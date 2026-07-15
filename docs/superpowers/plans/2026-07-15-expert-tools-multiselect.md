# 专家工具多选 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `/experts` 支持 Catalog 工具多选并持久化到 `tools_json`；专家 Hub 发言按白名单装 Toolkit（空 = 无业务工具，仍有 RAG）。

**Architecture:** expert-manager create/update 接收 `toolIds` 写入 `tools_json`；UI 用 `NSelect multiple`；`ExpertHubEngine` 解析 `toolsJson`（含过渡 `*`）注入 `AgentRunRequest.toolWhitelist`；`ExpertPeerAgentFactory` 一律 `buildForSubAgent`（禁止空列表回退全开）。

**Tech Stack:** JDK 21 · Spring Boot 3.2 · JPA · Vue3/Naive UI · AgentScope Toolkit

**设计 SSOT:** [2026-07-15-expert-tools-multiselect-design.md](../specs/2026-07-15-expert-tools-multiselect-design.md)

---

## 文件结构（边界锁定）

| 区域 | 创建 | 修改 | 测试 |
|------|------|------|------|
| **DB** | — | `docker/mysql/init/15-sunshine-expert-manager.sql` | 手测 DEFAULT |
| **expert-manager** | — | `ExpertCreateRequest`、`ExpertUpdateRequest`、`ExpertAdminService` | 可选手测 API |
| **orchestrator** | `ExpertToolsJson.java` | `ExpertPeerAgentFactory`、`ExpertHubEngine` | `ExpertToolsJsonTest`、`ExpertPeerAgentFactoryTest`（或 Hub 单测） |
| **UI API** | — | `sunshine-ui/src/api/experts.ts` | — |
| **UI** | — | `sunshine-ui/src/views/ExpertsView.vue` | `npx vue-tsc -b`（sunshine-ui） |
| **BFF** | — | 无（透传 body） | — |
| **文档** | — | 本计划勾选；可选补专家详设一句 | — |

---

## Task 1: MySQL 默认改为 `[]`

**Files:**
- Modify: `docker/mysql/init/15-sunshine-expert-manager.sql`

- [ ] **Step 1: 改列 DEFAULT**

将 `tools_json` 行改为：

```sql
    tools_json      VARCHAR(512) NOT NULL DEFAULT '[]',
```

种子 `INSERT` 未显式写 `tools_json` 时会吃 DEFAULT；新环境即为 `[]`。**不要**对已部署库强制 `UPDATE`。

- [ ] **Step 2: Commit**

```bash
git add docker/mysql/init/15-sunshine-expert-manager.sql
git commit -m "chore(db): expert tools_json 默认改为空数组"
```

---

## Task 2: orchestrator `ExpertToolsJson` 解析（TDD）

**Files:**
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/expert/ExpertToolsJson.java`
- Create: `orchestrator/src/test/java/com/sunshine/orchestrator/expert/ExpertToolsJsonTest.java`

- [ ] **Step 1: 写失败单测**

```java
package com.sunshine.orchestrator.expert;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExpertToolsJsonTest {

    @Test
    void nullOrBlank_returnsEmpty() {
        assertThat(ExpertToolsJson.parse(null)).isEmpty();
        assertThat(ExpertToolsJson.parse("")).isEmpty();
        assertThat(ExpertToolsJson.parse("   ")).isEmpty();
    }

    @Test
    void emptyArray_returnsEmpty() {
        assertThat(ExpertToolsJson.parse("[]")).isEmpty();
    }

    @Test
    void concreteIds_preservedInOrder() {
        assertThat(ExpertToolsJson.parse("[\"sdk__a__t1\",\"mcp__b__t2\"]"))
                .containsExactly("sdk__a__t1", "mcp__b__t2");
    }

    @Test
    void starAlone_isStarSentinel() {
        assertThat(ExpertToolsJson.isStarAll(ExpertToolsJson.parse("[\"*\"]"))).isTrue();
        assertThat(ExpertToolsJson.isStarAll(List.of("sdk__a__t1"))).isFalse();
        assertThat(ExpertToolsJson.isStarAll(List.of())).isFalse();
    }

    @Test
    void invalidJson_returnsEmpty() {
        assertThat(ExpertToolsJson.parse("not-json")).isEmpty();
    }
}
```

- [ ] **Step 2: 跑测确认失败**

```bash
mvn -pl orchestrator -Dtest=ExpertToolsJsonTest test
```

Expected: 编译失败（类不存在）或 FAIL。

- [ ] **Step 3: 最小实现**

```java
package com.sunshine.orchestrator.expert;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/** 专家 tools_json 解析；`["*"]` 为过渡全量哨兵。 */
public final class ExpertToolsJson {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<String>> LIST_STRING = new TypeReference<>() {};

    private ExpertToolsJson() {}

    public static List<String> parse(String toolsJson) {
        if (!StringUtils.hasText(toolsJson)) {
            return List.of();
        }
        try {
            List<String> raw = MAPPER.readValue(toolsJson.strip(), LIST_STRING);
            if (raw == null || raw.isEmpty()) {
                return List.of();
            }
            List<String> out = new ArrayList<>();
            for (String id : raw) {
                if (StringUtils.hasText(id)) {
                    out.add(id.strip());
                }
            }
            return List.copyOf(out);
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 过渡：仅当解析结果恰好为单个 `"*"` */
    public static boolean isStarAll(List<String> toolIds) {
        return toolIds != null && toolIds.size() == 1 && "*".equals(toolIds.get(0));
    }
}
```

- [ ] **Step 4: 跑测确认通过**

```bash
mvn -pl orchestrator -Dtest=ExpertToolsJsonTest test
```

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add orchestrator/src/main/java/com/sunshine/orchestrator/expert/ExpertToolsJson.java \
  orchestrator/src/test/java/com/sunshine/orchestrator/expert/ExpertToolsJsonTest.java
git commit -m "feat(orchestrator): ExpertToolsJson 解析 tools_json"
```

---

## Task 3: `ExpertPeerAgentFactory` 改走 `buildForSubAgent`

**Files:**
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/expert/ExpertPeerAgentFactory.java`
- Create: `orchestrator/src/test/java/com/sunshine/orchestrator/expert/ExpertPeerAgentFactoryTest.java`

- [ ] **Step 1: 写失败单测（Mockito 校验调用）**

```java
package com.sunshine.orchestrator.expert;

import com.sunshine.orchestrator.agent.DynamicToolkitFactory;
import com.sunshine.orchestrator.agent.ReActAgentFactory;
import com.sunshine.orchestrator.agent.runtime.AgentRole;
import com.sunshine.orchestrator.agent.runtime.AgentRunRequest;
import com.sunshine.orchestrator.agent.runtime.TimelineBinding;
import com.sunshine.orchestrator.catalog.ToolCatalogService;
import com.sunshine.orchestrator.memory.MemoryContext;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExpertPeerAgentFactoryTest {

    @Mock DynamicToolkitFactory dynamicToolkitFactory;
    @Mock ToolCatalogService toolCatalogService;
    @Mock ReActAgentFactory reactAgentFactory;

    ExpertPeerAgentFactory factory;

    @BeforeEach
    void setUp() {
        factory = new ExpertPeerAgentFactory(dynamicToolkitFactory, toolCatalogService, reactAgentFactory);
        ReflectionTestUtils.setField(factory, "modelName", "test-model");
        ReflectionTestUtils.setField(factory, "modelBaseUrl", "http://localhost:8300/v1");
        ReflectionTestUtils.setField(factory, "apiKey", "k");
        when(reactAgentFactory.composeSystemPrompt(org.mockito.ArgumentMatchers.any())).thenReturn("sys");
        when(reactAgentFactory.resolveMaxIters(org.mockito.ArgumentMatchers.any())).thenReturn(2);
        when(dynamicToolkitFactory.buildForSubAgent(anyList(), anyString())).thenReturn(new Toolkit());
        when(dynamicToolkitFactory.buildForSubAgent(anyList(), isNull())).thenReturn(new Toolkit());
    }

    @Test
    void emptyWhitelist_usesBuildForSubAgent_notFullBuild() {
        AgentRunRequest req = subWithTools(List.of());
        factory.create(req);
        verify(dynamicToolkitFactory).buildForSubAgent(eq(List.of()), isNull());
        org.mockito.Mockito.verify(dynamicToolkitFactory, org.mockito.Mockito.never())
                .build(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void concreteWhitelist_usesBuildForSubAgent() {
        AgentRunRequest req = subWithTools(List.of("sdk__a__t1"));
        factory.create(req);
        verify(dynamicToolkitFactory).buildForSubAgent(eq(List.of("sdk__a__t1")), isNull());
    }

    private static AgentRunRequest subWithTools(List<String> tools) {
        return new AgentRunRequest(
                AgentRole.SUB, "run-1", "parent",
                MemoryContext.forSubAgent(), "", List.of(),
                null, null, null, null, tools, "overlay", 2,
                TimelineBinding.SUB_COMPRESSED, false);
    }
}
```

若 `build(String)` 的 `never()` 因重载签名难匹配，可改为 `verifyNoMoreInteractions` 仅在确认 mock 只暴露 `buildForSubAgent` 调用；以能编译为准，核心断言是 **调用了 `buildForSubAgent`**。

- [ ] **Step 2: 跑测确认失败**

```bash
mvn -pl orchestrator -Dtest=ExpertPeerAgentFactoryTest test
```

Expected: FAIL（仍走旧 `build`）

- [ ] **Step 3: 改 `resolveToolkit`**

替换 `ExpertPeerAgentFactory.resolveToolkit` 为：

```java
    private Toolkit resolveToolkit(AgentRunRequest request) {
        List<String> whitelist = request.toolWhitelist() != null
                ? request.toolWhitelist()
                : List.of();
        return dynamicToolkitFactory.buildForSubAgent(whitelist, request.tenantId());
    }
```

删除对 `AgentRole.SUB` / 空列表分支回退 `build(tenantId)` 的逻辑。`null` whitelist 视为 `[]`（无业务工具）。

- [ ] **Step 4: 跑测通过**

```bash
mvn -pl orchestrator -Dtest=ExpertPeerAgentFactoryTest,ExpertToolsJsonTest test
```

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add orchestrator/src/main/java/com/sunshine/orchestrator/expert/ExpertPeerAgentFactory.java \
  orchestrator/src/test/java/com/sunshine/orchestrator/expert/ExpertPeerAgentFactoryTest.java
git commit -m "fix(orchestrator): 专家 Toolkit 走 buildForSubAgent，空名单不全开"
```

---

## Task 4: `ExpertHubEngine` 注入 whitelist

**Files:**
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/expert/ExpertHubEngine.java`
- Modify: `orchestrator/src/test/java/com/sunshine/orchestrator/expert/ExpertHubEngineCreateAgentTest.java`（新建）

说明：`createAgent` 现为 private。单测优先测 **解析 + 解析后列表** 的包内可测方法；若不愿抽方法，可把解析抽为 `package-private static List<String> resolveToolWhitelist(ExpertCatalogEntry, ToolSetResolver)` 再测。

- [ ] **Step 1: 注入 `ToolSetResolver`，改 `createAgent`**

`ExpertHubEngine` 构造依赖增加 `ToolSetResolver toolSetResolver`。

将 `createAgent` 改为（保留其余字段不变）：

```java
    private ReActAgent createAgent(String runId, ExpertCatalogEntry expert) {
        List<String> toolIds = resolveToolWhitelist(expert);
        AgentRunRequest request = new AgentRunRequest(
                AgentRole.SUB,
                runId + "-" + expert.id(),
                runId,
                MemoryContext.forSubAgent(),
                "",
                List.of(),
                null,
                null,
                null,
                expert.primarySkillId(),
                toolIds,
                expert.systemPrompt(),
                2,
                TimelineBinding.SUB_COMPRESSED,
                false);
        return expertPeerAgentFactory.create(request);
    }

    /** package-private for tests */
    List<String> resolveToolWhitelist(ExpertCatalogEntry expert) {
        List<String> parsed = ExpertToolsJson.parse(expert != null ? expert.toolsJson() : null);
        if (ExpertToolsJson.isStarAll(parsed)) {
            return toolSetResolver.resolveReactTools(null);
        }
        return parsed;
    }
```

- [ ] **Step 2: 单测 `resolveToolWhitelist`**

```java
package com.sunshine.orchestrator.expert;

import com.sunshine.orchestrator.catalog.ExpertCatalogEntry;
import com.sunshine.orchestrator.catalog.ToolSetResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExpertHubEngineCreateAgentTest {

    @Mock ToolSetResolver toolSetResolver;
    @Mock ExpertPeerAgentFactory expertPeerAgentFactory;
    @Mock com.sunshine.orchestrator.prompt.PromptComposer promptComposer;
    @Mock ExpertSpeakStreamer expertSpeakStreamer;
    @Mock com.sunshine.orchestrator.peer.PeerSynthesisProperties peerProperties;
    @Mock ExpertRoundCoordinatorService roundCoordinator;

    @InjectMocks ExpertHubEngine engine;

    @Test
    void star_expandsToReactPool() {
        when(toolSetResolver.resolveReactTools(isNull())).thenReturn(List.of("sdk__a__t1", "sdk__a__t2"));
        ExpertCatalogEntry e = entry("[\"*\"]");
        assertThat(engine.resolveToolWhitelist(e)).containsExactly("sdk__a__t1", "sdk__a__t2");
    }

    @Test
    void empty_staysEmpty() {
        assertThat(engine.resolveToolWhitelist(entry("[]"))).isEmpty();
    }

    @Test
    void concrete_passthrough() {
        assertThat(engine.resolveToolWhitelist(entry("[\"sdk__a__t1\"]")))
                .containsExactly("sdk__a__t1");
    }

    private static ExpertCatalogEntry entry(String toolsJson) {
        return new ExpertCatalogEntry("e1", "E", "d", "sys", List.of(), List.of(), toolsJson, true);
    }
}
```

- [ ] **Step 3: 跑测**

```bash
mvn -pl orchestrator -Dtest=ExpertHubEngineCreateAgentTest,ExpertPeerAgentFactoryTest,ExpertToolsJsonTest test
```

Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add orchestrator/src/main/java/com/sunshine/orchestrator/expert/ExpertHubEngine.java \
  orchestrator/src/test/java/com/sunshine/orchestrator/expert/ExpertHubEngineCreateAgentTest.java
git commit -m "feat(orchestrator): 专家 Hub 按 tools_json 注入工具白名单"
```

---

## Task 5: expert-manager API 读写 `toolIds`

**Files:**
- Modify: `expert-manager/src/main/java/com/sunshine/expert/dto/ExpertCreateRequest.java`
- Modify: `expert-manager/src/main/java/com/sunshine/expert/dto/ExpertUpdateRequest.java`
- Modify: `expert-manager/src/main/java/com/sunshine/expert/service/ExpertAdminService.java`

- [ ] **Step 1: DTO 增加 `toolIds`**

```java
// ExpertCreateRequest
public record ExpertCreateRequest(
        String id,
        String displayName,
        String description,
        String systemPrompt,
        List<String> skillIds,
        List<String> toolIds
) {}

// ExpertUpdateRequest
public record ExpertUpdateRequest(
        String displayName,
        String description,
        String systemPrompt,
        List<String> skillIds,
        List<String> toolIds
) {}
```

- [ ] **Step 2: AdminService 序列化写入**

在 `ExpertAdminService` 已有 `MAPPER` 上增加：

```java
    private String serializeToolIds(List<String> toolIds) {
        try {
            List<String> clean = new ArrayList<>();
            if (toolIds != null) {
                for (String id : toolIds) {
                    if (StringUtils.hasText(id) && !"*".equals(id.strip())) {
                        clean.add(id.strip());
                    }
                }
            }
            return MAPPER.writeValueAsString(clean);
        } catch (Exception e) {
            return "[]";
        }
    }
```

`create`：`def.setToolsJson(serializeToolIds(request.toolIds()));`（替换硬编码 `"[\"*\"]"`）

`update`：在 `save` 前 `def.setToolsJson(serializeToolIds(request.toolIds()));`

注意：请求省略 `toolIds`（null）时按 `[]` 写，与「空 = 无业务工具」一致；前端保存须始终传数组。

- [ ] **Step 3: 编译**

```bash
mvn -pl expert-manager -am compile -DskipTests
```

Expected: SUCCESS

- [ ] **Step 4: Commit**

```bash
git add expert-manager/src/main/java/com/sunshine/expert/dto/ExpertCreateRequest.java \
  expert-manager/src/main/java/com/sunshine/expert/dto/ExpertUpdateRequest.java \
  expert-manager/src/main/java/com/sunshine/expert/service/ExpertAdminService.java
git commit -m "feat(expert-manager): create/update 持久化 toolIds → tools_json"
```

---

## Task 6: 前端 API `toolIds`

**Files:**
- Modify: `sunshine-ui/src/api/experts.ts`

- [ ] **Step 1: 改 `createExpert` / `updateExpert` 签名**

```ts
export async function createExpert(
  id: string,
  displayName: string,
  systemPrompt: string,
  description?: string,
  skillIds?: string[],
  toolIds?: string[],
): Promise<ExpertEntry> {
  const res = await fetch(apiUrl('/api/experts'), {
    method: 'POST',
    headers: { ...apiHeaders(), 'Content-Type': 'application/json' },
    body: JSON.stringify({
      id,
      displayName,
      systemPrompt,
      description: description ?? '',
      skillIds: skillIds ?? [],
      toolIds: toolIds ?? [],
    }),
  })
  return parseApiResponse<ExpertEntry>(res)
}

export async function updateExpert(
  id: string,
  displayName: string,
  systemPrompt: string,
  description?: string,
  skillIds?: string[],
  toolIds?: string[],
): Promise<ExpertEntry> {
  const res = await fetch(apiUrl(`/api/experts/${encodeURIComponent(id)}`), {
    method: 'PUT',
    headers: { ...apiHeaders(), 'Content-Type': 'application/json' },
    body: JSON.stringify({
      displayName,
      systemPrompt,
      description: description ?? '',
      skillIds: skillIds ?? [],
      toolIds: toolIds ?? [],
    }),
  })
  return parseApiResponse<ExpertEntry>(res)
}
```

- [ ] **Step 2: Commit**

```bash
git add sunshine-ui/src/api/experts.ts
git commit -m "feat(ui): experts API 传递 toolIds"
```

---

## Task 7: `ExpertsView` 工具多选

**Files:**
- Modify: `sunshine-ui/src/views/ExpertsView.vue`

- [ ] **Step 1: 引入 catalog + form 字段**

```ts
import { listToolCatalog, type ToolCatalogEntry } from '../api/tools'

const toolOptions = ref<ToolCatalogEntry[]>([])

const editForm = ref({
  displayName: '',
  description: '',
  systemPrompt: '',
  skillIds: [] as string[],
  toolIds: [] as string[],
})

const toolSelectOptions = computed(() =>
  toolOptions.value
    .filter(t => t.enabled)
    .map(t => ({
      label: `${t.displayName || t.id} (${t.id})`,
      value: t.id,
    })),
)

const enabledToolIds = computed(() =>
  toolOptions.value.filter(t => t.enabled).map(t => t.id),
)

function parseExpertToolIds(toolsJson: string | undefined | null): string[] {
  if (!toolsJson?.trim()) return []
  try {
    const parsed = JSON.parse(toolsJson) as unknown
    if (!Array.isArray(parsed)) return []
    const ids = parsed.map(x => String(x).trim()).filter(Boolean)
    if (ids.length === 1 && ids[0] === '*') {
      return [...enabledToolIds.value]
    }
    return ids.filter(id => id !== '*')
  } catch {
    return []
  }
}
```

确认 `ToolCatalogEntry` 使用 `id` / `displayName` / `enabled`（与 Workflow Studio 一致）。

- [ ] **Step 2: `loadEditForm` / dirty / save / refresh**

```ts
function loadEditForm(expert: ExpertEntry) {
  editForm.value = {
    displayName: expert.displayName,
    description: expert.description ?? '',
    systemPrompt: expert.systemPrompt,
    skillIds: [...(expert.skillIds ?? [])],
    toolIds: parseExpertToolIds(expert.toolsJson),
  }
}

// isFormDirty 增加：
|| JSON.stringify([...editForm.value.toolIds].sort())
  !== JSON.stringify([...parseExpertToolIds(expert.toolsJson)].sort())

// refreshPage Promise.all 增加 listToolCatalog()
toolOptions.value = tools.filter(t => t.enabled) // 或保留全量、选项里 filter

// handleSave:
await updateExpert(
  selectedId.value,
  editForm.value.displayName.trim(),
  editForm.value.systemPrompt.trim(),
  editForm.value.description.trim(),
  editForm.value.skillIds,
  editForm.value.toolIds,
)
```

- [ ] **Step 3: 模板替换工具表单项**

```vue
<NFormItem label="工具">
  <NSelect
    v-model:value="editForm.toolIds"
    class="sun-field"
    multiple
    filterable
    :disabled="!isEditing"
    :options="toolSelectOptions"
    :menu-props="{ class: 'expert-select-menu' }"
    placeholder="可选 0~N 个工具"
  />
</NFormItem>
```

删除只读 `NInput`「全部工具（只读）」。

- [ ] **Step 4: 类型检查**

```bash
cd sunshine-ui && npx vue-tsc -b --pretty false
```

Expected: 无 error（与本改动相关）

- [ ] **Step 5: Commit**

```bash
git add sunshine-ui/src/views/ExpertsView.vue
git commit -m "feat(ui): /experts 工具 Catalog 多选"
```

---

## Task 8: 文档收口

**Files:**
- Modify: `docs/superpowers/specs/2026-07-15-expert-tools-multiselect-design.md`（状态 → 实施中/已落地）
- Modify: `docs/superpowers/specs/2026-07-07-expert-consultation-design.md` §7 工具行（只读 → 可多选）— 一行修订即可

- [x] **Step 1: 更新两处状态说明**

专家详设 §7 表：

| 工具 | Catalog 多选 → `tools_json` |

本设计文档状态改为「实施中」或实现完成后「已落地」。

- [x] **Step 2: Commit**

```bash
git add docs/superpowers/specs/2026-07-15-expert-tools-multiselect-design.md \
  docs/superpowers/specs/2026-07-07-expert-consultation-design.md
git commit -m "docs: 专家工具多选状态与专家详设对齐"
```

---

## Task 9: 手工 / Live 冒烟（服务已起时）

- [ ] **Step 1: 重启 expert-manager + orchestrator**（改 Java 后）

- [ ] **Step 2: API**

```bash
# 登录拿 token 后
curl -s -X PUT "$BFF/api/experts/finance-expert" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"displayName":"财务专家","systemPrompt":"...","description":"...","skillIds":["finance-analysis"],"toolIds":["sdk__sunshine-finance__list_finance_messages"]}'
```

GET 回读 `toolsJson` 应为 `["sdk__sunshine-finance__list_finance_messages"]`。

- [ ] **Step 3: UI**

打开 `/experts` → 编辑 → 多选工具 → 保存 → 刷新仍保留；清空保存后 `toolsJson` 为 `[]`。

- [ ] **Step 4:（可选）peer-collab 发言**

空名单专家不应出现业务 tool 步；有白名单时可出现对应 Catalog 工具。

---

## Spec 覆盖自检

| Spec 要求 | Task |
|-----------|------|
| `tools_json` 只存具体 ID / `[]` | T1, T5 |
| API `toolIds` | T5, T6 |
| 新建默认 `[]` | T1, T5 |
| UI 多选 + `*` 展开 | T7 |
| Hub 注入 whitelist + `*`→启用池 | T4 |
| `buildForSubAgent`、空不全开 | T3 |
| Skill 不管工具 | T4（仅传 expert tools） |
| 单测解析 / Factory / Hub | T2–T4 |
| 非目标（Chat 底栏、Skill 并集、`*` 快捷项） | 未做 |

---

## 执行交接

Plan 已保存至 `docs/superpowers/plans/2026-07-15-expert-tools-multiselect.md`。

**两种执行方式：**

1. **Subagent-Driven（推荐）** — 每任务新开子代理，任务间复审  
2. **Inline Execution** — 本会话按 executing-plans 连续推进  

选哪种？
