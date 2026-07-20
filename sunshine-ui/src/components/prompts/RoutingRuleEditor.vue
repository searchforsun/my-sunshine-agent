<script setup lang="ts">
import { computed, inject } from 'vue'
import {
  NButton,
  NEmpty,
  NForm,
  NFormItem,
  NInput,
  NInputNumber,
  NSelect,
  NSpace,
  NSpin,
} from 'naive-ui'
import { PROMPTS_PAGE_KEY, type PromptsPageApi } from '../../composables/usePromptsPage'

const page = inject(PROMPTS_PAGE_KEY) as PromptsPageApi

const matchTypeOptions = [
  { label: 'structural（多步跨域）', value: 'structural' },
  { label: 'peer_phrase（协作句式）', value: 'peer_phrase' },
  { label: 'regex（正则）', value: 'regex' },
]

const matchOptions = [
  { label: 'any（任一命中）', value: 'any' },
  { label: 'all（全部命中）', value: 'all' },
]

const planModeOptions = [
  { label: 'workflow', value: 'workflow' },
  { label: 'plan-workflow', value: 'plan-workflow' },
  { label: 'peer-collab', value: 'peer-collab' },
  { label: 'react', value: 'react' },
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
      .map(([k, val]) => `${k}=${val}`)
      .join('\n')
  },
  set: (v: string) => {
    if (!page.routingForm.plan) {
      page.routingForm.plan = { mode: 'react', workflowId: null, params: {} }
    }
    const params: Record<string, string> = {}
    for (const line of v.split('\n')) {
      const trimmed = line.trim()
      if (!trimmed) continue
      const idx = trimmed.indexOf('=')
      if (idx <= 0) continue
      params[trimmed.slice(0, idx).trim()] = trimmed.slice(idx + 1).trim()
    }
    page.routingForm.plan.params = params
  },
})

