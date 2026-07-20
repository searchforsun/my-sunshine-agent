<script setup lang="ts">
import { computed, inject } from 'vue'
import {
  NButton,
  NDropdown,
  NEmpty,
  NForm,
  NFormItem,
  NIcon,
  NInput,
  NInputNumber,
  NSelect,
  NSpin,
  NTag,
} from 'naive-ui'
import { EllipsisHorizontal } from '@vicons/ionicons5'
import { shortPromptId } from '../../api/prompts'
import { PROMPTS_PAGE_KEY, type PromptsPageApi } from '../../composables/usePromptsPage'
import ConfigFieldHelp from '../knowledge/ConfigFieldHelp.vue'
import { routingFieldHelp } from './routingFieldHelp'

const page = inject(PROMPTS_PAGE_KEY) as PromptsPageApi

const matchTypeOptions = [
  { label: '多步跨域', value: 'structural' },
  { label: '协作句式', value: 'peer_phrase' },
  { label: '正则匹配', value: 'regex' },
]

const matchOptions = [
  { label: '任一命中', value: 'any' },
  { label: '全部命中', value: 'all' },
]

const planModeOptions = [
  { label: '工作流', value: 'workflow' },
  { label: '动态规划', value: 'plan-workflow' },
  { label: '多专家协作', value: 'peer-collab' },
  { label: '自主推理', value: 'react' },
]

const patternsText = computed({
  get: () => (page.routingForm.patterns ?? []).join('\n'),
  set: (v: string) => {
    page.routingForm.patterns = v
      .split('\n')
      .map(s => s.trim())
      .filter(Boolean)
  },
})

const domainGroupsText = computed({
  get: () => {
    const groups = page.routingForm.domainGroups ?? {}
    return Object.entries(groups)
      .map(([name, words]) => `${name}: ${(words ?? []).join(', ')}`)
      .join('\n')
  },
  set: (v: string) => {
    const out: Record<string, string[]> = {}
    for (const line of v.split('\n')) {
      const trimmed = line.trim()
      if (!trimmed) continue
      const idx = trimmed.indexOf(':')
      if (idx <= 0) continue
      const name = trimmed.slice(0, idx).trim()
      const words = trimmed
        .slice(idx + 1)
        .split(/[,，]/)
        .map(s => s.trim())
        .filter(Boolean)
      if (name) out[name] = words
    }
    page.routingForm.domainGroups = out
  },
})

const paramsText = computed({
  get: () => {
    const params = page.routingForm.plan?.params ?? {}
    return Object.entries(params)
      .filter(([k]) => k !== 'reactPromptId')
      .map(([k, val]) => `${k}=${val}`)
      .join('\n')
  },
  set: (v: string) => {
    if (!page.routingForm.plan) {
      page.routingForm.plan = { mode: 'react', workflowId: null, params: {} }
    }
    const existingReactPromptId = page.routingForm.plan.params?.reactPromptId
    const params: Record<string, string> = {}
    for (const line of v.split('\n')) {
      const trimmed = line.trim()
      if (!trimmed) continue
      const idx = trimmed.indexOf('=')
      if (idx <= 0) continue
      params[trimmed.slice(0, idx).trim()] = trimmed.slice(idx + 1).trim()
    }
    if (existingReactPromptId) params.reactPromptId = existingReactPromptId
    page.routingForm.plan.params = params
  },
})

function ensurePlan() {
  if (!page.routingForm.plan) {
    page.routingForm.plan = { mode: 'react', workflowId: null, params: {} }
  }
  if (!page.routingForm.plan.params) {
    page.routingForm.plan.params = {}
  }
  return page.routingForm.plan
}

const planMode = computed({
  get: () => ensurePlan().mode ?? 'react',
  set: (v: string) => { ensurePlan().mode = v },
})

const workflowId = computed({
  get: () => ensurePlan().workflowId ?? '',
  set: (v: string) => { ensurePlan().workflowId = v.trim() || null },
})

