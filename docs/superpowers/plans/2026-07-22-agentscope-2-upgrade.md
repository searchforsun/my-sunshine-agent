# AgentScope Java 2.0 分阶段升级实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 Sunshine 从 AgentScope-Java 1.0.8 分 8 阶段（P0–P7）升级到 2.0，用原生 stateStore/interrupt/streamEvents/TaskList/Subagent/Workspace/Permission 替换自研 ReAct 内核，每阶段含强制回滚测试。

**Architecture:** 方案 1 兼容桥先行——AS 2.0 内核（HarnessAgent 单例 + AgentState + 事件流）在下，Sunshine 外壳（Timeline V2 / SSE / GenerationJob / Catalog / 路由 / Plan-Workflow）在上，中间经 EventAdapter 桥接。外壳自留，内核可替，禁长期双轨。

**Tech Stack:** Java 21 · Spring Boot 3.2.9 · AgentScope-Java 2.0.x（`io.agentscope:agentscope` + `agentscope-extensions-model-openai`）· Reactor（Flux/Mono）· Redis（AgentStateStore）· MySQL（不动 DDL）· Nacos（feature flag + 运行参数）

**Spec:** `docs/superpowers/specs/2026-07-22-agentscope-2-upgrade-design.md`（已锁定 13 条决策，实施前必读 §3.1 / §4.1a / §7.5a）

## Global Constraints

- 版本：`agentscope.version` 单点定义于 `pom.xml:44`，**仅此一处**升级到 2.0.x；勿升 Spring Boot 3.3+；Sa-Token 锁 1.45.0
- 载体：ReAct 主路径统一 `HarnessAgent` 单例（spec §3.1），P0 即定型，禁 P2→P3 二次迁移
- AgentState：**Redis-only · TTL=7d · 零 MySQL DDL**；`sessionId = assistantMessageId`（消息级推理现场，spec §4.1a）
- 每阶段 feature flag（spec §7.5）默认开、保留至 P7 统一拆；flag 配置 SSOT = Nacos，Java 侧落 `AgentExecutionProperties`
- 回滚测试为**强制闸门 #4**（spec §7.5a）：三段式（正向→回滚→回切）+ 脏数据清零，红一个即整阶段红灯
- 提示词正文 SSOT = prompt-manager Catalog（`/prompts`），**禁止**在 Java 代码硬编码提示词
- 模型输出不二次加工：禁截断/摘要/过滤兜底
- Nacos 配置改动后必跑 `python scripts/sync_nacos.py` 并重启 orchestrator
- 编译：`mvn -pl orchestrator -am compile`；启动：`python scripts/start.py`（或 `start.py --only orchestrator`）
- 改 orchestrator 后：编译 → 重启 → 跑对应 Live 留记录
- 运维脚本统一 Python（`scripts/*.py`），禁止临时脚本入库
- 每阶段 commit 信息前缀：`feat(as2-p<n>)` / `test(as2-p<n>)` / `chore(as2-p<n>)`

## 前端回归总约定（每阶段必读）

前端回归 = **自动化 Playwright e2e** + **人工真请求**两层，均为该阶段出口闸门的组成部分。

| 层 | 工具 | 跑法 | 覆盖 |
|----|------|------|------|
| 自动化 | Playwright（`sunshine-ui/e2e/`） | `cd sunshine-ui && npm run test:e2e`（CI 走 `--mode e2e-mock` mock-server :8001；阶段验收改打真实 Gateway，见下） | 时间线渲染、DAG、抽屉、确认框、任务卡等**结构性断言** |
| 人工 | 浏览器 `http://ecs4c16g:5173` | `npm run dev`（proxy `/api` → `127.0.0.1:8000`） | 流式手感、取消 UX、续跑语义、跨模式切换等**机器难断言项** |

**约定**

1. 每阶段**至少 1 个新/改 Playwright spec** 落入 `sunshine-ui/e2e/`，命名 `as2-p<n>-<feature>.spec.ts`，纳入 `npm run test:e2e`。
2. e2e 默认打 mock；**阶段出口前**必须用 `PLAYWRIGHT_BASE_URL=http://ecs4c16g:5173`（或 `webServer` 配置）切真实 Gateway 跑一次同 spec，确认与后端真链路一致。
3. 人工步骤固定格式：**前置（模式/开关）→ 提示词 → 预期（步骤序列 + 关键 UI 断言）**；提示词复用各 `verify_*_live.py` 的 `*_QUERY` 常量，保证 e2e 与 Live 同源。
4. 提示词原则：**短、确定性高、能触发该阶段特性**；涉及工具调用/HITL/取消的，提示词须能稳定命中（优先复用已验收的 golden query，不新造）。
5. 每阶段前端回归结果（e2e 绿 + 人工确认截图/记录）追加到 `docs/implementation-plan.md` 缺口行，与 Live 记录同级。

**提示词常量来源（e2e 与 Live 同源，禁止新造）**

| 常量 | 出处 | 内容 |
|------|------|------|
| `F1_QUERY` | `verify_react_taskboard_live.py:36` | 帮我查待审批报销，并对有风险的单据逐条说明原因 |
| `FN1_QUERY` | `verify_react_taskboard_live.py:37` | 先检索差旅报销相关制度，再查询待审批报销单，并对每条做合规分析后给出结论 |
| `E1_QUERY` | `verify_peer_collab_live.py:39` | 请人事制度分析专家和费用报销分析专家分别审查这笔报销是否合规，并互相验证 |
| `S1_QUERY` | `verify_spawn_subagent_live.py:47` | 请调用 spawn_subagent，prompt 写：用 search_knowledge 检索差旅住宿标准并返回要点摘要；label=制度检索… |
| `HITL_QUERY` | `verify_hitl_live.py:263` | 请调用 {OA_APPROVE} 工具审批 OA 待办 taskId=T1001，不要查询其它工具。 |
| `SANDBOX_CANCEL_QUERY` | `verify_sandbox_tool_cancel_live.py:29` | 请调用 sandbox__exec：command 必须是 sleep 120，并传 timeout_sec=180… |

e2e spec 顶部统一定义这些常量（从对应 verify 脚本 docstring 复制），保证 Playwright 与 Live 触发同一后端路径。

---

## P0 — 依赖可编译可跑

**出口闸门**：编译绿 + 7 类删除项零残留 + 基础 ReAct Chat 前端一轮 + peer 顺序降级可用 + 反应式 hub spike 结论 + `verify_rollback_p0_compile` 全绿。

### Task P0-1: 升级 agentscope.version + 引入 openai 扩展

**Files:**
- Modify: `pom.xml:44,76-80`

**Interfaces:**
- Produces: `agentscope.version=2.0.x`、新依赖 `io.agentscope:agentscope-extensions-model-openai`，供 P0-2 起所有阶段使用。

- [ ] **Step 1: 确认 2.0 GA 版本号**

Run: `mvn dependency:get -Dartifact=io.agentscope:agentscope:2.0.0 -o 2>&1 | tail -5 || curl -s https://repo1.maven.org/maven2/io/agentscope/agentscope/maven-metadata.xml | grep -oP '(?<=<latest>)[^<]+'`
Expected: 输出生效 GA 版本（下文以 `2.0.0` 为例，实施时以实际 latest 为准并全文替换）

- [ ] **Step 2: 改 `pom.xml:44` 版本属性**

```xml
<agentscope.version>2.0.0</agentscope.version>
```

- [ ] **Step 3: 在 `pom.xml:80` 后追加 openai 扩展依赖管理**

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-model-openai</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

- [ ] **Step 4: 在 `orchestrator/pom.xml` 的 `agentscope` 依赖后追加扩展引用**

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-model-openai</artifactId>
</dependency>
```

- [ ] **Step 5: 验证依赖解析**

Run: `mvn -pl orchestrator -am dependency:resolve 2>&1 | grep -E "agentscope" | head -10`
Expected: 出现 `io.agentscope:agentscope:jar:2.0.0` 与 `agentscope-extensions-model-openai:jar:2.0.0`，无 `1.0.8`

- [ ] **Step 6: Commit**

```bash
git add pom.xml orchestrator/pom.xml
git commit -m "chore(as2-p0): bump agentscope to 2.0.0 + add openai extension"
```

### Task P0-2: 迁移 OpenAIChatModel 包路径

**Files:**
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/agent/ReActAgentFactory.java:13`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/expert/ExpertPeerAgentFactory.java`（import 行）

**Interfaces:**
- Consumes: Task P0-1 的扩展依赖。
- Produces: 编译期 `OpenAIChatModel` 指向 `io.agentscope.extensions.model.openai.OpenAIChatModel`，供 P0-3 builder 改造用。

- [ ] **Step 1: 改 import（两个工厂文件同样处理）**

将 `import io.agentscope.core.model.OpenAIChatModel;` 改为：

```java
import io.agentscope.extensions.model.openai.OpenAIChatModel;
```

- [ ] **Step 2: 编译验证（预期仍有其他错误，仅确认 import 不再报"包不存在"）**

Run: `mvn -pl orchestrator -am compile 2>&1 | grep -E "OpenAIChatModel|package io.agentscope.core.model does not exist" | head -5`
Expected: 无 `package io.agentscope.core.model does not exist`

- [ ] **Step 3: Commit**

```bash
git add orchestrator/src/main/java/com/sunshine/orchestrator/agent/ReActAgentFactory.java orchestrator/src/main/java/com/sunshine/orchestrator/expert/ExpertPeerAgentFactory.java
git commit -m "refactor(as2-p0): migrate OpenAIChatModel to extensions module"
```

### Task P0-3: 新增 AgentStateStore 占位 + Builder `.memory()` → `.stateStore()`

**Files:**
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/agent/state/AgentStateStoreConfig.java`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/agent/ReActAgentFactory.java:56-80`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/expert/ExpertPeerAgentFactory.java:40-58`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/config/AgentExecutionProperties.java`（新增 `as2` 块）

**Interfaces:**
- Consumes: Task P0-2 的 OpenAIChatModel。
- Produces: `AgentStateStoreConfig#redisAgentStateStore()`（P2 续跑直接复用）；`AgentExecutionProperties.As2`（feature flag 容器，P0–P6 共用）。

