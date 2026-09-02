# P0-5 Spike — 反应式 hub 在 AS 2.0 下的可行性（spec §P6 G-a 路径 1）

> **日期**：2026-07-23 · **执行**：P0-5 spike（半日） · **结论**：**路径 1 可行（with-caveats）**

## 1. 背景

spec §P6 G-a 锁定：AS 2.0 删除 `pipeline` 包（含 `MsgHub`），P6 恢复反应式 hub 优先采用**路径 1**——保留自研 `roundCoordinator.selectReactiveSpeakers` 决策，仅把每位专家的 Agent 调用从 MsgHub 换成 `streamEvents`。本 spike 在 P0-4 顺序桥（commit `336ce57` + `5e7e755`）基础上，半日验证路径 1 在 2.0 下编译/流式可行，避免 P6 才发现死路。

## 2. Spike 步骤与证据

### 2.1 `streamEvents` 签名（`javap ReActAgent`，2.0.0 jar）

```
public reactor.core.publisher.Flux<io.agentscope.core.event.AgentEvent> streamEvents(java.util.List<io.agentscope.core.message.Msg>);
public reactor.core.publisher.Flux<io.agentscope.core.event.AgentEvent> streamEvents(io.agentscope.core.message.Msg);
public reactor.core.publisher.Flux<io.agentscope.core.event.AgentEvent> streamEvents(java.util.List<io.agentscope.core.message.Msg>, io.agentscope.core.agent.RuntimeContext);
public reactor.core.publisher.Flux<io.agentscope.core.event.AgentEvent> streamEvents(io.agentscope.core.message.Msg, io.agentscope.core.agent.RuntimeContext);
public reactor.core.publisher.Flux<io.agentscope.core.event.AgentEvent> streamEvents(java.lang.String);
public reactor.core.publisher.Flux<io.agentscope.core.event.AgentEvent> streamEvents(java.lang.String, io.agentscope.core.agent.RuntimeContext);
```

**结论**：`streamEvents` 在 `ReActAgent` 上是 public API，6 个重载可用。

### 2.2 相关 AgentEvent 类型（`javap io.agentscope.core.event.*`）

| 事件 | 关键方法 | 用途 |
|------|----------|------|
| `TextBlockDeltaEvent` | `getDelta(): String` / `getReplyId()` / `getBlockId()` | 专家发言正文增量（替换阶段 2 `expertSpeakStreamer` 的 Gateway 流式） |
| `TextBlockEndEvent` | `getReplyId()` / `getBlockId()` | 块结束边界（用于切断 speak 阶段，可选） |
| `AgentResultEvent` | `getResult(): Msg` | 最终聚合回复（替换阶段 1 `agent.call(...).block()` 的 gather） |
| `AgentEndEvent` | `getReplyId()` | 整个 agent call 终止信号 |
| `ThinkingBlockDeltaEvent` | 同 Text delta | ReAct reasoning 流（可用于主时间线 think 步投影） |
| `ToolCallStartEvent` / `ToolResultEndEvent` | — | 工具调用进度（替换 `StepEventBridge.bindExpertSpeakSink` 的 tool 步投影） |
| `RequireUserConfirmEvent` | — | HITL（P5 用，本 spike 不涉及） |

**结论**：单次 `streamEvents` 同时下发 delta + 最终 `Msg`，**「两阶段 gather → speak」可由「单阶段 stream」替代**——见 §2.4。

### 2.3 编译证据（throwaway spike，未提交）

`/tmp/p05-spike/SpikeStreamEvents.java`（编译后丢弃，不入仓）：

```java
Flux<AgentEvent> events = agent.streamEvents(List.<Msg>of());
events.doOnNext(ev -> {
    if (ev.getType() == AgentEventType.TEXT_BLOCK_DELTA) {
        speakDelta.append(((TextBlockDeltaEvent) ev).getDelta());
    }
    if (ev.getType() == AgentEventType.AGENT_RESULT) {
        Msg result = ((AgentResultEvent) ev).getResult();
        gathered.append(result != null ? result.toString() : "");
    }
}).blockLast();
```

