# 阶段二收尾 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 ecs4c16g 上完成阶段二 `2.10`–`2.16` 收尾，使 RAG 可清库评测、Workflow 无 Legacy 回退、Live 脚本 `--suite all` 全绿。

**Architecture:** RAG 侧通过 `rag-service` Admin rebuild API + Python 三脚本形成「清库→入库→评测」闭环；orchestrator 侧删除 `LegacyWorkflowExecutor`、统一 Rag 参数 SSOT、规则路由优先于 LLM 意图分类；验收脚本扩展为多 workflow/react 套件。

**Tech Stack:** Java 21 / Spring Boot 3.2、AgentScope-Java、Milvus、Python 3.11+（requests、PyYAML）、Nacos SSOT、`mvn test`

**Spec:** `docs/superpowers/specs/2026-06-20-phase2-closure-design.md`

**建议 worktree：** `git worktree add ../my-sunshine-agent-phase2 -b feat/phase2-closure`

---

## 文件结构（改动地图）

| 路径 | 职责 |
|------|------|
| `rag-service/.../service/MilvusService.java` | 暴露 `rebuildCollection()` |
| `rag-service/.../config/RagAdminProperties.java` | `rag.admin.token` |
| `rag-service/.../controller/RagAdminController.java` | `POST /api/rag/admin/rebuild` |
| `rag-service/src/test/.../RagAdminControllerTest.java` | Admin token + rebuild 单测 |
| `docs/nacos/sunshine-rag.yaml` | `rag.admin.token` |
| `scripts/rag_reset.py` | 调 rebuild API |
| `scripts/rag_ingest_bulk.py` | 读 corpus 批量入库 |
| `scripts/rag_eval.py` | Recall/MRR/EmptyRate/latency |
| `scripts/requirements.txt` | 增加 `pyyaml>=6.0` |
| `docs/knowledge/*.md` | +6 篇语料 |
| `docs/rag/golden-set.yaml` | 扩充至 66 条 query |
| `docs/rag/reports/` | 基线 JSON 归档目录 |
| `orchestrator/.../config/RagSearchProperties.java` | `rag.search.default-top-k` |
| `orchestrator/.../client/RagContextFormatter.java` | 统一 `formatHits(hits, mode)` |
| `orchestrator/.../agent/RagTool.java` | topK 读配置 |
| `orchestrator/.../execution/handler/RagNodeHandler.java` | 默认 topK 读配置 |
| `orchestrator/.../execution/WorkflowExecutor.java` | 删 Legacy；未知 workflow 报错 |
| `orchestrator/.../execution/LegacyWorkflowExecutor.java` | **删除** |
| `orchestrator/.../routing/RuleBasedRouter.java` | 规则硬路由 |
| `orchestrator/.../config/RoutingRuleProperties.java` | Nacos `agent.routing.rules` |
| `orchestrator/.../routing/ExecutionPlanRouter.java` | 规则优先 → IntentRouter |
| `orchestrator/.../routing/ExecutionPlan.java` | 增加 `ruleId` 字段 |
| `orchestrator/.../audit/StepsSummaryExtractor.java` | 从 steps JSON 提取 toolNames |
| `orchestrator/.../audit/AuditService.java` | payload 扩展 `stepsSummary` |
| `orchestrator/.../agent/DynamicToolkitFactory.java` | 白名单缺失 Catalog 时 `log.error` |
| `scripts/phase2_agent_demo.py` | `--suite all|react|workflow` |
| `docs/nacos/sunshine-orchestrator.yaml` | `rag.search` + `agent.routing.rules` |
| `CLAUDE.md` / `implementation-plan.md` / `phase2-closure-plan.md` | 文档同步 |

---

## 排期

```
周 1  Task 1–6   2.12 RAG rebuild + 脚本 + 语料 + golden-set + baseline
周 2  Task 7–9   2.11 Legacy 删除 + Rag 统一 + 2.14 规则路由
周 3  Task 10–11 2.10 --suite all + 2.13 审计
周 4  Task 12–13 2.15 白名单校验 + 2.16 文档 + 全量回归
```

---

### Task 1: rag-service Admin rebuild API

**Files:**
- Create: `rag-service/src/main/java/com/sunshine/rag/config/RagAdminProperties.java`
- Create: `rag-service/src/main/java/com/sunshine/rag/controller/RagAdminController.java`
- Modify: `rag-service/src/main/java/com/sunshine/rag/service/MilvusService.java`
- Modify: `docs/nacos/sunshine-rag.yaml`
- Test: `rag-service/src/test/java/com/sunshine/rag/controller/RagAdminControllerTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.sunshine.rag.controller;

import com.sunshine.rag.config.RagAdminProperties;
import com.sunshine.rag.service.MilvusService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.test.StepVerifier;

import java.util.Map;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class RagAdminControllerTest {

    @Mock
    private MilvusService milvusService;

    @InjectMocks
    private RagAdminController controller;

    @Test
    void rebuild_rejectsBadToken() {
        RagAdminProperties props = new RagAdminProperties();
        props.setToken("secret");
        controller = new RagAdminController(milvusService, props);

        StepVerifier.create(controller.rebuild("wrong"))
                .assertNext(body -> {
                    assert body.get("code").equals(403);
                })
                .verifyComplete();

        verifyNoInteractions(milvusService);
    }

    @Test
    void rebuild_okWithValidToken() {
        RagAdminProperties props = new RagAdminProperties();
        props.setToken("secret");
        controller = new RagAdminController(milvusService, props);

        StepVerifier.create(controller.rebuild("secret"))
                .assertNext(body -> {
                    assert body.get("code").equals(200);
                    assert body.get("collection").equals("sunshine_knowledge");
                })
                .verifyComplete();

        verify(milvusService).rebuildCollection();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl rag-service -Dtest=RagAdminControllerTest -q`
Expected: FAIL — `RagAdminController` / `rebuildCollection` 不存在

- [ ] **Step 3: Write minimal implementation**

`RagAdminProperties.java`:

```java
package com.sunshine.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;

@Data
@RefreshScope
@ConfigurationProperties(prefix = "rag.admin")
public class RagAdminProperties {
    /** 为空时不校验 token（仅内网 MVP） */
    private String token = "";
}
```

`MilvusService.java` — 在类末尾新增：

```java
    /** Admin：drop + recreate collection（清库重建） */
    public void rebuildCollection() {
        log.warn("[RAG] Admin rebuild: 清空 collection {}", COLLECTION);
        dropCollection();
        createCollection();
    }
```

`RagAdminController.java`:

```java
package com.sunshine.rag.controller;

import com.sunshine.rag.config.RagAdminProperties;
import com.sunshine.rag.service.MilvusService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
@RequestMapping("/api/rag/admin")
@RequiredArgsConstructor
public class RagAdminController {

    private final MilvusService milvusService;
    private final RagAdminProperties adminProperties;

    @PostMapping("/rebuild")
    public Mono<Map<String, Object>> rebuild(
            @RequestHeader(value = "X-Admin-Token", required = false) String token) {
        String required = adminProperties.getToken();
        if (required != null && !required.isBlank() && !required.equals(token)) {
            return Mono.just(Map.of("code", 403, "msg", "无效 admin token"));
        }
        milvusService.rebuildCollection();
        return Mono.just(Map.of(
                "code", 200,
                "msg", "rebuild ok",
                "collection", "sunshine_knowledge"));
    }
}
```

在 `rag-service` 主类或 `@Configuration` 加 `@EnableConfigurationProperties(RagAdminProperties.class)`。

`docs/nacos/sunshine-rag.yaml` 追加：

```yaml
rag:
  admin:
    token: sunshine-rag-admin-dev
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl rag-service -Dtest=RagAdminControllerTest -q`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add rag-service/src/main/java/com/sunshine/rag/config/RagAdminProperties.java \
        rag-service/src/main/java/com/sunshine/rag/controller/RagAdminController.java \
        rag-service/src/main/java/com/sunshine/rag/service/MilvusService.java \
        rag-service/src/test/java/com/sunshine/rag/controller/RagAdminControllerTest.java \
        docs/nacos/sunshine-rag.yaml
git commit -m "feat(rag): add admin rebuild API for collection reset"
```

---

### Task 2: rag_reset.py

**Files:**
- Create: `scripts/rag_reset.py`

- [ ] **Step 1: 创建脚本**

```python
#!/usr/bin/env python3
"""清库重建 — 调用 rag-service POST /api/rag/admin/rebuild。"""
from __future__ import annotations

import argparse
import os
import sys

import requests

DEFAULT_RAG_URL = os.environ.get("RAG_URL", "http://ecs4c16g:8400")
DEFAULT_TOKEN = os.environ.get("RAG_ADMIN_TOKEN", "sunshine-rag-admin-dev")


