# 多智能体协作统一设计 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将「专家」统一为「智能体」概念，内部走 `AgentRuntime.run` 统一内核、外部走 A2A 接入；多智能体协作统一为 **spawn_subagent(expertId) 中心化编排**；全量重命名 Expert->Agent；扩展智能体定义模型（租户/知识库/权限/数据范围）。

**Architecture:** 8 Phase / 22 Task。重命名与功能扩展**合并执行**（不分两步）。peer-collab 完全删除，Agent Team 去中心化方案**被否决**（详见 spec §1.3）。

**Tech Stack:** Spring Cloud Alibaba (Java 17) / AgentScope-Java 2.0 / Vue3 + Naive UI / MySQL / Redis / Nacos / A2A Protocol v1.0

**Spec:** `docs/superpowers/specs/2026-07-29-multi-agent-unified-design.md`

**分支:** `feature/multi-agent-unified`

---

## 预检：确认用户已确认的取舍

| 取舍 | 决定 | 依据 |
|------|------|------|
| 重命名范围 | **全量重命名**（Expert->Agent / Peer 删除） | 用户确认「全量重命名」 |
| 命名方案 | **Agent + Team**（`Agent*` 单数类 + `team.*` 路由前缀） | 用户确认「Agent + Team」 |
| 智能体定义模型扩展 | **保留**（tenant_id / kb_scope / data_scope / permissions / model_config） | 用户确认「保留」 |
| 外部 A2A 接入 | **保留** | 用户确认「保留」 |
| peer-collab 处理 | **删除**，用 spawn_subagent(expertId) 替代 | 用户确认「删除，用主子 agent 替代」 |
| Agent Team | **不做**（被否决） | spec §1.3 评审否决 |
| 兼容旧 peer-collab | **不做兼容兜底** | CLAUDE.md「禁止兼容旧行为兜底」 |

---

## Phase 1: 基础设施 — DDL + DTO + AgentRunRequest 扩展 + 后端全量重命名

### Task 1: `agent_definition` DDL 新建

**Files:**
- Modify: `docker/mysql/init/15-sunshine-expert-manager.sql`（重命名 + 扩展字段）
- 重命名为: `docker/mysql/init/15-sunshine-agent-manager.sql`

**Step 1: 读取现有 DDL**

```bash
cat docker/mysql/init/15-sunshine-expert-manager.sql
```

确认现有表结构（`expert_definition` / `expert_skill_link`）。

**Step 2: 新建 agent-manager SQL 文件**

将 `expert_definition` 改为 `agent_definition`，`expert_skill_link` 改为 `agent_skill_link`，新增扩展字段：

```sql
ALTER TABLE agent_definition
    ADD COLUMN tenant_id         VARCHAR(32) NOT NULL DEFAULT 'default' AFTER enabled,
    ADD COLUMN kb_scope_json     VARCHAR(512) NOT NULL DEFAULT '[]' AFTER tools_json,
    ADD COLUMN data_scope_json   TEXT AFTER kb_scope_json,
    ADD COLUMN permissions_json  VARCHAR(512) NOT NULL DEFAULT '{}' AFTER data_scope_json,
    ADD COLUMN model_config_json VARCHAR(512) NOT NULL DEFAULT '{}' AFTER permissions_json,
    ADD COLUMN max_iters         INT NOT NULL DEFAULT 2 AFTER model_config_json,
    ADD COLUMN max_handoffs      INT NOT NULL DEFAULT 5 AFTER max_iters,
    ADD COLUMN source            VARCHAR(16) NOT NULL DEFAULT 'INTERNAL' AFTER max_handoffs,
    ADD COLUMN agent_card_url    VARCHAR(512) AFTER source,
    ADD COLUMN auth_config_json  VARCHAR(512) AFTER agent_card_url,
    ADD COLUMN endpoint_override VARCHAR(512) AFTER auth_config_json,
    ADD INDEX idx_tenant_enabled (tenant_id, enabled),
    ADD INDEX idx_source (source);
```

注意：新环境直接用新 SQL（含全部扩展字段），不做 ALTER 增量。

**Step 3: 删除旧 SQL 文件**

```bash
rm docker/mysql/init/15-sunshine-expert-manager.sql
```

**Step 4: 验证**

```bash
ls docker/mysql/init/15-sunshine-agent-manager.sql
cat docker/mysql/init/15-sunshine-agent-manager.sql | grep -c "agent_definition"
cat docker/mysql/init/15-sunshine-agent-manager.sql | grep -c "agent_skill_link"
cat docker/mysql/init/15-sunshine-agent-manager.sql | grep -c "expert"
```

- [ ] 新文件存在
- [ ] `agent_definition` 出现 >= 2 次（CREATE + ALTER）
- [ ] `agent_skill_link` 出现 >= 1 次
- [ ] `expert` 出现 0 次

**Step 5: Commit**

```bash
git add docker/mysql/init/15-sunshine-agent-manager.sql
git rm docker/mysql/init/15-sunshine-expert-manager.sql
git commit -m "feat(agent-manager): rename expert_definition to agent_definition with extended fields"
```

---

### Task 2: `AgentCatalogEntry` DTO 新建（agent-manager + orchestrator）

**Files:**
- Modify: `agent-manager/src/main/java/com/sunshine/agent/catalog/AgentCatalogEntry.java`（新，从 ExpertCatalogEntry 重命名 + 扩展）
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/catalog/AgentCatalogEntry.java`（新，从 ExpertCatalogEntry 重命名 + 扩展）

**Step 1: 读取现有 ExpertCatalogEntry**

```bash
cat orchestrator/src/main/java/com/sunshine/orchestrator/catalog/ExpertCatalogEntry.java
```

**Step 2: 新建 AgentCatalogEntry（agent-manager 侧）**

```java
package com.sunshine.agent.catalog;

import java.util.List;

