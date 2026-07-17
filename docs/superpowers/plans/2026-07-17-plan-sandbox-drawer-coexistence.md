# Plan × Sandbox 抽屉对照模式 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 节点抽屉与沙箱工作区可同时打开；对照时隐藏 Chat，节点占主区、沙箱在右；沙箱文件树可独立调宽。

**Architecture:** 去掉 open 时互关；`compareMode = plan.open && sandbox.open` 驱动 ChatView / 两抽屉布局 class；对照下沙箱 `maxWidth = bodyW - 400`，节点 `flex:1`；树宽独立 localStorage。

**Tech Stack:** Vue 3 + TypeScript（sunshine-ui）；vitest 单测 composable 宽度逻辑。

**Spec:** `docs/superpowers/specs/2026-07-17-plan-sandbox-drawer-coexistence-design.md`

---

### Task 1: 取消互斥 + compare 宽度逻辑

**Files:**
- Modify: `sunshine-ui/src/composables/usePlanNodeDrawer.ts`
- Modify: `sunshine-ui/src/composables/useSandboxWorkspaceDrawer.ts`
- Modify: `sunshine-ui/src/composables/sandboxDrawerBridge.ts`（可留空实现或标废弃）
- Create: `sunshine-ui/src/composables/useSandboxWorkspaceDrawer.compare.test.ts`

- [x] **Step 1: 写失败单测（对照 max 宽）**

```ts
import { describe, expect, it, beforeEach } from 'vitest'
import { usePlanNodeDrawer, DRAWER_MIN_WIDTH as PLAN_MIN } from './usePlanNodeDrawer'
import { useSandboxWorkspaceDrawer } from './useSandboxWorkspaceDrawer'

describe('drawer compare width', () => {
  beforeEach(() => {
    usePlanNodeDrawer().close()
    useSandboxWorkspaceDrawer().close()
  })
  it('compare mode: sandbox max = body - planMin', () => {
    const plan = usePlanNodeDrawer()
    const sb = useSandboxWorkspaceDrawer()
    const el = document.createElement('div')
    Object.defineProperty(el, 'clientWidth', { value: 1200 })
    plan.registerChatBody(el)
    sb.registerChatBody(el)
    plan.open({
      planId: 'p1',
      node: { id: 'n1', label: 'x', type: 'llm', status: 'done' } as any,
    })
    sb.open({ conversationId: 'c1' })
    // 暴露 drawerMaxWidth 或通过拖拽钳制间接测；实现时 export 测试用 getDrawerMaxWidth
    expect((sb as any).drawerMaxWidth?.value ?? 0).toBe(1200 - PLAN_MIN)
  })
})
```

- [x] **Step 2: 实现**

`usePlanNodeDrawer.open`：删除 `closeSandboxWorkspaceDrawerIfOpen()`。  
`useSandboxWorkspaceDrawer.open`：删除 `planDrawer.close()`。  
`sandboxDrawerBridge.closeSandboxWorkspaceDrawerIfOpen`：改为 no-op（或删除所有调用方）。  
导出 `PLAN_COMPARE_MIN = 400`；沙箱 `drawerMaxWidth`：若 `planDrawer.state.open` 则 `bodyW - PLAN_COMPARE_MIN`，否则 `bodyW - CHAT_CONTENT_MIN_WIDTH`。  
两 composable `return` 增加 `compareMode` computed（`plan.open && sandbox.open`）与 `drawerMaxWidth`（供测）。

- [x] **Step 3: 跑单测**

```bash
cd sunshine-ui && npx vitest run src/composables/useSandboxWorkspaceDrawer.compare.test.ts
```

Expected: PASS

---

### Task 2: ChatView 对照布局

**Files:**
- Modify: `sunshine-ui/src/views/ChatView.vue`

- [x] **Step 1:** `compareMode = plan.state.open && sandbox.state.open`  
- [x] **Step 2:** `chat-body` 加 class `chat-body--compare`；`chat-main` 在 compare 时 `v-show="!compareMode"` 或 class 隐藏且不占 flex  
- [x] **Step 3:** `.chat-body--compare { min-width: 920px; overflow-x: auto; }`（400+520）

---

### Task 3: PlanNodeDrawer 对照样式

**Files:**
- Modify: `sunshine-ui/src/components/plan/PlanNodeDrawer.vue`

- [x] **Step 1:** `compareMode`（读 sandbox open）  
- [x] **Step 2:** compare 时 `:style` 用 `flex:1; minWidth:400px; width:auto`，隐藏左侧 resize handle  
- [x] **Step 3:** 非 compare 保持现宽 + handle

---

### Task 4: 沙箱文件树独立宽度

**Files:**
- Modify: `sunshine-ui/src/composables/useSandboxWorkspaceDrawer.ts`（或抽屉内本地 state）  
- Modify: `sunshine-ui/src/components/sandbox/SandboxWorkspaceDrawer.vue`

- [x] **Step 1:** `treeWidth` 160–360，默认 220，key `sunshine-sandbox-tree-width`；有效宽 `min(treeWidth, drawerWidth - 240)`  
- [x] **Step 2:** 树/预览间 resize handle + pointer 拖拽  
- [x] **Step 3:** `.file-tree-pane { width: treeWidthEffective; flex-shrink:0 }`

---

### Task 5: 手工验收 + 文档状态

- [x] 单测 + `vue-tsc` 通过；手工验收见 spec §验收 1–7（前端刷新后点验）  
- [x] Spec 状态改为「已实现」；plan 勾选完成

**不自动 git commit**（除非用户明确要求）。
