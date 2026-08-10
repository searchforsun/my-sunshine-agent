<script setup lang="ts">
import {
  NAlert,
  NButton,
  NDropdown,
  NForm,
  NFormItem,
  NIcon,
  NInput,
  NInputNumber,
  NSelect,
  NSwitch,
  NSpin,
  NTag,
  NText,
} from 'naive-ui'
import { EllipsisHorizontal } from '@vicons/ionicons5'
import type { TenantId } from '../../api/tenants'
import ConfigFieldHelp from './ConfigFieldHelp.vue'
import { fieldHelp, scopeHelp } from './kbConfigFieldHelp'
import { useKbConfigPanel } from '../../composables/useKbConfigPanel'
import { catalogEnabledModelOptions, fetchModelCatalog } from '../../api/models'
import { onMounted, ref } from 'vue'

const props = defineProps<{
  tenantId: TenantId
  kbId: string | null
}>()

const panel = useKbConfigPanel(props)
const modelOptions = ref<{ label: string; value: string }[]>([])

onMounted(() => {
  void fetchModelCatalog()
    .then((catalog) => {
      modelOptions.value = catalogEnabledModelOptions(catalog).map((o) => ({
        label: o.label,
        value: o.value,
      }))
    })
    .catch(() => {
      modelOptions.value = []
    })
})

const loading = panel.loading
const saving = panel.saving
const publishing = panel.publishing
const activatingId = panel.activatingId
const error = panel.error
const gateError = panel.gateError
const failedSamples = panel.failedSamples
const scopes = panel.scopes
const versions = panel.versions
const selectedVersionId = panel.selectedVersionId
const importInputRef = panel.importInputRef
const versionStatus = panel.versionStatus
const canEdit = panel.canEdit
const showEditDraft = panel.showEditDraft
const showSaveDraftButton = panel.showSaveDraftButton
const showSubmitEval = panel.showSubmitEval
const showActivateButton = panel.showActivateButton
const showMoreMenu = panel.showMoreMenu
const versionOptions = panel.versionOptions
const moreMenuOptions = panel.moreMenuOptions
const versionStatusLabel = panel.versionStatusLabel
const versionStatusTagType = panel.versionStatusTagType
const onVersionSelected = panel.onVersionSelected
const handleSaveAll = panel.handleSaveAll
const startDraftEditing = panel.startDraftEditing
const handleSubmitEval = panel.handleSubmitEval
const handleActivate = panel.handleActivate
const handleMoreMenuSelect = panel.handleMoreMenuSelect
const updateField = panel.updateField
const compactFields = panel.compactFields
const promptFields = panel.promptFields
const fieldLabel = panel.fieldLabel
const promptCharCount = panel.promptCharCount
const scopeValues = panel.scopeValues
const onImportFileChange = panel.onImportFileChange
</script>