```
$ javac -cp agentscope-core-2.0.0.jar:reactor-core-3.6.9.jar:reactive-streams-1.0.4.jar SpikeStreamEvents.java
exit=0（仅 jackson-annotation 类缺失警告，与 streamEvents 无关）
```

**编译通过**。

### 2.4 单阶段流能否同时 gather + speak？

| 维度 | P0-4 现状（两阶段） | P6 路径 1（单阶段 streamEvents） |
|------|---------------------|----------------------------------|
| 工具检索材料（gatheredContext） | 阶段 1 `agent.call(inputs).block()` 返回 `Msg` → `PeerMsgSupport.extractText` 抽文本 | `AgentResultEvent.getResult()` 拿到同一份 `Msg`；或聚合全部 `TextBlockDeltaEvent.getDelta()` |
| 流式 speak 正文 | 阶段 2 `expertSpeakStreamer.streamSpeak(...)` 走 Gateway 直链独立 prompt | `TextBlockDeltaEvent` 直接流出 |
| 工具进度投影 | `StepEventBridge.bindExpertSpeakSink` 拦截 tool 步 | `ToolCallStartEvent` / `ToolResultEndEvent` 直出 |
| Prompt 模板 | 阶段 1 `composeReactInputs`；阶段 2 `peer.speak-prompt` 独立 | **合并为一个 prompt**（catalog 调整，把 gather-instruction + speak-prompt 整合） |

**结论**：单阶段可行，**但需要 prompt 层重构**——把「先静默检索，后发言」两阶段提示合并为「边检索边流式发言」单提示。这不影响 streamEvents API 可用性，但属于 P6 的实际改动量。

### 2.5 HarnessAgent 缺口（**重大发现，影响 P0 定型**）

> **勘误（2026-07-23，见 `2026-07-23-harness-agent-spike.md`）**：本节结论错误。`HarnessAgent` 存在于独立 artifact `io.agentscope:agentscope-harness:2.0.0`，非 `agentscope-core` / 聚合 `agentscope`。本 spike 当时只检查了后两者，遗漏了 harness 模块。spec §3.1 锁定成立。下方原文保留作记录，**不再作为决策依据**。

~~```
$ jar -tf agentscope-core-2.0.0.jar | grep -i harness
（无结果）
$ jar -tf agentscope-2.0.0.jar | grep -i HarnessAgent
（无结果）
```~~

~~**AS 2.0.0 jar 内不存在 `HarnessAgent` 类**。spec §3.1 锁定的「统一 HarnessAgent 载体」基于官方 changelog B.4 描述，但 2.0.0 实际只发布了 `ReActAgent`。~~

**影响**：
- P0 已按 spec 落 HarnessAgent 骨架的方案需要复核——当前 P0 实际只用 `ReActAgent`（见 `ExpertPeerAgentFactory` / `ReActAgentFactory`），所以**未踩坑**。
- P6 路径 1 的实际载体就是 `ReActAgent.streamEvents`（本 spike 已验证签名），**不依赖 HarnessAgent**。
- spec §3.1 / §5 / P2-P5 中所有提到 HarnessAgent 的段落，实施时需先确认 HarnessAgent 何时发布（2.0.1？2.1.0？），否则降级到 ReActAgent + 自研 harness 能力（TaskList / Subagent / Workspace 等仍需自研，与 spec 锁定冲突）。

**本 spike 范围内**：路径 1 不依赖 HarnessAgent，**无阻塞**。但 spec §3.1 整体需要 review。

## 3. P0-4 保留的反应式资产盘点

