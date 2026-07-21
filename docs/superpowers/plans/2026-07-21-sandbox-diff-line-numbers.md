# Sandbox / 时间线行号 + Git contextual diff Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 沙箱抽屉全文显示绝对行号；时间线 `sandbox__write`/`sandbox__edit` 展开以 Git 双栏（旧号|新号|+/-）渲染；edit 带 ±3 上下文与 `···` 折叠；**不做**旧消息 `+/-/` 兼容。

**Architecture:** `EditDiffBuilder`（`sunshine-common`）在 edit 落盘前基于改前全文生成 structured `editDiff` → `ToolInvokeResponse.meta` → `SandboxEditDiffHolder` → `StepMetadata.editDiff`（SSE）；HITL 待确认时 orchestrator 读工作区文件用同一 Builder。前端 **仅** `metadata.editDiff`（edit）或 write 全文标 `add` 渲染 `CodeLineGutter`。

**Tech Stack:** Java 17 · Maven · Vue3/TS · highlight.js · vitest（`npx vitest run`）

**Spec:** [2026-07-21-sandbox-diff-line-numbers-design.md](../specs/2026-07-21-sandbox-diff-line-numbers-design.md)

---

## File map

| 文件 | 职责 |
|------|------|
| `common/.../sandbox/SandboxEditDiffLine.java` | 单行 DTO：`kind/text/oldLine/newLine` |
| `common/.../sandbox/SandboxEditDiff.java` | hunk：`path/contextRadius/lines` + `toUnifiedText()` |
| `common/.../sandbox/EditDiffBuilder.java` | ±3 contextual hunk + 绝对行号 |
| `common/.../sandbox/EditDiffBuilderTest.java` | Builder 单测 |
| `sandbox-service/.../SandboxToolExecutor.java` | edit：build → meta → 写盘 |
| `orchestrator/.../sandbox/SandboxEditDiffHolder.java` | toolUseId → editDiff 旁路 |
| `orchestrator/.../sandbox/SandboxAgentTools.java` | 成功 edit 时 put holder |
| `orchestrator/.../processing/StepMetadata.java` + Assembler + Serde | 字段 `editDiff` |
| `orchestrator/.../agent/ProcessingStepHook.java` | take holder → metadata + detail 复制文本 |
| `orchestrator/.../hitl/HitlConfirmationService.java` | HITL 读文件 + Builder → metadata |
| `sunshine-ui/src/api/sandboxEditDiff.ts` (+ test) | 类型扩展；去掉 parse 产品路径；write 行号 |
| `sunshine-ui/src/api/processingSteps.ts` + Parse | `metadata.editDiff` |
| `sunshine-ui/.../CodeLineGutter.vue` | 单栏/双栏 gutter |
| `sunshine-ui/.../SandboxDiffView.vue` | diff 行渲染 |
| `sunshine-ui/.../SandboxToolExpandPanel.vue` + `useSandboxToolExpand.ts` | 接 structured |
| `sunshine-ui/.../SandboxPreviewPane.vue` | 全文行号 |
| `docs/sandbox/README.md` | 索引更新 |

---

### Task 1: `EditDiffBuilder`（common，TDD）

**Files:**
- Create: `common/sunshine-common/src/main/java/com/sunshine/common/sandbox/SandboxEditDiffLine.java`
- Create: `common/sunshine-common/src/main/java/com/sunshine/common/sandbox/SandboxEditDiff.java`
- Create: `common/sunshine-common/src/main/java/com/sunshine/common/sandbox/EditDiffBuilder.java`
- Create: `common/sunshine-common/src/test/java/com/sunshine/common/sandbox/EditDiffBuilderTest.java`

- [ ] **Step 1: 写失败单测**