def main() -> int:
    parser = argparse.ArgumentParser(description="RAG Milvus 清库重建")
    parser.add_argument("--rag-url", default=DEFAULT_RAG_URL)
    parser.add_argument("--token", default=DEFAULT_TOKEN)
    args = parser.parse_args()

    url = f"{args.rag_url.rstrip('/')}/api/rag/admin/rebuild"
    resp = requests.post(url, headers={"X-Admin-Token": args.token}, timeout=120)
    resp.raise_for_status()
    body = resp.json()
    if body.get("code") != 200:
        print(f"[FAIL] rebuild: {body}", file=sys.stderr)
        return 1
    print(f"[OK] rebuild collection={body.get('collection')}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
```

- [ ] **Step 2: 同步 Nacos 并验证（需 rag-service :8400 运行）**

```bash
python scripts/sync_nacos.py --data-id sunshine-rag.yaml
python scripts/start.py   # 或仅重启 rag-service
python scripts/rag_reset.py --rag-url http://ecs4c16g:8400
```

Expected: `[OK] rebuild collection=sunshine_knowledge`

- [ ] **Step 3: Commit**

```bash
git add scripts/rag_reset.py
git commit -m "feat(scripts): add rag_reset.py for Milvus rebuild"
```

---

### Task 3: 语料包（7 篇 Markdown）

**Files:**
- Modify: `docs/knowledge/公司请假流程规范.md`（已有，核对 display_name 一致）
- Create: `docs/knowledge/公司报销管理制度.md`
- Create: `docs/knowledge/考勤与加班管理规定.md`
- Create: `docs/knowledge/新员工入职指引.md`
- Create: `docs/knowledge/财务审批权限矩阵.md`
- Create: `docs/knowledge/发票与税务合规FAQ.md`
- Create: `docs/knowledge/部门预算管理办法.md`

- [ ] **Step 1: 按模板撰写 6 篇新语料**

每篇须含：`# 标题`、版本/生效日期、≥1 个表格、≥3 个 `##` 章节、可与财务 workflow 交叉引用的条款。

`公司报销管理制度.md` 必备章节示例：

```markdown
# 公司报销管理制度

> 版本：v1.0 · 生效日期：2026-06-01

## 1. 适用范围
正式员工因公发生的交通、住宿、餐饮、办公用品等费用。

## 2. 报销限额（参考）

| 费用类型 | 单次上限 | 审批人 |
|----------|----------|--------|
| 市内交通 | 200 元 | 直属主管 |
| 差旅住宿 | 600 元/晚 | 部门负责人 |
| 餐饮招待 | 150 元/人 | 部门负责人 |

## 3. 发票要求
增值税专用发票优先；电子发票须含税号与项目名称。

## 4. 审批流程
员工提交 → 直属主管 → 财务复核 → 出纳付款。超过 5000 元须 CFO 加签。

## 5. 禁止事项
虚假发票、拆单报销、私人消费混入公务报销。
```

其余 5 篇按 spec §4.2 表内 `doc_id` 对应标题撰写，字数每篇 800–1500 字。

- [ ] **Step 2: 本地抽查 Markdown 可被 rag-service 解析**

Run: `mvn test -pl rag-service -Dtest=MarkdownParserTest -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add docs/knowledge/
git commit -m "docs(rag): add phase2 enterprise knowledge corpus (6 docs)"
```

---

### Task 4: golden-set 扩充至 66 条

**Files:**
- Modify: `docs/rag/golden-set.yaml`

- [ ] **Step 1: 更新 corpus 段（7 篇）**

```yaml
version: 2
description: 阶段二 RAG 基线评测集（7 篇语料 + 66 query）

corpus:
  - doc_id: leave-policy-v1
    display_name: 公司请假流程规范
    path: docs/knowledge/公司请假流程规范.md
  - doc_id: expense-policy-v1
    display_name: 公司报销管理制度
    path: docs/knowledge/公司报销管理制度.md
  - doc_id: attendance-policy-v1
    display_name: 考勤与加班管理规定
    path: docs/knowledge/考勤与加班管理规定.md
  - doc_id: onboarding-policy-v1
    display_name: 新员工入职指引
    path: docs/knowledge/新员工入职指引.md
  - doc_id: finance-approval-v1
    display_name: 财务审批权限矩阵
    path: docs/knowledge/财务审批权限矩阵.md
  - doc_id: invoice-faq-v1
    display_name: 发票与税务合规FAQ
    path: docs/knowledge/发票与税务合规FAQ.md
  - doc_id: budget-policy-v1
    display_name: 部门预算管理办法
    path: docs/knowledge/部门预算管理办法.md
```

- [ ] **Step 2: 追加 queries 至 66 条**

保留现有 `q001`–`q012`、`q_neg_001`–`q_neg_002`，并追加：

```yaml
  # expense-policy-v1 (q013–q024)
  - id: q013
    query: 市内交通报销上限多少
    relevant_docs: [expense-policy-v1]
    relevant_keywords: ["市内交通", "200"]
    category: expense
  - id: q014
    query: 差旅住宿一晚能报多少钱
    relevant_docs: [expense-policy-v1]
    relevant_keywords: ["差旅", "住宿", "600"]
    category: expense
  - id: q015
    query: 餐饮招待费人均标准
    relevant_docs: [expense-policy-v1]
    relevant_keywords: ["餐饮", "150"]
    category: expense
  - id: q016
    query: 报销需要什么样的发票
    relevant_docs: [expense-policy-v1]
    relevant_keywords: ["发票", "增值税"]
    category: expense
  - id: q017
    query: 超过五千的报销谁审批
    relevant_docs: [expense-policy-v1]
    relevant_keywords: ["5000", "CFO"]
    category: expense
  - id: q018
    query: 拆单报销是否允许
    relevant_docs: [expense-policy-v1]
    relevant_keywords: ["拆单", "禁止"]
    category: expense
  - id: q019
    query: 私人消费能报销吗
    relevant_docs: [expense-policy-v1]
    relevant_keywords: ["私人", "禁止"]
    category: expense
  - id: q020
    query: 报销审批流程有几步
    relevant_docs: [expense-policy-v1]
    relevant_keywords: ["审批", "财务复核"]
    category: expense
  - id: q021
    query: 电子发票报销要注意什么
    relevant_docs: [expense-policy-v1]
    relevant_keywords: ["电子发票", "税号"]
    category: expense
  - id: q022
    query: 办公用品采购怎么报销
    relevant_docs: [expense-policy-v1]
    relevant_keywords: ["办公用品"]
    category: expense
  - id: q023
    query: 虚假发票报销后果
    relevant_docs: [expense-policy-v1]
    relevant_keywords: ["虚假发票"]
    category: expense
  - id: q024
    query: 因公交通费谁审批
    relevant_docs: [expense-policy-v1]
    relevant_keywords: ["直属主管"]
    category: expense

  # attendance-policy-v1 (q025–q034)
  - id: q025
    query: 迟到几次算旷工
    relevant_docs: [attendance-policy-v1]
    relevant_keywords: ["迟到", "旷工"]
    category: attendance
  - id: q026
    query: 加班需要提前申请吗
    relevant_docs: [attendance-policy-v1]
    relevant_keywords: ["加班", "申请"]
    category: attendance
  - id: q027
    query: 周末加班怎么算调休
    relevant_docs: [attendance-policy-v1]
    relevant_keywords: ["周末", "调休"]
    category: attendance
  - id: q028
    query: 弹性上下班时间规定
    relevant_docs: [attendance-policy-v1]
    relevant_keywords: ["弹性"]
    category: attendance
  - id: q029
    query: 忘打卡怎么补签
    relevant_docs: [attendance-policy-v1]
    relevant_keywords: ["补签", "打卡"]
    category: attendance
  - id: q030
    query: 外出办公如何登记考勤
    relevant_docs: [attendance-policy-v1]
    relevant_keywords: ["外出"]
    category: attendance
  - id: q031
    query: 夜班补贴标准
    relevant_docs: [attendance-policy-v1]
    relevant_keywords: ["夜班"]
    category: attendance
  - id: q032
    query: 法定节假日加班工资
    relevant_docs: [attendance-policy-v1]
    relevant_keywords: ["法定节假日"]
    category: attendance
  - id: q033
    query: 考勤异常申诉找谁
    relevant_docs: [attendance-policy-v1]
    relevant_keywords: ["申诉", "HR"]
    category: attendance
  - id: q034
    query: 远程办公考勤要求
    relevant_docs: [attendance-policy-v1]
    relevant_keywords: ["远程"]
    category: attendance

  # onboarding-policy-v1 (q035–q042)
  - id: q035
    query: 新员工入职第一天做什么
    relevant_docs: [onboarding-policy-v1]
    relevant_keywords: ["入职", "第一天"]
    category: onboarding
  - id: q036
    query: 入职需要带哪些材料
    relevant_docs: [onboarding-policy-v1]
    relevant_keywords: ["材料", "身份证"]
    category: onboarding
  - id: q037
    query: 试用期多长时间
    relevant_docs: [onboarding-policy-v1]
    relevant_keywords: ["试用期"]
    category: onboarding
  - id: q038
    query: 入职培训有哪些环节
    relevant_docs: [onboarding-policy-v1]
    relevant_keywords: ["培训"]
    category: onboarding
  - id: q039
    query: 工牌和账号什么时候开通
    relevant_docs: [onboarding-policy-v1]
    relevant_keywords: ["工牌", "账号"]
    category: onboarding
  - id: q040
    query: 入职导师制度是什么
    relevant_docs: [onboarding-policy-v1]
    relevant_keywords: ["导师"]
    category: onboarding
  - id: q041
    query: 劳动合同什么时候签
    relevant_docs: [onboarding-policy-v1]
    relevant_keywords: ["劳动合同"]
    category: onboarding
  - id: q042
    query: 入职体检要求
    relevant_docs: [onboarding-policy-v1]
    relevant_keywords: ["体检"]
    category: onboarding

  # finance-approval-v1 (q043–q048)
  - id: q043
    query: 部门经理审批额度上限
    relevant_docs: [finance-approval-v1]
    relevant_keywords: ["部门经理", "额度"]
    category: finance
  - id: q044
    query: CFO审批什么金额以上的单据
    relevant_docs: [finance-approval-v1]
    relevant_keywords: ["CFO"]
    category: finance
  - id: q045
    query: 财务复核的职责是什么
    relevant_docs: [finance-approval-v1]
    relevant_keywords: ["财务复核"]
    category: finance
  - id: q046
    query: 预算外支出谁批准
    relevant_docs: [finance-approval-v1, budget-policy-v1]
    relevant_keywords: ["预算外"]
    category: finance
  - id: q047
    query: 采购付款审批矩阵
    relevant_docs: [finance-approval-v1]
    relevant_keywords: ["采购", "付款"]
    category: finance
  - id: q048
    query: 差旅费审批权限分级
    relevant_docs: [finance-approval-v1, expense-policy-v1]
    relevant_keywords: ["差旅", "审批"]
    category: finance

  # invoice-faq-v1 (q049–q054)
  - id: q049
    query: 增值税专用发票和普通发票区别
    relevant_docs: [invoice-faq-v1]
    relevant_keywords: ["增值税", "专用"]
    category: finance
  - id: q050
    query: 发票抬头必须写公司全称吗
    relevant_docs: [invoice-faq-v1]
    relevant_keywords: ["抬头"]
    category: finance
  - id: q051
    query: 电子发票如何验真
    relevant_docs: [invoice-faq-v1]
    relevant_keywords: ["验真"]
    category: finance
  - id: q052
    query: 报销发票税率有什么要求
    relevant_docs: [invoice-faq-v1]
    relevant_keywords: ["税率"]
    category: finance
  - id: q053
    query: 丢失发票还能报销吗
    relevant_docs: [invoice-faq-v1]
    relevant_keywords: ["丢失"]
    category: finance
  - id: q054
    query: 个人抬头发票能否报销
    relevant_docs: [invoice-faq-v1]
    relevant_keywords: ["个人抬头"]
    category: finance

  # budget-policy-v1 (q055–q060)
  - id: q055
    query: 部门预算什么时候编制
    relevant_docs: [budget-policy-v1]
    relevant_keywords: ["编制"]
    category: finance
  - id: q056
    query: 预算调剂需要谁批准
    relevant_docs: [budget-policy-v1]
    relevant_keywords: ["调剂"]
    category: finance
  - id: q057
    query: 季度预算执行率怎么考核
    relevant_docs: [budget-policy-v1]
    relevant_keywords: ["执行率"]
    category: finance
  - id: q058
    query: 项目预算超支怎么办
    relevant_docs: [budget-policy-v1]
    relevant_keywords: ["超支"]
    category: finance
  - id: q059
    query: 预算内采购流程
    relevant_docs: [budget-policy-v1]
    relevant_keywords: ["采购"]
    category: finance
  - id: q060
    query: 年度预算调整窗口期
    relevant_docs: [budget-policy-v1]
    relevant_keywords: ["调整"]
    category: finance

  # 负例 (q_neg_003–q_neg_006)
  - id: q_neg_003
    query: 如何投资比特币
    relevant_docs: []
    expect_empty: true
    category: negative
  - id: q_neg_004
    query: 公司上市代码是多少
    relevant_docs: []
    expect_empty: true
    category: negative
  - id: q_neg_005
    query: 帮我写一首唐诗
    relevant_docs: []
    expect_empty: true
    category: negative
  - id: q_neg_006
    query: 明天上海天气怎么样
    relevant_docs: []
    expect_empty: true
    category: negative

eval:
  top_k: [3, 5, 10]
  min_score: 0.48
  metrics: [recall_at_k, mrr, empty_rate, latency_ms]
```

- [ ] **Step 3: 校验 YAML 可解析**

Run: `python -c "import yaml; yaml.safe_load(open('docs/rag/golden-set.yaml')); print('OK', len(yaml.safe_load(open('docs/rag/golden-set.yaml'))['queries']))"`
Expected: `OK 66`

- [ ] **Step 4: Commit**

```bash
git add docs/rag/golden-set.yaml
git commit -m "docs(rag): expand golden-set to 66 queries across 7 corpus docs"
```

---

### Task 5: rag_ingest_bulk.py

**Files:**
- Create: `scripts/rag_ingest_bulk.py`
- Modify: `scripts/requirements.txt`

- [ ] **Step 1: 增加 PyYAML 依赖**

`scripts/requirements.txt` 追加一行：

```
pyyaml>=6.0
```

Run: `pip install -r scripts/requirements.txt`

- [ ] **Step 2: 创建入库脚本**

```python
#!/usr/bin/env python3
"""按 golden-set.corpus 批量 POST /api/rag/documents。"""
from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path

import requests
import yaml

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_RAG_URL = os.environ.get("RAG_URL", "http://ecs4c16g:8400")
GOLDEN_SET = ROOT / "docs" / "rag" / "golden-set.yaml"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--rag-url", default=DEFAULT_RAG_URL)
    parser.add_argument("--golden-set", default=str(GOLDEN_SET))
    args = parser.parse_args()

    data = yaml.safe_load(Path(args.golden_set).read_text(encoding="utf-8"))
    corpus = data.get("corpus") or []
    base = args.rag_url.rstrip("/")

    for item in corpus:
        path = ROOT / item["path"]
        content = path.read_text(encoding="utf-8")
        doc_name = item["display_name"]
        resp = requests.post(
            f"{base}/api/rag/documents",
            json={"content": content, "docName": doc_name},
            timeout=300,
        )
        resp.raise_for_status()
        body = resp.json()
        if body.get("code") != 200:
            print(f"[FAIL] {doc_name}: {body}", file=sys.stderr)
            return 1
        print(f"[OK] {item['doc_id']} chunks={body.get('chunks')}")

    print(f"[OK] ingested {len(corpus)} documents")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
```

- [ ] **Step 3: 全链路验证**

```bash
python scripts/rag_reset.py
python scripts/rag_ingest_bulk.py
```

Expected: 7 行 `[OK] ... chunks=N`

- [ ] **Step 4: Commit**

```bash
git add scripts/rag_ingest_bulk.py scripts/requirements.txt
git commit -m "feat(scripts): add rag_ingest_bulk.py for corpus ingestion"
```

---

### Task 6: rag_eval.py + baseline 报告

**Files:**
- Create: `scripts/rag_eval.py`
- Create: `docs/rag/reports/.gitkeep`
- Modify: `docs/rag/baseline-report.md`

- [ ] **Step 1: Write rag_eval.py**

```python
#!/usr/bin/env python3
"""RAG 基线评测 — Recall@K / MRR / EmptyRate / latency。"""
from __future__ import annotations

import argparse
import json
import os
import statistics
import time
from datetime import date
from pathlib import Path

import requests
import yaml

ROOT = Path(__file__).resolve().parent.parent


def load_golden(path: Path) -> dict:
    return yaml.safe_load(path.read_text(encoding="utf-8"))


def doc_id_to_name(corpus: list) -> dict[str, str]:
    return {c["doc_id"]: c["display_name"] for c in corpus}


def search(rag_url: str, query: str, top_k: int) -> tuple[list[dict], float]:
    t0 = time.perf_counter()
    resp = requests.post(
        f"{rag_url.rstrip('/')}/api/rag/search",
        json={"query": query, "topK": top_k},
        timeout=60,
    )
    resp.raise_for_status()
    ms = (time.perf_counter() - t0) * 1000
    results = resp.json().get("results") or []
    return results, ms


def recall_at_k(hits: list[dict], relevant_names: set[str], k: int, min_score: float) -> float:
    if not relevant_names:
        return 0.0
    top = hits[:k]
    for h in top:
        if h.get("score", 0) < min_score:
            continue
        if h.get("docName") in relevant_names:
            return 1.0
    return 0.0


def mrr(hits: list[dict], relevant_names: set[str], min_score: float) -> float:
    if not relevant_names:
        return 0.0
    for i, h in enumerate(hits, start=1):
        if h.get("score", 0) < min_score:
            continue
        if h.get("docName") in relevant_names:
            return 1.0 / i
    return 0.0


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--rag-url", default=os.environ.get("RAG_URL", "http://ecs4c16g:8400"))
    parser.add_argument("--golden-set", default=str(ROOT / "docs/rag/golden-set.yaml"))
    parser.add_argument("--suite", default="all", choices=["all", "core"])
    args = parser.parse_args()

    data = load_golden(Path(args.golden_set))
    id2name = doc_id_to_name(data["corpus"])
    eval_cfg = data.get("eval") or {}
    top_ks = eval_cfg.get("top_k") or [3, 5, 10]
    min_score = float(eval_cfg.get("min_score", 0.48))
    max_k = max(top_ks)

    queries = data["queries"]
    if args.suite == "core":
        queries = [q for q in queries if q["id"].startswith("q00") and int(q["id"][1:4]) <= 12]

    latencies: list[float] = []
    recalls = {k: [] for k in top_ks}
    mrrs: list[float] = []
    pos_empty = 0
    pos_total = 0
    neg_empty = 0
    neg_total = 0
    by_category: dict[str, list[float]] = {}

    for q in queries:
        relevant = {id2name[d] for d in q.get("relevant_docs") or [] if d in id2name}
        hits, ms = search(args.rag_url, q["query"], max_k)
        latencies.append(ms)

        filtered = [h for h in hits if h.get("score", 0) >= min_score]
        if relevant:
            pos_total += 1
            if not filtered:
                pos_empty += 1
            for k in top_ks:
                recalls[k].append(recall_at_k(filtered, relevant, k, min_score))
            mrrs.append(mrr(filtered, relevant, min_score))
            cat = q.get("category", "unknown")
            by_category.setdefault(cat, []).append(recalls[top_ks[0]][-1])
        else:
            neg_total += 1
            if not filtered:
                neg_empty += 1

    report = {
        "date": str(date.today()),
        "golden_version": data.get("version"),
        "query_count": len(queries),
        "min_score": min_score,
        "recall_at_k": {str(k): round(statistics.mean(recalls[k]), 4) for k in top_ks},
        "mrr": round(statistics.mean(mrrs) if mrrs else 0.0, 4),
        "empty_rate_positive": round(pos_empty / pos_total, 4) if pos_total else 0.0,
        "empty_rate_negative": round(neg_empty / neg_total, 4) if neg_total else 0.0,
        "latency_ms": {
            "p50": round(statistics.median(latencies), 1),
            "p95": round(sorted(latencies)[int(len(latencies) * 0.95)] if latencies else 0, 1),
        },
        "by_category_recall_at_3": {
            cat: round(statistics.mean(vals), 4) for cat, vals in by_category.items()
        },
    }

    out_dir = ROOT / "docs/rag/reports"
    out_dir.mkdir(parents=True, exist_ok=True)
    out_file = out_dir / f"baseline-{date.today()}.json"
    out_file.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
```

- [ ] **Step 2: 跑全量评测并填写 baseline-report.md**

```bash
python scripts/rag_reset.py
python scripts/rag_ingest_bulk.py
python scripts/rag_eval.py > /tmp/rag-baseline.json
```

在 `docs/rag/baseline-report.md` 填入实测数字（从 JSON 复制 `recall_at_k`、`mrr`、`empty_rate_*`、`latency_ms`）。

- [ ] **Step 3: Commit**

```bash
git add scripts/rag_eval.py docs/rag/reports/ docs/rag/baseline-report.md
git commit -m "feat(scripts): add rag_eval.py and record phase2 RAG baseline"
```

---

### Task 7: Rag 双路径统一（2.11 G3）

**Files:**
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/config/RagSearchProperties.java`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/client/RagContextFormatter.java`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/agent/RagTool.java`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/execution/handler/RagNodeHandler.java`
- Modify: `docs/nacos/sunshine-orchestrator.yaml`
- Test: `orchestrator/src/test/java/com/sunshine/orchestrator/client/RagContextFormatterTest.java`

- [ ] **Step 1: Write the failing test**

在 `RagContextFormatterTest.java` 追加：

```java
    @Test
    void formatHits_toolAndWorkflow_shareHitBody() {
        List<RagClient.RagHit> hits = List.of(
                new RagClient.RagHit("公司报销管理制度", "单次上限 200 元", 0.9f));

        String tool = RagContextFormatter.formatHits(hits, RagContextFormatter.Mode.TOOL);
        String wf = RagContextFormatter.formatHits(hits, RagContextFormatter.Mode.WORKFLOW);

        assertThat(tool).contains("公司报销管理制度");
        assertThat(wf).contains("公司报销管理制度");
        assertThat(tool).contains("200 元");
        assertThat(wf).contains("200 元");
    }
```

- [ ] **Step 2: Run test — expect FAIL**

Run: `mvn test -pl orchestrator -Dtest=RagContextFormatterTest -q`
Expected: `formatHits` 不存在

- [ ] **Step 3: Implement**

`RagSearchProperties.java`:

```java
package com.sunshine.orchestrator.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;

@Data
@RefreshScope
@ConfigurationProperties(prefix = "rag.search")
public class RagSearchProperties {
    private int defaultTopK = 3;
}
```

`RagContextFormatter.java` — 增加 enum + 统一方法，保留旧方法为委托：

```java
    public enum Mode { TOOL, WORKFLOW }

    public static String formatHits(List<RagClient.RagHit> hits, Mode mode) {
        if (hits == null || hits.isEmpty()) {
            return mode == Mode.TOOL
                    ? "未找到相关知识库内容。请如实告知用户，勿编造制度名称或条款。"
                    : "[知识库检索结果]\n未找到与用户问题直接相关的片段。";
        }
        StringBuilder sb = new StringBuilder();
        if (mode == Mode.TOOL) {
            sb.append("知识库检索结果（共 ").append(hits.size()).append(" 条）：\n");
        } else {
            sb.append("[知识库检索结果]\n");
        }
        appendHitsBody(sb, hits);
        if (mode == Mode.TOOL) {
            sb.append("引用文档名称须来自上方列表，内容须基于上述片段。");
        }
        return sb.toString().strip();
    }

    public static String formatToolResult(List<RagClient.RagHit> hits) {
        return formatHits(hits, Mode.TOOL);
    }

    public static String formatAgentContext(List<RagClient.RagHit> hits) {
        return formatHits(hits, Mode.WORKFLOW);
    }
```

`RagTool.java` — 注入 `RagSearchProperties`，`ragClient.search(query, ragSearchProperties.getDefaultTopK())`。

`RagNodeHandler.java` — 注入 `RagSearchProperties`；`parseTopK` 无值时返回 `ragSearchProperties.getDefaultTopK()`。

`docs/nacos/sunshine-orchestrator.yaml` — `rag:` 段改为：

```yaml
rag:
  base-url: http://ecs4c16g:8400
  search:
    default-top-k: 3
```

主配置类加 `@EnableConfigurationProperties(RagSearchProperties.class)`。

- [ ] **Step 4: Run tests**

Run: `mvn test -pl orchestrator -Dtest=RagContextFormatterTest -q`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add orchestrator/src/main/java/com/sunshine/orchestrator/config/RagSearchProperties.java \
        orchestrator/src/main/java/com/sunshine/orchestrator/client/RagContextFormatter.java \
        orchestrator/src/main/java/com/sunshine/orchestrator/agent/RagTool.java \
        orchestrator/src/main/java/com/sunshine/orchestrator/execution/handler/RagNodeHandler.java \
        orchestrator/src/test/java/com/sunshine/orchestrator/client/RagContextFormatterTest.java \
        docs/nacos/sunshine-orchestrator.yaml
git commit -m "refactor(orchestrator): unify RAG topK and context formatting"
```

---

### Task 8: 删除 LegacyWorkflowExecutor（2.11）

**Files:**
- Delete: `orchestrator/src/main/java/com/sunshine/orchestrator/execution/LegacyWorkflowExecutor.java`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/execution/WorkflowExecutor.java`
- Modify: `orchestrator/src/test/java/com/sunshine/orchestrator/execution/WorkflowExecutorTest.java`
- Grep: 删除所有 `LegacyWorkflowExecutor` / `toLegacyIntentLabel` 生产引用

- [ ] **Step 1: Write the failing test**

替换 `WorkflowExecutorTest.fallsBackToLegacyWhenDefinitionMissing` 为：

```java
    @Test
    void returnsErrorWhenDefinitionMissing() {
        when(loader.load("unknown")).thenReturn(java.util.Optional.empty());

        ExecutionStreamContext ctx = new ExecutionStreamContext(
                "c1", "m1", "test", MemoryContext.empty(),
                null, null, null, "u1", "default",
                new ExecutionPlan(ExecutionMode.WORKFLOW, "unknown", Map.of(), "test"));

        List<StreamToken> tokens = executor.execute(ctx).collectList().block();
        assertThat(tokens).hasSize(1);
        assertThat(tokens.get(0).text()).contains("unknown");
        assertThat(tokens.get(0).text()).contains("未定义");
    }
```

同时 `setUp()` 改为 `executor = new WorkflowExecutor(loader, registry);`（去掉 legacy mock）。

- [ ] **Step 2: Run test — expect FAIL**

Run: `mvn test -pl orchestrator -Dtest=WorkflowExecutorTest -q`
Expected: 仍 fallback legacy 或构造器签名不匹配

- [ ] **Step 3: Implement**

`WorkflowExecutor.java`：

```java
@RequiredArgsConstructor
public class WorkflowExecutor {
    private final WorkflowDefinitionLoader loader;
    private final NodeHandlerRegistry registry;

    public Flux<StreamToken> execute(ExecutionStreamContext ctx) {
        ExecutionPlan plan = ctx.plan();
        if (plan == null || plan.mode() != ExecutionMode.WORKFLOW) {
            return Flux.just(StreamToken.content("内部错误：WorkflowExecutor 收到非 workflow 计划"));
        }
        String workflowId = plan.workflowId();
        Optional<WorkflowDefinition> defOpt = loader.load(workflowId);
        if (defOpt.isEmpty()) {
            log.error("[WorkflowExecutor] 未找到 workflow 定义: {}", workflowId);
            return Flux.just(StreamToken.content(
                    "工作流「" + workflowId + "」未定义，请联系管理员。"));
        }
        return executeDefinition(defOpt.get(), ctx);
    }
    // ... 其余不变
}
```

删除 `LegacyWorkflowExecutor.java`；`grep -r LegacyWorkflowExecutor orchestrator` 应为 0。

`IntentRouter.toLegacyIntentLabel` ~~保留仅供 `IntentRouterLegacyLabelTest`~~ **已删**（见 [ADR-001](../../architecture/ADR-001-delete-legacy-compat.md) / TD-004）。

- [ ] **Step 4: Run orchestrator tests**

Run: `mvn test -pl orchestrator -q`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add orchestrator/
git commit -m "refactor(orchestrator): remove LegacyWorkflowExecutor and fail on unknown workflow"
```

---

### Task 9: 规则硬路由 RuleBasedRouter（2.14）

**Files:**
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/config/RoutingRuleProperties.java`
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/routing/RuleBasedRouter.java`
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/routing/ExecutionPlanRouter.java`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/routing/ExecutionPlan.java`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/controller/ChatController.java`
- Modify: `docs/nacos/sunshine-orchestrator.yaml`
- Test: `orchestrator/src/test/java/com/sunshine/orchestrator/routing/RuleBasedRouterTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.sunshine.orchestrator.routing;

import com.sunshine.orchestrator.config.RoutingRuleProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class RuleBasedRouterTest {

    @Test
    void matchesFinanceListPending_withoutLlm() {
        RoutingRuleProperties props = new RoutingRuleProperties();
        RoutingRuleProperties.Rule rule = new RoutingRuleProperties.Rule();
        rule.setId("rule-finance-list-pending");
        rule.setPriority(10);
        rule.setMatch("any");
        rule.setPatterns(List.of("待审批.*报销", "有哪些待审批"));
        RoutingRuleProperties.PlanSpec planSpec = new RoutingRuleProperties.PlanSpec();
        planSpec.setMode("workflow");
        planSpec.setWorkflowId("finance-list");
        planSpec.setParams(Map.of("status", "pending"));
        rule.setPlan(planSpec);
        props.setRules(List.of(rule));

        RuleBasedRouter router = new RuleBasedRouter(props);
        Optional<ExecutionPlan> hit = router.match("有哪些待审批报销");

        assertThat(hit).isPresent();
        assertThat(hit.get().workflowId()).isEqualTo("finance-list");
        assertThat(hit.get().ruleId()).isEqualTo("rule-finance-list-pending");
        assertThat(hit.get().params()).containsEntry("status", "pending");
    }
}
```

- [ ] **Step 2: Run test — expect FAIL**

Run: `mvn test -pl orchestrator -Dtest=RuleBasedRouterTest -q`

- [ ] **Step 3: Implement**

`ExecutionPlan.java` 扩展（保持向后兼容）：

```java
public record ExecutionPlan(
        ExecutionMode mode,
        String workflowId,
        Map<String, String> params,
        String reason,
        String ruleId
) {
    public ExecutionPlan(ExecutionMode mode, String workflowId, Map<String, String> params, String reason) {
        this(mode, workflowId, params, reason, null);
    }
    // reactFallback / intentLabel 不变
}
```

`RoutingRuleProperties.java` + `RuleBasedRouter.java`：`match` 按 `priority` 降序；`match: any` 表示任一 pattern 命中；pattern 用 `Pattern.compile(p).matcher(query).find()`。

`ExecutionPlanRouter.java`：

```java
@Component
@RequiredArgsConstructor
public class ExecutionPlanRouter {
    private final RuleBasedRouter ruleBasedRouter;
    private final IntentRouter intentRouter;

    public Mono<ExecutionPlan> route(String userMessage) {
        return ruleBasedRouter.match(userMessage)
                .map(Mono::just)
                .orElseGet(() -> intentRouter.classifyPlan(userMessage));
    }
}
```

`ChatController`：`intentRouter.classifyPlan` → `executionPlanRouter.route`。

Nacos `agent.routing.rules` 示例：

```yaml
agent:
  routing:
    rules:
      - id: rule-finance-list-pending
        priority: 10
        match: any
        patterns:
          - "有哪些待审批"
          - "待审批.*报销"
        plan:
          mode: workflow
          workflowId: finance-list
          params:
            status: pending
```

- [ ] **Step 4: Run tests**

Run: `mvn test -pl orchestrator -Dtest=RuleBasedRouterTest -q`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add orchestrator/src/main/java/com/sunshine/orchestrator/routing/ \
        orchestrator/src/main/java/com/sunshine/orchestrator/config/RoutingRuleProperties.java \
        orchestrator/src/main/java/com/sunshine/orchestrator/controller/ChatController.java \
        orchestrator/src/test/java/com/sunshine/orchestrator/routing/RuleBasedRouterTest.java \
        docs/nacos/sunshine-orchestrator.yaml
git commit -m "feat(orchestrator): add rule-based routing before LLM intent classifier"
```

---

### Task 10: phase2_agent_demo.py --suite（2.10）

**Files:**
- Modify: `scripts/phase2_agent_demo.py`

- [ ] **Step 1: 增加 argparse 与套件步骤**

在文件顶部常量区：

```python
GATEWAY_URL = os.environ.get("GATEWAY_URL", "http://ecs4c16g:8000")
FINANCE_URL = os.environ.get("FINANCE_URL", "http://ecs4c16g:8710")
RAG_URL = os.environ.get("RAG_URL", "http://ecs4c16g:8400")
BASE = GATEWAY_URL
```

`main()` 改为：

```python
def parse_args():
    p = argparse.ArgumentParser()
    p.add_argument("--suite", choices=["all", "react", "workflow"], default="all")
    p.add_argument("--skip-rag-prep", action="store_true")
    return p.parse_args()


def run_react_finance(token: str, conv_id: str) -> dict:
    # 复用现有 Step 3–4 逻辑，返回 {"pass": bool, "detail": ...}
    ...


def run_workflow_chat(token: str, query: str, label: str) -> dict:
    # POST conversation message + 消费 SSE + 断言 completed + step/content
    ...


def main() -> int:
    args = parse_args()
    report = {"suite": args.suite, "steps": []}

    if not args.skip_rag_prep and args.suite in ("all", "workflow"):
        # Step 0: rag 抽样 — POST search "年假可以请几天" 命中 leave doc
        ...

    if args.suite in ("all", "react"):
        report["steps"].append(("react-finance", run_react_finance(...)))

    if args.suite in ("all", "workflow"):
        report["steps"].append(("wf-knowledge", run_workflow_chat(token, conv, "年假可以请几天", ...)))
        report["steps"].append(("wf-finance-list", run_workflow_chat(token, conv, "有哪些待审批报销", ...)))
        report["steps"].append(("wf-finance-smart", run_workflow_chat(token, conv, "待审批报销是否合规", ...)))

    failed = [name for name, r in report["steps"] if not r.get("pass")]
    print(json.dumps(report, ensure_ascii=False, indent=2))
    if failed:
        raise RuntimeError(f"failed steps: {failed}")
    print("[PASS] phase2 suite=" + args.suite)
    return 0
```

`run_workflow_chat` 实现要点：
- `POST /api/conversations/{id}/messages` body `{"content": query}`
- 解析 SSE：`step_count >= 2`、`stream_completed`、`content` 非空
- `wf-finance-list` 断言 steps 含 `list_finance_messages` 或 node 步
- `wf-finance-smart` 断言含 `agent` 相关 step

- [ ] **Step 2: 验证三套命令**

```bash
python scripts/phase2_agent_demo.py --suite react
python scripts/phase2_agent_demo.py --suite workflow --skip-rag-prep
python scripts/phase2_agent_demo.py --suite all
```

Expected: 各套件 `[PASS]`

- [ ] **Step 3: Commit**

```bash
git add scripts/phase2_agent_demo.py
git commit -m "feat(scripts): extend phase2_agent_demo with --suite all|react|workflow"
```

---

### Task 11: 审计 stepsSummary（2.13）

**Files:**
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/audit/StepsSummaryExtractor.java`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/audit/AuditService.java`
- Test: `orchestrator/src/test/java/com/sunshine/orchestrator/audit/StepsSummaryExtractorTest.java`
- Test: `orchestrator/src/test/java/com/sunshine/orchestrator/audit/AuditServiceTest.java`

- [ ] **Step 1: Write the failing test**

`StepsSummaryExtractorTest.java`:

```java
@Test
void extractsToolNamesAndDuration() throws Exception {
    String stepsJson = """
        [{"id":"intent","phase":"intent","lifecycle":"done","durationMs":100},
         {"id":"tool-list_finance_messages@1710000000000","phase":"tool","lifecycle":"done","durationMs":500}]
        """;
    StepsSummaryExtractor.Summary s = StepsSummaryExtractor.fromStepsJson(stepsJson);
    assertThat(s.toolNames()).containsExactly("list_finance_messages");
    assertThat(s.stepCount()).isEqualTo(2);
    assertThat(s.totalDurationMs()).isEqualTo(600L);
}
```

- [ ] **Step 2: Run test — expect FAIL**

Run: `mvn test -pl orchestrator -Dtest=StepsSummaryExtractorTest -q`

- [ ] **Step 3: Implement**

`StepsSummaryExtractor.java` — 用 `ObjectMapper` 解析 `List<Map>`；对 `id` 用 `ToolStepIds.catalogToolName`；`phase==tool` 或 `id` 以 `tool-`/`rag` 开头计入 `toolNames`；累加 `durationMs`。

`AuditService.java` payload：

```java
StepsSummaryExtractor.Summary stepsSummary = StepsSummaryExtractor.fromStepsJson(message.getSteps());
Map<String, Object> payload = new LinkedHashMap<>();
payload.put("contentLen", ...);
payload.put("hasReasoning", ...);
payload.put("hasSteps", ...);
payload.put("stepsSummary", Map.of(
        "toolNames", stepsSummary.toolNames(),
        "stepCount", stepsSummary.stepCount(),
        "totalDurationMs", stepsSummary.totalDurationMs()));
```

`AuditServiceTest` 追加断言 `publish` 的 `AuditEvent.payloadJson` 含 `list_finance_messages`（mock message steps）。

- [ ] **Step 4: Run tests**

Run: `mvn test -pl orchestrator -Dtest=StepsSummaryExtractorTest,AuditServiceTest -q`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add orchestrator/src/main/java/com/sunshine/orchestrator/audit/
git add orchestrator/src/test/java/com/sunshine/orchestrator/audit/
git commit -m "feat(audit): add stepsSummary with toolNames to assistant audit payload"
```

---

### Task 12: Catalog 白名单校验（2.15）

**Files:**
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/agent/DynamicToolkitFactory.java`
- Create: `orchestrator/src/test/java/com/sunshine/orchestrator/agent/DynamicToolkitFactoryTest.java`

- [ ] **Step 1: Write the failing test**

```java
@Test
void logsErrorWhenWhitelistToolMissingFromCatalog() {
    when(executionProperties.getReact()).thenReturn(reactProps);
    when(reactProps.getTools()).thenReturn(List.of("ghost_tool"));
    when(toolCatalogService.isRagTool("ghost_tool")).thenReturn(false);
    when(remoteToolFactory.create("ghost_tool")).thenReturn(Optional.empty());

    factory.build();

    // 使用 @Captor 或 ListAppender 断言 log 含 "ghost_tool"
}
```

- [ ] **Step 2: Implement**

`DynamicToolkitFactory.build()` 末尾：

```java
        List<String> missing = new ArrayList<>();
        for (String toolName : whitelist) {
            ...
            }, () -> missing.add(toolName));
        }
        if (!missing.isEmpty()) {
            log.error("[Orchestrator] react 白名单工具未在 Catalog 注册: {}", missing);
        }
