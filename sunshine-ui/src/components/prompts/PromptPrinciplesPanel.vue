<script setup lang="ts">
import { inject } from 'vue'
import { NButton, NTag } from 'naive-ui'
import { PROMPTS_PAGE_KEY, type PromptsPageApi } from '../../composables/usePromptsPage'

const page = inject(PROMPTS_PAGE_KEY) as PromptsPageApi

const ROUTING_STEPS = [
  {
    key: 'L0',
    title: '硬绑定',
    detail: '消息内 #工作流 / $专家 / @Skill 优先锁定，不走后续规则。',
  },
  {
    key: '规则',
    title: '统一规则引擎',
    detail: 'Catalog 路由规则按优先级首命中（structural / peer_phrase / regex…）。',
  },
  {
    key: 'L3',
    title: '意图分类',
    detail: '未命中规则时，intent.classifier 输出执行模式 JSON。',
  },
  {
    key: '执行',
    title: '分发执行',
    detail: 'workflow · plan-workflow · peer-collab · react。',
  },
] as const

/** role: 实际落在哪类消息里 */
const MESSAGE_FLOW = [
  {
    role: 'system',
    roleLabel: 'system',
    title: 'Catalog 提示词层（多段叠加）',
    items: [
      'system-prompt（人设）',
      'mode-overlay.*（模式行为）',
      'react-prompt / skill overlay / HITL / restart（按路径可选）',
      'memory.layer-prompt + LTM/MTM 摘要',
      'scope-prompt（范围）',
      'workflow 节点 prompt（若有）',
    ],
    note: '运营页「系统配置 / React 场景」维护的正文，几乎都进 system，不是拼进 user/assistant 正文。',
  },
  {
    role: 'user/assistant',
    roleLabel: 'user · assistant',
    title: '短时记忆 STM（历史轮次）',
    items: [
      '同会话已结束轮次：按原角色还原为 user / assistant 消息',
      'STM 边界说明仍是一条 system（告知模型「以下仅供指代」）',
    ],
    note: '唯一会大量出现真实 user/assistant 对话形态的部分。',
  },
  {
    role: 'user',
    roleLabel: 'user',
    title: '当前提问（本轮）',
    items: [
      '可选 injected 上下文（仍以 user 消息注入）',
      '带「当前提问」标记的用户正文（本轮唯一作答目标）',
    ],
    note: '永远落在消息列表末尾的 user；续跑时可能再跟一条 partial assistant。',
  },
] as const

/** 各 Agent 角色实际叠哪些（对照 AgentRunRequest / PromptComposer / ReActAgentFactory） */
const AGENT_STACKS = [
  {
    key: 'MAIN',
    title: '主 Agent（顶层 ReAct）',
    when: '路由 mode=react / 底栏强制自主推理',
    sysPrompt: [
      'Catalog system-prompt（AgentScope sysPrompt 底座）',
    ],
    composer: [
      'mode-overlay.react',
      'react-prompt.*（仅当路由 params.reactPromptId 有值）',
      'mode-overlay.react-restart（续跑/重跑时）',
      'hitl.agent-prompt（HITL 开启时）',
      'Skill overlay（L0 @ 或路由 skillId）',
      'memory.layer-prompt + LTM/MTM + STM 历史轮',
      'scope-prompt',
    ],
    userTail: ['injectedBlocks（若有）', '当前用户提问'],
    note: '唯一带全量会话记忆；可 spawn_subagent、TaskBoard。',
  },
  {
    key: 'spawn',
    title: 'spawn_subagent 子任务',
    when: '主 Agent 调用元工具委派；禁止嵌套 spawn',
    sysPrompt: [
      'system-prompt + mode-overlay.subagent（拼进同一段 sysPrompt）',
    ],
    composer: [
      'mode-overlay.react',
      'scope-prompt',
      '无 react-prompt / 无 HITL restart / 默认无 skill（除非后续扩展）',
      '记忆强制为空（MemoryContext.forSubAgent）',
    ],
    userTail: ['子任务 prompt 作为本轮 user（无 STM）'],
    note: '工具集与主 Agent 同租户 ReAct 工具；时间线进主卡 subSteps。',
  },
  {
    key: 'wf-agent',
    title: 'Workflow / Plan · agent 节点',
    when: '静态或动态 DAG 的 agent 节点',
    sysPrompt: [
      'system-prompt + 节点 params.systemOverlay（有则追加）',
    ],
    composer: [
      'mode-overlay.react',
      'Skill overlay（params.skill）',
      'scope-prompt',
      '记忆为空；上游材料不走 LTM/STM',
    ],
    userTail: [
      'params.context → injected user 块',
      'params.query（缺省用 start.userQuery）',
    ],
    note: '子 think/tool 不上主时间线；结果由下游 answer 合成。',
  },
  {
    key: 'expert-gather',
    title: '多专家 · 检索阶段（Hub）',
    when: 'peer-collab 阶段1：专家调工具收集材料',
    sysPrompt: [
      'system-prompt + 专家目录 systemPrompt（作 systemOverlay）',
    ],
    composer: [
      'mode-overlay.react + 专家 primarySkill overlay',
      'scope-prompt；记忆为空',
      'peer.gather-instruction 注入为额外上下文',
    ],
    userTail: ['用户问题 + 讨论上下文块'],
    note: '只产出检索摘要，不写完整发言稿。',
  },
  {
    key: 'expert-speak',
    title: '多专家 · 正式发言',
    when: 'peer-collab 阶段2：Gateway 直链流式',
    sysPrompt: [
      '走 DIRECT Composer：system-prompt + mode-overlay.direct',
      'Skill overlay + 专家 systemPrompt（nodePrompt 层）+ scope',
    ],
    composer: [
      '记忆为空',
      '正文模板：Catalog peer.speak-prompt（占位符替换后作为 user）',
    ],
    userTail: ['speak-prompt 渲染结果（含 transcript / gatheredContext）'],
    note: '不经 ReAct 工具循环；与综合作答同通路。',
  },
  {
    key: 'synth',
    title: '多专家 · 综合作答',
    when: 'Hub 结束后面向用户',
    sysPrompt: ['不走六层 Composer'],
    composer: [
      '仅 Catalog peer.synthesis-prompt 替换 {userQuery}/{transcript}',
      'LlmGatewayClient 直链流式 → message.content',
    ],
    userTail: ['无单独 user 层；模板内已含问题与讨论记录'],
    note: '无 generate 时间线步；禁止截断模型 token。',
  },
] as const
</script>

