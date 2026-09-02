# 系统状态架构泳道 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 状态页改为分层架构泳道展示上下游；补全 Sandbox/OA 探测；Gateway 增 health 路由；`start.py` 纳入 OA。

**Architecture:** 探测逻辑仍经 Gateway `/health/*`；服务清单与泳道元数据抽到 `statusArchitecture.ts`；`StatusView.vue` 只负责探测与泳道渲染。OA/Finance 视觉嵌在 Tool Manager 下，仍独立探测。中间件底部条带不探测。

**Tech Stack:** Vue3/TS · Naive UI（仅刷新按钮等）· Nacos Gateway YAML · `scripts/start.py` · vitest

**Spec:** [2026-07-17-status-architecture-lanes-design.md](../specs/archive/2026-07-17-status-architecture-lanes-design.md)

---

## File map

| 路径 | 动作 |
|------|------|
| `docs/nacos/sunshine-gateway.yaml` | 追加 `health-sandbox`、`health-oa` |
| `scripts/start.py` | `SERVICES` 增加 oa |
| `sunshine-ui/src/status/statusArchitecture.ts` | **Create** — 服务定义、中间件、泳道分组 helper |
| `sunshine-ui/src/status/statusArchitecture.test.ts` | **Create** — 15 服务、lane、tool-child 归属断言 |
| `sunshine-ui/src/views/StatusView.vue` | 改用 architecture 模块 + 泳道 UI，删除 NGrid 卡片 |
| （可选）`README.md` | 架构 ASCII 补 Sandbox/OA — 非必须 |

**不改：** Vite `/health` 代理已覆盖子路径；oa/sandbox 已有 `sunshine-common` 自动 `/health`。

---

### Task 1: Gateway health 路由

**Files:**
- Modify: `docs/nacos/sunshine-gateway.yaml`（在 `health-finance` 之后、`sunshine-auth-api` 之前）

- [x] **Step 1:** 插入两条路由：

```yaml
        - id: health-sandbox
          uri: lb://sunshine-sandbox-service
          predicates:
            - Path=/health/sandbox
          filters:
            - RewritePath=/health/sandbox, /health
        - id: health-oa
          uri: lb://sunshine-oa
          predicates:
            - Path=/health/oa
          filters:
            - RewritePath=/health/oa, /health
```

- [x] **Step 2:** 同步 Nacos（需网络/中间件可达）：

```bash
python scripts/sync_nacos.py
```

Expected: 脚本成功同步 `sunshine-gateway.yaml`（或项目惯用的全量 sync 成功日志）。

- [x] **Step 3:** Commit（执行时若用户未要求提交则跳过，仅 stage 备查）

```bash
git add docs/nacos/sunshine-gateway.yaml
git commit -m "$(cat <<'EOF'
chore(gateway): expose /health/sandbox and /health/oa

EOF
)"
```

---

### Task 2: start.py 纳入 OA

**Files:**
- Modify: `scripts/start.py`

- [x] **Step 1:** 在 `SERVICES` 的 finance 行后追加：

```python
    ("finance", "finance-service", "sunshine-finance", 8710),
    ("oa", "oa-service", "sunshine-oa", 8700),
    ("tool-manager", "tool-manager", "sunshine-tool-manager", 8210),
```

（保持 tuple 四元组格式与现有一致；`oa-service` 模块目录、`sunshine-oa` artifact、端口 `8700`。）

- [x] **Step 2:** 校验解析表含 oa：

```bash
python -c "from scripts.start import SERVICE_BY_NAME; assert 'oa' in SERVICE_BY_NAME and SERVICE_BY_NAME['oa'][2]==8700; print('ok', SERVICE_BY_NAME['oa'])"
```

若 `scripts` 非 package，改用：

```bash
cd /usr/local/gitproj/my-sunshine-agent && python -c "
import importlib.util
spec = importlib.util.spec_from_file_location('start', 'scripts/start.py')
m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)
assert 'oa' in m.SERVICE_BY_NAME
print('ok', m.SERVICE_BY_NAME['oa'])
"
```

Expected: `ok ('oa-service', 'sunshine-oa', 8700)`

- [x] **Step 3:** Commit（同上，按用户许可）

```bash
git add scripts/start.py
git commit -m "$(cat <<'EOF'
chore(start): include oa-service in startup list

EOF
)"
```

---

### Task 3: statusArchitecture 模块 + 单测（先红后绿）

**Files:**
- Create: `sunshine-ui/src/status/statusArchitecture.ts`
- Create: `sunshine-ui/src/status/statusArchitecture.test.ts`

- [x] **Step 1: 写失败测试**

