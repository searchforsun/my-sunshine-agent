<script setup lang="ts">
import { inject } from 'vue'
import { NButton, NTag } from 'naive-ui'
import { PROMPTS_PAGE_KEY, type PromptsPageApi } from '../../composables/usePromptsPage'

const page = inject(PROMPTS_PAGE_KEY) as PromptsPageApi

/** 意图链路；底栏强制只锁执行方式，仍可解析 # / $ / @ */
const ROUTING_STEPS = [
  { key: 'L0', title: '硬绑定', detail: '#工作流 / $专家 / @Skill → 立刻锁定' },
  { key: '规则', title: '统一规则', detail: 'Catalog 路由规则（routing-rule）按 priority 首命中' },
  { key: 'L3', title: '意图分类', detail: '未命中 → intent.classifier' },
  { key: '执行', title: '分发', detail: 'workflow / plan-workflow / peer-collab / react' },
] as const

/** 底栏强制执行模式（≠ 自动） */
const FORCE_ROWS = [
  {
    item: '触发',
    detail: 'Chat 底栏执行模式 ≠ 自动（react / workflow / plan-workflow / peer-collab）',
  },
  {
    item: '效果',
    detail: '锁定所选 ExecutionMode，不再由意图自动改道',
  },
  {
    item: '仍走',
    detail: '仍走 L0 / 同 mode 规则 / L3，解析 Skill、react 提示词、workflowId',
  },
  {
    item: '工作流模板',
    detail: '具体模板用 Chat # + workflow-manager；底栏不做二级下拉',
  },
] as const

/** 发给模型的消息顺序（从上到下） */
const MESSAGE_STACK = [
  {
    role: 'system',
    title: 'Catalog 行为层',
    detail: 'system-prompt → mode-overlay →（ReAct）react-prompt / restart / HITL → skill → scope / 节点 prompt',
  },
  {
    role: 'system',
    title: 'L2 用户状态',
    detail: '分层说明 + [用户状态 · L2]（仅 active）',
  },
  {
    role: 'system',
    title: 'L1 Far 摘要',
    detail: '远窗摘要（可空）；折叠时对照现行 L2，冲突以 L2 为准',
  },
  {
    role: 'user / assistant',
    title: 'L1 Mid / Near',
    detail: 'Mid：完整 user + 摘要 assistant；Near：近 N 轮原文（仅供指代）',
  },
  {
    role: 'system',
    title: 'L3 历史材料',
    detail: '召回的旧对话片段（可能过期；排除已在 Mid/Near 中的消息）',
  },
  {
    role: 'user',
    title: '当前提问',
    detail: '本轮输入；若有注入内容，会标成「当前提问」',
  },
] as const

/** 超预算裁剪与摘要 / 审计 */
const BUDGET_ROWS = [
  {
    item: '裁剪顺序',
    detail: '超字数上限：先丢 L3 → 再丢 Far → 再整轮丢 Mid → 不丢 L2（含 constraint）',
  },
  {
    item: 'Far / Mid 摘要',
    detail: 'Far 折叠对照现行 L2；Mid 另有压缩摘要',
  },
  {
    item: '腐败审计',
    detail: '维护任务 / L2 抽取后，检查 Far 与 L2 是否自相矛盾',
  },
  {
    item: '可调参数',
    detail: 'max-chars / near-turns / mid-turns（运维配置，非本页条目）',
  },
] as const

/** 各路径叠层对照 */
const AGENT_ROWS = [
  {
    key: 'MAIN',
    title: '主 Agent',
    when: 'react / 底栏强制',
    stack: 'L1 / L2 / L3 全量；叠 mode-overlay.react 与可选 react-prompt / skill / HITL；本轮 TOOL 另有压缩',
  },
  {
    key: 'spawn',
    title: '子任务',
    when: '元工具委派',
    stack: 'system + subagent 叠层；无会话 L1/L2/L3，靠委派 prompt 带上下文',
  },
  {
    key: 'wf-agent',
    title: 'DAG · agent 节点',
    when: 'Workflow / Plan',
    stack: 'system + 节点 systemOverlay / skill；上游靠注入，默认不带会话 L1/L2/L3',
  },
  {
    key: 'expert',
    title: '多专家 Hub',
    when: 'peer-collab',
    stack: '可先 ReAct 收材料 → 专家发言 → Synthesizer 综合',
  },
] as const
</script>

