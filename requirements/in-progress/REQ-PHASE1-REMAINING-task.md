# [REQ-PHASE1-REMAINING] 一阶段剩余任务 — 实施计划

> **执行说明：** 实施**仅**能通过 **`apk-subagent-coding`** skill 完成：按 Task 顺序用 Task Tool 派发子 Agent；**仅**更新每个 Task 标题下一行的任务级 `- [ ]` / `- [x]`，**不**维护步骤级勾选。**禁止**内联/本会话主 Agent 直接按本清单改业务代码；禁止 `executing-plans` 类同会话批量实现。计划落盘后须经人类伙伴明确同意再启动该 skill。

**Goal:** 补齐一阶段（含 1.5/1.6/Timeline V2）剩余验收、缺口联调、可观测性接线与技术债，使 `implementation-plan.md` 检查门全部通过后可进入阶段二。

**Architecture:** 不新增微服务；在既有 BFF → Orchestrator → LLM Gateway / RAG 链路上完成手动+自动化验收，注入 SkyWalking Agent，修复 Agent Memory 隔离，同步 Nacos/docs/CI。

**Tech Stack:** JDK 21, Spring Boot 3.2.9, AgentScope 1.0.7, Vue3 + Playwright, SkyWalking 9.7.0, Redis, MySQL, Milvus

**Constraints:**

### 项目规则
- JDK 21：`D:/MyWorkStation/Java/jdk/jdk-21/bin/java` 或 `switch-java 21`（见 `CLAUDE.md`）
- dev-yml 变更须同步 `docs/nacos/` 并提醒更新 Nacos 线上（IC-01）
- 不升级 Spring Boot 3.3+、AgentScope 2.0.0
- 集成测试 `@Tag("integration")` 默认 excluded（`orchestrator/pom.xml`）

---

## 文件结构（本需求涉及）

| 操作 | 路径 | 职责 |
|------|------|------|
| 修改 | `docs/implementation-plan.md` | 检查门勾选状态 |
| 修改 | `docs/superpowers/plans/2026-06-07-phase1-gap-closure.md` | checkbox 与代码对齐 |
| 修改 | `scripts/phase1-demo.ps1` / `scripts/phase1-demo.sh` | Gateway Step 5 自动化 |
| 新增/修改 | `docker/skywalking-agent/skywalking-agent.jar` | Java Agent（或 README 下载指引 + gitignore） |
| 修改 | `scripts/start.sh`（及 Windows 等价） | `-javaagent` 注入 |
| 新增 | `docs/nacos/sunshine-gateway.yaml` | Gateway Nacos 配置模板 |
| 修改 | `orchestrator/.../agent/AgentConfig.java` | Memory 会话隔离 |
| 修改 | `sunshine-ui/mock-server.mjs` | Timeline V2 step payload |
| 修改 | `sunshine-ui/e2e/processing-timeline*.spec.ts` | V2 断言 |
| 新增 | `.github/workflows/ci.yml` | Maven + 前端 build + Playwright mock |
| 修改 | `requirements/INDEX.md` | 需求状态更新 |

---

### Task 1: 阶段一检查门 — Nacos System Prompt 热更新验收
- [x]

**涉及文件：**
- 修改：`docs/implementation-plan.md`（阶段一检查门第 4 项）
- 参考：`docs/nacos/sunshine-orchestrator.yaml`（IC-01）
- 参考：`orchestrator/src/main/java/com/sunshine/orchestrator/agent/AgentConfig.java`（`@RefreshScope`）

**实施说明：**