public record AgentCatalogEntry(
        String id,
        String displayName,
        String description,
        String systemPrompt,
        List<String> skillIds,
        List<String> tags,
        String toolsJson,
        boolean enabled,
        String tenantId,
        List<String> kbScope,
        String dataScopeJson,
        String permissionsJson,
        String modelConfigJson,
        int maxIters,
        int maxHandoffs,
        AgentSource source,
        String agentCardUrl,
        String authConfigJson,
        String endpointOverride
) {
    public enum AgentSource { INTERNAL, EXTERNAL }

    public String primarySkillId() {
        return skillIds != null && !skillIds.isEmpty() ? skillIds.get(0) : null;
    }
}
```

**Step 3: 新建 AgentCatalogEntry（orchestrator 侧，同结构）**

同结构，包名 `com.sunshine.orchestrator.catalog`。

**Step 4: 验证编译**

```bash
cd agent-manager && mvn compile -pl . -am -q
cd ../orchestrator && mvn compile -pl . -am -q
```

- [ ] agent-manager 编译通过
- [ ] orchestrator 编译通过

**Step 5: Commit**

```bash
git add agent-manager/src/main/java/com/sunshine/agent/catalog/AgentCatalogEntry.java
git add orchestrator/src/main/java/com/sunshine/orchestrator/catalog/AgentCatalogEntry.java
git commit -m "feat(agent-manager): create AgentCatalogEntry DTO with extended fields"
```

---

### Task 3: `AgentRunRequest` 扩展（kbScope / dataScopeJson / permissionsJson / modelConfigJson）

**Files:**
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/agent/runtime/AgentRunRequest.java`

**Step 1: 读取现有 AgentRunRequest**

```bash
cat orchestrator/src/main/java/com/sunshine/orchestrator/agent/runtime/AgentRunRequest.java
```

**Step 2: 添加扩展字段**

在 record 中新增字段：

```java
public record AgentRunRequest(
        // ... 现有字段 ...
        List<String> kbScope,           // 知识库范围（覆盖会话级 kbId）
        String dataScopeJson,           // 数据访问范围
        String permissionsJson,         // 权限配置
        String modelConfigJson          // 模型配置
) {
    // 新增 builder 方法
    public static AgentRunRequestBuilder sub(...) { ... }
}
```

注意：record 不可变，新增字段需同步修改所有构造点。

**Step 3: 验证编译**

```bash
cd orchestrator && mvn compile -pl . -am -q
```

- [ ] 编译通过
- [ ] 无构造点遗漏

**Step 4: Commit**

```bash
git add orchestrator/src/main/java/com/sunshine/orchestrator/agent/runtime/AgentRunRequest.java
git commit -m "feat(orchestrator): extend AgentRunRequest with kbScope/dataScope/permissions/modelConfig"
```

---

### Task 4: 后端全量重命名（expert-manager -> agent-manager + orchestrator Expert* -> Agent*）

**Files:**
- Modify: 整个 `expert-manager/` 目录 -> `agent-manager/`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/expert/` -> `catalog/`（存活类移入）
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/peer/` -> **删除**
- Modify: `bff/src/main/java/com/sunshine/bff/expert/` -> `bff/src/main/java/com/sunshine/bff/agent/`
- Modify: `docs/nacos/sunshine-expert-manager.yaml` -> `docs/nacos/sunshine-agent-manager.yaml`

**Step 1: 列出所有待重命名文件**

```bash
find expert-manager -type f -name "*.java" | head -20
find orchestrator/src/main/java/com/sunshine/orchestrator/expert -type f -name "*.java" 2>/dev/null
find orchestrator/src/main/java/com/sunshine/orchestrator/peer -type f -name "*.java" 2>/dev/null
find bff/src/main/java/com/sunshine/bff -path "*expert*" -name "*.java" 2>/dev/null
```

**Step 2: 重命名 expert-manager 目录 + 包名**

```bash
# 1. 目录重命名
mv expert-manager agent-manager

# 2. 包路径重命名（com.sunshine.expert -> com.sunshine.agent）
find agent-manager -type f -name "*.java" -exec sed -i 's/com\.sunshine\.expert/com.sunshine.agent/g' {} +

# 3. 类名重命名（Expert* -> Agent*）
find agent-manager -type f -name "*.java" -exec sed -i 's/ExpertManagerApplication/AgentManagerApplication/g' {} +
find agent-manager -type f -name "*.java" -exec sed -i 's/ExpertDefinitionEntity/AgentDefinitionEntity/g' {} +
find agent-manager -type f -name "*.java" -exec sed -i 's/ExpertSkillLinkEntity/AgentSkillLinkEntity/g' {} +
find agent-manager -type f -name "*.java" -exec sed -i 's/ExpertSkillLinkId/AgentSkillLinkId/g' {} +
find agent-manager -type f -name "*.java" -exec sed -i 's/ExpertAdminService/AgentAdminService/g' {} +
find agent-manager -type f -name "*.java" -exec sed -i 's/ExpertCatalogRegistry/AgentCatalogRegistry/g' {} +
find agent-manager -type f -name "*.java" -exec sed -i 's/ExpertCreateRequest/AgentCreateRequest/g' {} +
find agent-manager -type f -name "*.java" -exec sed -i 's/ExpertUpdateRequest/AgentUpdateRequest/g' {} +
find agent-manager -type f -name "*.java" -exec sed -i 's/ExpertEnableRequest/AgentEnableRequest/g' {} +
find agent-manager -type f -name "*.java" -exec sed -i 's/ExpertCatalogIndexEntry/AgentCatalogIndexEntry/g' {} +
find agent-manager -type f -name "*.java" -exec sed -i 's/ExpertCatalogEntry/AgentCatalogEntry/g' {} +
find agent-manager -type f -name "*.java" -exec sed -i 's/ExpertSkillLinkRepository/AgentSkillLinkRepository/g' {} +
find agent-manager -type f -name "*.java" -exec sed -i 's/ExpertDefinitionRepository/AgentDefinitionRepository/g' {} +
find agent-manager -type f -name "*.java" -exec sed -i 's/ExpertAdminController/AgentAdminController/g' {} +
find agent-manager -type f -name "*.java" -exec sed -i 's/ExpertCatalogController/AgentCatalogController/g' {} +
find agent-manager -type f -name "*.java" -exec sed -i 's/ExpertErrorCode/AgentErrorCode/g' {} +

# 4. 文件名重命名
find agent-manager -type f -name "Expert*.java" | while read f; do mv "$f" "$(echo $f | sed 's/Expert/Agent/g')"; done
```

**Step 3: orchestrator 存活类重命名 + 移入 catalog 包**