```java
package com.sunshine.common.sandbox;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class EditDiffBuilderTest {

    @Test
    void middleReplace_hasContextRadius3_andAbsoluteLines() {
        String before = String.join("\n",
                "L1", "L2", "L3", "L4", "L5", "OLD", "L7", "L8", "L9", "L10");
        SandboxEditDiff diff = EditDiffBuilder.build(before, "OLD", "NEW", 3)
                .withPath("/workspace/a.txt");
        assertThat(diff.contextRadius()).isEqualTo(3);
        assertThat(diff.path()).isEqualTo("/workspace/a.txt");
        // 文件头被 fold；ctx L3..L5；del OLD@6；add NEW@6；ctx L7..L9；尾 fold
        assertThat(diff.lines().get(0).kind()).isEqualTo("fold");
        assertThat(diff.lines().stream().filter(l -> "ctx".equals(l.kind())).map(SandboxEditDiffLine::text))
                .containsExactly("L3", "L4", "L5", "L7", "L8", "L9");
        SandboxEditDiffLine del = diff.lines().stream().filter(l -> "del".equals(l.kind())).findFirst().orElseThrow();
        SandboxEditDiffLine add = diff.lines().stream().filter(l -> "add".equals(l.kind())).findFirst().orElseThrow();
        assertThat(del.oldLine()).isEqualTo(6);
        assertThat(del.newLine()).isNull();
        assertThat(add.newLine()).isEqualTo(6);
        assertThat(add.oldLine()).isNull();
        assertThat(diff.toUnifiedText()).contains("-OLD").contains("+NEW").contains(" L5");
    }

    @Test
    void nearFileStart_noLeadingFold_partialContext() {
        String before = "A\nB\nC\n";
        SandboxEditDiff diff = EditDiffBuilder.build(before, "A\nB", "X\nY", 3);
        assertThat(diff.lines().get(0).kind()).isNotEqualTo("fold");
        assertThat(diff.lines().stream().anyMatch(l -> "fold".equals(l.kind()))).isTrue(); // trailing after C area
    }

    @Test
    void notFound_returnsEmptyOptional() {
        assertThat(EditDiffBuilder.tryBuild("abc", "zzz", "q", 3)).isEmpty();
    }

    @Test
    void notUnique_returnsEmptyOptional() {
        assertThat(EditDiffBuilder.tryBuild("x\nx\n", "x", "y", 3)).isEmpty();
    }
}
```

- [ ] **Step 2: 跑测确认失败**

```bash
cd /usr/local/gitproj/my-sunshine-agent && mvn test -pl common/sunshine-common -Dtest=EditDiffBuilderTest -q
```

Expected: FAIL（类不存在）

- [ ] **Step 3: 实现 DTO + Builder**

`SandboxEditDiffLine` record：`String kind, String text, Integer oldLine, Integer newLine`  
`SandboxEditDiff` record：`String path, int contextRadius, List<SandboxEditDiffLine> lines`，方法：

```java
public SandboxEditDiff withPath(String path) {
    return new SandboxEditDiff(path, contextRadius, lines);
}

public String toUnifiedText() {
    StringBuilder sb = new StringBuilder();
    for (SandboxEditDiffLine l : lines) {
        if ("fold".equals(l.kind())) continue; // 复制文本不含 fold 行；或输出 " ..." —— **约定：跳过 fold**
        char p = switch (l.kind()) {
            case "del" -> '-';
            case "add" -> '+';
            default -> ' ';
        };
        if (!sb.isEmpty()) sb.append('\n');
        sb.append(p).append(l.text() != null ? l.text() : "");
    }
    return sb.toString();
}
```

`EditDiffBuilder` 核心算法（实现要点，写入类注释也可）：

1. `splitLines(before)` 与现 `HitlParamSupport` 一致（空串 → `[""]`；`split("\n", -1)`）
2. `indexOf` 找唯一匹配；次数 ≠1 → `Optional.empty()`
3. 将匹配区间映射为 before 的起止**行** `[startLine, endLine)`（0-based）
4. `oldLines` = 该区间行；`newLines` = `splitLines(newString)`（若 new 为空则单空或空列表：与 replace 语义一致——`content.replace` 后行数）
5. 对 oldLines/newLines 做行级 LCS → 片段内 `del`/`add`/`ctx`（片段内 ctx 的 old/new 行号按两侧游标映射到**文件绝对 1-based**）
6. 变更块（含片段内所有行）在文件中的 old 覆盖行 `oldCoverStart..oldCoverEnd`、new 侧覆盖长度算出 `newCoverStart`
7. 向上取 `contextRadius` 行 before 作为前导 `ctx`（绝对行号）；再往前若还有行 → 插入一个 `fold`
8. 向下同理后导 `ctx` + 可选尾 `fold`
9. `build(...)` 在 tryBuild 为空时抛 `IllegalArgumentException`；生产路径用 `tryBuild`