function ensurePlan() {
  if (!page.routingForm.plan) {
    page.routingForm.plan = { mode: 'react', workflowId: null, params: {} }
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
</script>

<template>
  <main v-if="page.detail && page.detail.kind === 'routing-rule'" class="detail-panel">
    <div class="detail-toolbar">
      <div class="detail-toolbar-text">
        <h3 class="detail-heading">{{ page.detail.displayName }}</h3>
        <span class="detail-id">{{ page.detail.id }}</span>
      </div>
      <NSpace :size="8">
        <NButton
          size="small"
          round
          secondary
          :loading="page.saving || page.validating"
          @click="page.saveRoutingRule()"
        >
          保存草稿
        </NButton>
        <NButton
          size="small"
          round
          type="primary"
          class="action-btn"
          :loading="page.publishing"
          :disabled="!page.hasDraft"
          @click="page.handlePublish()"
        >
          发布最新草稿
        </NButton>
      </NSpace>
    </div>

    <div v-if="page.routingWarnings.length" class="warn-bar">
      <p v-for="(w, i) in page.routingWarnings" :key="i">{{ w.message }}</p>
    </div>

    <NSpin :show="page.detailLoading" class="detail-spin">
      <div class="detail-scroll">
        <NForm class="detail-form" label-placement="top" :show-feedback="false">
          <section class="form-section">
            <header class="form-section-head">
              <h4 class="form-section-title">规则元数据</h4>
            </header>
            <div class="form-grid">
              <NFormItem label="展示名">
                <NInput v-model:value="page.editDisplayName" class="sun-field" />
              </NFormItem>
              <NFormItem label="优先级（越大越优先）">
                <NInputNumber
                  v-model:value="page.editPriority"
                  class="sun-field"
                  :min="0"
                  :show-button="false"
                />
              </NFormItem>
            </div>
            <NFormItem label="描述">
              <NInput
                v-model:value="page.editDescription"
                class="sun-field"
                type="textarea"
                :autosize="{ minRows: 2, maxRows: 4 }"
              />
            </NFormItem>
            <NFormItem label="变更说明">
              <NInput v-model:value="page.editChangeNote" class="sun-field" placeholder="可选" />
            </NFormItem>
          </section>

          <section class="form-section">
            <header class="form-section-head">
              <h4 class="form-section-title">匹配条件</h4>
            </header>
            <div class="form-grid">
              <NFormItem label="matchType">
                <NSelect
                  v-model:value="page.routingForm.matchType"
                  class="sun-field"
                  :options="matchTypeOptions"
                  :consistent-menu-width="false"
                />
              </NFormItem>
              <NFormItem label="match">
                <NSelect
                  v-model:value="page.routingForm.match"
                  class="sun-field"
                  :options="matchOptions"
                />
              </NFormItem>
            </div>
            <NFormItem label="patterns（每行一条）">
              <NInput
                v-model:value="patternsText"
                class="sun-field sun-field-grow mono"
                type="textarea"
                :autosize="{ minRows: 4, maxRows: 12 }"
                placeholder="是否合规"
              />
            </NFormItem>
            <NFormItem label="domainGroups（每行：域名: 词1, 词2）">
              <NInput
                v-model:value="domainGroupsText"
                class="sun-field sun-field-grow mono"
                type="textarea"
                :autosize="{ minRows: 3, maxRows: 10 }"
                placeholder="knowledge: 制度, 检索"
              />
            </NFormItem>
            <NFormItem label="minDomainGroups">
              <NInputNumber
                v-model:value="page.routingForm.minDomainGroups"
                class="sun-field"
                :min="1"
                :show-button="false"
              />
            </NFormItem>
          </section>

          <section class="form-section">
            <header class="form-section-head">
              <h4 class="form-section-title">目标 Plan</h4>
            </header>
            <div class="form-grid">
              <NFormItem label="mode">
                <NSelect
                  v-model:value="planMode"
                  class="sun-field"
                  :options="planModeOptions"
                />
              </NFormItem>
              <NFormItem label="workflowId">
                <NInput
                  v-model:value="workflowId"
                  class="sun-field"
                  placeholder="仅 workflow 模式需要"
                />
              </NFormItem>
            </div>
            <NFormItem label="params（每行 key=value）">
              <NInput
                v-model:value="paramsText"
                class="sun-field mono"
                type="textarea"
                :autosize="{ minRows: 2, maxRows: 6 }"
                placeholder="status=pending"
              />
            </NFormItem>
          </section>

          <section class="form-section">
            <header class="form-section-head">
              <h4 class="form-section-title">版本</h4>
            </header>
            <div v-if="page.versions.length" class="version-list">
              <div
                v-for="ver in page.versions"
                :key="ver.version"
                class="version-row"
              >
                <span class="version-num">v{{ ver.version }}</span>
                <span class="version-status">{{ ver.status === 'published' ? '已发布' : '草稿' }}</span>
                <span
                  v-if="ver.version === page.detail.activeVersion"
                  class="active-mark"
                >当前</span>
                <NSpace :size="6" class="version-actions">
                  <NButton
                    v-if="ver.status === 'draft'"
                    size="tiny"
                    secondary
                    @click="page.handlePublish(ver.version)"
                  >
                    发布
                  </NButton>
                  <NButton
                    v-if="ver.status === 'published' && ver.version !== page.detail.activeVersion"
                    size="tiny"
                    quaternary
                    @click="page.handleRollback(ver.version)"
                  >
                    回滚
                  </NButton>
                  <NButton
                    size="tiny"
                    quaternary
                    @click="page.loadVersionIntoEditor(ver)"
                  >
                    载入
                  </NButton>
                </NSpace>
              </div>
            </div>
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
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 18px 22px;
  border-bottom: 1px solid var(--sun-border);
  flex-shrink: 0;
}

.detail-toolbar-text {
  display: flex;
  flex-direction: column;
  gap: 6px;
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
  padding: 18px 20px;
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-md);
  background: var(--sun-black);
}

.form-section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--sun-border);
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

.mono :deep(.n-input__textarea-el),
.mono :deep(.n-input__input-el) {
  font-family: var(--sun-font-mono, monospace);
  font-size: 12px;
}

.version-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.version-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 12px;
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-md);
}

.version-num {
  font-weight: 600;
  font-family: var(--sun-font-mono, monospace);
}

.version-status {
  font-size: 12px;
  color: var(--sun-text-secondary);
}

.active-mark {
  font-size: 11px;
  color: var(--sun-accent);
}

.version-actions {
  margin-left: auto;
}

.action-btn {
  --n-color: var(--sun-accent) !important;
}
</style>