```bash
# 存活类：ExpertCatalogService / ExpertCatalogClient / ExpertBindingParser / ExpertBindingRoutingPolicy / ExpertBindingOutcome / ExpertBindingSource / ExpertToolsJson / ExpertStepLabels
# 移入 catalog/ 包 + 重命名

# 1. 移动文件
mv orchestrator/src/main/java/com/sunshine/orchestrator/expert/ExpertCatalogService.java orchestrator/src/main/java/com/sunshine/orchestrator/catalog/AgentCatalogService.java
mv orchestrator/src/main/java/com/sunshine/orchestrator/expert/ExpertCatalogClient.java orchestrator/src/main/java/com/sunshine/orchestrator/catalog/AgentCatalogClient.java
# ... 其他存活类同理

# 2. 包名 + 类名替换
find orchestrator/src/main/java/com/sunshine/orchestrator/catalog -type f -name "*.java" -exec sed -i 's/package com\.sunshine\.orchestrator\.expert/package com.sunshine.orchestrator.catalog/g' {} +
find orchestrator/src/main/java/com/sunshine/orchestrator/catalog -type f -name "*.java" -exec sed -i 's/ExpertCatalogService/AgentCatalogService/g' {} +
find orchestrator/src/main/java/com/sunshine/orchestrator/catalog -type f -name "*.java" -exec sed -i 's/ExpertCatalogClient/AgentCatalogClient/g' {} +
# ... 其他类名同理
```

**Step 4: 删除 peer 包**

```bash
rm -rf orchestrator/src/main/java/com/sunshine/orchestrator/peer/
```

**Step 5: 删除 orchestrator expert 包中删除类**

```bash
# 删除类（spec §15.1.3）
rm orchestrator/src/main/java/com/sunshine/orchestrator/expert/ExpertHubEngine.java
rm orchestrator/src/main/java/com/sunshine/orchestrator/expert/ExpertSpeakStreamer.java
rm orchestrator/src/main/java/com/sunshine/orchestrator/expert/ExpertSpeakHook.java
rm orchestrator/src/main/java/com/sunshine/orchestrator/expert/ExpertPeerAgentFactory.java
rm orchestrator/src/main/java/com/sunshine/orchestrator/expert/ExpertRoundCoordinatorService.java
rm orchestrator/src/main/java/com/sunshine/orchestrator/expert/ExpertConsultationExecutor.java
rm orchestrator/src/main/java/com/sunshine/orchestrator/expert/ExpertSessionRounds.java
rm orchestrator/src/main/java/com/sunshine/orchestrator/expert/ExpertContinueDecision.java
rm orchestrator/src/main/java/com/sunshine/orchestrator/expert/ExpertSpeakCallback.java
rm orchestrator/src/main/java/com/sunshine/orchestrator/expert/ExpertCollaborationParams.java
rm orchestrator/src/main/java/com/sunshine/orchestrator/expert/ExpertRoster.java
rm orchestrator/src/main/java/com/sunshine/orchestrator/expert/ConsultationSynthesizer.java
rm orchestrator/src/main/java/com/sunshine/orchestrator/expert/PeerSynthesisProperties.java
rm orchestrator/src/main/java/com/sunshine/orchestrator/expert/PeerStepLabels.java
rm orchestrator/src/main/java/com/sunshine/orchestrator/expert/PeerTranscriptEntry.java
rm orchestrator/src/main/java/com/sunshine/orchestrator/expert/PeerRunRepository.java
rm orchestrator/src/main/java/com/sunshine/orchestrator/expert/PeerRunAuditView.java
rm orchestrator/src/main/java/com/sunshine/orchestrator/expert/PeerMsgSupport.java
rm orchestrator/src/main/java/com/sunshine/orchestrator/expert/PeerRunAuditService.java
rm orchestrator/src/main/java/com/sunshine/orchestrator/expert/PeerRunEntity.java
rm orchestrator/src/main/java/com/sunshine/orchestrator/expert/ExpertTranscriptEntry.java
rm orchestrator/src/main/java/com/sunshine/orchestrator/expert/ExpertCollaborationPlanSanitizer.java
rm orchestrator/src/main/java/com/sunshine/orchestrator/expert/ExpertCoordinatorProperties.java
```

**Step 6: BFF 重命名**

```bash
# BFF: ExpertsController -> AgentsController, ExpertManagerClient -> AgentManagerClient
find bff/src/main/java -type f -name "*.java" -exec sed -i 's/ExpertsController/AgentsController/g' {} +
find bff/src/main/java -type f -name "*.java" -exec sed -i 's/ExpertManagerClient/AgentManagerClient/g' {} +
# 文件名重命名
find bff/src/main/java -type f -name "Experts*.java" | while read f; do mv "$f" "$(echo $f | sed 's/Experts/Agents/g')"; done
find bff/src/main/java -type f -name "Expert*.java" | while read f; do mv "$f" "$(echo $f | sed 's/Expert/Agent/g')"; done
```

**Step 7: Nacos 配置文件重命名**

```bash
mv docs/nacos/sunshine-expert-manager.yaml docs/nacos/sunshine-agent-manager.yaml
# 修改文件内容中的服务名
sed -i 's/expert-manager/agent-manager/g' docs/nacos/sunshine-agent-manager.yaml
```

**Step 8: 全局替换残留引用**

```bash
# 检查是否还有 Expert* 残留
grep -r "ExpertHubEngine\|ExpertSpeak\|PeerMsg\|ConsultationSynthesizer" orchestrator/src/main/java/ --include="*.java" | head -5
grep -r "expert-manager" docs/nacos/ --include="*.yaml" | head -5
grep -r "ExpertDefinition\|ExpertSkillLink" agent-manager/src/main/java/ --include="*.java" | head -5
```

- [ ] 无 `ExpertHubEngine` / `ExpertSpeak*` / `PeerMsg*` / `ConsultationSynthesizer` 残留
- [ ] 无 `expert-manager` Nacos 配置残留
- [ ] 无 `ExpertDefinition` / `ExpertSkillLink` 残留

**Step 9: 编译验证**

```bash
cd agent-manager && mvn compile -pl . -am -q
cd ../orchestrator && mvn compile -pl . -am -q
cd ../bff && mvn compile -pl . -am -q
```

- [ ] agent-manager 编译通过
- [ ] orchestrator 编译通过
- [ ] bff 编译通过

**Step 10: Commit**