- [ ] **Step 1: 在 `AgentExecutionProperties` 末尾（`PlanWorkflow` 内嵌类之后、类闭合前）新增 `as2` 块**

```java
    private As2 as2 = new As2();

    @Data
    public static class As2 {
        /** P0 总开关：编译期置 true，所有 as2 子 flag 依此生效 */
        private boolean enabled = true;
        /** P1 事件路径：true=streamEvents，false=legacy-hook */
        private boolean streamEvents = false;
        /** P2 续跑：true=原生 checkpoint，false=retainIntentStepsOnly 软续跑 */
        private boolean reactCheckpoint = false;
        /** P3：true=enableTaskList，false=manage_tasks */
        private boolean tasklistNative = false;
        /** P4：true=Harness subagent，false=SpawnSubagentTool */
        private boolean subagentNative = false;
        /** P5：true=Workspace 沙箱，false=现网沙箱内核 */
        private boolean sandboxWorkspace = false;
        /** P5：true=Permission HITL，false=自研 HITL */
        private boolean hitlPermission = false;
        /** P6：true=反应式 hub，false=顺序桥 */
        private boolean peerReactive = false;
        /** AgentState Redis TTL 秒（spec §4.1 锁定 7 天） */
        private long stateTtlSec = 604800L;
        /** AgentState Redis key 前缀（与 GenerationJob 隔离） */
        private String stateKeyPrefix = "agentscope:state:";
    }
```

- [ ] **Step 2: 创建 `AgentStateStoreConfig.java`**

```java
package com.sunshine.orchestrator.agent.state;

import com.sunshine.orchestrator.config.AgentExecutionProperties;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.RedisAgentStateStore;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/** AgentState Redis Store（spec §4.1：Redis-only · TTL 7d · key 前缀隔离） */
@Configuration
@RequiredArgsConstructor
public class AgentStateStoreConfig {

    private final StringRedisTemplate redisTemplate;
    private final AgentExecutionProperties props;

    @Bean
    public AgentStateStore redisAgentStateStore() {
        AgentExecutionProperties.As2 as2 = props.getAs2();
        return new RedisAgentStateStore(redisTemplate, as2.getStateKeyPrefix(), as2.getStateTtlSec());
    }
}
```

- [ ] **Step 3: 改 `ReActAgentFactory.create` builder 链（56-70 行）——删 `.memory(...)`，改 `.stateStore(...)`；AutoContext 暂留 hook 桥**

```java
        ReActAgent.Builder builder = ReActAgent.builder()
                .name(resolveAgentName(request))
                .sysPrompt(composeSystemPrompt(request))
                .model(model)
                .toolkit(toolkit)
                .maxIters(maxIters)
                .stateStore(stateStore);   // 注入 AgentStateStore（P0 占位，P2 启用续跑语义）

        // AutoContextHook 仍走 LegacyHookDispatcher 桥（spec §P0 清单第 7 行，P7 才拆）
        if (autoContext) {
            builder.hook(new AutoContextHook())
                    .hook(stepHookFactory.forBridge(bridgeId));
        } else {
            builder.hook(stepHookFactory.forBridge(bridgeId));
        }
        return builder.build();
```

同时：注入 `private final AgentStateStore stateStore;` 到构造器（`@RequiredArgsConstructor` 自动覆盖）；删除 `createMemory(OpenAIChatModel)` 方法与 `buildAutoContextConfig` 中对 `AutoContextMemory` 的引用（P2 用 `CompactionConfig` 替代，本阶段先注释保留 `AutoContextConfig` 字段供 P2 对标阈值）。

- [ ] **Step 4: `ExpertPeerAgentFactory` 同步删除 `.memory(...)`、改 `.stateStore(...)`**（同 Step 3 模式）

- [ ] **Step 5: 编译验证**

Run: `mvn -pl orchestrator -am compile 2>&1 | grep -E "ERROR|BUILD" | head -20`
Expected: `BUILD SUCCESS`；无 `cannot find symbol: method memory` / `AutoContextMemory`

- [ ] **Step 6: Commit**

```bash
git add orchestrator/src/main/java/com/sunshine/orchestrator/agent/state/AgentStateStoreConfig.java orchestrator/src/main/java/com/sunshine/orchestrator/config/AgentExecutionProperties.java orchestrator/src/main/java/com/sunshine/orchestrator/agent/ReActAgentFactory.java orchestrator/src/main/java/com/sunshine/orchestrator/expert/ExpertPeerAgentFactory.java
git commit -m "feat(as2-p0): wire AgentStateStore placeholder + drop removed .memory() builder"
```

### Task P0-4: ExpertHubEngine 去 MsgHub 改顺序调用

**Files:**
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/expert/ExpertHubEngine.java:83-135`

**Interfaces:**
- Consumes: 现有 `ExpertPeerAgentFactory` / `roundCoordinator` / `expertSpeakStreamer`。
- Produces: `invokeExpertSequential(...)` 顺序调用方法，P6 反应式恢复时复用。

- [ ] **Step 1: 删除 `io.agentscope.core.pipeline.MsgHub` import 与 83-87 行 `try (MsgHub hub = ...)` 块**

- [ ] **Step 2: 把 `hub.broadcast(...)`（L118-122）替换为显式 contextBlocks 追加——新建私有方法**

```java
    /** AS2_P0_PEER_SEQUENTIAL：去 MsgHub，专家间上下文经 transcript 显式传递（spec §3 / §P6 G-a 路径 1） */
    private void appendToTranscript(List<PeerTranscriptEntry> transcript, String expertId, Msg reply) {
        transcript.add(new PeerTranscriptEntry(expertId, reply.getTextContent(), System.currentTimeMillis()));
    }
```

- [ ] **Step 3: 主循环（89-135）改为：每位专家 invoke 时把 `transcript` 作为 `contextBlocks` 传入 `invokeAgent`，发言后 `appendToTranscript`；保留 `roundCoordinator.evaluateContinue` 早退**

- [ ] **Step 4: 类顶部加标记常量**

```java
    /** spec §P0：顺序桥标记，P6 反应式恢复后删除 */
    public static final String AS2_P0_PEER_SEQUENTIAL = "AS2_P0_PEER_SEQUENTIAL";
```

- [ ] **Step 5: 编译 + 单测**

Run: `mvn -pl orchestrator -am compile 2>&1 | grep BUILD && mvn -pl orchestrator test -Dtest='ExpertHub*' 2>&1 | grep -E "Tests run|BUILD"`
Expected: `BUILD SUCCESS` + 专家相关单测通过

- [ ] **Step 6: Commit**

```bash
git add orchestrator/src/main/java/com/sunshine/orchestrator/expert/ExpertHubEngine.java
git commit -m "refactor(as2-p0): replace MsgHub with sequential expert invocation + transcript context"
```

### Task P0-5: 反应式 hub spike（spec §P6 G-a 路径 1 验证）

**Files:**
- Create: `docs/superpowers/spikes/2026-07-23-peer-reactive-hub-spike.md`

- [ ] **Step 1: 半日 spike——在 `ExpertHubEngine` 顺序桥基础上，验证「每专家 HarnessAgent/ReActAgent + `streamEvents` + 自研 `roundCoordinator.selectReactiveSpeakers`」在 2.0 下编译与流式可行**

- [ ] **Step 2: 结论写入 spike 文档**：可行 → P6 按路径 1；不可行 → 记录阻塞点，P6 降级「顺序 + 自研轮次控制」并明示对 4.7.3 反应式特性的取舍

- [ ] **Step 3: Commit**

```bash
git add docs/superpowers/spikes/2026-07-23-peer-reactive-hub-spike.md
git commit -m "docs(as2-p0): reactive hub feasibility spike (G-a path 1)"
```

### Task P0-6: 回滚测试脚本 `verify_rollback_p0_compile.py`

**Files:**
- Create: `scripts/verify_rollback_p0_compile.py`

**Interfaces:**
- Consumes: spec §7.5a 通用三段式。
- Produces: P0 回滚闸门脚本，P0 出口必跑。

- [ ] **Step 1: 写脚本（骨架，沿用 `verify_spawn_subagent_live.py` 的 argparse/main 模式）**

```python
#!/usr/bin/env python3
"""AS2 P0 回滚验收（spec §7.5a）：7 类删除项零残留 + 1.0.8 回切编译绿。"""
import argparse, subprocess, sys, re