<template>
  <main class="detail-panel">
    <div class="detail-toolbar">
      <div class="detail-title-block">
        <h3 class="detail-heading">原理分析</h3>
        <p class="detail-sub">全局说明 · 与左侧选中条目无关</p>
      </div>
      <NButton size="small" round quaternary @click="page.closePrinciples()">
        返回编辑
      </NButton>
    </div>

    <div class="detail-scroll">
      <!-- 一、意图 -->
      <section class="block">
        <h4 class="block-title">一、意图怎么走</h4>
        <ol class="pipeline" aria-label="意图识别链路">
          <li
            v-for="(step, index) in ROUTING_STEPS"
            :key="step.key"
            class="pipeline-step"
          >
            <span class="pipeline-badge">{{ step.key }}</span>
            <div class="pipeline-body">
              <strong>{{ step.title }}</strong>
              <span>{{ step.detail }}</span>
            </div>
            <span
              v-if="index < ROUTING_STEPS.length - 1"
              class="pipeline-sep"
              aria-hidden="true"
            >→</span>
          </li>
        </ol>
        <h4 class="block-subtitle">强制执行模式</h4>
        <div class="kv-table" role="table">
          <div class="kv-head" role="row">
            <span role="columnheader">项</span>
            <span role="columnheader">说明</span>
          </div>
          <div
            v-for="row in FORCE_ROWS"
            :key="row.item"
            class="kv-row"
            role="row"
          >
            <strong class="kv-item" role="cell">{{ row.item }}</strong>
            <span class="kv-detail" role="cell">{{ row.detail }}</span>
          </div>
        </div>
      </section>

      <!-- 二、消息落点 -->
      <section class="block">
        <h4 class="block-title">二、提示词落在哪</h4>
        <p class="block-lead">
          Catalog / L2 / Far / L3 进多条 <code>system</code>；Mid / Near 与当前提问走
          <code>user</code> / <code>assistant</code>。跨轮记忆为 <strong>L1 · L2 · L3</strong>。
        </p>
        <div class="msg-table" role="table">
          <div class="msg-head" role="row">
            <span role="columnheader">#</span>
            <span role="columnheader">角色</span>
            <span role="columnheader">层</span>
            <span role="columnheader">内容</span>
          </div>
          <div
            v-for="(seg, i) in MESSAGE_STACK"
            :key="seg.title"
            class="msg-row"
            role="row"
          >
            <span class="msg-idx" role="cell">{{ i + 1 }}</span>
            <span role="cell">
              <NTag size="tiny" :bordered="false" round class="role-tag">
                {{ seg.role }}
              </NTag>
            </span>
            <strong class="msg-title" role="cell">{{ seg.title }}</strong>
            <span class="msg-detail" role="cell">{{ seg.detail }}</span>
          </div>
        </div>
      </section>

      <!-- 二附、预算 -->
      <section class="block">
        <h4 class="block-title">预算与摘要</h4>
        <div class="kv-table" role="table">
          <div class="kv-head" role="row">
            <span role="columnheader">项</span>
            <span role="columnheader">说明</span>
          </div>
          <div
            v-for="row in BUDGET_ROWS"
            :key="row.item"
            class="kv-row"
            role="row"
          >
            <strong class="kv-item" role="cell">{{ row.item }}</strong>
            <span class="kv-detail" role="cell">{{ row.detail }}</span>
          </div>
        </div>
      </section>

      <!-- 三、路径对照 -->
      <section class="block">
        <h4 class="block-title">三、各路径叠哪些</h4>
        <p class="block-lead">
          spawn / workflow agent 默认不带会话 L1/L2/L3；上游事实靠注入，不靠跨轮记忆。
        </p>
        <div class="compare" role="table">
          <div class="compare-head" role="row">
            <span role="columnheader">路径</span>
            <span role="columnheader">何时</span>
            <span role="columnheader">叠层要点</span>
          </div>
          <div
            v-for="row in AGENT_ROWS"
            :key="row.key"
            class="compare-row"
            role="row"
          >
            <div class="compare-path" role="cell">
              <NTag size="tiny" :bordered="false" round class="role-tag">
                {{ row.key }}
              </NTag>
              <strong>{{ row.title }}</strong>
            </div>
            <span class="compare-when" role="cell">{{ row.when }}</span>
            <span class="compare-stack" role="cell">{{ row.stack }}</span>
          </div>
        </div>
        <p class="callout muted">
          <span class="callout-k">本页怎么改</span>
          系统配置 = system / mode-overlay；路由规则 = 路径 + 可选 reactPromptId；React 提示词 = 仅 MAIN；上下文分层 =
          context.*。
        </p>
      </section>
    </div>
  </main>
