<script setup lang="ts">
import { inject } from 'vue'
import { NButton, NTag } from 'naive-ui'
import { PROMPTS_PAGE_KEY, type PromptsPageApi } from '../../composables/usePromptsPage'

const page = inject(PROMPTS_PAGE_KEY) as PromptsPageApi

/** 意图链路：自上而下；底栏强制锁 mode，仍解析绑定 */
const ROUTING_STEPS = [
  { key: 'L0', title: '硬绑定', detail: '#工作流 / $专家 / @Skill → 立刻锁定' },
  { key: '规则', title: '统一规则', detail: 'Catalog 按 priority 首命中' },
  { key: 'L3', title: '意图分类', detail: '未命中 → intent.classifier' },
  { key: '执行', title: '分发', detail: 'workflow / plan / peer / react' },
] as const

/** 发给模型的消息顺序（从上到下） */
const MESSAGE_STACK = [
  {
    role: 'system',
    title: 'Catalog 提示词',
    lines: [
      'system-prompt → mode-overlay → 场景/skill/HITL（按路径）',
      'memory.layer-prompt + LTM/MTM 摘要 → scope → 节点 prompt',
    ],
  },
  {
    role: 'system',
    title: 'STM 边界',
    lines: ['一条说明：「以下历史仅供指代」'],
  },
  {
    role: 'user / assistant',
    title: 'STM 历史轮',
    lines: ['同会话已结束轮次，按原角色还原'],
  },
  {
    role: 'user',
    title: '当前提问',
    lines: ['可选 injected 上下文 → 带「当前提问」标记的正文'],
  },
] as const

/** 各路径叠层对照（一行看清差异） */
const AGENT_ROWS = [
  {
    key: 'MAIN',
    title: '主 Agent',
    when: 'react / 底栏强制',
    stack: '全量记忆 + mode-overlay.react + 可选 react-prompt / skill / HITL',
  },
  {
    key: 'spawn',
    title: 'spawn 子任务',
    when: '元工具委派',
    stack: 'system + subagent overlay；无会话记忆；user = 子任务 prompt',
  },
  {
    key: 'wf-agent',
    title: 'DAG · agent 节点',
    when: 'Workflow / Plan',
    stack: 'system + 节点 systemOverlay / skill；上游靠 context 注入',
  },
  {
    key: 'expert',
    title: '多专家 Hub',
    when: 'peer-collab',
    stack: '检索阶段 ReAct 收材料 → 发言/综合走 Gateway（speak / synthesis 模板）',
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
        <p class="callout">
          <span class="callout-k">强制</span>
          底栏执行模式 ≠ 自动 → 锁死 ExecutionMode，仍走 L0 / 同 mode 规则 / L3 解析 skill、react 提示词、workflowId。
        </p>
      </section>

      <!-- 二、消息落点 -->
      <section class="block">
        <h4 class="block-title">二、提示词落在哪</h4>
        <p class="block-lead">
          Catalog 正文进多条 <code>system</code>；只有历史轮与当前提问走
          <code>user</code> / <code>assistant</code>。
        </p>
        <ol class="stack">
          <li
            v-for="(seg, i) in MESSAGE_STACK"
            :key="seg.title"
            class="stack-row"
          >
            <span class="stack-idx">{{ i + 1 }}</span>
            <NTag size="tiny" :bordered="false" round class="role-tag">
              {{ seg.role }}
            </NTag>
            <div class="stack-body">
              <strong>{{ seg.title }}</strong>
              <p v-for="line in seg.lines" :key="line">{{ line }}</p>
            </div>
          </li>
        </ol>
      </section>

      <!-- 三、路径对照 -->
      <section class="block">
        <h4 class="block-title">三、各路径叠哪些</h4>
        <p class="block-lead">
          子路径默认无 LTM/MTM/STM；上游事实靠注入，不靠会话记忆。
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
          <span class="callout-k">运营</span>
          系统配置 = system 层；路由规则 = 路径 + 可选
          <code>reactPromptId</code>；React 提示词 = 仅 MAIN 场景叠加。
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

/* —— 消息栈 —— */
.stack {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-md);
  overflow: hidden;
}

.stack-row {
  display: grid;
  grid-template-columns: 28px auto 1fr;
  gap: 10px;
  align-items: start;
  padding: 12px 14px;
  border-bottom: 1px solid var(--sun-border);
}

.stack-row:last-child {
  border-bottom: none;
}

.stack-idx {
  font-size: 12px;
  font-weight: 700;
  color: var(--sun-text-secondary);
  line-height: 22px;
}

.role-tag {
  --n-color: transparent !important;
  --n-text-color: var(--sun-text) !important;
  border: 1px solid var(--sun-border) !important;
  font-weight: 600;
  margin-top: 1px;
}

.stack-body {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.stack-body strong {
  font-size: 13px;
  color: var(--sun-text);
}

.stack-body p {
  margin: 0;
  font-size: 12px;
  line-height: 1.5;
  color: var(--sun-text-secondary);
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

  .stack-row {
    grid-template-columns: 24px 1fr;
  }

  .stack-row .role-tag {
    grid-column: 2;
  }

  .stack-body {
    grid-column: 2;
  }
}
</style>