<template>
  <div class="config-panel">
    <input
      ref="importInputRef"
      type="file"
      accept="application/json,.json"
      class="import-input"
      @change="onImportFileChange"
    />

    <div class="config-toolbar">
      <div v-if="versions.length > 0" class="config-actions">
        <div class="version-row">
          <span class="version-label">版本</span>
          <NTag
            v-if="versionStatus"
            size="small"
            :bordered="false"
            round
            :type="versionStatusTagType(versionStatus)"
          >
            {{ versionStatusLabel(versionStatus) }}
          </NTag>
          <NSelect
            :value="selectedVersionId"
            :options="versionOptions"
            size="small"
            class="version-select"
            placeholder="选择版本"
            :disabled="loading || saving || publishing"
            :menu-props="{ class: 'kb-config-version-menu' }"
            @update:value="onVersionSelected"
          />
          <NDropdown
            v-if="showMoreMenu"
            trigger="click"
            size="small"
            :options="moreMenuOptions"
            :disabled="loading || moreMenuOptions.length === 0"
            @select="handleMoreMenuSelect"
          >
            <NButton size="small" quaternary class="more-menu-btn" title="版本操作">
              <template #icon><NIcon :component="EllipsisHorizontal" :size="16" /></template>
            </NButton>
          </NDropdown>
        </div>
        <div v-if="showEditDraft || showSaveDraftButton || showSubmitEval || showActivateButton" class="action-group">
          <NButton
            v-if="showEditDraft"
            size="small"
            round
            secondary
            :disabled="loading || scopes.length === 0 || !kbId"
            @click="startDraftEditing"
          >
            编辑草稿
          </NButton>
          <NButton
            v-if="showSaveDraftButton"
            size="small"
            round
            secondary
            :loading="saving"
            :disabled="loading || scopes.length === 0 || !kbId"
            @click="handleSaveAll"
          >
            保存草稿
          </NButton>
          <NButton
            v-if="showSubmitEval"
            size="small"
            round
            type="primary"
            class="action-btn"
            :loading="publishing"
            :disabled="loading || !kbId || saving"
            @click="handleSubmitEval"
          >
            提交评测
          </NButton>
          <NButton
            v-if="showActivateButton"
            size="small"
            round
            type="primary"
            class="action-btn"
            :loading="activatingId === selectedVersionId"
            :disabled="loading || selectedVersionId == null"
            @click="selectedVersionId != null && handleActivate(selectedVersionId)"
          >
            生效
          </NButton>
        </div>
      </div>
    </div>

    <NAlert v-if="error" type="error" :bordered="false" class="config-alert">{{ error }}</NAlert>
    <NAlert v-if="gateError" type="warning" :bordered="false" class="config-alert">{{ gateError }}</NAlert>

    <NSpin :show="loading" class="config-spin">
      <div class="config-scroll">
        <section
          v-for="scopeGroup in scopes"
          :key="scopeGroup.scope"
          class="scope-section"
        >
          <header class="scope-header">
            <h3>
              {{ scopeGroup.label }}
              <ConfigFieldHelp :text="scopeHelp(scopeGroup.scope)" />
            </h3>
          </header>

          <NForm label-placement="top" size="small" class="config-form">
            <NFormItem
              v-for="field in compactFields(scopeGroup)"
              :key="field.fieldId"
            >
              <template #label>
                <span class="field-label-row">
                  {{ fieldLabel(field.fieldId, field.label) }}
                  <ConfigFieldHelp :text="fieldHelp(scopeGroup.scope, field.fieldId)" />
                </span>
              </template>
              <NSwitch
                v-if="field.type === 'boolean'"
                :value="Boolean(scopeValues(scopeGroup.scope)[field.fieldId])"
                :disabled="!canEdit"
                @update:value="(v: boolean) => updateField(scopeGroup.scope, field.fieldId, v)"
              />
              <NSelect
                v-else-if="field.type === 'enum'"
                :value="String(scopeValues(scopeGroup.scope)[field.fieldId] ?? '')"
                :options="(field.enumValues ?? []).map((v) => ({ label: v, value: v }))"
                class="field-control"
                :disabled="!canEdit"
                :menu-props="{ class: 'kb-config-select-menu' }"
                @update:value="(v: string) => updateField(scopeGroup.scope, field.fieldId, v)"
              />
              <NSelect
                v-else-if="field.fieldId === 'model'"
                :value="String(scopeValues(scopeGroup.scope)[field.fieldId] ?? '') || null"
                :options="modelOptions"
                filterable
                clearable
                class="field-control"
                :disabled="!canEdit"
                :menu-props="{ class: 'kb-config-select-menu' }"
                placeholder="选择模型"
                @update:value="(v: string | null) => updateField(scopeGroup.scope, field.fieldId, v ?? '')"
              />
              <NInputNumber
                v-else-if="field.type === 'number'"
                :value="Number(scopeValues(scopeGroup.scope)[field.fieldId] ?? 0)"
                :min="typeof field.min === 'number' ? field.min : undefined"
                :max="typeof field.max === 'number' ? field.max : undefined"
                class="field-control"
                :disabled="!canEdit"
                @update:value="(v: number | null) => updateField(scopeGroup.scope, field.fieldId, v ?? 0)"
              />
              <NInput
                v-else
                :value="String(scopeValues(scopeGroup.scope)[field.fieldId] ?? '')"
                class="field-control"
                :disabled="!canEdit"
                @update:value="(v: string) => updateField(scopeGroup.scope, field.fieldId, v)"
              />
            </NFormItem>
          </NForm>

          <div
            v-for="field in promptFields(scopeGroup)"
            :key="field.fieldId"
            class="prompt-block"
          >
            <div class="prompt-head">
              <span class="prompt-label">
                {{ fieldLabel(field.fieldId, field.label) }}
                <ConfigFieldHelp :text="fieldHelp(scopeGroup.scope, field.fieldId)" />
              </span>
              <NText depth="3" class="prompt-count">{{ promptCharCount(scopeGroup.scope, field.fieldId) }} 字</NText>
            </div>
            <NInput
              type="textarea"
              :value="String(scopeValues(scopeGroup.scope)[field.fieldId] ?? '')"
              :autosize="{ minRows: 8, maxRows: 24 }"
              :disabled="!canEdit"
              placeholder="仅草稿版本可编辑；可在右上角切换应用配置后在调试/评测 Tab 验证…"
              class="prompt-input"
              @update:value="(v: string) => updateField(scopeGroup.scope, field.fieldId, v)"
            />
          </div>
        </section>

        <div v-if="failedSamples.length > 0" class="failed-samples">
          <NText depth="3">未通过样本（前 {{ failedSamples.length }} 条）</NText>
          <ul>
            <li v-for="sample in failedSamples" :key="sample.queryId">
              {{ sample.queryId }} · {{ sample.query }}
            </li>
          </ul>
        </div>
      </div>
    </NSpin>
  </div>