ROOT = "/usr/local/gitproj/my-sunshine-agent"
DELETED_PATTERNS = [  # spec §P0 清单：7 类删除项在 orchestrator 源码中应零残留
    (r"io\.agentscope\.core\.pipeline", "pipeline 包"),
    (r"io\.agentscope\.core\.model\.OpenAIChatModel", "core.model.OpenAIChatModel"),
    (r"\.memory\(\s*new\s+AutoContextMemory", ".memory(AutoContextMemory)"),
    (r"SessionManager", "SessionManager"),
    (r"io\.agentscope\.core\.plan\.", "core.plan 包"),
    (r"agent\.stream\(", "粗粒度 stream()"),
    (r"StatePersistence", "StatePersistence"),
]

def sh(cmd):
    return subprocess.run(cmd, shell=True, cwd=ROOT, capture_output=True, text=True)

def grep_residual():
    bad = []
    r = sh("grep -rnE '%s' orchestrator/src/main/java --include='*.java' || true" % "|".join(p for p, _ in DELETED_PATTERNS))
    for line in r.stdout.splitlines():
        for pat, name in DELETED_PATTERNS:
            if re.search(pat, line):
                bad.append(f"{name}: {line.strip()}")
    return bad

def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--check-rollback", action="store_true", help="临时回切 1.0.8 验证编译（需 sed 改 pom 并还原）")
    args = ap.parse_args()

    residual = grep_residual()
    if residual:
        print("[FAIL] 删除项残留:"); [print("  " + b) for b in residual]; return 1
    print("[OK] 7 类删除项零残留")

    r = sh("mvn -pl orchestrator -am compile -q 2>&1 | tail -3")
    if r.returncode != 0:
        print("[FAIL] 2.0 编译失败\n" + r.stdout); return 1
    print("[OK] 2.0 编译绿")

    if args.check_rollback:
        sh("sed -i 's|<agentscope.version>2.0.0</agentscope.version>|<agentscope.version>1.0.8</agentscope.version>|' pom.xml")
        rb = sh("git stash && mvn -pl orchestrator -am compile -q 2>&1 | tail -3; git stash pop")
        sh("sed -i 's|<agentscope.version>1.0.8</agentscope.version>|<agentscope.version>2.0.0</agentscope.version>|' pom.xml")
        print("[OK] 1.0.8 回切编译验证（stash 暂存下）")
    return 0

if __name__ == "__main__":
    sys.exit(main())
```

- [ ] **Step 2: 跑脚本**

Run: `python scripts/verify_rollback_p0_compile.py`
Expected: 全 `[OK]`，exit 0

- [ ] **Step 3: Commit**

```bash
git add scripts/verify_rollback_p0_compile.py
git commit -m "test(as2-p0): rollback gate — 7 deleted-item residual + compile check"
```

### Task P0-7: P0 出口验收（前端真请求）

- [ ] **Step 1: Nacos 同步 + 重启**

Run: `python scripts/sync_nacos.py && python scripts/start.py --only orchestrator`
Expected: orchestrator 启动无 AS 相关 NoClassDefFound

- [ ] **Step 2: 基础 ReAct Chat 前端一轮对话**（人工）：步骤时间线正常、正文流式完整

- [ ] **Step 3: peer-collab `$` 触发一轮**（人工）：能出专家步（顺序降级，不要求与现网轮次一致）

- [ ] **Step 4: 验收记录追加到 `docs/implementation-plan.md` 缺口行**

- [ ] **Step 5: Commit + 打 P0 标签**

```bash
git commit -m "test(as2-p0): gate pass — react + peer-sequential live" --allow-empty
git tag as2-p0-done
```

### Task P0-8: 前端回归（基线对齐 + peer 顺序降级）

**Files:**
- Modify: `sunshine-ui/e2e/processing-timeline-real.spec.ts`（断言基线不变）
- Create: `sunshine-ui/e2e/as2-p0-peer-sequential.spec.ts`

**自动化 e2e**

- [ ] **Step 1: 跑全量现有 e2e 确认无回归（升级 jar 后外壳应零变化）**

Run: `cd sunshine-ui && npm run test:e2e`
Expected: 全绿（重点 `processing-timeline-real.spec.ts` / `reasoning-panel.spec.ts` / `stream-content-format.spec.ts`）

- [ ] **Step 2: 新增 `as2-p0-peer-sequential.spec.ts`——peer 模式下专家步按顺序出现（不再断言反应式 hub 的并发交错）**

```ts
test('as2-p0 peer sequential expert steps', async ({ page }) => {
  await page.goto('/chat');
  await page.getByTestId('execution-mode-selector').selectOption('peer-collab');
  await page.getByTestId('composer-input').fill(E1_QUERY);
  await page.getByTestId('composer-send').click();
  // 顺序桥：专家步逐个 append，无并发交错
  const expertSteps = page.locator('[data-phase="expert"]');
  await expect(expertSteps.first()).toBeVisible({ timeout: 60000 });
  // 至少 2 位专家依次出现
  await expect(expertSteps).toHaveCount(2, { timeout: 120000 });
});
```

**人工真请求**

| 前置 | 提示词 | 预期 |
|------|--------|------|
| 模式 `react` | `帮我查待审批报销，并对有风险的单据逐条说明原因`（F1_QUERY） | 时间线 `intent → think → tool → generate` 完整；正文流式无截断；与 1.0.8 基线一致 |
| 模式 `peer-collab` | `请人事制度分析专家和费用报销分析专家分别审查这笔报销是否合规，并互相验证`（E1_QUERY） | 能出 ≥2 个专家步（`data-phase=expert`）；**顺序出现即可**，不要求与现网反应式轮次一致；Synthesizer 终态正文正常 |

- [ ] **Step 3: e2e 绿 + 人工两项确认 → 记录到 `docs/implementation-plan.md` → Commit**

```bash
git add sunshine-ui/e2e/as2-p0-peer-sequential.spec.ts
git commit -m "test(as2-p0): peer-sequential e2e + baseline regression pass"
```

---

## P1 — 事件契约 streamEvents → Timeline

**出口闸门**：ReAct/Workflow agent 节点步骤 + 流式正文前端一致 + 适配层性能基线达标（P99 不劣于 hook 路径 10%）+ `verify_rollback_p1_events` 全绿。

### Task P1-1: 新增 `AgentScopeEventMapper`（AgentEvent → StreamToken）

**Files:**
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/agent/runtime/AgentScopeEventMapper.java`
- Test: `orchestrator/src/test/java/com/sunshine/orchestrator/agent/runtime/AgentScopeEventMapperTest.java`

**Interfaces:**
- Consumes: `io.agentscope.core.event.AgentEvent` 28 类（`TextBlockDeltaEvent` / `ToolCallStartEvent` / `AgentStartEvent` / `AgentEndEvent` / `ReasoningDeltaEvent` 等）。
- Produces: `List<StreamToken> mapAgentEvent(AgentEvent ev, String messageId)`——P1-2 在 `ReActAgentRuntime` 中调用。返回现网 `com.sunshine.orchestrator.client.StreamToken`（record，工厂方法以 `client/StreamToken.java` 为准：`content(text)` / `contentStart(segmentId, afterStepId)` / `contentInSegment(segmentId, text)` / `contentEnd(segmentId)` / `reasoning(text)` / `step(ProcessingStep)` / `stepDelta(stepId, channel, text)`）。**禁止**新增 `StreamToken.noop()` 等不存在的方法——无映射时返回空 `List.of()`。

- [ ] **Step 1: 写失败单测——映射 `TextBlockDeltaEvent` → `StreamToken.content`**

```java
@Test
void mapsTextBlockDeltaToContent() {
    AgentScopeEventMapper m = new AgentScopeEventMapper();
    TextBlockDeltaEvent ev = TextBlockDeltaEvent.builder().delta("你好").build();
    List<StreamToken> out = m.mapAgentEvent(ev, "msg-1");
    assertEquals(1, out.size());
    assertEquals("content", out.get(0).type());
    assertEquals("你好", out.get(0).text());
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -pl orchestrator test -Dtest=AgentScopeEventMapperTest -q 2>&1 | grep -E "FAIL|ERROR" | head -3`
Expected: 编译错 / `cannot find symbol AgentScopeEventMapper`

- [ ] **Step 3: 实现 `AgentScopeEventMapper`——覆盖 spec §P1 数据流核心 5 类事件（工厂签名严格对齐 `client/StreamToken.java`）**

```java
package com.sunshine.orchestrator.agent.runtime;

import com.sunshine.orchestrator.client.StreamToken;
import io.agentscope.core.event.*;
import java.util.List;

/** AS2 P1：AgentEvent → StreamToken 适配（spec §P1，单一适配器，禁截断模型输出） */
public final class AgentScopeEventMapper {

    public List<StreamToken> mapAgentEvent(AgentEvent ev, String messageId) {
        if (ev instanceof TextBlockDeltaEvent d)  return List.of(StreamToken.content(d.getDelta()));
        if (ev instanceof ReasoningDeltaEvent r)  return List.of(StreamToken.reasoning(r.getDelta()));
        // tool/step 事件经 StepEventBridge 转 ProcessingStep 后由 StreamToken.step(...) 下发（P1-2 接线）
        return List.of();
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -pl orchestrator test -Dtest=AgentScopeEventMapperTest -q 2>&1 | grep "Tests run"`
Expected: `Tests run: 1, Failures: 0`

- [ ] **Step 5: 补 28 类事件中与 Timeline 相关的其余分支（ToolResultBlock / RequireUserConfirm 等），每类一测**