行号赋值规则（与 Git 一致）：

- 遍历 before 时维护 `oldLine`（1-based）；emit `del`/`ctx(from before)` 时带 `oldLine` 再递增
- emit `add` 时带当前 `newLine` 再递增；`del` 不递增 `newLine`
- 前导/后导纯文件 ctx：`oldLine == newLine`（在变更块之前）或变更后按两侧各自计数对齐

- [ ] **Step 4: 跑测通过**

```bash
mvn test -pl common/sunshine-common -Dtest=EditDiffBuilderTest -q
```

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add common/sunshine-common/src/main/java/com/sunshine/common/sandbox/SandboxEditDiff*.java \
  common/sunshine-common/src/main/java/com/sunshine/common/sandbox/EditDiffBuilder.java \
  common/sunshine-common/src/test/java/com/sunshine/common/sandbox/EditDiffBuilderTest.java
git commit -m "$(cat <<'EOF'
feat(common): EditDiffBuilder with ±3 context and absolute line numbers

EOF
)"
```

---

### Task 2: sandbox-service `edit` 写入 `meta.editDiff`

**Files:**
- Modify: `sandbox-service/src/main/java/com/sunshine/sandbox/tool/SandboxToolExecutor.java`
- Modify: `sandbox-service/src/test/java/com/sunshine/sandbox/tool/SandboxToolExecutorTest.java`（若已有 edit 测例则扩展）

- [ ] **Step 1: 扩展 / 新增单测** — edit 成功后 `response.meta()` 含 `editDiff` map/对象（按 Jackson 序列化习惯：若 meta 值为 `SandboxEditDiff`，确保 Web 层能序列化；更稳妥：meta 放 `Map` 结构或直接放 record 且全局 ObjectMapper 支持）

约定 meta key：`"editDiff"` → 可序列化为：

```json
{
  "path": "/workspace/a.txt",
  "contextRadius": 3,
  "lines": [ { "kind": "del", "text": "OLD", "oldLine": 6, "newLine": null }, ... ]
}
```

- [ ] **Step 2: 改 `edit(...)`**

在 `Files.writeString` **之前**：

```java
var built = EditDiffBuilder.tryBuild(content, oldString, newString, 3)
        .map(d -> d.withPath(path))
        .orElse(null);
String updated = content.replace(oldString, newString);
Files.writeString(host, updated, StandardCharsets.UTF_8);
Map<String, Object> meta = new LinkedHashMap<>();
if (built != null) {
    meta.put("editDiff", built);
}
return new ToolInvokeResponse(true, "", null, meta);
```

（`count` 校验仍在 build 前；tryBuild 与 count 语义一致。）

- [ ] **Step 3: 跑测**

```bash
mvn test -pl sandbox-service -Dtest=SandboxToolExecutorTest -q
```

Expected: PASS

- [ ] **Step 4: Commit**

```bash
git commit -am "$(cat <<'EOF'
feat(sandbox): attach editDiff meta on sandbox__edit