</template>

<style scoped>
.detail-panel {
  min-height: 0;
  border-radius: var(--radius-lg);
  border: 1px solid var(--sun-border);
  background: var(--sun-black);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.detail-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
  padding: 18px 22px;
  border-bottom: 1px solid var(--sun-border);
  flex-shrink: 0;
}

.detail-title-block {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}

.detail-heading {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  color: var(--sun-text);
}

.detail-sub {
  margin: 0;
  font-size: 12px;
  color: var(--sun-text-secondary);
}

.detail-scroll {
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding: 18px 22px 28px;
  display: flex;
  flex-direction: column;
  gap: 28px;
}

.block-title {
  margin: 0 0 10px;
  font-size: 14px;
  font-weight: 600;
  color: var(--sun-text);
}

.block-subtitle {
  margin: 14px 0 10px;
  font-size: 13px;
  font-weight: 600;
  color: var(--sun-text);
}

.block-lead {
  margin: 0 0 12px;
  font-size: 13px;
  line-height: 1.55;
  color: var(--sun-text-secondary);
}

.block-lead code,
.callout code {
  font-size: 12px;
  color: var(--sun-text);
}

/* —— 意图流水线 —— */
.pipeline {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-wrap: wrap;
  align-items: stretch;
  gap: 0;
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-md);
  overflow: hidden;
}

.pipeline-step {
  display: flex;
  align-items: center;
  flex: 1 1 140px;
  min-width: 0;
  gap: 10px;
  padding: 12px 12px 12px 14px;
  border-right: 1px solid var(--sun-border);
}

.pipeline-step:last-child {
  border-right: none;
}

.pipeline-badge {
  flex-shrink: 0;
  min-width: 36px;
  text-align: center;
  font-size: 11px;
  font-weight: 700;
  color: var(--sun-text);
  border: 1px solid var(--sun-border);
  border-radius: 6px;
  padding: 4px 6px;
}

.pipeline-body {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.pipeline-body strong {
  font-size: 13px;
  color: var(--sun-text);
}

.pipeline-body span {
  font-size: 12px;
  line-height: 1.4;
  color: var(--sun-text-secondary);
}

.pipeline-sep {
  display: none;
}

.callout {
  margin: 12px 0 0;
  display: flex;
  gap: 10px;
  align-items: baseline;
  padding: 10px 12px;
  border: 1px dashed var(--sun-border);
  border-radius: var(--radius-md);
  font-size: 12px;
  line-height: 1.5;
  color: var(--sun-text-secondary);
}

.callout.muted {
  margin-top: 14px;
}

.callout-k {
  flex-shrink: 0;
  font-weight: 600;
  color: var(--sun-text);
}

/* —— 消息栈表 / 键值表 —— */
.msg-table,
.kv-table {
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-md);
  overflow: hidden;
}