- [ ] **Step 6: Commit**

```bash
git add orchestrator/src/main/java/com/sunshine/orchestrator/agent/runtime/AgentScopeEventMapper.java orchestrator/src/test/java/com/sunshine/orchestrator/agent/runtime/AgentScopeEventMapperTest.java
git commit -m "feat(as2-p1): AgentScopeEventMapper — AgentEvent to StreamToken adapter"
```

### Task P1-2: `ReActAgentRuntime` 切 streamEvents（feature flag 双路径）

**Files:**
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/agent/runtime/ReActAgentRuntime.java:96-153`

**Interfaces:**
- Consumes: P1-1 `AgentScopeEventMapper`、`AgentExecutionProperties.As2#streamEvents`。
- Produces: 双路径入口——flag=开走 `streamEvents`，flag=关走现网 `stream()` + hook 桥（P7 才删旧路径）。

- [ ] **Step 1: 在 `ReActAgentRuntime.run` 事件消费处分支**

```java
        Flux<StreamToken> eventFlux = props.getAs2().isStreamEvents()
                ? agent.streamEvents(inputs, runtimeContext).map(ev -> eventMapper.mapAgentEvent(ev, sessionCtx))
                : legacyStreamPath(agent, inputs, options);   // 现网 L132-153 逻辑原样抽成私有方法
```

- [ ] **Step 2: 保留 `drainHookTokens(hookQueue)` 仅在 legacy 分支；streamEvents 分支正文仍经 `ContentSegmentCoordinator`（spec §P1 禁截断）**

- [ ] **Step 3: 编译 + 单测**

Run: `mvn -pl orchestrator -am compile -q && mvn -pl orchestrator test -Dtest='ReActAgentRuntime*' -q 2>&1 | grep "Tests run"`
Expected: 全绿

- [ ] **Step 4: Commit**

```bash
git add orchestrator/src/main/java/com/sunshine/orchestrator/agent/runtime/ReActAgentRuntime.java
git commit -m "feat(as2-p1): dual-path streamEvents behind agent.execution.as2.stream-events flag"
```

### Task P1-3: 性能基线脚本 `bench_event_adapter.py`

**Files:**
- Create: `scripts/bench_event_adapter.py`

- [ ] **Step 1: 写基准脚本——同一会话分别走 legacy / streamEvents 各 N=50 请求，采 SSE 端到端 P99**

```python
#!/usr/bin/env python3
"""AS2 P1 适配层性能基线（spec §7.5a / §P1 闸门 G-e）。"""
import argparse, statistics, subprocess, time, requests, os, sys

GW = os.environ.get("GATEWAY_URL", "http://ecs4c16g:8000")

def set_flag(on: bool):
    subprocess.run(["python", "scripts/sync_nacos.py", "--set", f"agent.execution.as2.stream-events={str(on).lower()}"], check=True)
    subprocess.run(["python", "scripts/start.py", "--only", "orchestrator"], check=True)
    time.sleep(8)

def one_round():
    t0 = time.time()
    with requests.post(f"{GW}/api/chat/stream", json={"query": "ping", "mode": "react"}, stream=True, timeout=60) as r:
        for _ in r.iter_lines():
            pass
    return (time.time() - t0) * 1000

def p99(samples):
    s = sorted(samples); return s[int(len(s) * 0.99) - 1]

def main() -> int:
    ap = argparse.ArgumentParser(); ap.add_argument("--n", type=int, default=50)
    args = ap.parse_args()
    set_flag(False); legacy = [one_round() for _ in range(args.n)]
    set_flag(True);  native = [one_round() for _ in range(args.n)]
    lp, np_ = p99(legacy), p99(native)
    print(f"legacy P99={lp:.0f}ms  streamEvents P99={np_:.0f}ms  delta={(np_-lp)/lp*100:+.1f}%")
    ok = np_ <= lp * 1.10
    print("[OK] 达标" if ok else "[FAIL] 超 10% 阈值")
    return 0 if ok else 1

if __name__ == "__main__":
    sys.exit(main())
```

- [ ] **Step 2: 跑基线**

Run: `python scripts/bench_event_adapter.py --n 50`
Expected: `[OK] 达标`

- [ ] **Step 3: Commit**

```bash
git add scripts/bench_event_adapter.py
git commit -m "test(as2-p1): event adapter perf baseline gate (P99 <= legacy +10%)"
```

### Task P1-4: 回滚测试 `verify_rollback_p1_events.py`

**Files:**
- Create: `scripts/verify_rollback_p1_events.py`

- [ ] **Step 1: 三段式脚本——flag 开/关各跑一次 ReAct，断言 SSE Timeline 步骤数与正文逐字节一致**

```python
#!/usr/bin/env python3
"""AS2 P1 回滚验收（spec §7.5a）：legacy-hook ↔ streamEvents 输出逐字节一致。"""
import json, os, subprocess, sys, time, requests

GW = os.environ.get("GATEWAY_URL", "http://ecs4c16g:8000")
Q = "用一句话介绍 AgentScope，并调用一次工具"

def set_flag(on: bool):
    subprocess.run(["python", "scripts/sync_nacos.py", "--set", f"agent.execution.as2.stream-events={str(on).lower()}"], check=True)
    subprocess.run(["python", "scripts/start.py", "--only", "orchestrator"], check=True); time.sleep(8)

def run_react():
    steps, body = [], []
    with requests.post(f"{GW}/api/chat/stream", json={"query": Q, "mode": "react"}, stream=True, timeout=120) as r:
        for line in r.iter_lines(decode_unicode=True):
            if not line or not line.startswith("data:"): continue
            ev = json.loads(line[5:])
            if ev.get("type") == "step": steps.append(ev["step"]["id"])
            if ev.get("type") == "content_delta": body.append(ev["delta"])
    return steps, "".join(body)

def main() -> int:
    set_flag(False); s1, b1 = run_react()
    set_flag(True);  s2, b2 = run_react()
    set_flag(False); s3, b3 = run_react()   # 回切再验
    ok = (s1 == s2 == s3) and (b1 == b2 == b3) and len(b1) > 0
    print(f"steps legacy={len(s1)} native={len(s2)} back={len(s3)}  body_len={len(b1)}")
    print("[OK] 三段输出一致" if ok else "[FAIL] 双路径输出不一致")
    return 0 if ok else 1

if __name__ == "__main__":
    sys.exit(main())
```

- [ ] **Step 2: 跑脚本**

Run: `python scripts/verify_rollback_p1_events.py`
Expected: `[OK] 三段输出一致`

- [ ] **Step 3: Commit + P1 出口验收（前端：步骤 + 流式正文）+ 打标签 `as2-p1-done`**

```bash
git add scripts/verify_rollback_p1_events.py
git commit -m "test(as2-p1): rollback gate — legacy vs streamEvents byte-identical"
```

### Task P1-5: 前端回归（事件契约切换后时间线/正文一致）

**Files:**
- Create: `sunshine-ui/e2e/as2-p1-stream-events.spec.ts`

**自动化 e2e**

- [ ] **Step 1: 新增 `as2-p1-stream-events.spec.ts`——streamEvents 路径下步骤序列 + 流式正文结构断言**

```ts
test('as2-p1 streamEvents timeline + content parity', async ({ page }) => {
  await page.goto('/chat');
  await page.getByTestId('execution-mode-selector').selectOption('react');
  await page.getByTestId('composer-input').fill(F1_QUERY);
  await page.getByTestId('composer-send').click();
  // 步骤容器渲染（OperationStack），含 think/tool/generate
  await expect(page.locator('.operation-stack .operation-card')).toHaveCount(3, { timeout: 90000 });
  // 正文流式完整（markdown 渲染、无截断）
  await expect(page.locator('.markdown-content').last()).not.toBeEmpty({ timeout: 90000 });
});
```

- [ ] **Step 2: 打真实 Gateway 跑一次**

Run: `cd sunshine-ui && PLAYWRIGHT_BASE_URL=http://ecs4c16g:5173 npm run test:e2e -- as2-p1-stream-events`
Expected: 绿（与 `verify_rollback_p1_events` 的 SSE 一致断言互为印证）

**人工真请求**

| 前置 | 提示词 | 预期 |
|------|--------|------|
| 模式 `react`、flag `stream-events=on` | `先检索差旅报销相关制度，再查询待审批报销单，并对每条做合规分析后给出结论`（FN1_QUERY） | 步骤卡片逐个出现、无重复/乱序；正文 token 平滑流式；**与 flag=off 时逐字一致** |
| 模式 `plan-workflow` | 触发含 RAG 节点的 Plan（复用 `verify_plan_dag_live` 用例） | DAG `node-*` 步骤 + `PlanNodeDrawer` 综合分析/最终输出正常；正文不经 think/generate 步 |

- [ ] **Step 3: e2e + 人工两项确认 → 记录 → Commit**

```bash
git add sunshine-ui/e2e/as2-p1-stream-events.spec.ts
git commit -m "test(as2-p1): streamEvents timeline/content parity e2e"
```

---

## P2 — 原生 ReAct checkpoint / resume

**出口闸门**：`verify_react_checkpoint_live` 全绿 + 前端停→「继续执行」步骤连续 + `verify_rollback_p2_checkpoint` 全绿。

### Task P2-1: HarnessAgent 单例骨架（spec §3.1 落地）