```typescript
import { describe, expect, it } from 'vitest'
import {
  INFRA_ITEMS,
  SERVICE_DEFS,
  buildServiceList,
  countProbeable,
  platformRoots,
  toolChildrenOf,
  type ServiceDef,
} from './statusArchitecture'

describe('statusArchitecture', () => {
  it('lists 15 probeable microservices including sandbox and oa', () => {
    expect(countProbeable(SERVICE_DEFS)).toBe(15)
    const names = SERVICE_DEFS.map((d) => d.name)
    expect(names).toContain('Sandbox Service')
    expect(names).toContain('OA')
  })

  it('places OA and Finance under tool-manager', () => {
    const kids = toolChildrenOf(SERVICE_DEFS)
    expect(kids.map((k) => k.name).sort()).toEqual(['Finance', 'OA'])
    for (const k of kids) {
      expect(k.lane).toBe('tool-child')
      expect(k.parent).toBe('tool-manager')
      expect(k.gatewayPath).toBeTruthy()
    }
  })

  it('keeps Desensitize as platform root (not tool-child)', () => {
    const roots = platformRoots(SERVICE_DEFS)
    expect(roots.some((r) => r.name === 'Desensitize')).toBe(true)
    expect(roots.some((r) => r.name === 'OA')).toBe(false)
  })

  it('infra strip is display-only', () => {
    expect(INFRA_ITEMS.length).toBeGreaterThanOrEqual(5)
    expect(INFRA_ITEMS).toEqual(
      expect.arrayContaining(['Nacos', 'Redis', 'MySQL', 'Milvus', 'ES', 'RocketMQ', 'MinIO']),
    )
  })

  it('buildServiceList defaults probeable to checking', () => {
    const list = buildServiceList(SERVICE_DEFS)
    expect(list.every((s) => s.status === 'checking' || s.status === 'external')).toBe(true)
    expect(list.filter((s) => s.gatewayPath).every((s) => s.status === 'checking')).toBe(true)
  })
})
```

- [x] **Step 2: 跑测确认失败**

```bash
cd sunshine-ui && npx vitest run src/status/statusArchitecture.test.ts
```

Expected: FAIL（模块不存在或 export 缺失）

- [x] **Step 3: 实现模块**

`sunshine-ui/src/status/statusArchitecture.ts`：

```typescript
export type ServiceLane = 'entry' | 'orchestrator' | 'platform' | 'tool-child'
export type ProbeStatus = 'online' | 'offline' | 'checking' | 'external'

export interface ServiceDef {
  name: string
  port: number
  description: string
  gatewayPath?: string
  expectedService?: string
  lane: ServiceLane
  /** Catalog id key for parent; only tool-child uses 'tool-manager' */
  parent?: 'tool-manager'
}

export interface ServiceStatus extends ServiceDef {
  status: ProbeStatus
  latency?: number
}

export const INFRA_ITEMS = [
  'Nacos',
  'Redis',
  'MySQL',
  'Milvus',
  'ES',
  'RocketMQ',
  'MinIO',
] as const

export const SERVICE_DEFS: ServiceDef[] = [
  { name: 'Gateway', port: 8000, description: 'API 网关与路由', gatewayPath: '/health', expectedService: 'sunshine-gateway', lane: 'entry' },
  { name: 'BFF', port: 8001, description: 'SSE 流式转发', gatewayPath: '/health/bff', expectedService: 'sunshine-bff', lane: 'entry' },
  { name: 'Auth Center', port: 8100, description: 'Sa-Token 认证中心', gatewayPath: '/health/auth', expectedService: 'sunshine-auth', lane: 'entry' },
  { name: 'Orchestrator', port: 8200, description: 'Agent 编排与 Workflow', gatewayPath: '/health/orchestrator', expectedService: 'sunshine-orchestrator', lane: 'orchestrator' },
  { name: 'Tool Manager', port: 8210, description: '业务工具注册与 Catalog', gatewayPath: '/health/tool-manager', expectedService: 'sunshine-tool-manager', lane: 'platform' },
  { name: 'OA', port: 8700, description: 'OA 模拟 / Tool App', gatewayPath: '/health/oa', expectedService: 'sunshine-oa', lane: 'tool-child', parent: 'tool-manager' },
  { name: 'Finance', port: 8710, description: '财务消息与审批 Mock', gatewayPath: '/health/finance', expectedService: 'sunshine-finance', lane: 'tool-child', parent: 'tool-manager' },
  { name: 'Skill Manager', port: 8225, description: 'Skill 包管理与 Catalog', gatewayPath: '/health/skill-manager', expectedService: 'sunshine-skill-manager', lane: 'platform' },
  { name: 'Sandbox Service', port: 8226, description: 'Skills Docker 沙箱', gatewayPath: '/health/sandbox', expectedService: 'sunshine-sandbox-service', lane: 'platform' },
  { name: 'Workflow Manager', port: 8230, description: 'Workflow Studio DB / Catalog（4.13）', gatewayPath: '/health/workflow-manager', expectedService: 'sunshine-workflow-manager', lane: 'platform' },
  { name: 'Expert Manager', port: 8235, description: '多专家协作 Catalog / Admin', gatewayPath: '/health/expert-manager', expectedService: 'sunshine-expert-manager', lane: 'platform' },
  { name: 'LLM Gateway', port: 8300, description: '多厂商大模型路由', gatewayPath: '/health/llm-gateway', expectedService: 'sunshine-llm-gateway', lane: 'platform' },
  { name: 'RAG Service', port: 8400, description: 'Milvus 向量检索', gatewayPath: '/health/rag', expectedService: 'sunshine-rag', lane: 'platform' },
  { name: 'Prompt Manager', port: 8500, description: '提示词模板管理', gatewayPath: '/health/prompt', expectedService: 'sunshine-prompt', lane: 'platform' },
  { name: 'Desensitize', port: 8600, description: '数据脱敏引擎', gatewayPath: '/health/desensitize', expectedService: 'sunshine-desensitize', lane: 'platform' },
]

export function countProbeable(defs: ServiceDef[]): number {
  return defs.filter((d) => !!d.gatewayPath).length
}

export function entryServices(defs: ServiceDef[]): ServiceDef[] {
  return defs.filter((d) => d.lane === 'entry')
}

export function orchestratorServices(defs: ServiceDef[]): ServiceDef[] {
  return defs.filter((d) => d.lane === 'orchestrator')
}

export function platformRoots(defs: ServiceDef[]): ServiceDef[] {
  return defs.filter((d) => d.lane === 'platform')
}

export function toolChildrenOf(defs: ServiceDef[]): ServiceDef[] {
  return defs.filter((d) => d.lane === 'tool-child' && d.parent === 'tool-manager')
}

export function buildServiceList(defs: ServiceDef[]): ServiceStatus[] {
  return defs.map((d) => ({
    ...d,
    status: d.gatewayPath ? 'checking' : 'external',
  }))
}
```