.msg-head,
.msg-row {
  display: grid;
  grid-template-columns: 32px 120px minmax(100px, 0.7fr) minmax(0, 1.8fr);
  gap: 10px;
  padding: 10px 14px;
  align-items: start;
}

.msg-head,
.kv-head {
  border-bottom: 1px solid var(--sun-border);
  font-size: 11px;
  font-weight: 600;
  color: var(--sun-text);
  letter-spacing: 0.02em;
}

.msg-row,
.kv-row {
  border-bottom: 1px solid var(--sun-border);
  font-size: 12px;
  line-height: 1.5;
  color: var(--sun-text-secondary);
}

.msg-row:last-child,
.kv-row:last-child {
  border-bottom: none;
}

.msg-idx {
  font-weight: 700;
  color: var(--sun-text-secondary);
}

.msg-title {
  font-size: 13px;
  color: var(--sun-text);
  font-weight: 600;
}

.msg-detail,
.kv-detail {
  color: var(--sun-text-secondary);
  word-break: break-word;
}

.kv-head,
.kv-row {
  display: grid;
  grid-template-columns: minmax(110px, 0.45fr) minmax(0, 1.55fr);
  gap: 12px;
  padding: 10px 14px;
  align-items: start;
}

.kv-item {
  font-size: 13px;
  color: var(--sun-text);
  font-weight: 600;
}

.role-tag {
  --n-color: transparent !important;
  --n-text-color: var(--sun-text) !important;
  border: 1px solid var(--sun-border) !important;
  font-weight: 600;
}

/* —— 路径对照表 —— */
.compare {
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-md);
  overflow: hidden;
}

.compare-head,
.compare-row {
  display: grid;
  grid-template-columns: minmax(120px, 0.9fr) minmax(100px, 0.7fr) minmax(0, 1.6fr);
  gap: 12px;
  padding: 10px 14px;
  align-items: start;
}

.compare-head {
  border-bottom: 1px solid var(--sun-border);
  font-size: 11px;
  font-weight: 600;
  color: var(--sun-text);
  letter-spacing: 0.02em;
}

.compare-row {
  border-bottom: 1px solid var(--sun-border);
  font-size: 12px;
  line-height: 1.5;
  color: var(--sun-text-secondary);
}

.compare-row:last-child {
  border-bottom: none;
}

.compare-path {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 6px;
}

.compare-path strong {
  font-size: 13px;
  color: var(--sun-text);
  font-weight: 600;
}

.compare-when {
  color: var(--sun-text-secondary);
}

.compare-stack {
  color: var(--sun-text-secondary);
}

@media (max-width: 720px) {
  .pipeline-step {
    flex: 1 1 100%;
    border-right: none;
    border-bottom: 1px solid var(--sun-border);
  }

  .pipeline-step:last-child {
    border-bottom: none;
  }

  .compare-head {
    display: none;
  }

  .compare-row {
    grid-template-columns: 1fr;
    gap: 6px;
    padding: 12px 14px;
  }

  .compare-when::before {
    content: '何时 · ';
    font-weight: 600;
    color: var(--sun-text);
  }

  .compare-stack::before {
    content: '叠层 · ';
    font-weight: 600;
    color: var(--sun-text);
  }

  .msg-head,
  .kv-head {
    display: none;
  }

  .msg-row {
    grid-template-columns: 24px 1fr;
    gap: 6px;
  }

  .msg-row .role-tag {
    grid-column: 2;
  }

  .msg-title,
  .msg-detail {
    grid-column: 2;
  }

  .kv-row {
    grid-template-columns: 1fr;
    gap: 4px;
  }

  .kv-detail::before {
    content: '说明 · ';
    font-weight: 600;
    color: var(--sun-text);
  }
}
</style>