EOF
)"
```

---

### Task 3: `StepMetadata.editDiff` + Serde

**Files:**
- Modify: `orchestrator/.../processing/StepMetadata.java`
- Modify: `orchestrator/.../processing/StepMetadataAssembler.java`（所有 `new StepMetadata(...)` 末尾加 `null` 或保留 `editDiff`）
- Modify: `orchestrator/.../processing/RagStepMetadataParser.java`（同上）
- Modify: `orchestrator/.../agent/ProcessingStepSerde.java`
- Create: `orchestrator/.../processing/StepMetadataAssembler` 内 `withEditDiff`
- Test: 扩展现有 Serde/Merger 测或新建 `StepMetadataEditDiffSerdeTest`

- [ ] **Step 1: 在 record 末尾增加字段**

```java
/** 沙箱 edit：Git contextual diff（绝对行号）；UI 只认此字段 */
SandboxEditDiff editDiff
```

导入 `com.sunshine.common.sandbox.SandboxEditDiff`。

- [ ] **Step 2: Assembler**

```java
static StepMetadata withEditDiff(StepMetadata base, SandboxEditDiff editDiff) {
    if (editDiff == null) return base;
    if (base == null) {
        return new StepMetadata(/* 全 null ... */, null, editDiff);
    }
    return new StepMetadata(
            base.hitCount(), /* ... 原字段复制 ... */,
            base.cancellable(), editDiff);
}
```

`merge` 路径：incoming.editDiff 非空则覆盖。机械更新所有构造调用：在原 `cancellable` 后再加一参。

- [ ] **Step 3: Serde `metadataToMap` / parse**

写出：

```java
if (metadata.editDiff() != null) {
    map.put("editDiff", editDiffToMap(metadata.editDiff()));
}
```

读入：若 `map.get("editDiff")` 为 Map，解析 `path/contextRadius/lines`。

- [ ] **Step 4: 单测 round-trip** — metadata → map → 结构字段齐全

```bash
mvn test -pl orchestrator -Dtest=ProcessingStepMergerTest,StepMetadataEditDiffSerdeTest -q
```

- [ ] **Step 5: Commit**

```bash
git commit -am "$(cat <<'EOF'
feat(orchestrator): StepMetadata.editDiff SSE field

EOF
)"
```

---

### Task 4: Holder + AgentTools + Hook

**Files:**
- Create: `orchestrator/.../sandbox/SandboxEditDiffHolder.java`
- Create: `orchestrator/.../sandbox/SandboxEditDiffHolderTest.java`
- Modify: `orchestrator/.../sandbox/SandboxAgentTools.java`
- Modify: `orchestrator/.../agent/ProcessingStepHook.java`

- [ ] **Step 1: Holder**

```java
public final class SandboxEditDiffHolder {
    private static final ConcurrentHashMap<String, SandboxEditDiff> BY_TOOL_USE = new ConcurrentHashMap<>();
    private SandboxEditDiffHolder() {}
    public static void put(String toolUseId, SandboxEditDiff diff) {
        if (toolUseId == null || diff == null) return;
        BY_TOOL_USE.put(toolUseId, diff);
    }
    public static SandboxEditDiff take(String toolUseId) {
        if (toolUseId == null) return null;
        return BY_TOOL_USE.remove(toolUseId);
    }
}
```

单测：put/take 一次消费。

- [ ] **Step 2: `SandboxAgentTools` invoke 成功后**

```java
if (SandboxIds.EDIT.equals(name) && resp != null && resp.meta() != null) {
    Object raw = resp.meta().get("editDiff");
    SandboxEditDiff parsed = SandboxEditDiffCodec.fromMeta(raw); // 小工具：record 或 Map → SandboxEditDiff
    if (parsed != null) {
        SandboxEditDiffHolder.put(toolUseId, parsed);
    }
}
```

`SandboxEditDiffCodec` 可放 orchestrator 或 common（若 meta 已是 record，直接 cast）。

- [ ] **Step 3: Hook 完成 edit**

```java
if (SandboxIds.EDIT.equals(toolName)) {
    SandboxEditDiff editDiff = SandboxEditDiffHolder.take(toolUseId);
    if (editDiff != null) {
        sandboxMeta = StepMetadata.withEditDiff(sandboxMeta, editDiff);
        expandDetail = editDiff.toUnifiedText(); // 仅复制
    } else {
        expandDetail = null; // 不做旧 HitlParamSupport edit 片段兜底
    }
} else if (SandboxIds.WRITE.equals(toolName)) {
    expandDetail = HitlParamSupport.expandBodyFromParams(toStringParams(input)); // 仍用 content
}
```

- [ ] **Step 4: 跑相关单测 + Commit**

```bash
mvn test -pl orchestrator -Dtest=SandboxEditDiffHolderTest -q
git commit -am "$(cat <<'EOF'
feat(orchestrator): bridge editDiff meta into timeline StepMetadata