1. 启动 orchestrator（profile dev，Nacos 可达），记录当前 System Prompt 下 Agent 自我介绍风格。
2. 在 Nacos Console（`http://ecs4c16g:8848/nacos`，nacos/nacos）修改 `sunshine-orchestrator.yaml` 中 `agent.system-prompt` 为明显不同文案（如「你是 Sunshine 测试助手，回答必须以【测试】开头」）。
3. 不重启 orchestrator，通过 BFF 发起新一轮 SSE 对话，确认行为变化；确认 `InMemoryMemory` Bean 未被 RefreshScope 刷新导致异常。
4. 若 Nacos 与 `docs/nacos/sunshine-orchestrator.yaml` 不一致，同步 docs 并提醒更新线上。
5. **测试覆盖范围**：本 Task 为手动验收；若发现 RefreshScope 不生效，在 Task 7 或单独 fix Task 中补 `@RefreshScope` 触发验证单测（非必须）。

**验证：**

- 命令：按 `scripts/phase1-demo.ps1` Step 6 流程操作；另 `curl -N -X POST http://localhost:8001/api/chat/stream -H "x-user-id: phase1-demo" -H "Content-Type: application/json" -d "{\"content\":\"你是谁\"}"` 观察回复前缀
- 预期：修改 Prompt 后 **无需重启**，回复符合新 Prompt；`implementation-plan.md` 阶段一检查门第 4 项改为 `[x]`

**提交建议：** `docs: mark phase1 nacos hot-reload gate passed`

---

### Task 2: 阶段 1.5 检查门 — 会话持久化与续传手动验收
- [x]

**涉及文件：**
- 修改：`docs/implementation-plan.md`（1.5 检查门）
- 参考：`sunshine-ui/src/stores/chatStore.ts`、`sunshine-ui/src/api/chatSessions.ts`
- 参考：`orchestrator/src/test/java/com/sunshine/orchestrator/ConversationIntegrationTest.java`

**实施说明：**

1. **跨浏览器恢复**：浏览器 A 用固定 `x-user-id`（如 `phase1-demo`）创建会话并发 3 轮；浏览器 B 同 userId 打开 `/chat`，确认会话列表与消息完整（Step 7 / spec 验收清单）。
2. **删除级联**：DELETE 某会话后 MySQL `chat_message` 对应记录清空，前端列表移除。
3. **周期性 flush**：长回答生成中 F5，确认 partial content 立即可见（不等 completed）。
4. **knowledge 409**：知识库问题（如「公司考勤制度是什么？」）Stop 后，UI 不展示「继续生成」或 resume API 返回 409。
5. **knowledge 多轮**：同会话连续 2 轮知识库追问，上下文连贯。
6. 更新 `implementation-plan.md` 1.5 检查门「换浏览器」为 `[x]`；spec 验收清单对应项勾选。

**验证：**

- 命令：`mvn test -pl orchestrator -am "-Dtest=ConversationIntegrationTest" -q`（自动化基线）
- 预期：集成测试绿 + 上述 5 项手动验收通过并落盘勾选

**提交建议：** `docs: phase1.5 acceptance gate verified`

---

### Task 3: 阶段 1.6 检查门 — Redis 重连与 G/F 降级联调
- [x]

**涉及文件：**
- 修改：`docs/implementation-plan.md`（1.6 检查门前端项）
- 参考：`sunshine-ui/src/views/ChatView.vue`（onMounted reconnect）
- 参考：`sunshine-ui/src/api/chatSessions.ts`（410 处理）
- 测试：`orchestrator/src/test/java/com/sunshine/orchestrator/GenerationReconnectIntegrationTest.java`

**实施说明：**

1. **同 Tab 刷新 reconnect**：simple 意图发消息，流式进行中 F5；确认内容连续追加、不新开 assistant 行（`auto-on-reload: true` 已配）。
2. **410 降级 Track F**：流式中点 Stop（或 POST cancel）→ 刷新 → reconnect 返回 410 → UI 展示「继续生成」→ 点击后同一条 assistant 追加完成。
3. **可选组合用例**：在 `GenerationReconnectIntegrationTest` 或新测试类增加「reconnect 410 后 POST resume 200」用例（mock LLM）。
4. **mock-server F5**：`npm run dev` mock 模式下重复步骤 1–2。
5. 更新 1.6 检查门「前端降级需手动点验」为已验。