```bash
git add -A
git commit -m "refactor: full rename expert-manager to agent-manager, Expert* to Agent*, delete Peer*"
```

---

## Phase 2: 安全缺口修复（最高优先级）

### Task 5: HITL 身份校验（HitlTokenRegistry 存发起 userId，confirm 时校验）

**Files:**
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/hitl/HitlTokenRegistry.java`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/hitl/HitlConfirmController.java`

**Step 1: 读取现有 HitlTokenRegistry**

```bash
cat orchestrator/src/main/java/com/sunshine/orchestrator/hitl/HitlTokenRegistry.java
cat orchestrator/src/main/java/com/sunshine/orchestrator/hitl/HitlConfirmController.java
```

**Step 2: HitlTokenRegistry 增加 userId 存储**

```java
public record HitlTokenEntry(
        String token,
        String conversationId,
        String userId,          // 新增：发起用户
        String toolCallId,
        String toolName,
        Map<String, Object> params,
        Instant createdAt,
        Instant expiresAt
) {}
```

**Step 3: HitlConfirmController confirm 时校验 userId**

```java
@PostMapping("/confirm")
public ResponseEntity<?> confirm(@RequestBody ConfirmRequest request,
                                  @RequestHeader("x-user-id") String currentUserId) {
    HitlTokenEntry entry = hitlTokenRegistry.get(request.getToken());
    if (entry == null) {
        return ResponseEntity.notFound().build();
    }
    // 新增：校验发起用户身份
    if (!entry.userId().equals(currentUserId)) {
        return ResponseEntity.status(403).body(Map.of("error", "无权确认他人发起的操作"));
    }
    // ... 现有确认逻辑
}
```

**Step 4: 编译 + 单测**

```bash
cd orchestrator && mvn compile -pl . -am -q
mvn test -pl . -Dtest=HitlTokenRegistryTest
```

- [ ] 编译通过
- [ ] 单测通过

**Step 5: Commit**

```bash
git add orchestrator/src/main/java/com/sunshine/orchestrator/hitl/
git commit -m "fix(security): add userId validation to HITL confirm"
```

---

### Task 6: 工具 output 脱敏（ToolAuditService output 也走 DesensitizeClient.scrub）

**Files:**
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/tool/ToolAuditService.java`

**Step 1: 读取现有 ToolAuditService**

```bash
cat orchestrator/src/main/java/com/sunshine/orchestrator/tool/ToolAuditService.java
```

**Step 2: output 脱敏**

找到 output 截断 240 字符的位置，改为：

```java
// 原代码（仅截断）
// String output = truncate(result, 240);

// 新代码（脱敏 + 截断）
String output = desensitizeClient.scrub(result);
output = truncate(output, 240);
```

**Step 3: 编译 + 单测**

```bash
cd orchestrator && mvn compile -pl . -am -q
```

- [ ] 编译通过

**Step 4: Commit**

```bash
git add orchestrator/src/main/java/com/sunshine/orchestrator/tool/ToolAuditService.java
git commit -m "fix(security): desensitize tool output in audit"
```

---

### Task 7: 审计查询鉴权（AuditController 按 conversationId/userId 归属校验）

**Files:**
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/audit/AuditController.java`

**Step 1: 读取现有 AuditController**

```bash
cat orchestrator/src/main/java/com/sunshine/orchestrator/audit/AuditController.java
```

**Step 2: 添加归属校验**

```java
@GetMapping("/recent")
public ResponseEntity<?> recent(@RequestParam String conversationId,
                                 @RequestHeader("x-user-id") String currentUserId) {
    // 新增：校验 conversationId 归属
    ChatConversationEntity conversation = conversationRepository.findById(conversationId);
    if (conversation == null || !conversation.getUserId().equals(currentUserId)) {
        return ResponseEntity.status(403).body(Map.of("error", "无权访问该会话审计"));
    }
    // ... 现有逻辑
}
```

**Step 3: 编译**

```bash
cd orchestrator && mvn compile -pl . -am -q
```

- [ ] 编译通过

**Step 4: Commit**

```bash
git add orchestrator/src/main/java/com/sunshine/orchestrator/audit/AuditController.java
git commit -m "fix(security): add conversation ownership check to audit endpoints"
```

---

### Task 8: transcript 全文脱敏（peer_run.transcript_json 落库前脱敏）

**Files:**
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/expert/PeerRunAuditService.java`（或重命名后的对应类）

**Step 1: 读取现有 PeerRunAuditService**

```bash
cat orchestrator/src/main/java/com/sunshine/orchestrator/expert/PeerRunAuditService.java
```

**Step 2: 落库前脱敏**

找到 transcript_json 落库位置，对 content 字段脱敏：

```java
// 原代码
// transcriptJson = objectMapper.writeValueAsString(entries);

// 新代码：脱敏后落库
List<PeerTranscriptEntry> sanitized = entries.stream()
    .map(e -> new PeerTranscriptEntry(e.role(), desensitizeClient.scrub(e.content()), e.timestamp()))
    .toList();
transcriptJson = objectMapper.writeValueAsString(sanitized);
```

**Step 3: 编译**

```bash
cd orchestrator && mvn compile -pl . -am -q
```

- [ ] 编译通过

**Step 4: Commit**

```bash
git add orchestrator/src/main/java/com/sunshine/orchestrator/expert/PeerRunAuditService.java
git commit -m "fix(security): desensitize transcript content before persistence"
```

---

## Phase 3: 权限落地（HITL / 沙箱 / 模型配置）

### Task 9: HITL 动态化（bindHitlBridge 按 permissions.toolConfirmation 决定）

**Files:**
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/agent/runtime/ReActAgentRuntime.java`（或 HITL 绑定处）
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/agent/StepEventBridge.java`

**Step 1: 读取现有 bindHitlBridge 调用**

```bash
grep -rn "bindHitlBridge" orchestrator/src/main/java/ --include="*.java"
```

**Step 2: 按 permissions.toolConfirmation 动态决定**

```java
// 原代码（硬编码 false）
// StepEventBridge.bindHitlBridge(..., false);