<template>
  <main class="detail-panel">
    <div class="detail-toolbar">
      <div class="detail-title-block">
        <h3 class="detail-heading">原理分析</h3>
        <p class="detail-sub">全局说明：与左侧选中条目无关</p>
      </div>
      <NButton size="small" round quaternary @click="page.closePrinciples()">
        返回编辑
      </NButton>
    </div>

    <div class="detail-scroll">
      <section class="block">
        <h4 class="block-title">一、意图识别怎么走</h4>
        <p class="block-lead">
          用户消息进入编排后按固定链路选执行方式；底栏强制模式时整段绕过规则与 L3。
        </p>
        <div class="flow">
          <div
            v-for="(step, index) in ROUTING_STEPS"
            :key="step.key"
            class="flow-item"
          >
            <div class="flow-card">
              <span class="flow-badge">{{ step.key }}</span>
              <strong class="flow-title">{{ step.title }}</strong>
              <p class="flow-detail">{{ step.detail }}</p>
            </div>
            <div v-if="index < ROUTING_STEPS.length - 1" class="flow-arrow" aria-hidden="true">↓</div>
          </div>
        </div>
        <div class="note-row">
          <span class="note-label">旁路</span>
          <span class="note-text">executionPreference ≠ auto → ForcedExecutionRouter 直接指定模式。</span>
        </div>
      </section>

      <section class="block">
        <h4 class="block-title">二、提示词叠到哪里？</h4>
        <p class="block-lead">
          <strong class="em">结论：Catalog 里的系统/模式/场景提示词，叠加进多条
          <code>role=system</code> 消息</strong>；
          不是改写进 user/assistant 的「聊天正文」。只有 STM 历史轮与「当前提问」走 user/assistant。
        </p>

        <div class="msg-flow">
          <div
            v-for="(seg, index) in MESSAGE_FLOW"
            :key="seg.role + seg.title"
            class="msg-seg"
          >
            <div class="msg-seg-head">
              <NTag size="tiny" :bordered="false" round class="role-tag">
                {{ seg.roleLabel }}
              </NTag>
              <strong class="msg-seg-title">{{ seg.title }}</strong>
            </div>
            <ul class="msg-seg-list">
              <li v-for="item in seg.items" :key="item">{{ item }}</li>
            </ul>
            <p class="msg-seg-note">{{ seg.note }}</p>
            <div
              v-if="index < MESSAGE_FLOW.length - 1"
              class="flow-arrow"
              aria-hidden="true"
            >↓</div>
          </div>
        </div>

        <div class="note-row">
          <span class="note-label">ReAct</span>
          <span class="note-text">
            底座人设进 AgentScope <code>sysPrompt</code>；Composer 再追加其余 system 层与 STM/当前 user。
            缺层跳过；禁止对模型输出做截断/摘要兜底。
          </span>
        </div>
      </section>

      <section class="block">
        <h4 class="block-title">三、各子 Agent 叠哪些？</h4>
        <p class="block-lead">
          子路径一律 <code>MemoryContext.forSubAgent()</code>（无 LTM/MTM/STM）；人设仍在，会话记忆不带，靠
          <code>injectedBlocks</code> / 节点 context / 专家材料传入上游事实。
        </p>
        <div class="agent-grid">
          <article
            v-for="agent in AGENT_STACKS"
            :key="agent.key"
            class="agent-card"
          >
            <div class="agent-card-head">
              <NTag size="tiny" :bordered="false" round class="role-tag">{{ agent.key }}</NTag>
              <strong class="agent-title">{{ agent.title }}</strong>
            </div>
            <p class="agent-when">{{ agent.when }}</p>
            <div class="agent-cols">
              <div>
                <span class="agent-col-label">sysPrompt 底座</span>
                <ul class="msg-seg-list compact">
                  <li v-for="item in agent.sysPrompt" :key="item">{{ item }}</li>
                </ul>
              </div>
              <div>
                <span class="agent-col-label">Composer 追加</span>
                <ul class="msg-seg-list compact">
                  <li v-for="item in agent.composer" :key="item">{{ item }}</li>
                </ul>
              </div>
              <div>
                <span class="agent-col-label">尾部 user</span>
                <ul class="msg-seg-list compact">
                  <li v-for="item in agent.userTail" :key="item">{{ item }}</li>
                </ul>
              </div>
            </div>
            <p class="msg-seg-note">{{ agent.note }}</p>
          </article>
        </div>
        <p class="block-foot">
          运营：「系统配置」维护各 system 层；「路由规则」决定路径与可选
          <code>reactPromptId</code>；「React 提示词」仅 MAIN 场景 overlay；子 Agent 叠加看
          <code>mode-overlay.subagent</code> / 节点 <code>systemOverlay</code> / 专家目录。
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
  margin: 0 0 8px;
  font-size: 14px;
  font-weight: 600;
  color: var(--sun-text);
}