**Files:**
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/agent/ReActAgentFactory.java`（整体重构为单例缓存）
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/agent/HarnessAgentHolder.java`

**Interfaces:**
- Produces: `HarnessAgentHolder#get(toolsetKey)` 返回按工具集缓存的 HarnessAgent 单例；`ReActAgentFactory.create` 改为返回单例包装。P2-2 续跑、P3-P5 都基于此单例。

- [ ] **Step 1: 写失败单测——同 toolsetKey 两次 get 返回同一实例**

```java
@Test
void sameToolsetReturnsSingleton() {
    HarnessAgentHolder h = new HarnessAgentHolder(deps);
    HarnessAgent a1 = h.get("default");
    HarnessAgent a2 = h.get("default");
    assertSame(a1, a2);
}
```

- [ ] **Step 2: 跑测试确认失败 → 实现 `HarnessAgentHolder`（ConcurrentHashMap 缓存 + builder 装配 model/toolkit/stateStore/compaction）**

```java
package com.sunshine.orchestrator.agent;

import io.agentscope.harness.agent.HarnessAgent;
import java.util.concurrent.ConcurrentHashMap;

/** AS2 §3.1：HarnessAgent 单例载体（不可变配置 + RuntimeContext 区分会话） */
public final class HarnessAgentHolder {
    private final ConcurrentHashMap<String, HarnessAgent> cache = new ConcurrentHashMap<>();
    // deps 注入 model / stateStore / compaction 阈值
    public HarnessAgent get(String toolsetKey) {
        return cache.computeIfAbsent(toolsetKey, this::build);
    }
    private HarnessAgent build(String key) { /* builder 装配，详见 P2-2 */ return null; }
}
```

- [ ] **Step 3: 跑测试确认通过 → Commit**

```bash
git add orchestrator/src/main/java/com/sunshine/orchestrator/agent/HarnessAgentHolder.java
git commit -m "feat(as2-p2): HarnessAgentHolder singleton per toolset (spec 3.1)"
```

### Task P2-2: interrupt 停止 + checkpoint 续跑

**Files:**
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/generation/GenerationJob.java:158-169`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/controller/ChatController.java:157-167`
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/agent/ReactCheckpointService.java`

**Interfaces:**
- Consumes: P0-3 `AgentStateStore`、P2-1 `HarnessAgentHolder`、`AgentExecutionProperties.As2#reactCheckpoint`。
- Produces: `ReactCheckpointService#hasCheckpoint(assistantMessageId)` / `#interrupt(assistantMessageId)` / `#resume(assistantMessageId)`；前端 `resolveResumeMode` 对 ReAct 返回 `checkpoint`。

- [ ] **Step 1: 写失败单测——interrupt 后 stateStore 有落盘、hasCheckpoint=true**

```java
@Test
void interruptPersistsCheckpoint() {
    ReactCheckpointService s = new ReactCheckpointService(holder, stateStore);
    s.interrupt("u-1", "msg-1");
    assertTrue(s.hasCheckpoint("u-1", "msg-1"));
}
```

- [ ] **Step 2: 跑测试确认失败 → 实现 `ReactCheckpointService`（字段经 `@RequiredArgsConstructor` 注入；`userId` 由 `RuntimeContextFactory` 按当前请求上下文解析，与 Gateway `x-user-id` 同源）**

```java
package com.sunshine.orchestrator.agent;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** AS2 P2：ReAct 真续跑（spec §P2）；sessionId=assistantMessageId（§4.1a） */
@Service
@RequiredArgsConstructor
public class ReactCheckpointService {

    private final HarnessAgentHolder holder;
    private final AgentStateStore stateStore;

    /** userId 由调用方（ChatController `@RequestHeader("x-user-id")`，见 ChatController.java:83）显式传入 */
    public boolean hasCheckpoint(String userId, String assistantMessageId) {
        return stateStore.exists(userId, assistantMessageId);
    }

    public void interrupt(String userId, String assistantMessageId) {
        HarnessAgent agent = holder.get("default");
        agent.interrupt(RuntimeContext.builder()
                .userId(userId)
                .sessionId(assistantMessageId)
                .build());
    }

    public RuntimeContext resumeCtx(String userId, String assistantMessageId) {
        return RuntimeContext.builder()
                .userId(userId)
                .sessionId(assistantMessageId)
                .build();
    }
}
```

- [ ] **Step 3: `GenerationJob.cancel` 在 `reactCheckpoint=true` 时先调 `checkpointService.interrupt(messageId)` 再走现网取消**

- [ ] **Step 4: `ChatController:157-167` 分支——`reactCheckpoint=true && hasCheckpoint` 时走续跑（保留 steps、新事件 append），**不再**调 `retainIntentStepsOnly`；否则走现网软续跑**

- [ ] **Step 5: 编译 + 单测 → Commit**

```bash
git add orchestrator/src/main/java/com/sunshine/orchestrator/agent/ReactCheckpointService.java orchestrator/src/main/java/com/sunshine/orchestrator/generation/GenerationJob.java orchestrator/src/main/java/com/sunshine/orchestrator/controller/ChatController.java
git commit -m "feat(as2-p2): native interrupt/checkpoint resume behind react-checkpoint flag"
```

### Task P2-3: CompactionConfig 替代 AutoContextHook（spec §5）

**Files:**
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/agent/HarnessAgentHolder.java`

**前置事实（P0-3 实测校准）**：`memory/autocontext` 包在 2.0 已整体删除，P0-3 已移除全部引用，当前**无任何压缩**（SDK 默认）。阈值字段保留在 `MemoryProperties.AutoContext`（Nacos `agent.memory.auto-context.*` 仍下发、暂不消费）。本任务恢复压缩能力。

- [ ] **Step 1: 在 `HarnessAgentHolder.build` 装配 `CompactionConfig`，阈值对标 `MemoryProperties.AutoContext` 字段（先用 `javap` 确认 `CompactionConfig` builder 真实方法名——P0-3 已证 2.0 API 与文档假设可能不符；下方代码为示意，以实测为准）**

```java
        .compaction(CompactionConfig.builder()
                .triggerMessages(ac.getMsgThreshold())     // 对标 MemoryProperties.AutoContext.msgThreshold
                .keepMessages(ac.getLastKeep())            // 对标 MemoryProperties.AutoContext.lastKeep
                .build())
```

- [ ] **Step 2: 单测断言压缩触发时机与现网 AutoContext 一致 → Commit**

```bash
git commit -m "feat(as2-p2): CompactionConfig replaces AutoContextHook on HarnessAgent"
```

- [ ] **Step 3: 落地 AgentState TTL（P0-3 遗留）**：`RedisAgentStateStore` 无 TTL 参数，按 spec §4.1 锁定 7 天（604800s）。三选一并在报告说明：`clientAdapter` 自定义带 TTL 的 save / 在 keyPrefix 层包装 expire / 评估 SDK 是否后续版本补 TTL。完成后单测断言 key 写入带 TTL。

### Task P2-4: Live `verify_react_checkpoint_live.py`（新建）

**Files:**
- Create: `scripts/verify_react_checkpoint_live.py`

- [ ] **Step 1: 写 Live——同一会话：跑到第 2 个 tool 步后 `cancel` → 查 `hasCheckpoint=true` → 续跑 → 断言 steps 连续（无 intent 重发）、已完成 tool 不整轮重来**

（骨架沿用 `verify_spawn_subagent_live.py` 的 SSE 流式断言模式；关键断言：`resume` 后首步 id ≠ `intent`、已完成的 `tool-*` 步状态仍为 `completed`）

- [ ] **Step 2: 跑 Live**

Run: `python scripts/verify_react_checkpoint_live.py`
Expected: 全 `[OK]`

- [ ] **Step 3: Commit**

```bash
git add scripts/verify_react_checkpoint_live.py
git commit -m "test(as2-p2): react checkpoint resume live gate"
```

### Task P2-5: 回滚测试 `verify_rollback_p2_checkpoint.py`

**Files:**
- Create: `scripts/verify_rollback_p2_checkpoint.py`

- [ ] **Step 1: 三段式——checkpoint 开：停→续跑（steps 连续）；回滚关：停→重新生成（走软续跑，steps 清空重发）；回切开：停→续跑恢复；脏数据清零（Redis `agentscope:state:*` 按前缀清，MySQL steps 无半写入）**

- [ ] **Step 2: 跑脚本全绿 → Commit + P2 出口验收（前端停→「继续执行」）+ 打标签 `as2-p2-done`**

```bash
git add scripts/verify_rollback_p2_checkpoint.py
git commit -m "test(as2-p2): rollback gate — checkpoint vs soft-resume + state cleanup"
```

### Task P2-6: 前端回归（停→「继续执行」真续跑 UX）

**Files:**
- Create: `sunshine-ui/e2e/as2-p2-checkpoint-resume.spec.ts`

**自动化 e2e**

- [ ] **Step 1: 新增 `as2-p2-checkpoint-resume.spec.ts`——中断后续跑按钮文案 + 步骤连续性**

```ts
test('as2-p2 checkpoint resume keeps steps continuous', async ({ page }) => {
  await page.goto('/chat');
  await page.getByTestId('execution-mode-selector').selectOption('react');
  await page.getByTestId('composer-input').fill(F1_QUERY);
  await page.getByTestId('composer-send').click();
  // 出现 tool 步后中断
  await page.locator('[data-step-kind="tool"]').first().waitFor({ timeout: 60000 });
  await page.getByTestId('stop-generation').click();
  // 续跑按钮文案 = 继续执行（有 checkpoint）
  await expect(page.getByTestId('resume-btn')).toHaveText(/继续执行/);
  const stepsBefore = await page.locator('.operation-card').count();
  await page.getByTestId('resume-btn').click();
  // 续跑后：已有 steps 保留（不重新发 intent）、新事件 append
  await page.locator('.msg-status-completed').waitFor({ timeout: 120000 });
  const stepsAfter = await page.locator('.operation-card').count();
  expect(stepsAfter).toBeGreaterThanOrEqual(stepsBefore);   // 不回退、不整轮重来
});
```

**人工真请求**

| 前置 | 提示词 | 预期 |
|------|--------|------|
| 模式 `react`、flag `react-checkpoint=on` | `先检索差旅报销相关制度，再查询待审批报销单，并对每条做合规分析后给出结论`（FN1_QUERY），在 tool 步出现时点「停止」 | 停止后按钮显示「**继续执行**」（非「重新生成」）；点击后**保留已有 steps**、从断点续跑；已完成的 tool 步状态仍 `completed`、**不整轮重来** |
| 同上，但等 TTL 过期/手动清 Redis `agentscope:state:*` 后 | 同一消息点续跑 | 按钮退化为「**重新生成**」+ 明确提示（spec §4.3）；**禁止**静默空跑 |

- [ ] **Step 2: e2e + 人工两项确认（含 TTL 降级路径）→ 记录 → Commit**

```bash
git add sunshine-ui/e2e/as2-p2-checkpoint-resume.spec.ts
git commit -m "test(as2-p2): checkpoint resume UX e2e + TTL fallback manual check"
```

---

## P3 — TaskList 替换 TaskBoard

**出口闸门**：TaskBoard Live（改断言）全绿 + 前端任务卡一致 + `verify_rollback_p3_tasklist` 全绿。

### Task P3-1: enableTaskList + TodoTools 装配

**Files:**
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/agent/HarnessAgentHolder.java`