// 新代码：按 permissions.toolConfirmation 决定
String toolConfirmation = parsePermissions(permissionsJson).getToolConfirmation(); // always/never/inherit
boolean hitlEnabled = switch (toolConfirmation) {
    case "always" -> true;
    case "never" -> false;
    default -> toolRequireConfirmation; // inherit = 读工具 require_confirmation
};
StepEventBridge.bindHitlBridge(..., hitlEnabled);
```

**Step 3: 编译**

```bash
cd orchestrator && mvn compile -pl . -am -q
```

- [ ] 编译通过

**Step 4: Commit**

```bash
git add orchestrator/src/main/java/com/sunshine/orchestrator/agent/
git commit -m "feat(orchestrator): dynamic HITL based on agent permissions.toolConfirmation"
```

---

### Task 10: 沙箱 WriteMode 覆盖（按 permissions.sandboxWriteMode 覆盖 SandboxWriteHitlMode）

**Files:**
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/sandbox/SandboxHitlPolicy.java`

**Step 1: 读取现有 SandboxHitlPolicy**

```bash
cat orchestrator/src/main/java/com/sunshine/orchestrator/sandbox/SandboxHitlPolicy.java
```

**Step 2: 按 permissions.sandboxWriteMode 覆盖**

```java
// 新增：从 AgentRunRequest 读取 permissions.sandboxWriteMode
String sandboxWriteMode = parsePermissions(permissionsJson).getSandboxWriteMode(); // never/always/smart
if ("never".equals(sandboxWriteMode)) {
    return SandboxHitlDecision.deny("该智能体禁止沙箱写操作");
}
// always/smart 走现有逻辑
```

**Step 3: 编译**

```bash
cd orchestrator && mvn compile -pl . -am -q
```

- [ ] 编译通过

**Step 4: Commit**

```bash
git add orchestrator/src/main/java/com/sunshine/orchestrator/sandbox/SandboxHitlPolicy.java
git commit -m "feat(orchestrator): override sandbox write mode from agent permissions"
```

---

### Task 11: 模型配置覆盖（AgentRuntime 读 modelConfigJson 覆盖 OpenAIChatModel）

**Files:**
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/agent/runtime/ReActAgentRuntime.java`

**Step 1: 读取现有 ReActAgentRuntime 模型构建**

```bash
grep -n "OpenAIChatModel\|ChatModel" orchestrator/src/main/java/com/sunshine/orchestrator/agent/runtime/ReActAgentRuntime.java | head -10
```

**Step 2: 按 modelConfigJson 覆盖**

```java
// 新增：从 AgentRunRequest 读取 modelConfigJson
String modelConfigJson = request.modelConfigJson();
if (modelConfigJson != null && !modelConfigJson.equals("{}")) {
    ModelConfig config = objectMapper.readValue(modelConfigJson, ModelConfig.class);
    if (config.getModel() != null) {
        // 覆盖默认模型
        chatModel = OpenAIChatModel.builder()
            .model(config.getModel())
            .temperature(config.getTemperature())
            .build();
    }
}
```

**Step 3: 编译**

```bash
cd orchestrator && mvn compile -pl . -am -q
```

- [ ] 编译通过

**Step 4: Commit**

```bash
git add orchestrator/src/main/java/com/sunshine/orchestrator/agent/runtime/ReActAgentRuntime.java
git commit -m "feat(orchestrator): override model config from agent modelConfigJson"
```

---

## Phase 4: 知识库范围 + 数据范围透传

### Task 12: RagTool kbScope（resolveKbId 优先读智能体 kbScope，覆盖会话级 kbId）

**Files:**
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/agent/RagTool.java`

**Step 1: 读取现有 RagTool.resolveKbId**

```bash
cat orchestrator/src/main/java/com/sunshine/orchestrator/agent/RagTool.java
```

**Step 2: 优先读 kbScope**

```java
// 原代码
// String kbId = conversation.getKbId();

// 新代码：优先读 AgentRunRequest.kbScope
List<String> kbScope = request.kbScope();
String kbId;
if (kbScope != null && !kbScope.isEmpty() && !kbScope.contains("*")) {
    kbId = kbScope.get(0); // 取第一个（或按策略合并）
} else {
    kbId = conversation.getKbId(); // 继承会话级
}
```

**Step 3: 编译**

```bash
cd orchestrator && mvn compile -pl . -am -q
```

- [ ] 编译通过

**Step 4: Commit**

```bash
git add orchestrator/src/main/java/com/sunshine/orchestrator/agent/RagTool.java
git commit -m "feat(orchestrator): RagTool kbScope override conversation kbId"
```

---

### Task 13: dataScope 透传（ToolAuditContext 加 dataScope / kbScope / permissions 字段）

**Files:**
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/tool/ToolAuditContext.java`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/agent/StepEventBridge.java`

**Step 1: 读取现有 ToolAuditContext**

```bash
cat orchestrator/src/main/java/com/sunshine/orchestrator/tool/ToolAuditContext.java
```

**Step 2: 添加 dataScope / kbScope / permissions 字段**

```java
public record ToolAuditContext(
        String conversationId,
        String userId,
        String tenantId,
        String kbId,
        // 新增
        String dataScopeJson,
        List<String> kbScope,
        String permissionsJson
) {}
```

**Step 3: StepEventBridge 绑定新字段**

```java
ToolAuditContext context = new ToolAuditContext(
    conversationId, userId, tenantId, kbId,
    request.dataScopeJson(),
    request.kbScope(),
    request.permissionsJson()
);
```

**Step 4: 编译**

```bash
cd orchestrator && mvn compile -pl . -am -q
```

- [ ] 编译通过

**Step 5: Commit**

```bash
git add orchestrator/src/main/java/com/sunshine/orchestrator/tool/ToolAuditContext.java
git add orchestrator/src/main/java/com/sunshine/orchestrator/agent/StepEventBridge.java
git commit -m "feat(orchestrator): pass dataScope/kbScope/permissions to ToolAuditContext"
```

---

## Phase 5: 前端重命名 + 配置页扩展

### Task 14: 前端全量重命名（/experts -> /agents + 组件名 + 文案）

**Files:**
- Modify: `sunshine-ui/src/views/ExpertsView.vue` -> `AgentsView.vue`
- Modify: `sunshine-ui/src/router/index.ts`（路由改）
- Modify: `sunshine-ui/src/api/experts.ts` -> `agents.ts`
- Modify: `sunshine-ui/src/composables/useChatExpertMention.ts` -> `useChatAgentMention.ts`
- Modify: `sunshine-ui/src/composables/useExpertsRouteState.ts` -> `useAgentsRouteState.ts`
- Modify: `sunshine-ui/src/utils/expertMention.ts` -> `agentMention.ts`
- Delete: `sunshine-ui/src/components/PeerCollabPanel.vue`