| 资产 | 位置 | P6 复用？ |
|------|------|-----------|
| `resolveSpeakers` 分支（round 1 = roster / round ≥ 2 = reactive） | `ExpertHubEngine.java:142-155` | ✅ 完全保留，无需改 |
| `roundCoordinator.selectReactiveSpeakers` | `ExpertRoundCoordinatorService` | ✅ 完全保留 |
| `roundCoordinator.evaluateContinue` 早退 | `ExpertHubEngine.java:117-123` | ✅ 完全保留 |
| `appendToTranscript` + `contextBlocks` 传递 | `ExpertHubEngine.java:128-140` | ✅ 保留（**反应式语义本身不依赖 MsgHub 广播，仅依赖每位专家 invoke 时拿到完整 transcript**） |
| 专家 for-loop 顺序调用 | `ExpertHubEngine.java:92-116` | ⚠️ **保留为顺序**，反应式语义不强制并发（见 §4） |

## 4. 反应式语义是否需要并发专家调用？

**结论：不需要。**

`selectReactiveSpeakers` 的输入是 `(userQuery, roster, transcript, round)`，输出是发言人 ID 列表。它的决策依据是「到目前为止的 transcript」，**决策本身是同步函数**，与专家 invoke 是否并发无关。

MsgHub 的 `enableAutoBroadcast=true` 在 1.x 提供的价值是：
1. 专家 A 发言后，**所有**其他专家立刻在自己的 memory 里看到这条发言
2. 下一位专家调用时不需要显式注入 transcript

而 P0-4 顺序桥用 `contextBlocks.add(...)` 显式注入 transcript，**等价达成 (1)**，且 (2) 通过 prompt 拼装显式完成。反应式选人的**决策点**在 `selectReactiveSpeakers`，它读的是 `transcript` list（Java 内存对象），与 MsgHub 无关。

**因此**：路径 1 的「反应式」语义 = `selectReactiveSpeakers` 决策 + 顺序 invoke + 显式 transcript 传递，**与并发无关**。P6 不需要恢复 MsgHub 的并发广播。

## 5. P6 落地清单（concrete）

### 5.1 必改

| # | 改动点 | 文件 | 说明 |
|---|--------|------|------|
| 1 | `invokeAgent` 改单阶段 stream | `ExpertHubEngine.java:158-216` | 删 `agent.call(inputs).block()` + `expertSpeakStreamer.streamSpeak(...)`；改 `agent.streamEvents(inputs).doOnNext(ev -> ...).blockLast()` |
| 2 | 事件分发器 | 新增 `ExpertSpeakEventDispatcher`（或就地 lambda） | 按 `ev.getType()` 分发：`TEXT_BLOCK_DELTA` → `callback.onSpeakDelta`；`AGENT_RESULT` → `speakText` 聚合；`TOOL_CALL_START/END` → `maybeNotifyToolProgress` |
| 3 | Prompt 合并 | `prompt-manager` DB | 新增 catalog 项（如 `peer.speak-stream-prompt`）合并 `peer.gather-instruction` + `peer.speak-prompt`；阶段 1 的 `composeReactInputs` 直接用新 prompt |
| 4 | `expertSpeakStreamer` 调用点删除 | `ExpertHubEngine.java:204` | 整个调用替换；`ExpertSpeakStreamer` 类可保留供 Synthesizer 用，但 hub 路径不再走它 |
| 5 | `AS2_P0_PEER_SEQUENTIAL` 常量删除 | `ExpertHubEngine.java:43` | 反应式恢复后清理标记 |
| 6 | feature flag `agent.peer.reactive` | spec §7.5 P6 行 | 默认开，关闭时回退 P0-4 顺序两阶段 |

### 5.2 不改

- `resolveSpeakers` / `selectReactiveSpeakers` / `evaluateContinue` / `appendToTranscript` / `contextBlocks` 传递
- `createAgent` / `buildPeerRequest` / `resolveToolWhitelist`
- 前端专家步投影（`step.label` / `step_delta`）— 由 P1 `AgentScopeEventMapper` 统一接管
- `ExpertPeerAgentFactory`（ReActAgent builder 已经够用）

### 5.3 风险