**Interfaces:**
- Consumes: `AgentExecutionProperties.As2#tasklistNative`。
- Produces: flag=开时 builder 装配 `.enableTaskList(true)` + `Toolkit.registerTool(new TodoTools())` + `TaskReminderMiddleware`；Timeline 仍投影为单一 `tasks` 步（复用 `TaskBoardStepLabelService`）。

- [ ] **Step 1: 写失败单测——flag 开时 toolkit 含 TodoTools 且 builder enableTaskList=true**

- [ ] **Step 2: 实现——`build` 分支**

```java
        if (props.getAs2().isTasklistNative()) {
            toolkit.registerTool(new TodoTools());
            builder.enableTaskList(true).middleware(new TaskReminderMiddleware());
        }
```

- [ ] **Step 3: Timeline 投影——TaskList 事件 → 单一 `tasks` 步（复用现网 `ProcessingStepHook` 的 tasks 锚定逻辑，前端零改）**

- [ ] **Step 4: 编译 + 单测 → Commit**

```bash
git commit -m "feat(as2-p3): enableTaskList + TodoTools behind tasklist-native flag"
```

### Task P3-2: 下线 manage_tasks 主路径（双轨期内 flag 切换）

**Files:**
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/agent/DynamicToolkitFactory.java`

- [ ] **Step 1: flag=开时不注册 `ManageTasksTool`；flag=关保留（P7 才删类）**

- [ ] **Step 2: 编译 + 单测 → Commit**

```bash
git commit -m "feat(as2-p3): gate manage_tasks registration behind tasklist-native flag"
```

### Task P3-3: TaskBoard Live 改断言 + 回滚 `verify_rollback_p3_tasklist.py`

**Files:**
- Modify: `scripts/verify_react_taskboard_live.py`
- Create: `scripts/verify_rollback_p3_tasklist.py`

- [ ] **Step 1: 改 Live 断言——任务卡数据源从 manage_tasks 切到 TaskList 事件投影，UI 字段不变**

- [ ] **Step 2: 回滚脚本三段式——TaskList ↔ manage_tasks 切换，任务卡 UI 数据一致、`tasks` 步投影不回退**

- [ ] **Step 3: 跑 Live + 回滚全绿 → Commit + P3 出口验收（前端任务卡）+ 打标签 `as2-p3-done`**

```bash
git commit -m "test(as2-p3): taskboard live re-assert + rollback gate"
```

### Task P3-4: 前端回归（任务卡数据源切换后 UI 零变化）

**Files:**
- Create: `sunshine-ui/e2e/as2-p3-tasklist.spec.ts`

**自动化 e2e**

- [ ] **Step 1: 新增 `as2-p3-tasklist.spec.ts`——TaskList 路径下任务卡渲染与现网一致**

```ts
test('as2-p3 tasklist renders single tasks step card', async ({ page }) => {
  await page.goto('/chat');
  await page.getByTestId('execution-mode-selector').selectOption('react');
  await page.getByTestId('composer-input').fill(F1_QUERY);
  await page.getByTestId('composer-send').click();
  // 单一 tasks 步（TaskBoardPanel）
  await expect(page.locator('.task-board-panel')).toBeVisible({ timeout: 90000 });
  await expect(page.locator('.task-board-panel')).toHaveCount(1);
  // 任务项状态推进 pending → in_progress → completed
  await expect(page.locator('.task-item[data-status="completed"]').first()).toBeVisible({ timeout: 120000 });
});
```

**人工真请求**

| 前置 | 提示词 | 预期 |
|------|--------|------|
| 模式 `react`、TaskBoard 开、flag `tasklist-native=on` | `帮我查待审批报销，并对有风险的单据逐条说明原因`（F1_QUERY） | 任务卡出现且**仍只有一个 `tasks` 步**（锚定 think 后）；任务项增删/状态切换正常；**与 flag=off（manage_tasks）UI 完全一致** |
| 回滚 flag=off | 同上 | 任务卡行为回退到 manage_tasks 路径，无脏数据/重复卡 |

- [ ] **Step 2: e2e + 人工两项确认 → 记录 → Commit**

```bash
git add sunshine-ui/e2e/as2-p3-tasklist.spec.ts
git commit -m "test(as2-p3): tasklist card parity e2e + manage_tasks rollback check"
```

---

## P4 — Harness Subagent 替换 spawn

**出口闸门**：`verify_spawn_subagent_live`（含单独取消不 bump epoch）全绿 + 前端子卡/取消一致 + `verify_rollback_p4_subagent` 全绿。

### Task P4-1: 子取消 spike（spec §P4 G-b 前置）

**Files:**
- Create: `docs/superpowers/spikes/2026-07-23-subagent-cancel-spike.md`

- [ ] **Step 1: 半日 spike——HarnessAgent 异步 subagent（`timeout_seconds=0`）下按 `task_id` 单独 cancel，验证是否触发父 Agent State 落盘中断 / 是否 bump stream epoch**

- [ ] **Step 2: 结论写文档**：原生支持 → P4-2 直接用；不支持 → 保留 `SpawnRunRegistry` 作取消适配层（spec §P4 明确不算双轨）

- [ ] **Step 3: Commit**

```bash
git commit -m "docs(as2-p4): subagent standalone-cancel spike (G-b)"
```

### Task P4-2: 声明式 Subagent 装配 + 薄封装 SpawnSubagentTool

**Files:**
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/agent/HarnessAgentHolder.java`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/agent/SpawnSubagentTool.java`

**Interfaces:**
- Consumes: P4-1 spike 结论、`AgentExecutionProperties.As2#subagentNative`。
- Produces: flag=开时走 `.subagent(SubagentDeclaration...)`；主卡 `subagent-*` / 抽屉 `spawnPrompt`/`subSteps` 字段不变；单独取消不 bump epoch。

- [ ] **Step 1: flag 开时 builder 装配声明式 subagent；`SpawnSubagentTool.spawnSubagent` 改为调用 Harness subagent，保留 `spawnRunRegistry.isCancelled` 检查与取消路径（按 P4-1 结论）**

- [ ] **Step 2: 编译 + 单测 → Commit**

```bash
git commit -m "feat(as2-p4): declarative subagent behind subagent-native flag"
```

### Task P4-3: Live + 回滚 `verify_rollback_p4_subagent.py`

**Files:**
- Create: `scripts/verify_rollback_p4_subagent.py`

- [ ] **Step 1: 回滚脚本三段式——原生 subagent ↔ `SpawnSubagentTool` 切换，**两条路径下单独取消都不 bump epoch**（断言 stream epoch 不变、主卡 `subagent-*` 状态正确）**

- [ ] **Step 2: 跑 `verify_spawn_subagent_live --suite all` + 回滚全绿 → Commit + P4 出口验收（前端子卡/取消）+ 打标签 `as2-p4-done`**

```bash
git commit -m "test(as2-p4): spawn live re-run + rollback gate (cancel no-epoch-bump both paths)"
```

### Task P4-4: 前端回归（子卡 / 抽屉 / 单独取消不 bump epoch）

**Files:**
- Create: `sunshine-ui/e2e/as2-p4-subagent.spec.ts`

**自动化 e2e**

- [ ] **Step 1: 新增 `as2-p4-subagent.spec.ts`——子卡 + 抽屉字段 + 单独取消**