**Step 1: 文件重命名**

```bash
cd sunshine-ui/src
mv views/ExpertsView.vue views/AgentsView.vue
mv api/experts.ts api/agents.ts
mv composables/useChatExpertMention.ts composables/useChatAgentMention.ts
mv composables/useExpertsRouteState.ts composables/useAgentsRouteState.ts
mv utils/expertMention.ts utils/agentMention.ts
rm components/PeerCollabPanel.vue
```

**Step 2: 全局替换**

```bash
# 路由
sed -i 's|/experts|/agents|g' router/index.ts
# 组件名
find . -type f \( -name "*.vue" -o -name "*.ts" \) -exec sed -i 's/ExpertsView/AgentsView/g' {} +
find . -type f \( -name "*.vue" -o -name "*.ts" \) -exec sed -i 's/useChatExpertMention/useChatAgentMention/g' {} +
find . -type f \( -name "*.vue" -o -name "*.ts" \) -exec sed -i 's/useExpertsRouteState/useAgentsRouteState/g' {} +
# API
find . -type f \( -name "*.vue" -o -name "*.ts" \) -exec sed -i 's/experts\.ts/agents.ts/g' {} +
find . -type f \( -name "*.vue" -o -name "*.ts" \) -exec sed -i 's/expertMention/agentMention/g' {} +
# 文案
find . -type f -name "*.vue" -exec sed -i 's/专家/智能体/g' {} +
find . -type f -name "*.vue" -exec sed -i 's/多专家协作/多智能体协作/g' {} +
```

**Step 3: 验证编译**

```bash
cd sunshine-ui && npm run build 2>&1 | tail -20
```

- [ ] 编译通过
- [ ] 无 `experts` / `Experts` / `expert` 残留（除注释外）

**Step 4: Commit**

```bash
git add sunshine-ui/
git commit -m "refactor(ui): rename /experts to /agents, Expert to Agent, delete PeerCollabPanel"
```

---

### Task 15: 前端配置页扩展（kbScope / dataScope / permissions / model / maxIters）

**Files:**
- Modify: `sunshine-ui/src/views/AgentsView.vue`

**Step 1: 读取现有 AgentsView.vue**

```bash
cat sunshine-ui/src/views/AgentsView.vue
```

**Step 2: 添加配置区块**

在现有表单基础上，新增配置区块：

- 知识库范围：`kbScope` 多选（从 `/api/rag/kb/list` 拉取）
- 数据范围：`dataScope` JSON 编辑器
- 权限：`toolConfirmation` 下拉 / `sandboxWriteMode` 下拉 / `allowDelegate` 开关 / `allowFinishTask` 开关
- 模型：`model` 下拉 / `temperature` 滑块
- 执行限制：`maxIters` / `maxHandoffs` 数值输入
- 租户：`tenantId`（admin 可见）
- 外部接入：`source` / `agentCardUrl` / `authConfigJson` / `endpointOverride`（外部 tab）

**Step 3: 编译验证**

```bash
cd sunshine-ui && npm run build 2>&1 | tail -20
```

- [ ] 编译通过

**Step 4: Commit**

```bash
git add sunshine-ui/src/views/AgentsView.vue
git commit -m "feat(ui): extend agent config page with kbScope/dataScope/permissions/model/maxIters"
```

---

## Phase 6: 删除 peer-collab + spawn_subagent expertId 扩展

### Task 16: 删除 peer-collab 全套代码

**Files:**
- Delete: `orchestrator/src/main/java/com/sunshine/orchestrator/expert/ExpertHubEngine.java`
- Delete: `orchestrator/src/main/java/com/sunshine/orchestrator/expert/ExpertSpeakStreamer.java`
- Delete: `orchestrator/src/main/java/com/sunshine/orchestrator/expert/ExpertSpeakHook.java`
- Delete: `orchestrator/src/main/java/com/sunshine/orchestrator/expert/ExpertPeerAgentFactory.java`
- Delete: `orchestrator/src/main/java/com/sunshine/orchestrator/expert/ExpertRoundCoordinatorService.java`
- Delete: `orchestrator/src/main/java/com/sunshine/orchestrator/expert/ExpertConsultationExecutor.java`
- Delete: `orchestrator/src/main/java/com/sunshine/orchestrator/expert/ConsultationSynthesizer.java`
- Delete: `orchestrator/src/main/java/com/sunshine/orchestrator/expert/PeerMsgSupport.java`
- Delete: `orchestrator/src/main/java/com/sunshine/orchestrator/expert/PeerRunAuditService.java`
- Delete: `orchestrator/src/main/java/com/sunshine/orchestrator/expert/PeerSynthesisProperties.java`
- Delete: `orchestrator/src/main/java/com/sunshine/orchestrator/peer/`（整个包）

**Step 1: 删除文件**

已在 Task 4 中一并删除（重命名时合并执行）。此 Task 确认无残留。

```bash
grep -rn "ExpertHubEngine\|ExpertSpeak\|PeerMsgSupport\|ConsultationSynthesizer\|ExpertConsultationExecutor" orchestrator/src/main/java/ --include="*.java" | wc -l
```

- [ ] 残留数 = 0

**Step 2: Commit**

```bash
git add -A
git commit -m "refactor: delete peer-collab code (ExpertHubEngine/ExpertSpeak*/PeerMsg*/ConsultationSynthesizer)"
```

---

### Task 17: SpawnSubagentTool 扩展（expertId + resolveAgent + AgentExecutorRouter）

**Files:**
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/agent/SpawnSubagentTool.java`
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/catalog/AgentExecutorRouter.java`

**Step 1: 读取现有 SpawnSubagentTool**

```bash
cat orchestrator/src/main/java/com/sunshine/orchestrator/agent/SpawnSubagentTool.java
```

**Step 2: 入参扩展 expertId**