**验证：**

- 命令：`mvn test -pl orchestrator -am "-Dtest=GenerationReconnectIntegrationTest" -q`
- 预期：集成测试绿 + 手动 1–2 通过；若实现步骤 3，新用例绿

**提交建议：** `test: generation reconnect 410 to resume combo case`（若新增用例）

---

### Task 4: 缺口补齐 — Qwen / RAG-Agent / Gateway / ChatIntegrationTest 全链路验收
- [x]

**涉及文件：**
- 修改：`docs/superpowers/plans/2026-06-07-phase1-gap-closure.md`（Final Checklist）
- 参考：`llm-gateway/.../QwenAdapter.java`（IC-02, IC-10）
- 参考：`orchestrator/.../RagTool.java`（IC-06）
- 参考：`gateway/src/main/resources/application.yml`（IC-04）
- 测试：`orchestrator/src/test/java/com/sunshine/orchestrator/ChatIntegrationTest.java`

**实施说明：**

1. **Qwen 路由**：`curl -X POST http://localhost:8300/v1/chat/completions -H "Content-Type: application/json" -d "{\"model\":\"qwen-plus\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"` 返回 200（需 `QWEN_API_KEY`）。
2. **RAG-Agent**：上传 `docs/knowledge/*.md` → BFF 问「病假需要哪些材料？」→ 回答引用知识库（Agent 或 knowledge 路径）。
3. **Gateway 转发**：`curl -N -m 8 -X POST http://localhost:8000/api/chat/stream -H "x-user-id: test" -H "Content-Type: application/json" -d "{\"content\":\"hello\"}"` 收到 SSE。
4. **ChatIntegrationTest**：LLM Gateway 启动后 `mvn test -pl orchestrator "-Dgroups=integration" "-Dtest=ChatIntegrationTest"`.
5. 将 gap-closure Final Checklist A/B/D1/D3 标 `[x]`；README 补充 integration 测试运行说明。

**验证：**

- 命令：上述 curl + `mvn test -pl orchestrator "-Dgroups=integration" "-Dtest=ChatIntegrationTest" -q`
- 预期：四项链路验收通过

**提交建议：** `docs: phase1 gap-closure checklist verified`

---

### Task 5: SkyWalking Java Agent 接入与 Trace 验证
- [x]

**涉及文件：**
- 新增：`docker/skywalking-agent/skywalking-agent.jar`（或更新 `docker/skywalking-agent/README.md` 下载脚本）
- 修改：`scripts/start.sh`（及 `scripts/start.ps1` 若存在）
- 修改：`.gitignore`（jar 策略：跟踪或忽略二选一，与 README 一致）
- 参考：`docker/skywalking-agent/README.md`

**实施说明：**

1. 按 README 下载 SkyWalking Java Agent 9.7.0 至 `docker/skywalking-agent/skywalking-agent.jar`。
2. 在 `scripts/start.sh` 为核心服务（至少 bff、orchestrator、llm-gateway、gateway）追加 JVM 参数：
   `-javaagent:docker/skywalking-agent/skywalking-agent.jar`
   `-DSW_AGENT_NAME=sunshine-<service>`
   `-DSW_AGENT_COLLECTOR_BACKEND_SERVICES=ecs4c16g:11800`
3. 重启服务，发起一次 BFF SSE 请求。
4. 打开 SkyWalking UI（`http://ecs4c16g:8084`），确认拓扑含 `sunshine-gateway → sunshine-bff → sunshine-orchestrator → sunshine-llm-gateway`（IC-05）。
5. 更新 gap-closure D2 为 `[x]`。

**验证：**

- 命令：启动脚本启服务 → `curl -N -m 5 -X POST http://localhost:8001/api/chat/stream -H "x-user-id: sw-test" -H "Content-Type: application/json" -d "{\"content\":\"trace test\"}"` → 检查 SkyWalking UI 拓扑
- 预期：Trace 链路可见