```ts
test('as2-p4 subagent card + drawer + standalone cancel', async ({ page }) => {
  await page.goto('/chat');
  await page.getByTestId('execution-mode-selector').selectOption('react');
  await page.getByTestId('composer-input').fill(S1_QUERY);   // 诱导 spawn_subagent
  await page.getByTestId('composer-send').click();
  // 主卡 subagent-* 出现
  const card = page.locator('[data-step-phase="subagent"]').first();
  await expect(card).toBeVisible({ timeout: 90000 });
  // 抽屉含 spawnPrompt / subSteps
  await card.click();
  await expect(page.locator('.plan-node-drawer [data-field="spawnPrompt"]')).not.toBeEmpty();
  // 单独取消：点取消钮 → 主卡「已取消」、主消息仍 completed、stream epoch 不变（正文不被清空）
  await page.getByTestId('subagent-cancel').click();
  await expect(card).toContainText('已取消');
  await expect(page.locator('.msg-status-completed')).toBeVisible({ timeout: 120000 });
});
```

**人工真请求**

| 前置 | 提示词 | 预期 |
|------|--------|------|
| 模式 `react`、flag `subagent-native=on` | `请调用 spawn_subagent，prompt 写：用 search_knowledge 检索差旅住宿标准并返回要点摘要；label=制度检索。主 Agent 只根据子任务返回作答。`（S1_QUERY） | 主卡 `subagent-*` 一行摘要 + 抽屉（`spawnPrompt`/`subSteps`）；子 Agent think/tool **不上主时间线** |
| 同上，子任务运行中点取消 | 同上 | 子卡「已取消」、主消息最终 `completed`；**单独取消不 bump epoch**（已流正文不清空、不重发）；与 flag=off（SpawnSubagentTool）行为一致 |

- [ ] **Step 2: e2e + 人工两项确认 → 记录 → Commit**

```bash
git add sunshine-ui/e2e/as2-p4-subagent.spec.ts
git commit -m "test(as2-p4): subagent card/drawer/standalone-cancel e2e"
```

---

## P5 — Workspace 沙箱 + Permission HITL

**出口闸门**：沙箱 Live + ReAct HITL Live + `verify_sandbox_tool_cancel_live` 全绿 + 前端沙箱抽屉/写确认一致 + `verify_rollback_p5_sandbox` 全绿。

### Task P5-1: Workspace 沙箱执行内核迁移

**Files:**
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/sandbox/SandboxAgentTools.java`

**Interfaces:**
- Consumes: `AgentExecutionProperties.As2#sandboxWorkspace`。
- Produces: flag=开时 `SandboxAgentTool.callAsync` 经 Workspace/DockerFilesystemSpec 执行；**取消入口、SSE `lifecycle=paused`、`summary.after=已取消`、detail 保留 command/pattern 全部不变**（spec §P5 G-c）。

- [ ] **Step 1: flag 开时执行内核切 Workspace；保留 `CancellableToolRunRegistry` 取消路径（spike 验证 Workspace 原生取消粒度，不足则保留自研适配层）**

- [ ] **Step 2: 编译 + 单测 → Commit**

```bash
git commit -m "feat(as2-p5): workspace sandbox kernel behind sandbox-workspace flag"
```

### Task P5-2: Permission HITL 替代自研确认

**Files:**
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/agent/runtime/ReActAgentRuntime.java`

- [ ] **Step 1: flag=开时 Catalog `require_confirmation` → Permission 事件（`RequireUserConfirmEvent`）→ 现有确认 UI；Workflow 节点 HITL 仍自研（spec §P5 明确）**

- [ ] **Step 2: 编译 + 单测 → Commit**

```bash
git commit -m "feat(as2-p5): permission HITL behind hitl-permission flag"
```

### Task P5-3: Live + 回滚 `verify_rollback_p5_sandbox.py`

**Files:**
- Create: `scripts/verify_rollback_p5_sandbox.py`

- [ ] **Step 1: 回滚脚本——Workspace ↔ 现网沙箱切换，`verify_sandbox_tool_cancel_live` **两条路径全绿**；Permission ↔ 自研 HITL 切换确认 UI 一致**

- [ ] **Step 2: 跑 `verify_sandbox_live --suite all` + `verify_hitl_live --live` + `verify_sandbox_tool_cancel_live` + 回滚全绿 → Commit + P5 出口验收（前端沙箱抽屉 + 写确认）+ 打标签 `as2-p5-done`**

```bash
git commit -m "test(as2-p5): sandbox+hitl live + rollback gate (cancel UX both paths)"
```

### Task P5-4: 前端回归（沙箱抽屉 + 写确认 + 取消 UX 不变）

**Files:**
- Create: `sunshine-ui/e2e/as2-p5-sandbox-workspace.spec.ts`

**自动化 e2e**

- [ ] **Step 1: 新增 `as2-p5-sandbox-workspace.spec.ts`——Workspace 内核下抽屉/取消 UX 不变**

```ts
test('as2-p5 workspace sandbox drawer + tool cancel unchanged', async ({ page }) => {
  await page.goto('/chat');
  await page.getByTestId('composer-input').fill(SANDBOX_CANCEL_QUERY);   // 诱导 sleep 120
  await page.getByTestId('composer-send').click();
  const toolStep = page.locator('[data-tool="sandbox__exec"]').first();
  await expect(toolStep).toBeVisible({ timeout: 60000 });
  // hover 出取消圆钮 → 取消 → lifecycle=paused + summary.after=已取消
  await toolStep.hover();
  await toolStep.getByTestId('tool-cancel-btn').click();
  await expect(toolStep).toHaveAttribute('data-lifecycle', 'paused');
  await expect(toolStep).toContainText('已取消');
  // detail 保留 command 供展开
  await toolStep.click();
  await expect(page.locator('.tool-detail')).toContainText('sleep 120');
});
```

**人工真请求**

| 前置 | 提示词 | 预期 |
|------|--------|------|
| 模式 `react`、flag `sandbox-workspace=on` | `请调用 sandbox__exec：command 必须是 sleep 120，并传 timeout_sec=180；不要自己提前结束。若工具返回「用户已取消」，请换方案执行 echo cancel-ok 并作答。`（SANDBOX_CANCEL_QUERY） | hover 出取消圆钮；取消后主行「已取消」、detail 保留 command；主消息仍 completed；**与现网 4.5.7 UX 完全一致** |
| 同上、写工具、flag `hitl-permission=on` | `请调用 {OA_APPROVE} 工具审批 OA 待办 taskId=T1001，不要查询其它工具。`（HITL_QUERY） | 弹出现有确认 UI（Permission 事件驱动，**非**新组件）；确认/拒绝路径正常；沙箱抽屉（文件树/diff/writeHitlMode）不变 |

- [ ] **Step 2: e2e + 人工两项确认 → 记录 → Commit**

```bash
git add sunshine-ui/e2e/as2-p5-sandbox-workspace.spec.ts
git commit -m "test(as2-p5): workspace sandbox drawer + permission hitl UX e2e"
```

---

## P6 — peer-collab 正式化

**出口闸门**：`verify_peer_collab_live` + `verify_expert_consultation_live`（反应式选人恢复）全绿 + 前端 `$` 完整路径 + `verify_rollback_p6_peer` 全绿。

### Task P6-1: 反应式 hub 恢复（按 P0-5 spike 结论）

**Files:**
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/expert/ExpertHubEngine.java`

**Interfaces:**
- Consumes: P0-5 spike 结论（路径 1：自研 `roundCoordinator.selectReactiveSpeakers` + 每专家 `streamEvents`）、`AgentExecutionProperties.As2#peerReactive`。
- Produces: flag=开时恢复第 2 轮起反应式选人；删除 P0 顺序桥标记 `AS2_P0_PEER_SEQUENTIAL`。

- [ ] **Step 1: flag 开时 `resolveSpeakers` 恢复 `roundCoordinator.selectReactiveSpeakers`（现网 L144-157 逻辑）；专家调用经 `streamEvents`；删除顺序桥常量**

- [ ] **Step 2: 编译 + 单测 → Commit**

```bash
git commit -m "feat(as2-p6): restore reactive speaker selection behind peer-reactive flag"
```

### Task P6-2: Live + 回滚 `verify_rollback_p6_peer.py`

**Files:**
- Create: `scripts/verify_rollback_p6_peer.py`

- [ ] **Step 1: 回滚脚本——反应式 ↔ 顺序桥切换，`verify_peer_collab_live` 主路径 + `$` 绑定两条路径全绿**

- [ ] **Step 2: 跑 `verify_peer_collab_live` + `verify_expert_consultation_live` + 回滚全绿 → Commit + P6 出口验收（前端 `$` 完整路径）+ 打标签 `as2-p6-done`**

```bash
git commit -m "test(as2-p6): peer/expert live + rollback gate"
```

### Task P6-3: 前端回归（反应式选人恢复 + `$` 完整路径）

**Files:**
- Create: `sunshine-ui/e2e/as2-p6-peer-reactive.spec.ts`

**自动化 e2e**

- [ ] **Step 1: 新增 `as2-p6-peer-reactive.spec.ts`——反应式轮次下专家步 + Synthesizer 正文**