.block-lead,
.block-foot {
  margin: 0 0 14px;
  font-size: 13px;
  line-height: 1.55;
  color: var(--sun-text-secondary);
}

.block-foot {
  margin: 14px 0 0;
}

.block-lead code,
.block-foot code {
  font-size: 12px;
  color: var(--sun-text);
}

.em {
  color: var(--sun-text);
  font-weight: 600;
}

.flow,
.msg-flow {
  display: flex;
  flex-direction: column;
  align-items: stretch;
}

.flow-item {
  display: flex;
  flex-direction: column;
  align-items: stretch;
}

.flow-card {
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-md);
  padding: 12px 14px;
  display: grid;
  grid-template-columns: auto 1fr;
  grid-template-rows: auto auto;
  column-gap: 10px;
  row-gap: 4px;
}

.flow-badge {
  grid-row: 1 / span 2;
  align-self: center;
  min-width: 40px;
  text-align: center;
  font-size: 12px;
  font-weight: 700;
  color: var(--sun-text);
  border: 1px solid var(--sun-border);
  border-radius: 6px;
  padding: 6px 4px;
}

.flow-title {
  font-size: 13px;
  color: var(--sun-text);
}

.flow-detail {
  margin: 0;
  grid-column: 2;
  font-size: 12px;
  line-height: 1.5;
  color: var(--sun-text-secondary);
}

.flow-arrow {
  text-align: center;
  color: var(--sun-text-secondary);
  font-size: 14px;
  line-height: 1.4;
  padding: 2px 0;
  opacity: 0.7;
}

.note-row {
  margin-top: 12px;
  display: flex;
  gap: 10px;
  align-items: flex-start;
  padding: 10px 12px;
  border: 1px dashed var(--sun-border);
  border-radius: var(--radius-md);
}

.note-label {
  flex-shrink: 0;
  font-size: 12px;
  font-weight: 600;
  color: var(--sun-text);
}

.note-text {
  font-size: 12px;
  line-height: 1.5;
  color: var(--sun-text-secondary);
}

.msg-seg {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.msg-seg-head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.role-tag {
  --n-color: transparent !important;
  --n-text-color: var(--sun-text) !important;
  border: 1px solid var(--sun-border) !important;
  font-weight: 600;
}

.msg-seg-title {
  font-size: 13px;
  color: var(--sun-text);
}

.msg-seg-list {
  margin: 0;
  padding: 10px 14px 10px 28px;
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-md);
  font-size: 12px;
  line-height: 1.55;
  color: var(--sun-text-secondary);
}

.msg-seg-list li + li {
  margin-top: 4px;
}

.msg-seg-note {
  margin: 0;
  font-size: 12px;
  line-height: 1.45;
  color: var(--sun-text-secondary);
}

.agent-grid {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.agent-card {
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-md);
  padding: 12px 14px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.agent-card-head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.agent-title {
  font-size: 13px;
  color: var(--sun-text);
}

.agent-when {
  margin: 0;
  font-size: 12px;
  color: var(--sun-text-secondary);
  line-height: 1.45;
}

.agent-cols {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
}

.agent-col-label {
  display: block;
  margin-bottom: 6px;
  font-size: 11px;
  font-weight: 600;
  color: var(--sun-text);
}

.msg-seg-list.compact {
  padding: 8px 10px 8px 22px;
}

@media (max-width: 960px) {
  .agent-cols {
    grid-template-columns: 1fr;
  }
}
</style>