```java
@SunshineTool(name = "spawn_subagent", description = "Spawn a sub-agent to handle a specific task")
public Flux<StreamToken> spawnSubagent(
        @ToolParam(name = "prompt", description = "Task description for the sub-agent", required = false) String prompt,
        @ToolParam(name = "expertId", description = "ID of a pre-defined agent to spawn", required = false) String expertId,
        @ToolParam(name = "label", description = "Display label for the sub-agent", required = false) String label
) {
    if (prompt == null && expertId == null) {
        return Flux.error(new IllegalArgumentException("prompt or expertId is required"));
    }
    
    if (expertId != null) {
        AgentCatalogEntry agent = agentCatalogService.find(expertId);
        if (agent == null) {
            return Flux.error(new IllegalArgumentException("Agent not found: " + expertId));
        }
        return agentExecutorRouter.invokeAgent(agent, prompt, ...);
    }
    
    // 现有逻辑：临时子 Agent
    return spawnTemporarySubagent(prompt, label);
}
```

**Step 3: 新建 AgentExecutorRouter**

```java
@Component
public class AgentExecutorRouter {
    private final AgentRuntime agentRuntime;
    private final ExternalAgentClient externalAgentClient;
    
    public Flux<StreamToken> invokeAgent(AgentCatalogEntry agent, String query, ...) {
        return switch (agent.source()) {
            case INTERNAL -> agentRuntime.run(buildInternalSubRequest(agent, query, ...));
            case EXTERNAL -> externalAgentClient.invoke(agent, query, ...);
        };
    }
}
```

**Step 4: 编译**

```bash
cd orchestrator && mvn compile -pl . -am -q
```

- [ ] 编译通过

**Step 5: Commit**

```bash
git add orchestrator/src/main/java/com/sunshine/orchestrator/agent/SpawnSubagentTool.java
git add orchestrator/src/main/java/com/sunshine/orchestrator/catalog/AgentExecutorRouter.java
git commit -m "feat(orchestrator): extend spawn_subagent with expertId + AgentExecutorRouter"
```

---

## Phase 7: 外部 A2A + $A $B 路由

### Task 18: ExternalAgentClient（A2A Client）

**Files:**
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/catalog/ExternalAgentClient.java`

**Step 1: 新建 ExternalAgentClient**

```java
@Component
public class ExternalAgentClient {
    private final WebClient webClient;
    
    public Flux<StreamToken> invoke(AgentCatalogEntry agent, String query, List<String> contextBlocks) {
        String endpoint = resolveEndpoint(agent);
        String authHeader = resolveAuth(agent);
        Map<String, Object> payload = Map.of(
            "message", Map.of("role", "user", "parts", List.of(Map.of("text", composeA2aMessage(query, contextBlocks)))),
            "acceptedOutputModes", List.of("text/plain"));
        return webClient.post().uri(endpoint + "/tasks/sendSubscribe")
                .header("Authorization", authHeader)
                .bodyValue(payload)
                .retrieve()
                .bodyToFlux(String.class)
                .flatMap(line -> mapA2aEvent(line, agent));
    }
}
```

**Step 2: 编译**

```bash
cd orchestrator && mvn compile -pl . -am -q
```

- [ ] 编译通过

**Step 3: Commit**

```bash
git add orchestrator/src/main/java/com/sunshine/orchestrator/catalog/ExternalAgentClient.java
git commit -m "feat(orchestrator): add ExternalAgentClient for A2A integration"
```

---

### Task 19: agent-manager API（外部智能体 CRUD + Agent Card 拉取预填接口）

**Files:**
- Modify: `agent-manager/src/main/java/com/sunshine/agent/controller/AgentAdminController.java`
- Modify: `agent-manager/src/main/java/com/sunshine/agent/service/AgentAdminService.java`

**Step 1: 新增外部智能体注册接口**

```java
@PostMapping("/external")
public ResponseEntity<AgentCatalogEntry> registerExternal(@RequestBody ExternalAgentRequest request) {
    // 1. 拉取 Agent Card
    AgentCard card = agentCardClient.fetch(request.getAgentCardUrl());
    // 2. 预填 display_name / description / tags
    AgentDefinitionEntity entity = new AgentDefinitionEntity();
    entity.setDisplayName(card.getName());
    entity.setDescription(card.getDescription());
    entity.setSource("EXTERNAL");
    entity.setAgentCardUrl(request.getAgentCardUrl());
    // 3. 存库
    return ResponseEntity.ok(agentAdminService.save(entity));
}
```

**Step 2: 编译**

```bash
cd agent-manager && mvn compile -pl . -am -q
```

- [ ] 编译通过

**Step 3: Commit**

```bash
git add agent-manager/src/main/java/com/sunshine/agent/
git commit -m "feat(agent-manager): add external agent registration with Agent Card prefetch"
```

---

### Task 20: $A $B 路由改造（主 Agent = 首个 $ 绑定 + systemPrompt 注入可 spawn 列表）

**Files:**
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/routing/AgentBindingRoutingPolicy.java`

**Step 1: 读取现有 AgentBindingRoutingPolicy**

```bash
cat orchestrator/src/main/java/com/sunshine/orchestrator/routing/AgentBindingRoutingPolicy.java
```

**Step 2: 多 $ 绑定时主 Agent = 首个，注入可 spawn 列表**

```java
// 当检测到多个 $ 绑定时
List<String> boundAgentIds = parseAllDollarBindings(query);
String mainAgentId = boundAgentIds.get(0); // 主 Agent = 首个
List<String> otherAgentIds = boundAgentIds.subList(1, boundAgentIds.size());

// 注入 systemPrompt：告知主 Agent 可 spawn 其他智能体
String spawnHint = "用户要求以下智能体也参与协作：" + String.join(", ", otherAgentIds) + 
    "。你可以通过 spawn_subagent(expertId='<agent-id>', prompt='...') 调用它们。";
```

**Step 3: 编译**

```bash
cd orchestrator && mvn compile -pl . -am -q
```

- [ ] 编译通过

**Step 4: Commit**

```bash
git add orchestrator/src/main/java/com/sunshine/orchestrator/routing/AgentBindingRoutingPolicy.java
git commit -m "feat(orchestrator): multi-dollar binding routes to first agent with spawn hints"
```

---

## Phase 8: Catalog + 脚本 + Live

### Task 21: Catalog 废弃 peer.* / expert.* 协作专属 + 新增 react.spawn-agent.desc

**Files:**
- Modify: prompt-manager DB（Catalog 表）

**Step 1: 废弃 Catalog ID**