```ts
test('as2-p6 reactive peer expert steps + synthesizer', async ({ page }) => {
  await page.goto('/chat');
  await page.getByTestId('execution-mode-selector').selectOption('peer-collab');
  await page.getByTestId('composer-input').fill(E1_QUERY);
  await page.getByTestId('composer-send').click();
  // 专家步 expert-{id}-s{seq} 出现（data-phase=expert）
  const experts = page.locator('[data-phase="expert"]');
  await expect(experts.first()).toBeVisible({ timeout: 90000 });
  // 反应式：第 2 轮起可能少于全名册（动态选人）——不强断言人数，但至少有 2 位
  expect(await experts.count()).toBeGreaterThanOrEqual(2);
  // Synthesizer 终态正文流式（无 generate Timeline 步）
  await expect(page.locator('.markdown-content').last()).not.toBeEmpty({ timeout: 120000 });
});
```

**人工真请求**

| 前置 | 提示词 | 预期 |
|------|--------|------|
| 模式 `peer-collab`、flag `peer-reactive=on` | `请人事制度分析专家和费用报销分析专家分别审查这笔报销是否合规，并互相验证`（E1_QUERY） | `expert-convene` → 多专家步 + `step_delta(result)`；**第 2 轮起反应式选人**（与 P0 顺序桥可观察差异）；Synthesizer 终态正文流式完整、无截断 |
| Chat 输入框 `$` 绑定专家 | `$费用报销分析专家 这笔报销合规吗` | `$` 绑定路径正常、指定专家发言；Catalog/`$`/前端专家步产品层不变 |

- [ ] **Step 2: e2e + 人工两项确认 → 记录 → Commit**

```bash
git add sunshine-ui/e2e/as2-p6-peer-reactive.spec.ts
git commit -m "test(as2-p6): reactive peer + dollar-bind e2e"
```

---

## P7 — 清桥收口

**出口闸门**：全量回归包 + feature flag 全拆 + 四模式前端抽检 + 全量回滚脚本最终回归。

### Task P7-1: 删除 legacy 桥与 feature flag

**Files:**
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/config/AgentExecutionProperties.java`（删 `as2` 块）
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/agent/ReActAgentRuntime.java`（删 legacy-stream 分支）
- Delete: `orchestrator/src/main/java/com/sunshine/orchestrator/agent/ManageTasksTool.java`、`SpawnSubagentTool.java`（若 P4 已全量切原生）
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/agent/ProcessingStepHook.java`（删 Legacy Hook 依赖）

- [ ] **Step 1: 逐一删除：Legacy Hook 桥 / AutoContextHook / 顺序桥标记 / 全部 `as2.*` flag 与双路径分支 / 被替代的自研类**

- [ ] **Step 2: 编译 + 全量单测**

Run: `mvn -pl orchestrator -am compile -q && mvn -pl orchestrator test -q 2>&1 | grep "Tests run:" | tail -1`
Expected: 全绿、无 flag 残留引用

- [ ] **Step 3: Commit**

```bash
git commit -m "chore(as2-p7): remove legacy bridges, feature flags, superseded impls"
```

### Task P7-2: 更新 CLAUDE.md

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 1: 删除「勿升 AgentScope 2.0.0」；写明「ReAct 续跑依赖 Redis StateStore TTL=7d（`agent.execution` 运行参数，非提示词）」**

- [ ] **Step 2: Commit**

```bash
git commit -m "docs(as2-p7): drop AS2 ban, note Redis StateStore TTL=7d resume dependency"
```

### Task P7-3: 全量回归 + 四模式前端抽检

- [ ] **Step 1: 跑全量 Live 回归包**

Run: `python scripts/phase2_agent_demo.py --suite all && python scripts/verify_spawn_subagent_live.py && python scripts/verify_react_taskboard_live.py && python scripts/verify_sandbox_live.py --suite all && python scripts/verify_sandbox_tool_cancel_live.py && python scripts/verify_peer_collab_live.py && python scripts/verify_expert_consultation_live.py && python scripts/verify_react_checkpoint_live.py`
Expected: 全绿

- [ ] **Step 2: 全量回滚脚本最终回归（确认 P7 拆 flag 后无残留依赖）**

Run: `for f in scripts/verify_rollback_p*.py; do python "$f" || echo "REGRESS $f"; done`
Expected: 全部 exit 0（P7 后这些脚本应仅作历史归档，若因 flag 删除失效则同步归档）

- [ ] **Step 3: 四模式前端抽检（人工）**：react / workflow / plan-workflow / peer-collab 各一轮

- [ ] **Step 4: 更新 `docs/implementation-plan.md` 缺口行 → Commit + 打标签 `as2-upgrade-done`**

```bash
git commit -m "test(as2-p7): full regression + four-mode frontend spot check" --allow-empty
git tag as2-upgrade-done
```

### Task P7-4: 前端回归（四模式全量 + 清桥后无残留）

**Files:**
- Modify: `sunshine-ui/e2e/`（全量回归；删除 P1–P6 中依赖已拆 flag 的临时 spec，保留通用回归）

**自动化 e2e**

- [ ] **Step 1: 全量 e2e 回归（含 P0–P6 全部 as2-p*.spec.ts，flag 已拆后应仍全绿）**

Run: `cd sunshine-ui && npm run test:e2e`
Expected: 全绿；无对已删 flag 的引用

- [ ] **Step 2: 打真实 Gateway 全量**

Run: `cd sunshine-ui && PLAYWRIGHT_BASE_URL=http://ecs4c16g:5173 npm run test:e2e`
Expected: 全绿

**人工真请求（四模式抽检，每模式一条 golden query）**

| 模式 | 提示词 | 预期 |
|------|--------|------|
| `react` | `帮我查待审批报销，并对有风险的单据逐条说明原因`（F1_QUERY） | ReAct 时间线 + 真续跑 + 任务卡全链路 |
| `workflow` | 触发静态 workflow 标杆（`#` 选模板） | DAG + answer 节点 `step_delta(result)` 正常 |
| `plan-workflow` | 触发 Plan-Workflow（动态 DAG） | Planner → 校验 → 执行 → answer；Plan 用户确认框正常 |
| `peer-collab` | `请人事制度分析专家和费用报销分析专家分别审查这笔报销是否合规，并互相验证`（E1_QUERY） | 反应式专家步 + Synthesizer 正文 |

- [ ] **Step 3: 四模式全绿 + 全量 e2e 绿 → 记录 → Commit**

```bash
git commit -m "test(as2-p7): four-mode frontend regression + e2e full pass" --allow-empty
```

---

## 附录 A — 阶段-脚本-闸门速查

| 阶段 | 新建脚本 | 复用 Live | 回滚脚本 | 自动化 e2e | 前端人工验收 |
|------|----------|-----------|----------|------------|--------------|
| P0 | — | — | `verify_rollback_p0_compile.py` | 全量基线 + `as2-p0-peer-sequential.spec.ts` | ReAct 一轮（F1）+ peer 降级（E1） |
| P1 | `bench_event_adapter.py` | — | `verify_rollback_p1_events.py` | `as2-p1-stream-events.spec.ts` | 步骤+流式正文（FN1）+ Plan DAG |
| P2 | `verify_react_checkpoint_live.py` | — | `verify_rollback_p2_checkpoint.py` | `as2-p2-checkpoint-resume.spec.ts` | 停→「继续执行」（FN1）+ TTL 降级 |
| P3 | — | `verify_react_taskboard_live.py` | `verify_rollback_p3_tasklist.py` | `as2-p3-tasklist.spec.ts` | 任务卡（F1）+ 回滚 |
| P4 | — | `verify_spawn_subagent_live.py` | `verify_rollback_p4_subagent.py` | `as2-p4-subagent.spec.ts` | 子卡/抽屉（S1）+ 单独取消 |
| P5 | — | `verify_sandbox_live` / `verify_hitl_live` / `verify_sandbox_tool_cancel_live` | `verify_rollback_p5_sandbox.py` | `as2-p5-sandbox-workspace.spec.ts` | 沙箱取消（SANDBOX_CANCEL）+ 写确认（HITL） |
| P6 | — | `verify_peer_collab_live` / `verify_expert_consultation_live` | `verify_rollback_p6_peer.py` | `as2-p6-peer-reactive.spec.ts` | 反应式（E1）+ `$` 绑定 |
| P7 | — | 全量回归包 | 全量回滚最终回归 | 全量 e2e（真实 Gateway） | 四模式抽检（F1/模板/Plan/E1） |

## 附录 B — 关键文件改动速查

| 文件 | 涉及阶段 | 改动 |
|------|----------|------|
| `pom.xml` | P0 | 版本 + openai 扩展 |
| `ReActAgentFactory.java` | P0/P2 | builder 迁移 → HarnessAgentHolder |
| `HarnessAgentHolder.java`（新） | P2–P5 | 单例载体 + TaskList/Subagent/Workspace 装配 |
| `AgentStateStoreConfig.java`（新） | P0/P2 | Redis StateStore |
| `AgentScopeEventMapper.java`（新） | P1 | AgentEvent→StreamToken |
| `ReActAgentRuntime.java` | P1/P2/P5 | 双路径 → 原生 |
| `ReactCheckpointService.java`（新） | P2 | interrupt/resume |
| `ExpertHubEngine.java` | P0/P6 | 去 MsgHub → 反应式恢复 |
| `SandboxAgentTools.java` | P5 | Workspace 内核 |
| `AgentExecutionProperties.java` | P0/P7 | as2 flag 块 → 删除 |
