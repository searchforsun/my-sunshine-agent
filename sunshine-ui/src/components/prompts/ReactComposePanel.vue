<script setup lang="ts">
import { inject } from 'vue'
import {
  NButton,
  NEmpty,
  NFormItem,
  NInput,
  NInputNumber,
  NSpace,
  NSwitch,
  NTag,
} from 'naive-ui'
import { PROMPTS_PAGE_KEY, type PromptsPageApi } from '../../composables/usePromptsPage'

const page = inject(PROMPTS_PAGE_KEY) as PromptsPageApi

/** 与 PromptComposer ReAct 层顺序一致（只读预览） */
const LAYER_SKELETON = [
  { id: 'mode-overlay.react', label: 'mode-overlay（含 fragments）' },
  { id: 'mode-overlay.react-restart', label: 'react-restart（重规划时）' },
  { id: 'hitl.agent-prompt', label: 'HITL' },
  { id: 'skill', label: 'skill overlay' },
  { id: 'memory.layer-prompt', label: 'memory' },
  { id: 'scope-prompt', label: 'scope' },
]

function goAllTab() {
  page.activeTab = 'all'
}
</script>

<template>
  <main class="compose-panel">
    <div class="detail-toolbar">
      <div class="detail-toolbar-text">
        <h3 class="detail-heading">ReAct 拼装</h3>
        <span class="detail-id">层顺序只读 · 可编辑 overlay 与 fragments</span>
      </div>
      <NButton size="small" round secondary @click="goAllTab">
        时间线请在「全部」管理
      </NButton>
    </div>

    <div class="detail-scroll">
      <section class="form-section">
        <header class="form-section-head">
          <h4 class="form-section-title">层顺序（只读）</h4>
        </header>
        <ol class="layer-list">
          <li v-for="(layer, idx) in LAYER_SKELETON" :key="layer.id" class="layer-item">
            <span class="layer-idx">{{ idx + 1 }}</span>
            <span class="layer-label">{{ layer.label }}</span>
            <span class="layer-id mono">{{ layer.id }}</span>
          </li>
        </ol>
      </section>

      <section class="form-section">
        <header class="form-section-head">
          <h4 class="form-section-title">mode-overlay.react</h4>
          <NButton
            size="tiny"
            secondary
            :loading="page.saving"
            @click="page.saveReactOverlay('mode-overlay.react')"
          >
            保存草稿
          </NButton>
        </header>
        <NInput
          v-model:value="page.reactOverlayText['mode-overlay.react']"
          class="sun-field sun-field-grow content-input"
          type="textarea"
          :autosize="{ minRows: 6, maxRows: 20 }"
          placeholder="ReAct 模式叠加提示词"
        />
      </section>

      <section class="form-section">
        <header class="form-section-head">
          <h4 class="form-section-title">mode-overlay.react-restart</h4>
          <NButton
            size="tiny"
            secondary
            :loading="page.saving"
            @click="page.saveReactOverlay('mode-overlay.react-restart')"
          >
            保存草稿
          </NButton>
        </header>
        <NInput
          v-model:value="page.reactOverlayText['mode-overlay.react-restart']"
          class="sun-field sun-field-grow content-input"
          type="textarea"
          :autosize="{ minRows: 4, maxRows: 14 }"
          placeholder="重新生成 / 续跑重规划叠加"
        />
      </section>

      <section class="form-section">
        <header class="form-section-head">
          <h4 class="form-section-title">react-fragment</h4>
          <NTag size="tiny" :bordered="false" round>{{ page.reactFragments.length }}</NTag>
        </header>
        <p class="hint">
          无 fragment 时可仅维护上方整段 overlay。启停、sortOrder 与正文保存为草稿，需在「全部」或此处发布。
        </p>
        <div v-if="page.reactFragments.length" class="fragment-list">
          <div
            v-for="frag in page.reactFragments"
            :key="frag.id"
            class="fragment-card"
            :class="{ active: frag.id === page.selectedId }"
          >
            <div class="fragment-head">
              <div class="fragment-titles">
                <span class="fragment-name">{{ frag.displayName }}</span>
                <span class="fragment-id mono">{{ frag.id }}</span>
              </div>
              <NSpace :size="10" align="center">
                <NSwitch
                  :value="frag.enabled"
                  size="small"
                  @update:value="(v: boolean) => page.handleToggleEnabled(
                    { id: frag.id, kind: 'react-fragment', displayName: frag.displayName, enabled: frag.enabled, priority: 0, activeVersion: 1, catalogVersion: 1 },
                    v,
                  )"
                />
                <NButton
                  size="tiny"
                  secondary
                  :loading="page.saving"
                  @click="page.saveReactFragment(frag.id)"
                >
                  保存草稿
                </NButton>
              </NSpace>
            </div>
            <div class="fragment-meta">
              <NFormItem label="attachTo" :show-feedback="false">
                <NInput v-model:value="frag.attachTo" class="sun-field" size="small" />
              </NFormItem>
              <NFormItem label="sortOrder" :show-feedback="false">
                <NInputNumber
                  v-model:value="frag.sortOrder"
                  class="sun-field"
                  size="small"
                  :show-button="false"
                />
              </NFormItem>
            </div>
            <NInput
              v-model:value="frag.contentText"
              class="sun-field content-input"
              type="textarea"
              :autosize="{ minRows: 3, maxRows: 12 }"
              placeholder="片段正文"
            />
          </div>
        </div>
        <NEmpty v-else size="small" description="暂无 react-fragment（可仅编辑整段 overlay）" />
      </section>
    </div>
  </main>
</template>

<style scoped>
.compose-panel {
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
}

.detail-scroll {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 22px;
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.form-section {
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 18px 20px;
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-md);
  background: var(--sun-black);
}

.form-section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--sun-border);
}

.form-section-title {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  color: var(--sun-text);
}

.layer-list {
  margin: 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.layer-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 8px 10px;
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-md);
}

.layer-idx {
  width: 22px;
  height: 22px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 1px solid var(--sun-border);
  border-radius: 50%;
  font-size: 11px;
  color: var(--sun-text-secondary);
  flex-shrink: 0;
}

.layer-label {
  font-size: 13px;
  color: var(--sun-text);
  flex-shrink: 0;
}

.layer-id {
  margin-left: auto;
  font-size: 11px;
  color: var(--sun-text-muted);
}

.mono {
  font-family: var(--sun-font-mono, monospace);
}

.hint {
  margin: 0;
  font-size: 12px;
  color: var(--sun-text-muted);
  line-height: 1.5;
}

.content-input :deep(.n-input__textarea-el) {
  font-size: var(--sun-font-base, 14px);
  line-height: 1.6;
}

.fragment-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.fragment-card {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 14px;
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-md);
  background: transparent;
}

.fragment-card.active {
  box-shadow: inset 0 0 0 1px var(--sun-accent);
  border-color: var(--sun-accent);
}

.fragment-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.fragment-titles {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}

.fragment-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--sun-text);
}

.fragment-id {
  font-size: 11px;
  color: var(--sun-text-muted);
}

.fragment-meta {
  display: grid;
  grid-template-columns: 1fr 120px;
  gap: 12px;
}

.fragment-meta :deep(.n-form-item) {
  margin-bottom: 0;
}

.fragment-meta :deep(.n-form-item-label) {
  color: var(--sun-text-secondary);
  font-size: 12px;
  padding-bottom: 6px;
}
</style>