const reactPromptId = computed({
  get: () => ensurePlan().params?.reactPromptId || null,
  set: (v: string | null) => {
    const plan = ensurePlan()
    if (!plan.params) plan.params = {}
    if (v) plan.params.reactPromptId = v
    else delete plan.params.reactPromptId
  },
})

const matchType = computed(() => page.routingForm.matchType || 'regex')
const showMatch = computed(() => matchType.value === 'regex')
const showDomainGroups = computed(() => matchType.value === 'structural')
const showWorkflowId = computed(() => planMode.value === 'workflow')
const showReactPrompt = computed(() => planMode.value === 'react')
const showPlanParams = computed(() =>
  planMode.value === 'workflow' || planMode.value === 'react',
)
</script>

<template>
  <main v-if="page.detail && page.detail.kind === 'routing-rule'" class="detail-panel">
    <div class="detail-toolbar">
      <div class="detail-title-block">
        <h3 class="detail-heading">{{ page.detail.displayName }}</h3>
        <span class="detail-id">{{ shortPromptId(page.detail.id) }}</span>
      </div>
      <div class="detail-actions">
        <div v-if="page.showVersionSelect" class="version-row">
          <span class="version-label">当前版本</span>
          <NTag
            v-if="page.selectedVersionStatus"
            size="small"
            :bordered="false"
            round
            :type="page.detailVersionTagType"
          >
            {{ page.selectedVersionStatusLabel }}
          </NTag>
          <NSelect
            v-model:value="page.selectedVersion"
            :options="page.versionOptions"
            size="small"
            class="version-select"
            placeholder="选择版本"
            :disabled="page.isActionBusy"
            :menu-props="{ class: 'version-select-menu' }"
            @update:value="page.onVersionSelected"
          />
        </div>
        <NButton
          v-if="page.showPrimaryPublishButton"
          size="small"
          round
          type="primary"
          class="action-btn"
          :loading="page.publishing"
          :disabled="page.isActionBusy"
          @click="page.handlePrimaryPublish()"
        >
          {{ page.primaryPublishLabel }}
        </NButton>
        <NDropdown
          trigger="click"
          size="small"
          :options="page.moreMenuOptions"
          :disabled="page.isActionBusy"
          @select="page.handleMoreMenuSelect"
        >
          <NButton
            size="small"
            quaternary
            class="more-menu-btn"
            title="版本操作"
            aria-label="版本与元数据操作"
            :loading="page.isActionBusy"
            :disabled="page.isActionBusy"
          >
            <template #icon><NIcon :component="EllipsisHorizontal" :size="16" /></template>
          </NButton>
        </NDropdown>
      </div>
    </div>

    <div v-if="page.routingWarnings.length" class="warn-bar">
      <p v-for="(w, i) in page.routingWarnings" :key="i">{{ w.message }}</p>
    </div>

    <NSpin :show="page.detailLoading" class="detail-spin">
      <div class="detail-scroll">
        <NForm class="detail-form" label-placement="top" :show-feedback="false">
          <section class="form-section">
            <header class="form-section-head">
              <h4 class="form-section-title">基本信息</h4>
              <NButton
                v-if="page.showSaveDraftButton"
                size="small"
                round
                secondary
                :loading="page.saving || page.validating"
                :disabled="page.isActionBusy"
                @click="page.saveRoutingRule()"
              >
                保存草稿
              </NButton>
            </header>
            <div class="form-grid">
              <NFormItem>
                <template #label>
                  <span class="field-label-row">展示名</span>
                </template>
                <NInput
                  v-model:value="page.editDisplayName"
                  class="sun-field"
                  :disabled="!page.isContentEditable || page.isActionBusy"
                />
              </NFormItem>
              <NFormItem>
                <template #label>
                  <span class="field-label-row">
                    优先级
                    <ConfigFieldHelp :text="routingFieldHelp('priority')" />
                  </span>
                </template>
                <NInputNumber
                  v-model:value="page.editPriority"
                  class="sun-field"
                  :min="0"
                  :show-button="false"
                  :disabled="!page.isContentEditable || page.isActionBusy"
                />
              </NFormItem>
            </div>
            <NFormItem label="描述">
              <NInput
                v-model:value="page.editDescription"
                class="sun-field"
                type="textarea"
                :autosize="{ minRows: 2, maxRows: 4 }"
                :disabled="!page.isContentEditable || page.isActionBusy"
              />
            </NFormItem>
          </section>

          <section class="form-section">
            <header class="form-section-head">
              <h4 class="form-section-title">匹配条件</h4>
            </header>
            <div class="form-grid" :class="{ 'form-grid-single': !showMatch }">
              <NFormItem>
                <template #label>
                  <span class="field-label-row">
                    匹配类型
                    <ConfigFieldHelp :text="routingFieldHelp('matchType')" />
                  </span>
                </template>
                <NSelect
                  v-model:value="page.routingForm.matchType"
                  class="sun-field"
                  :options="matchTypeOptions"
                  :consistent-menu-width="false"
                  :disabled="!page.isContentEditable || page.isActionBusy"
                />
              </NFormItem>
              <NFormItem v-if="showMatch">
                <template #label>
                  <span class="field-label-row">
                    命中方式
                    <ConfigFieldHelp :text="routingFieldHelp('match')" />
                  </span>
                </template>
                <NSelect
                  v-model:value="page.routingForm.match"
                  class="sun-field"
                  :options="matchOptions"
                  :disabled="!page.isContentEditable || page.isActionBusy"
                />
              </NFormItem>
            </div>
            <NFormItem>
              <template #label>
                <span class="field-label-row">
                  匹配模式
                  <ConfigFieldHelp :text="routingFieldHelp('patterns')" />
                </span>
              </template>
              <NInput
                v-model:value="patternsText"
                class="sun-field sun-field-grow mono"
                type="textarea"
                :autosize="{ minRows: 4, maxRows: 12 }"
                placeholder="每行一条正则，如：是否合规"
                :disabled="!page.isContentEditable || page.isActionBusy"
              />
            </NFormItem>
            <template v-if="showDomainGroups">
              <NFormItem>
                <template #label>
                  <span class="field-label-row">
                    域关键词组
                    <ConfigFieldHelp :text="routingFieldHelp('domainGroups')" />
                  </span>
                </template>
                <NInput
                  v-model:value="domainGroupsText"
                  class="sun-field sun-field-grow mono"
                  type="textarea"
                  :autosize="{ minRows: 3, maxRows: 10 }"
                  placeholder="knowledge: 制度, 检索"
                  :disabled="!page.isContentEditable || page.isActionBusy"
                />
              </NFormItem>
              <NFormItem>
                <template #label>
                  <span class="field-label-row">
                    最少命中域数
                    <ConfigFieldHelp :text="routingFieldHelp('minDomainGroups')" />
                  </span>
                </template>
                <NInputNumber
                  v-model:value="page.routingForm.minDomainGroups"
                  class="sun-field"
                  :min="1"
                  :show-button="false"
                  :disabled="!page.isContentEditable || page.isActionBusy"
                />
              </NFormItem>
            </template>
          </section>

          <section class="form-section">
            <header class="form-section-head">
              <h4 class="form-section-title">目标 Plan</h4>
            </header>
            <NFormItem>
              <template #label>
                <span class="field-label-row">
                  执行模式
                  <ConfigFieldHelp :text="routingFieldHelp('mode')" />
                </span>
              </template>
              <NSelect
                v-model:value="planMode"
                class="sun-field"
                :options="planModeOptions"
                :disabled="!page.isContentEditable || page.isActionBusy"
              />
            </NFormItem>
            <NFormItem v-if="showWorkflowId">
              <template #label>
                <span class="field-label-row">
                  工作流 ID
                  <ConfigFieldHelp :text="routingFieldHelp('workflowId')" />
                </span>
              </template>
              <NInput
                v-model:value="workflowId"
                class="sun-field"
                placeholder="如 finance-list"
                :disabled="!page.isContentEditable || page.isActionBusy"
              />
            </NFormItem>
            <NFormItem v-if="showReactPrompt">
              <template #label>
                <span class="field-label-row">
                  React 提示词
                  <ConfigFieldHelp :text="routingFieldHelp('reactPromptId')" />
                </span>
              </template>
              <NSelect
                v-model:value="reactPromptId"
                class="sun-field"
                clearable
                filterable
                placeholder="可选：绑定 React 场景"
                :options="page.reactPromptOptions"
                :consistent-menu-width="false"
                :disabled="!page.isContentEditable || page.isActionBusy"
              />
            </NFormItem>
            <NFormItem v-if="showPlanParams">
              <template #label>
                <span class="field-label-row">
                  附加参数
                  <ConfigFieldHelp :text="routingFieldHelp('params')" />
                </span>
              </template>
              <NInput
                v-model:value="paramsText"
                class="sun-field mono"
                type="textarea"
                :autosize="{ minRows: 2, maxRows: 6 }"
                placeholder="每行 key=value，如：status=pending"
                :disabled="!page.isContentEditable || page.isActionBusy"
              />
            </NFormItem>
          </section>
        </NForm>
      </div>
    </NSpin>
  </main>
  <main v-else class="detail-panel detail-empty">
    <NEmpty description="选择一条路由规则" />
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