- [x] **Step 4: 跑测通过**

```bash
cd sunshine-ui && npx vitest run src/status/statusArchitecture.test.ts
```

Expected: PASS（全部 it 绿）

- [x] **Step 5: Commit**

```bash
git add sunshine-ui/src/status/statusArchitecture.ts sunshine-ui/src/status/statusArchitecture.test.ts
git commit -m "$(cat <<'EOF'
feat(ui): add status architecture service catalog

EOF
)"
```

---

### Task 4: StatusView 泳道 UI

**Files:**
- Modify: `sunshine-ui/src/views/StatusView.vue`（整文件替换结构；保留探测逻辑）

- [x] **Step 1:** script — 改为从 `statusArchitecture` 导入；删除本地 `SERVICE_DEFS`；`services` 用 `buildServiceList(SERVICE_DEFS)`；增加按 name 查找与 lane 计算属性：

```typescript
import { computed, ref, onMounted } from 'vue'
import { NButton, NTag } from 'naive-ui'
import { resolveHealthProbeUrl } from '../api/config'
import {
  INFRA_ITEMS,
  SERVICE_DEFS,
  buildServiceList,
  entryServices,
  orchestratorServices,
  platformRoots,
  toolChildrenOf,
  type ServiceStatus,
} from '../status/statusArchitecture'

// …保留 HealthPayload、probeHttp、checkServices、statusType/Label、endpoint、onlineServices

const services = ref<ServiceStatus[]>(buildServiceList(SERVICE_DEFS))

function byName(name: string): ServiceStatus | undefined {
  return services.value.find((s) => s.name === name)
}

const entry = computed(() =>
  entryServices(SERVICE_DEFS).map((d) => byName(d.name)!).filter(Boolean),
)
const orch = computed(() =>
  orchestratorServices(SERVICE_DEFS).map((d) => byName(d.name)!).filter(Boolean),
)
const platform = computed(() =>
  platformRoots(SERVICE_DEFS).map((d) => byName(d.name)!).filter(Boolean),
)
const toolKids = computed(() =>
  toolChildrenOf(SERVICE_DEFS).map((d) => byName(d.name)!).filter(Boolean),
)

function nodeMeta(item: ServiceStatus): string {
  if (item.status === 'online' && item.latency !== undefined) {
    return `● ${item.latency}ms · :${item.port}`
  }
  if (item.status === 'checking') return '● 检测中…'
  if (item.status === 'offline') return `● 离线 · :${item.port}`
  return `● 内网 · :${item.port}`
}
```

探测函数签名改为操作 `services.value` 中的对象（与现逻辑相同：`Promise.all(services.value.map(probeHttp))`）。

- [x] **Step 2:** template — 删除 `NCard`/`NGrid` 微服务卡片区，改为泳道：

结构要点（实现时写完整 markup）：