EOF
)"
```

---

### Task 5: HITL 待确认预览（同 Builder，无弱兜底）

**Files:**
- Modify: `orchestrator/.../hitl/HitlConfirmationService.java`
- Modify: `orchestrator/.../processing/TimelineSessionToolFlow.java`（若需 attach 时带 editDiff）
- Modify: `orchestrator/.../hitl/HitlParamSupport.java` — edit 分支可停止往 detail 填片段 unified（避免前端误用）；write/exec 不变
- Test: `HitlParamSupportTest` 调整：`expandBodyFromParams` 对 edit **返回 null**（或仅 path 无正文）；新增 HITL preview 测（可用 mock `SandboxClient`）

- [ ] **Step 1: `HitlParamSupport.expandBodyFromParams`**

```java
// 删除/跳过 old_string+new_string → formatEditUnifiedDiff 分支
// edit 正文只走 metadata.editDiff；detail 复制文本由调用方用 toUnifiedText 填
String content = params.get("content");
if (StringUtils.hasText(content)) return content;
String command = params.get("command");
return StringUtils.hasText(command) ? command : null;
```

更新 `HitlParamSupportTest.expandBodyFromParams_editOldNew` → 期望 `null`。

- [ ] **Step 2: HITL attach 时构建 editDiff**

在 `awaitConfirmation`（及 workflow 同源路径）当 `SandboxIds.EDIT.equals(toolId)`：

```java
SandboxEditDiff preview = null;
String expandBody = HitlParamSupport.expandBodyFromParams(params);
try {
    String sid = Optional.ofNullable(SandboxSessionHolder.get(timelineBridgeId))
            .map(SandboxSessionHolder.Binding::sessionId).orElse(null);
    String path = params.get("path");
    if (sid != null && StringUtils.hasText(path)) {
        FsContentDto fs = sandboxClient.readFsContent(sid, path, workspaceContentMaxChars);
        String before = fs != null ? fs.content() : null;
        preview = EditDiffBuilder.tryBuild(before, params.get("old_string"), params.get("new_string"), 3)
                .map(d -> d.withPath(path))
                .orElse(null);
    }
} catch (Exception e) {
    log.debug("HITL editDiff preview skipped: {}", e.getMessage());
}
if (preview != null) {
    expandBody = preview.toUnifiedText();
}
// attachHitlPending(..., expandBody) 且 metadata 合并 withEditDiff
```

扩展 `attachHitlPending` / `attachHitlPendingOnStep`：增加 `SandboxEditDiff editDiff` 参数，内部：

```java
StepMetadata meta = StepMetadata.withHitl(base, HitlStepMeta.awaiting(...));
meta = StepMetadata.withEditDiff(meta, editDiff);
emitter.applyAt(stepId, null, EventKind.PROGRESS, null, expandDetail, meta, ...);
```

读失败 / 不唯一 → `editDiff=null`，`expandDetail=null`（空态，**无**片段弱渲染）。

注入：`SandboxClient`、`AgentSandboxProperties`（maxChars）。

- [ ] **Step 3: 编译测试**

```bash
mvn test -pl orchestrator -Dtest=HitlParamSupportTest -q
```

- [ ] **Step 4: Commit**

```bash
git commit -am "$(cat <<'EOF'
feat(orchestrator): HITL edit preview via EditDiffBuilder only

EOF
)"
```

---

### Task 6: 前端 `sandboxEditDiff` + metadata 解析（无旧兼容）

**Files:**
- Modify: `sunshine-ui/src/api/sandboxEditDiff.ts`
- Modify: `sunshine-ui/src/api/sandboxEditDiff.test.ts`
- Modify: `sunshine-ui/src/api/processingSteps.ts`（`StepMetadata.editDiff`）
- Modify: `sunshine-ui/src/api/processingStepsParse.ts`（解析 editDiff）

- [ ] **Step 1: 失败单测（替换旧 parse 产品用例）**

```ts
import { describe, expect, it } from 'vitest'
import {
  writeContentAsAddLines,
  linesFromEditDiffMeta,
  summarizeDiffCounts,
} from './sandboxEditDiff'