**提交建议：** `chore: wire skywalking java agent into start scripts`

---

### Task 6: Gateway 默认链路与 Nacos 配置补齐
- [x]

**涉及文件：**
- 新增：`docs/nacos/sunshine-gateway.yaml`
- 修改：`docs/nacos/README.md`（配置清单 + 上传命令）
- 修改：`scripts/phase1-demo.ps1`、`scripts/phase1-demo.sh`（Step 5 由 Optional 改为 PASS/FAIL）
- 修改：`README.md`（快速开始含 Gateway :8000）
- 可选修改：`sunshine-ui/vite.config.ts`（proxy 指向 :8000）

**实施说明：**

1. 从 `gateway/src/main/resources/application.yml` 提取路由/Sentinel 配置，写入 `docs/nacos/sunshine-gateway.yaml`（IC-01, IC-04）。
2. 更新 `docs/nacos/README.md` 清单与 bulk upload 脚本含 gateway。
3. 修改 `phase1-demo` Step 5：自动 curl `:8000/api/chat/stream`，失败则 `[FAIL]`。
4. 更新 README 启动顺序：Gateway 在 BFF 之前或并列说明。
5. （可选）Vite dev proxy `/api` → `localhost:8000`，统一开发入口。

**验证：**

- 命令：`powershell -ExecutionPolicy Bypass -File scripts/phase1-demo.ps1` Step 5 输出 `[OK]`
- 预期：Gateway 转发 200 + SSE

**提交建议：** `chore: gateway nacos template and demo script step5`

---

### Task 7: ReActAgent Memory 会话隔离
- [x]

**涉及文件：**
- 修改：`orchestrator/src/main/java/com/sunshine/orchestrator/agent/AgentConfig.java`
- 修改：`orchestrator/src/main/java/com/sunshine/orchestrator/agent/SunshineAgent.java`（若需 per-call 清 memory）
- 测试：`orchestrator/src/test/java/com/sunshine/orchestrator/agent/SunshineAgentMemoryTest.java`（新建）

**实施说明：**

1. 分析当前 `InMemoryMemory` 单例 Bean：多用户/多会话 ReAct 调用是否污染内部 state。
2. 方案 A（推荐）：ReActAgent 不依赖 Memory，仅通过 `buildInputs(history)` 注入上下文 — 移除 `.memory(agentMemory)` 或换 `NoOpMemory`。
3. 方案 B：每请求创建临时 Memory 或在 `SunshineAgent.chat` 入口清空 agent memory。
4. 新增单测：模拟用户 A、B 交替调用，确认 B 的回复不受 A 的 ReAct 内部残留影响。
5. `mvn test -pl orchestrator -am` 全绿。

**验证：**

- 命令：`mvn test -pl orchestrator -am "-Dtest=SunshineAgentMemoryTest,ConversationIntegrationTest" -q`
- 预期：新单测 + 既有会话测试通过

**提交建议：** `fix(orchestrator): isolate react agent memory per request`

---

### Task 8: Processing Timeline V2 — mock/E2E 收尾与文档同步
- [x]

**涉及文件：**
- 修改：`sunshine-ui/mock-server.mjs`（V2 `stepPayload`：`lifecycle/summary/durationMs`）
- 修改：`sunshine-ui/e2e/processing-timeline.spec.ts`
- 修改：`sunshine-ui/e2e/processing-timeline-real.spec.ts`（若断言需对齐 V2 文案）
- 修改：`docs/superpowers/plans/2026-06-13-processing-timeline-v2.md`（Task checkbox）
- 修改：`docs/superpowers/specs/2026-06-13-processing-timeline-v2-design.md`（状态 → 已实施）

**实施说明：**