| 风险 | 缓解 |
|------|------|
| 单阶段 stream 把「先静默 gather、后流式 speak」合并，导致模型边检索边说话（thinking-aloud），与现网两阶段产品体验不同 | P6 先在测试环境跑 live，对比 4.7.3 反应式 Live（`verify_peer_collab_live` / `verify_expert_consultation_live`）的发言质量；如果 thinking-aloud 不可接受，**保留两阶段**（阶段 1 仍 `call().block()`，阶段 2 也走 streamEvents 但 prompt 只让模型复述） |
| `AgentResultEvent.getResult()` 与全部 delta 聚合是否完全一致（含 token 截断） | 单测断言：聚合 delta == result.getTextContent()（忽略尾部空白） |
| 工具进度事件类型 (`ToolCallStartEvent` 等）的 metadata 字段不足以投影到现网 step | P1 `AgentScopeEventMapper` 已经做了同样的事，复用其映射规则 |

## 6. Memory 维度（P0-4 留下的问题）

**P0-4 现实**：MsgHub 移除后，专家 Agent 跨轮调用时，SUB memory 不再累积（只有 `contextBlocks` 显式注入）。

**P6 路径 1 是否要解决？**

- **如果 P6 沿用 contextBlocks 传递**：反应式语义已完整（`selectReactiveSpeakers` 读 transcript，专家读 contextBlocks），**无需 memory 维度**。
- **如果 P6 想利用 AS 2.0 原生 AgentState + stateStore**（P2 落地后）：可以让专家 Agent 跨轮通过 stateStore 拿到自己的 memory，**作为补充**而非替代。这样可以减少 `contextBlocks` 体积（contextBlocks 是 user-message 注入，每轮 token 成本随轮次线性增长）。

**建议**：P6 第一阶段保持 contextBlocks 传递（与 P0-4 一致），不动 memory；P6 后期或 P7 再评估是否切到 stateStore。这是**优化**而非阻塞。

## 7. 结论

**路径 1 可行（with-caveats）**：

- ✅ `ReActAgent.streamEvents` 在 2.0.0 是 public API，签名匹配
- ✅ `TextBlockDeltaEvent` / `AgentResultEvent` 提供完整的 delta + result 通路
- ✅ 编译验证通过（throwaway spike，未提交）
- ✅ P0-4 已保留所有反应式决策逻辑（`selectReactiveSpeakers` / `evaluateContinue`）
- ✅ 反应式语义不要求并发专家调用，顺序桥等价
- ⚠️ **Caveat 1**：单阶段 stream 需要 prompt 层重构（gather-instruction + speak-prompt 合并），不是零成本
- ⚠️ **Caveat 2**：`HarnessAgent` 在 2.0.0 jar 中**不存在**，spec §3.1 假设需复核——但路径 1 不依赖 HarnessAgent，本 spike 无阻塞
- ⚠️ **Caveat 3**：Memory 维度（P0-4 留下的 SUB memory 不累积问题）P6 不必处理，可作为后续优化

**给 P6 的决策**：按路径 1 实施，**不需要**降级到「顺序 + 自研轮次控制」（因为它本来就是路径 1 的形态）；反应式语义恢复 = 把 `invokeAgent` 的两阶段合并为单阶段 streamEvents + 合并 prompt。

**给 spec 的反馈**：§3.1 关于 HarnessAgent 的锁定需要独立 spike 验证（**不在本任务范围**），如果 2.0.0 实际无 HarnessAgent，整个 P2-P5 计划需重新评估载体。

## 8. 参考

- spec：`docs/superpowers/specs/2026-07-22-agentscope-2-upgrade-design.md` §P6 G-a
- P0-4 实施：`commit 336ce57 + 5e7e755` / `.superpowers/sdd/task-P0-4-report.md`
- 主类：`orchestrator/src/main/java/com/sunshine/orchestrator/expert/ExpertHubEngine.java`
- throwaway spike：`/tmp/p05-spike/SpikeStreamEvents.java`（编译验证后丢弃）