describe('sandboxEditDiff', () => {
  it('writeContentAsAddLines assigns newLine 1..N', () => {
    const lines = writeContentAsAddLines('a\nb\n')
    expect(lines).toEqual([
      { kind: 'add', text: 'a', oldLine: null, newLine: 1 },
      { kind: 'add', text: 'b', oldLine: null, newLine: 2 },
    ])
    expect(summarizeDiffCounts(lines)).toEqual({ add: 2, del: 0 })
  })

  it('linesFromEditDiffMeta maps structured metadata', () => {
    const lines = linesFromEditDiffMeta({
      path: '/x.py',
      contextRadius: 3,
      lines: [
        { kind: 'ctx', text: 'a', oldLine: 1, newLine: 1 },
        { kind: 'del', text: 'b', oldLine: 2, newLine: null },
        { kind: 'add', text: 'c', oldLine: null, newLine: 2 },
        { kind: 'fold', text: '', oldLine: null, newLine: null },
      ],
    })
    expect(lines?.map(l => l.kind)).toEqual(['ctx', 'del', 'add', 'fold'])
  })

  it('linesFromEditDiffMeta returns null when missing', () => {
    expect(linesFromEditDiffMeta(undefined)).toBeNull()
  })
})
```

删除（或改成非产品）对 `parseSandboxEditDiff` 的依赖用例；若函数仍被复制逻辑需要可标 `@deprecated` 并停止导出给 expand。

- [ ] **Step 2: 实现类型**

```ts
export type SandboxDiffLineKind = 'del' | 'add' | 'ctx' | 'fold'
export type SandboxDiffLine = {
  kind: SandboxDiffLineKind
  text: string
  oldLine?: number | null
  newLine?: number | null
}
export type SandboxEditDiffMeta = {
  path?: string
  contextRadius?: number
  lines: SandboxDiffLine[]
}
```

`writeContentAsAddLines` 赋 `newLine`；`linesFromEditDiffMeta` 校验 `lines` 数组。

`StepMetadata` 增加 `editDiff?: SandboxEditDiffMeta`；Parse 从 SSE object 读入。

- [ ] **Step 3: 跑测**

```bash
cd /usr/local/gitproj/my-sunshine-agent/sunshine-ui && npx vitest run src/api/sandboxEditDiff.test.ts
```

Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add sunshine-ui/src/api/sandboxEditDiff.ts sunshine-ui/src/api/sandboxEditDiff.test.ts \
  sunshine-ui/src/api/processingSteps.ts sunshine-ui/src/api/processingStepsParse.ts
git commit -m "$(cat <<'EOF'
feat(ui): structured editDiff metadata; drop legacy unified parse path

EOF
)"
```

---

### Task 7: `CodeLineGutter` + `SandboxDiffView` + expand 接线

**Files:**
- Create: `sunshine-ui/src/components/sandbox/CodeLineGutter.vue`
- Create: `sunshine-ui/src/components/sandbox/SandboxDiffView.vue`
- Modify: `sunshine-ui/src/components/operation/SandboxToolExpandPanel.vue`
- Modify: `sunshine-ui/src/composables/useSandboxToolExpand.ts`

- [ ] **Step 1: `CodeLineGutter.vue`**

Props：`mode: 'diff' | 'file'`；`oldLine?: number | null`；`newLine?: number | null`；`mark?: '' | '+' | '-'`

```vue
<template>
  <span class="code-gutter" :class="`is-${mode}`" aria-hidden="true">
    <template v-if="mode === 'diff'">
      <span class="gutter-old">{{ oldLine ?? '' }}</span>
      <span class="gutter-new">{{ newLine ?? '' }}</span>
      <span class="gutter-mark">{{ mark || '' }}</span>
    </template>
    <template v-else>
      <span class="gutter-new">{{ newLine ?? '' }}</span>
    </template>
  </span>
</template>
```

样式：等宽、右对齐、`user-select: none`、muted 色；宽约 `ch` 随位数（可用 CSS `min-width`）。

- [ ] **Step 2: `SandboxDiffView.vue`**

Props：`lines: SandboxDiffLine[]`；`lang: string | null`

每行：`CodeLineGutter` + `code.hljs`（沿用 useSandboxToolExpand 的 highlight）；`kind===fold'` 显示 `···`（无高亮）；`is-del`/`is-add` 背景同现面板。

- [ ] **Step 3: `useSandboxToolExpand`**

```ts
const sandboxEditDiffLines = computed((): SandboxDiffLine[] => {
  const step = toValue(stepSource)
  if (!isSandboxTool.value || isSandboxExec.value) return []
  if (sandboxPathEntries.value.length) return []
  if (isSandboxEditStep(step)) {
    return linesFromEditDiffMeta(step.metadata?.editDiff) ?? []
  }
  if (isSandboxWriteStep(step) && sandboxRaw.value) {
    return writeContentAsAddLines(sandboxRaw.value)
  }
  return []
})
```