1. mock-server 的 step 事件包含 V2 全字段（见 design spec `stepPayload` 示例）。
2. E2E mock 断言：三态文案（before/active/after）+ 耗时 `\d+ms|\d+\.\d+s` 可见。
3. 真实后端 E2E：simple + knowledge 路径 timeline 标签与 summary 可见。
4. 验证 V1 历史 `steps` JSON 经 `migrateV1Step` 降级渲染正常。
5. F5 reconnect 后 timeline 步骤状态与断线前一致（Redis Stream 含 step 事件）。
6. 更新 V2 plan/spec 状态。

**验证：**

- 命令：`cd sunshine-ui && npx playwright test e2e/processing-timeline.spec.ts e2e/processing-timeline-real.spec.ts`
- 预期：mock 必绿；real 在后端启动时绿

**提交建议：** `test(ui): processing timeline v2 e2e and mock payloads`

---

### Task 9: phase1-demo 脚本增强与集成测试文档
- [x]

**涉及文件：**
- 修改：`scripts/phase1-demo.sh`（与 ps1 步骤对齐，含 Gateway Step 5）
- 修改：`README.md`、`CLAUDE.md`（integration test 运行方式）
- 修改：`orchestrator/pom.xml`（确认 `-Dgroups=integration` 文档化，非改代码除非 bug）

**实施说明：**

1. 确保 `phase1-demo.sh` 与 `.ps1` 步骤 0–7 一致（LLM、RAG、SSE、Knowledge、Gateway、Nacos 手动提示、跨浏览器提示）。
2. README 增加：
   - 默认测试：`mvn test -pl orchestrator -am`
   - 全链路：`mvn test -pl orchestrator "-Dgroups=integration" "-Dtest=ChatIntegrationTest"`
3. CLAUDE.md Build & Run 节同步上述说明。

**验证：**

- 命令：`bash scripts/phase1-demo.sh`（Git Bash）Step 1–5 无 FAIL
- 预期：Linux/macOS 与 Windows 演示脚本等价

**提交建议：** `docs: phase1 demo parity and integration test instructions`

---

### Task 10: 实施计划与 gap-closure 文档状态批量同步
- [x]

**涉及文件：**
- 修改：`docs/implementation-plan.md`
- 修改：`docs/superpowers/plans/2026-06-07-phase1-gap-closure.md`
- 修改：`docs/superpowers/plans/2026-06-11-phase1.5-conversation-mvp.md`
- 修改：`docs/superpowers/plans/2026-06-11-phase1.6-generation-reconnect.md`
- 修改：`requirements/INDEX.md`（Task 1–9 完成后更新需求状态）

**实施说明：**

1. 根据 Task 1–9 实际验收结果，将各 plan 中已实现步骤的 `[ ]` 改为 `[x]`（避免文档与代码长期不一致）。
2. `implementation-plan.md` 阶段一 / 1.5 / 1.6 检查门全部反映真实状态。
3. 若 P0 全部完成，在 `REQ-PHASE1-REMAINING.md` 需求级验收标准勾选，并准备归档至 `requirements/completed/`（可选，用户确认后）。

**验证：**

- 命令：人工 diff 各 md 勾选与 Task 1–9 结论一致
- 预期：无「代码已绿但文档仍 `[ ]`」的 P0 项

**提交建议：** `docs: sync phase1 plan checkboxes with implementation`

---

### Task 11: GitHub Actions CI 基础流水线
- [x]

**涉及文件：**
- 新增：`.github/workflows/ci.yml`
- 参考：`orchestrator/pom.xml`（excludedGroups integration）
- 参考：`sunshine-ui/package.json`

**实施说明：**

1. workflow 触发：`push` / `pull_request` 到 main（或 master）。
2. Job `backend`：`mvn test -pl orchestrator,bff,llm-gateway,rag-service -am`（exclude integration，无需 API Key）。
3. Job `frontend`：`cd sunshine-ui && npm ci && npm run build`。
4. Job `e2e-mock`（可选并行）：安装 Playwright → `npx playwright test e2e/processing-timeline.spec.ts e2e/chat.spec.ts`（mock-server，无外部依赖）。
5. 不在 CI 跑 `ChatIntegrationTest` / `processing-timeline-real`（需密钥与多服务）。