1. 页头 + 统计（`onlineServices()/services.length`）不变  
2. `section.arch`：  
   - L0：`Browser / sunshine-ui :5173` 虚线框（不探测）  
   - `div.lane-arrow` ↓  
   - L1：`v-for="svc in entry"` → `article.node`  
   - ↓  
   - L2：`orch` 节点加 class `node--core`  
   - ↓  
   - L3：`platform` 网格；若 `svc.name === 'Tool Manager'`，节点内再渲染 `toolKids` 为 `node node--child`  
   - ↓  
   - 基础设施：`INFRA_ITEMS` 用 `·` 分隔的虚线条带  
3. 每个业务节点：名称 + `NTag`（可选，保持紧凑可只用色点）+ `nodeMeta`；**不要**展开 description 正文（spec：紧凑一行）

- [x] **Step 3:** style — 对齐 Codex：

```css
.arch { border: 1px solid var(--sun-border); border-radius: var(--radius-lg); padding: 16px; background: var(--sun-black); }
.lane-label { font-size: 11px; color: var(--sun-text-muted); text-transform: uppercase; letter-spacing: 0.04em; margin-bottom: 8px; }
.node {
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-md);
  background: var(--sun-black);
  padding: 8px 12px;
  text-align: center;
}
.node:hover { border-color: var(--sun-border-light); }
.node--core { border-color: var(--sun-accent, #5b8def); }
.node--child {
  border-style: dashed;
  font-size: 12px;
  margin-top: 6px;
}
.node-name { font-size: 13px; font-weight: 600; color: var(--sun-text); }
.node-meta { margin-top: 4px; font-size: 11px; font-family: 'JetBrains Mono', monospace; color: var(--sun-text-muted); }
.lane-arrow { text-align: center; color: var(--sun-text-muted); margin: 6px 0; opacity: 0.5; }
.infra {
  border: 1px dashed var(--sun-border);
  border-radius: var(--radius-md);
  padding: 10px;
  text-align: center;
  color: var(--sun-text-muted);
  font-size: 12px;
}
/* 禁止使用 --sun-surface / --sun-deep 作节点底 */
```

- [x] **Step 4:** 类型检查

```bash
cd sunshine-ui && npx vue-tsc -b --pretty false 2>&1 | head -40
```

Expected: 无 StatusView / statusArchitecture 相关错误。

- [x] **Step 5: Commit**

```bash
git add sunshine-ui/src/views/StatusView.vue
git commit -m "$(cat <<'EOF'
feat(ui): render system status as architecture lanes

EOF
)"
```

---

### Task 5: Live 验收

**前置：** Gateway 已 sync 并重启；Sandbox/OA 进程已起（`python scripts/start.py` 或至少 gateway + sandbox + oa）。

- [x] **Step 1:** 探测路由

```bash
curl -sS http://localhost:8000/health/sandbox | tee /tmp/h-sandbox.json
curl -sS http://localhost:8000/health/oa | tee /tmp/h-oa.json
python -c "import json; s=json.load(open('/tmp/h-sandbox.json')); o=json.load(open('/tmp/h-oa.json')); assert s['status']=='UP' and s['service']=='sunshine-sandbox-service'; assert o['status']=='UP' and o['service']=='sunshine-oa'; print('health ok')"
```

Expected: `health ok`

- [x] **Step 2:** 浏览器打开 `http://localhost:5173` → 系统状态：分层泳道、Tool 下挂 OA/Finance、底中间件条带、无旧 3 列卡片；统计分母为 15。

- [x] **Step 3:** 若 Gateway/OA 未起导致离线，记录实际 online 数，但节点必须出现在正确分层。

---

### Task 6: Spec 状态回写

**Files:**
- Modify: `docs/superpowers/specs/2026-07-17-status-architecture-lanes-design.md` 顶部状态 → `✅ 已实现`（实现完成后再改）

- [x] **Step 1:** 将 `> **状态**：待实现` 改为 `> **状态**：✅ 已实现`
- [x] **Step 2:** 与本 plan、spec、UI、gateway、start 一并提交（用户许可时）

---

## Spec coverage（自审）

| Spec 要求 | Task |
|-----------|------|
| 补 Sandbox/OA 定义 | T3 |
| OA 进 start.py | T2 |
| Gateway `/health/sandbox` `/health/oa` | T1 |
| 分层泳道 UI、紧凑节点、无侧栏 | T4 |
| OA/Finance 挂 Tool 下 | T3+T4 |
| Desensitize 在 L3 platform | T3 |
| 中间件条带不探测 | T3+T4 |
| 去掉卡片网格 | T4 |
| 15 服务统计 | T3+T4 |
| Live 验收 | T5 |

无 TBD；类型名 `ServiceDef` / `lane` / `tool-child` 在 T3–T4 一致。