```

- [ ] **Step 3: Run test + full orchestrator tests**

Run: `mvn test -pl orchestrator -Dtest=DynamicToolkitFactoryTest -q`

- [ ] **Step 4: Commit**

```bash
git add orchestrator/src/main/java/com/sunshine/orchestrator/agent/DynamicToolkitFactory.java \
        orchestrator/src/test/java/com/sunshine/orchestrator/agent/DynamicToolkitFactoryTest.java
git commit -m "fix(orchestrator): error-log react whitelist tools missing from catalog"
```

---

### Task 13: 文档同步 + 全量回归（2.16）

**Files:**
- Modify: `CLAUDE.md`
- Modify: `docs/phase2-closure-plan.md`
- Modify: `docs/implementation-plan.md`
- Modify: `docs/rag/README.md`

- [ ] **Step 1: 更新 CLAUDE.md 收尾命令块**

追加：

```markdown
# RAG 基线（阶段二）
python scripts/rag_reset.py
python scripts/rag_ingest_bulk.py
python scripts/rag_eval.py

# 阶段二 Live 验收
python scripts/phase2_agent_demo.py --suite all
```

- [ ] **Step 2: implementation-plan.md 勾选 2.10–2.16 检查门**

- [ ] **Step 3: 全量回归**

```bash
python scripts/sync_nacos.py
mvn test -pl orchestrator,rag-service -q
python scripts/rag_reset.py && python scripts/rag_ingest_bulk.py && python scripts/rag_eval.py
python scripts/phase2_agent_demo.py --suite all --skip-rag-prep
```

Expected: 全部成功

- [ ] **Step 4: Commit**

```bash
git add CLAUDE.md docs/phase2-closure-plan.md docs/implementation-plan.md docs/rag/README.md
git commit -m "docs: phase2 closure checklist and RAG SOP"
```

---

## 自审（Plan Self-Review）

### 1. Spec 覆盖

| Spec 章节 | 对应 Task |
|-----------|-----------|
| §4 RAG 清库/语料/golden/eval | Task 1–6 |
| §5 Live `--suite all` | Task 10 |
| §6 Legacy + Rag 统一 | Task 7–8 |
| §7 审计 tool 摘要 | Task 11 |
| §8 规则硬路由 | Task 9 |
| §9 白名单校验 | Task 12 |
| §10 文档 | Task 13 |
| §12 总检查门 | Task 6 + 10 + 13 回归命令 |

**缺口：** 无。前端 Timeline 手动 2 轮为人工验收，不入代码 Task。

### 2. Placeholder 扫描

- 无 TBD / implement later
- Task 3 语料正文需在实施时按模板写满（结构+示例已给出）
- Task 10 `run_react_finance` / `run_workflow_chat` 用 `...` 标示复用现有函数 — 实施时从现有 `main()` 抽取，非留空

### 3. 类型一致性

- `ExecutionPlan.ruleId` 在 Task 9 引入，Task 9 测试与 Nacos 规则一致
- `RagContextFormatter.Mode` 与 `formatHits` 在 Task 7 定义，Task 7 测试引用同名
- golden-set `doc_id` ↔ corpus `display_name` ↔ `rag_ingest_bulk` docName 链路一致

---

## 总检查门（完成后勾选）

- [ ] `rag_reset` → `rag_ingest_bulk` → `rag_eval` 全量报告归档
- [ ] `phase2_agent_demo.py --suite all` ecs4c16g 全 PASS
- [ ] `LegacyWorkflowExecutor` 已删除
- [ ] Rag 双路径统一 `default-top-k`
- [ ] `RuleBasedRouterTest` 绿
- [ ] 审计含 `toolNames`
- [ ] `mvn test -pl orchestrator,rag-service` 绿
- [ ] 前端 Timeline 手动 2 轮