**验证：**

- 命令：本地 `act` 或 push 到分支观察 Actions 绿
- 预期：backend + frontend job 通过

**提交建议：** `ci: add github actions for maven and frontend build`

---

### Task 12: knowledge 路径 Generation 重连（P2 可选）
- [x]

**涉及文件：**
- 修改：`orchestrator/src/main/java/com/sunshine/orchestrator/controller/ChatController.java`
- 修改：`orchestrator/src/main/java/com/sunshine/orchestrator/generation/GenerationJobFactory.java`
- 修改：`orchestrator/src/main/java/com/sunshine/orchestrator/conversation/ConversationService.java`（resume 409 逻辑）
- 测试：`orchestrator/src/test/java/com/sunshine/orchestrator/GenerationReconnectIntegrationTest.java`（knowledge 用例）
- 参考：`docs/superpowers/specs/2026-06-11-phase1.5-conversation-mvp-design.md` §阶段 1.6 不做

**实施说明：**

1. 扩展 `GenerationJob` 支持 `intent=knowledge`：Agent 流写入 Redis Stream，meta 含 generationId。
2. 调整 knowledge 路径 Stop/interrupted 的 resume 策略：允许 Track F 续传或 reconnect（设计评审后二选一）。
3. 移除或调整 `resumeKnowledgeIntent_returns409` 为期望 200 的新行为。
4. 前端 knowledge 路径 F5 reconnect 与 410 降级与 simple 一致。
5. **测试覆盖**：knowledge 流中断 → reconnect afterSeq 连续；cancel → 410 → resume 成功。

**验证：**

- 命令：`mvn test -pl orchestrator -am "-Dtest=GenerationReconnectIntegrationTest,ConversationIntegrationTest" -q`
- 预期：新 knowledge 用例绿；手动 F5 knowledge 问题不断流

**提交建议：** `feat(orchestrator): generation reconnect for knowledge intent`

---

## Task 依赖与建议顺序

```
Task 1 ─┬─ Task 10
Task 2 ─┤
Task 3 ─┤
Task 4 ─┘
Task 5 → Task 4（SkyWalking 验收入 gap D2）
Task 6 → Task 4（Gateway 验收入 gap D1）
Task 7（独立，可与 Task 5/6 并行）
Task 8（可与 Task 3 并行）
Task 9（依赖 Task 6）
Task 11（Task 8 mock E2E 稳定后）
Task 12（P2 可选，全部 P0 完成后）
```

**最小闭环（约 3–4 人日）：** Task 1 → 2 → 3 → 4 → 10  
**完整一阶段签字（约 8–12 人日）：** Task 1–11  
**体验增强：** Task 12

---

## Spec Coverage Check

| 来源 | Task |
|------|------|
| implementation-plan 阶段一检查门 | Task 1, 10 |
| implementation-plan 1.5 检查门 | Task 2, 10 |
| implementation-plan 1.6 检查门 | Task 3, 10 |
| phase1-gap-closure Final Checklist | Task 4, 5, 6, 10 |
| phase1.5 spec 验收清单 | Task 2 |
| phase1.6 Track G 验收 | Task 3 |
| processing-timeline-v2 spec | Task 8 |
| IC-01 ~ IC-10 | Task 1, 4, 5, 6 |

---

## 快速自检命令包

```bash
# 后端（默认 exclude integration）
mvn test -pl orchestrator -am

# 需 LLM Gateway 的集成测试
mvn test -pl orchestrator "-Dgroups=integration" "-Dtest=ChatIntegrationTest"

# 阶段一演示
powershell -ExecutionPolicy Bypass -File scripts/phase1-demo.ps1

# 前端 E2E mock
cd sunshine-ui && npx playwright test e2e/processing-timeline.spec.ts e2e/chat.spec.ts
```