**禁止**再调用 `parseSandboxEditDiff(sandboxRaw)`。

- [ ] **Step 4: `SandboxToolExpandPanel`** 用 `<SandboxDiffView :lines="..." />` 替换手写 `op-diff-line` 循环；复制仍用 `sandboxRaw` / unified detail。

- [ ] **Step 5: Commit**

```bash
git add sunshine-ui/src/components/sandbox/CodeLineGutter.vue \
  sunshine-ui/src/components/sandbox/SandboxDiffView.vue \
  sunshine-ui/src/components/operation/SandboxToolExpandPanel.vue \
  sunshine-ui/src/composables/useSandboxToolExpand.ts
git commit -m "$(cat <<'EOF'
feat(ui): Git-style dual line gutter for sandbox write/edit expand

EOF
)"
```

---

### Task 8: 沙箱抽屉全文行号

**Files:**
- Modify: `sunshine-ui/src/components/sandbox/SandboxPreviewPane.vue`
- 可选 Modify: `sunshine-ui/src/components/sandbox/SandboxWorkspaceDrawer.vue`（若需传 split lines）

- [ ] **Step 1: 预览区**

对 `previewCodeHtml` / 纯文本 `preview`：按 `\n` 拆成行数组（空内容 → 不渲染 gutter，符合 spec）。

结构示例：

```vue
<pre v-else-if="previewLines.length" class="preview-code preview-code--guttered">
  <div v-for="(line, i) in previewLines" :key="i" class="preview-line">
    <CodeLineGutter mode="file" :new-line="i + 1" />
    <code :class="previewLangClass" v-html="lineHtml[i]" />
  </div>
</pre>
```

`lineHtml`：对**整文件**一次 hljs 再按行切不安全；改为**逐行** `highlightCode(line, lang)`（与时间线一致），或整块 highlight 后不用行号包裹——**采用逐行 highlight**（简单、与 diff 一致）。

Markdown 美化模式：不显示 gutter。

- [ ] **Step 2: 样式** — gutter + 代码横滚容器；`white-space: pre`；行高对齐。

- [ ] **Step 3: Commit**

```bash
git commit -am "$(cat <<'EOF'
feat(ui): absolute line numbers in sandbox workspace preview

EOF
)"
```

---

### Task 9: 文档 + 手工验收清单

**Files:**
- Modify: `docs/sandbox/README.md`
- Modify: spec 状态行可改为「实施中/已落地」（完成手工验后）

- [ ] **Step 1: README**

- 工作区抽屉：代码预览带绝对行号  
- edit 展开：Git 双行号 + ±3 上下文（`metadata.editDiff`）；**无**旧消息兼容

- [ ] **Step 2: 手工验收**

1. Chat 触发 `sandbox__write` 新文件 → 时间线展开：全绿 `add`、仅新行号、与抽屉行号一致  
2. `sandbox__edit` 改文件中部一行 → 上下各 ≤3 行 ctx、头尾 `···`、旧/新号正确  
3. HITL awaiting 展开：有 `editDiff` 或空态（无片段假 diff）  
4. 历史无 `editDiff` 的 edit：不出现双栏 gutter  

- [ ] **Step 3: Commit docs**

```bash
git commit -am "$(cat <<'EOF'
docs(sandbox): line numbers and contextual editDiff in README

EOF
)"
```

---

## Spec coverage（自检）

| Spec 项 | Task |
|---------|------|
| 抽屉绝对行号 | T8 |
| Git 双栏 + 红绿 | T7 |
| ±3 ctx + fold | T1–T2 |
| write 全 add 行号 | T6–T7 |
| 后端 meta → metadata | T2–T4 |
| HITL 同 Builder、无弱兜底 | T5 |
| 不做旧消息兼容 | T4/T5/T6 |
| 单测 Builder / 前端 | T1/T6 |
| README | T9 |

## 类型一致性

- Java/TS：`kind` ∈ `ctx|del|add|fold`；行号 1-based；缺侧用 `null`
- meta key / SSE field：`editDiff`
- 复制文本：`toUnifiedText()`（无 fold 行）