```sql
-- 废弃 peer-collab 专属
DELETE FROM catalog WHERE id IN (
    'peer.gather-instruction',
    'peer.speak-prompt',
    'peer.synthesis-prompt',
    'peer.round-continue-prompt',
    'peer.round-speakers-prompt',
    'expert.coordinator-prompt',
    'expert.complexity-prompt',
    'timeline.steps.expert',
    'timeline.steps.expert-convene'
);

-- 废弃 Agent Team 专属（不存在，无需删除）
-- team.collaboration-overlay / team.start-agent-prompt / team.synthesis-prompt / team.handoff-instruction / timeline.steps.team-convene / timeline.steps.team-agent / react.delegate-to-agent.desc / react.finish-task.desc
```

**Step 2: 新增 Catalog ID**

```sql
INSERT INTO catalog (id, content, ...) VALUES
('react.spawn-agent.desc', '调用预定义智能体处理特定任务。参数：expertId（智能体ID）、prompt（任务描述）。', ...);
```

**Step 3: 同步到 Nacos**

```bash
python scripts/sync_nacos.py
```

**Step 4: Commit**

```bash
git add docker/mysql/init/
git commit -m "feat(catalog): deprecate peer.* expert.* collaboration prompts, add react.spawn-agent.desc"
```

---

### Task 22: Live 脚本（T1-T7 + C1-C5 + R1-R5 + X1-X6 + H1-H7）

**Files:**
- Modify: `scripts/verify_spawn_subagent_live.py`（扩展 T1-T7 + R1-R5）
- Create: `scripts/verify_external_agent_live.py`（X1-X6，需 mock A2A server）
- Rename: `scripts/verify_expert_consultation_live.py` -> `scripts/verify_spawn_subagent_live.py`（合并）
- Rename: `scripts/verify_peer_collab_live.py` -> `scripts/verify_spawn_subagent_live.py`（合并）
- Rename: `scripts/sync_enterprise_experts.py` -> `scripts/sync_enterprise_agents.py`

**Step 1: 扩展 verify_spawn_subagent_live.py**

新增检查门：
- T1: `$policy-agent $finance-agent 分析差旅报销合规性`
- T2: ReAct 主 Agent 调 `spawn_subagent(expertId)`
- T3: 并行 spawn 多个智能体
- T4: 子智能体执行失败/超时
- T5: peer-collab 代码零残留
- T6: 敏感信息不进子智能体
- T7: 子智能体工具白名单生效

**Step 2: 新建 verify_external_agent_live.py**

X1-X6 检查门（需 mock A2A server）。

**Step 3: 运行 Live 验证**

```bash
python scripts/verify_spawn_subagent_live.py --suite all
python scripts/verify_external_agent_live.py --suite all
```

- [ ] T1-T7 通过
- [ ] R1-R5 通过
- [ ] X1-X6 通过
- [ ] H1-H7 通过

**Step 4: Commit**

```bash
git add scripts/
git commit -m "feat(scripts): extend live verification for spawn_subagent expertId + A2A"
```

---

## Self-Review

### Spec Coverage

| Spec 章节 | 对应 Task | 覆盖？ |
|-----------|-----------|--------|
| §3 智能体定义模型扩展 | Task 1, 2, 3 | ✅ |
| §4 核心概念（spawn_subagent 中心化） | Task 17, 20 | ✅ |
| §5 架构与改动 | Task 4, 16, 17 | ✅ |
| §6 外部智能体市场 | Task 18, 19 | ✅ |
| §7 智能体间上下文传递 | Task 12, 13 | ✅ |
| §7.5 安全缺口修复 | Task 5, 6, 7, 8 | ✅ |
| §8 执行流程 | Task 17, 20 | ✅ |
| §9 Timeline / UI | Task 14, 15 | ✅ |
| §10 Catalog | Task 21 | ✅ |
| §11 调用契约 | Task 17 | ✅ |
| §15 后端代码全量重命名 | Task 4 | ✅ |
| §16.2 任务拆解 | 全部 Task | ✅ |

### Placeholder Scan

```bash
grep -n "TBD\|TODO\|FIXME\|XXX" docs/superpowers/plans/2026-07-29-multi-agent-unified.md
```

- [ ] 无 TBD/TODO/FIXME/XXX

### Type Consistency

| 类型 | 定义位置 | 使用位置 | 一致？ |
|------|----------|----------|--------|
| `AgentCatalogEntry` | Task 2 | Task 17, 18, 19 | ✅ |
| `AgentRunRequest` | Task 3 | Task 9, 10, 11, 12, 13 | ✅ |
| `AgentExecutorRouter` | Task 17 | Task 17 | ✅ |
| `ExternalAgentClient` | Task 18 | Task 17 | ✅ |

### 删除清单确认

| 删除项 | 对应 Task | 确认删除？ |
|--------|-----------|-----------|
| Agent Team 全部组件 | 不做 | ✅（spec §1.3 否决） |
| peer-collab 全套代码 | Task 16 | ✅ |
| PeerCollabPanel.vue | Task 14 | ✅ |
| peer.* / expert.* Catalog | Task 21 | ✅ |

---

## 执行交接

**Plan complete and stored at `docs/superpowers/plans/2026-07-29-multi-agent-unified.md`.**

**Execution approach:** Subagent-Driven (recommended) — 每个 Task 一个 subagent，Task 间 review checkpoint。

**执行顺序：**
1. Phase 1（Task 1-4）：基础设施 + 重命名 — 其他任务依赖
2. Phase 2（Task 5-8）：安全缺口修复 — 最高优先级
3. Phase 3（Task 9-11）：权限落地
4. Phase 4（Task 12-13）：知识库/数据范围
5. Phase 5（Task 14-15）：前端 — 可并行
6. Phase 6（Task 16-17）：删除 peer-collab + spawn 扩展
7. Phase 7（Task 18-20）：外部 A2A + 路由
8. Phase 8（Task 21-22）：Catalog + Live

**关键依赖：**
- Task 4（重命名）依赖 Task 1（DDL）+ Task 2（DTO）
- Task 17（spawn 扩展）依赖 Task 2（AgentCatalogEntry）+ Task 3（AgentRunRequest）
- Task 18（A2A）依赖 Task 2（AgentCatalogEntry）
- Task 20（$A $B 路由）依赖 Task 17（spawn 扩展）

**Ready to execute?**