.detail-empty {
  display: flex;
  align-items: center;
  justify-content: center;
}

.detail-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
  flex-wrap: wrap;
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

.detail-id {
  font-size: 12px;
  color: var(--sun-text-muted);
  font-family: var(--sun-font-mono, monospace);
}

.detail-actions {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  flex-shrink: 0;
  min-height: 28px;
}

.version-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.version-label {
  font-size: 13px;
  color: var(--sun-text-secondary);
  white-space: nowrap;
}

.version-select {
  width: min(228px, 44vw);
}

.version-select :deep(.n-base-selection) {
  --n-color: var(--sun-black) !important;
  --n-color-active: var(--sun-black) !important;
  --n-color-disabled: var(--sun-black) !important;
  --n-text-color: var(--sun-text) !important;
  --n-text-color-disabled: var(--sun-text-muted) !important;
  --n-placeholder-color: var(--sun-text-muted) !important;
  --n-arrow-color: var(--sun-text-secondary) !important;
  --n-border: 1px solid var(--sun-border) !important;
  --n-border-hover: 1px solid var(--sun-border-light) !important;
  --n-border-active: 1px solid var(--sun-border-light) !important;
  --n-border-focus: 1px solid var(--sun-border-light) !important;
  --n-box-shadow-focus: none !important;
  --n-box-shadow-hover: none !important;
  --n-box-shadow-active: none !important;
}