</template>

<style scoped>
.config-panel {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  gap: 12px;
  overflow: hidden;
}

.import-input {
  display: none;
}

.config-toolbar {
  flex-shrink: 0;
  padding-bottom: 4px;
  border-bottom: 1px solid var(--sun-border);
}

.config-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  min-height: 32px;
}

.version-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: nowrap;
  min-width: 0;
}

.version-label {
  font-size: 13px;
  color: var(--sun-text-secondary);
  white-space: nowrap;
}

.version-select {
  width: min(220px, 36vw);
  flex-shrink: 1;
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
}

.more-menu-btn {
  flex-shrink: 0;
}

.action-group {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
  margin-left: auto;
}

.action-btn {
  --n-color: var(--sun-accent) !important;
  --n-color-hover: var(--sun-accent-hover) !important;
  --n-color-pressed: var(--sun-accent-hover) !important;
  --n-color-focus: var(--sun-accent-hover) !important;
  --n-text-color: var(--btn-primary-text) !important;
  --n-text-color-hover: var(--btn-primary-text) !important;
  --n-text-color-pressed: var(--btn-primary-text) !important;
  --n-text-color-focus: var(--btn-primary-text) !important;
  --n-border: none !important;
}

.config-alert {
  flex-shrink: 0;
}

.config-spin {
  flex: 1;
  min-height: 0;
}

.config-spin :deep(.n-spin-content) {
  height: 100%;
  min-height: 0;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

.config-scroll {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding-right: 4px;
}

.scope-section {
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-md);
  padding: 14px 16px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.scope-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.scope-header h3 {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  color: var(--sun-text);
  display: inline-flex;
  align-items: center;
}

.field-label-row {
  display: inline-flex;
  align-items: center;
}

.prompt-label {
  display: inline-flex;
  align-items: center;
  font-size: 13px;
  font-weight: 500;
  color: var(--sun-text);
}

.config-form {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: 10px 20px;
}

.field-control {
  width: 100%;
}

.config-form :deep(.n-base-selection) {
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

.config-form :deep(.n-input),
.prompt-input {
  --n-color: var(--sun-black) !important;
  --n-color-focus: var(--sun-black) !important;
  --n-color-disabled: var(--sun-black) !important;
  --n-text-color: var(--sun-text) !important;
  --n-text-color-disabled: var(--sun-text-muted) !important;
  --n-placeholder-color: var(--sun-text-muted) !important;
  --n-border: 1px solid var(--sun-border) !important;
  --n-border-hover: 1px solid var(--sun-border-light) !important;
  --n-border-focus: 1px solid var(--sun-border-light) !important;
  --n-box-shadow-focus: none !important;
}

.prompt-block {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-top: 4px;
  padding-top: 12px;
  border-top: 1px solid var(--sun-border);
}

.prompt-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
}

.prompt-count {
  font-size: 12px;
  flex-shrink: 0;
}

.prompt-input {
  width: 100%;
}

.prompt-input :deep(.n-input__textarea-el) {
  font-size: var(--sun-font-base, 14px);
  line-height: 1.6;
  font-family: inherit;
}

.failed-samples {
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-md);
  padding: 12px 16px;
}

.failed-samples ul {
  margin: 8px 0 0;
  padding-left: 18px;
  color: var(--sun-text-secondary);
  font-size: 13px;
  line-height: 1.5;
}
</style>

<style>
.kb-config-select-menu.n-base-select-menu,
.kb-config-version-menu.n-base-select-menu {
  --n-color: var(--sun-black) !important;
  --n-option-color-active: transparent !important;
  --n-option-color-active-pending: var(--sun-row-hover) !important;
  --n-option-color-pending: var(--sun-row-hover) !important;
  --n-option-text-color: var(--sun-text) !important;
  --n-option-text-color-active: var(--sun-text) !important;
  --n-option-check-color: var(--sun-text) !important;
  background: var(--sun-black) !important;
  border: 1px solid var(--sun-border) !important;
  box-shadow: var(--shadow-elevated) !important;
}
</style>