.more-menu-btn {
  padding: 0 6px;
}

.warn-bar {
  flex-shrink: 0;
  padding: 10px 18px;
  border-bottom: 1px solid var(--sun-border);
  background: transparent;
  border-left: 3px solid #e6a23c;
  color: #e6a23c;
  font-size: 13px;
}

.warn-bar p {
  margin: 0 0 4px;
}

.warn-bar p:last-child {
  margin-bottom: 0;
}

.detail-spin {
  flex: 1;
  min-height: 0;
}

.detail-spin :deep(.n-spin-content) {
  height: 100%;
}

.detail-scroll {
  height: 100%;
  overflow-y: auto;
  padding: 22px;
}

.detail-form {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.detail-form :deep(.n-form-item) {
  margin-bottom: 0;
}

.detail-form :deep(.n-form-item-label) {
  color: var(--sun-text-secondary);
  font-size: 13px;
  font-weight: 500;
  padding-bottom: 8px;
}

.form-section {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.form-section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.form-section-title {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  color: var(--sun-text);
}

.form-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}

.form-grid-single {
  grid-template-columns: 1fr;
}

.field-label-row {
  display: inline-flex;
  align-items: center;
  gap: 2px;
}

.mono :deep(.n-input__textarea-el),
.mono :deep(.n-input__input-el) {
  font-family: var(--sun-font-mono, monospace);
  font-size: 12px;
}

.action-btn {
  --n-color: var(--sun-accent) !important;
}
</style>
