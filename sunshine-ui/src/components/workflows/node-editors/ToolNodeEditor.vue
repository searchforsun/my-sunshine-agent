<script setup lang="ts">
import { computed } from 'vue'
import { NButton, NFormItem, NInput, NSelect, NSwitch } from 'naive-ui'
import ConfigFieldHelp from '../../knowledge/ConfigFieldHelp.vue'
import VariableReferencePicker from '../VariableReferencePicker.vue'
import WorkflowNodeConfigSection from '../WorkflowNodeConfigSection.vue'
import type { ToolCatalogEntry } from '../../../api/tools'
import type { WorkflowPlanInputBinding, WorkflowPlanNode } from '../../../api/workflows'
import { parseToolSchemaFields } from '../../../utils/workflowNodeIo'
import { workflowNodeFieldHelp } from '../workflowFieldHelp'
import { WORKFLOWS_PAGE_KEY, type WorkflowsPageApi } from '../../../composables/useWorkflowsPage'
import { inject } from 'vue'

const page = inject(WORKFLOWS_PAGE_KEY) as WorkflowsPageApi

const props = defineProps<{
  node: WorkflowPlanNode
  readOnly: boolean
  toolCatalog?: ToolCatalogEntry | null
  upstreamNodes: WorkflowPlanNode[]
}>()

const emit = defineEmits<{
  'update:inputs': [inputs: WorkflowPlanInputBinding[]]
  'update:tool': [toolId: string]
}>()

const INPUT_TYPE_OPTIONS = [
  { label: 'string', value: 'string' },
  { label: 'number', value: 'number' },
  { label: 'boolean', value: 'boolean' },
  { label: 'object', value: 'object' },
  { label: 'array', value: 'array' },
]

/** 当前 tool 节点的 inputs（缺省空数组） */
const inputBindings = computed<WorkflowPlanInputBinding[]>(() => props.node.inputs ?? [])

const toolSelectOptions = computed(() =>
  page.toolOptions.map((t) => ({
    label: `${t.displayName || t.id} (${t.id})`,
    value: t.id,
  })),
)

const selectedToolId = computed(() => String(props.node.params?.tool ?? '').trim())

/** 工具 schema 字段，用于「从 schema 预填 inputs」 */
const schemaFields = computed(() => parseToolSchemaFields(props.toolCatalog))

/** 添加一行 input 绑定 */
function addInput(presetName?: string, presetSource = '') {
  if (props.readOnly) return
  const next: WorkflowPlanInputBinding = {
    name: presetName?.trim() || '',
    source: presetSource,
    type: 'string',
    required: false,
  }
  emit('update:inputs', [...inputBindings.value, next])
}

/** 从工具 schema 一键预填 inputs（覆盖现有） */
function prefillFromSchema() {
  if (props.readOnly || schemaFields.value.length === 0) return
  const next: WorkflowPlanInputBinding[] = schemaFields.value.map((f) => ({
    name: f.name,
    source: '',
    type: 'string',
    required: f.required,
  }))
  emit('update:inputs', next)
}

function updateInput(idx: number, patch: Partial<WorkflowPlanInputBinding>) {
  if (props.readOnly) return
  const next = inputBindings.value.map((b, i) => (i === idx ? { ...b, ...patch } : b))
  emit('update:inputs', next)
}

function removeInput(idx: number) {
  if (props.readOnly) return
  const next = inputBindings.value.filter((_, i) => i !== idx)
  emit('update:inputs', next)
}

function onToolSelect(toolId: string) {
  if (props.readOnly) return
  emit('update:tool', toolId ?? '')
}
</script>

<template>
  <div>
    <WorkflowNodeConfigSection title="工具">
      <NFormItem>
        <template #label>
          <span class="field-label-row">Catalog 工具<ConfigFieldHelp :text="workflowNodeFieldHelp('tool')" /></span>
        </template>
        <NSelect
          class="sun-field"
          filterable
          :disabled="readOnly"
          :value="selectedToolId"
          :options="toolSelectOptions"
          placeholder="选择工具"
          @update:value="onToolSelect"
        />
      </NFormItem>
    </WorkflowNodeConfigSection>

    <WorkflowNodeConfigSection title="输入绑定" :help="workflowNodeFieldHelp('nodeInputs')">
      <div v-if="inputBindings.length === 0" class="wf-inputs-empty">
        <p class="wf-inputs-empty-hint">暂无输入绑定。可手动添加，或从工具 Schema 预填。</p>
        <div class="wf-inputs-empty-actions">
          <NButton size="small" secondary :disabled="readOnly" @click="addInput()">+ 添加绑定</NButton>
          <NButton
            v-if="schemaFields.length"
            size="small"
            secondary
            :disabled="readOnly"
            @click="prefillFromSchema"
          >
            从 Schema 预填
          </NButton>
        </div>
      </div>
      <template v-else>
        <div v-for="(binding, idx) in inputBindings" :key="idx" class="wf-input-row">
          <div class="wf-input-row-head">
            <span class="wf-input-row-index">#{{ idx + 1 }}</span>
            <button
              v-if="!readOnly"
              type="button"
              class="wf-input-row-del"
              title="删除该绑定"
              @click="removeInput(idx)"
            >
              ×
            </button>
          </div>
          <div class="wf-input-row-grid">
            <NFormItem :show-feedback="false">
              <template #label>
                <span class="wf-param-label"><code class="wf-param-name">参数名</code></span>
              </template>
              <NInput
                class="sun-field wf-mono-field"
                :disabled="readOnly"
                :value="binding.name"
                placeholder="如 expenseId"
                @update:value="(v) => updateInput(idx, { name: v })"
              />
            </NFormItem>
            <NFormItem :show-feedback="false">
              <template #label>
                <span class="wf-param-label"><code class="wf-param-name">变量引用</code></span>
              </template>
              <VariableReferencePicker
                :upstream-nodes="upstreamNodes"
                :model-value="binding.source"
                :disabled="readOnly"
                placeholder="{{上游节点.output}} 或字面量"
                @update:model-value="(v) => updateInput(idx, { source: v })"
              />
            </NFormItem>
            <div class="wf-input-row-meta">
              <NFormItem :show-feedback="false">
                <template #label>
                  <span class="wf-param-label"><code class="wf-param-name">类型</code></span>
                </template>
                <NSelect
                  class="sun-field"
                  :disabled="readOnly"
                  :value="binding.type || 'string'"
                  :options="INPUT_TYPE_OPTIONS"
                  @update:value="(v) => updateInput(idx, { type: String(v) })"
                />
              </NFormItem>
              <NFormItem :show-feedback="false">
                <template #label>
                  <span class="wf-param-label"><code class="wf-param-name">必填</code></span>
                </template>
                <NSwitch
                  :disabled="readOnly"
                  :value="!!binding.required"
                  @update:value="(v) => updateInput(idx, { required: v })"
                />
              </NFormItem>
            </div>
          </div>
        </div>
        <div class="wf-inputs-footer">
          <NButton size="small" secondary :disabled="readOnly" @click="addInput()">+ 添加绑定</NButton>
          <NButton
            v-if="schemaFields.length"
            size="small"
            quaternary
            :disabled="readOnly"
            @click="prefillFromSchema"
          >
            从 Schema 预填（覆盖）
          </NButton>
        </div>
      </template>
    </WorkflowNodeConfigSection>
  </div>
</template>

<style scoped>
.wf-inputs-empty {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.wf-inputs-empty-hint {
  margin: 0;
  font-size: var(--sun-font-xs);
  color: var(--sun-text-muted);
}
.wf-inputs-empty-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.wf-input-row {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 10px;
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-sm);
}
.wf-input-row + .wf-input-row {
  margin-top: 8px;
}
.wf-input-row-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.wf-input-row-index {
  font-size: var(--sun-font-xs);
  font-family: var(--sun-font-mono);
  color: var(--sun-text-muted);
}
.wf-input-row-del {
  border: none;
  background: transparent;
  color: var(--sun-text-muted);
  cursor: pointer;
  font-size: 16px;
  line-height: 1;
  padding: 2px 6px;
  border-radius: 4px;
}
.wf-input-row-del:hover:not(:disabled) {
  color: var(--sun-danger, #e88080);
  background: color-mix(in srgb, var(--sun-border) 35%, transparent);
}
.wf-input-row-grid {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.wf-input-row-meta {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
}
.wf-inputs-footer {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  margin-top: 8px;
}
.wf-param-label {
  display: inline-flex;
  align-items: center;
  gap: 3px;
}
.wf-param-name {
  font-family: var(--sun-font-mono);
  font-size: var(--sun-font-sm);
  font-weight: 500;
  color: var(--sun-text);
  background: none;
  padding: 0;
}
.wf-mono-field :deep(.n-input__input-el) {
  font-family: var(--sun-font-mono);
  font-size: var(--sun-font-sm);
}
.field-label-row {
  display: inline-flex;
  align-items: center;
  gap: 2px;
}

.wf-input-row-meta :deep(.n-base-selection),
.wf-input-row-grid :deep(.n-base-selection),
.wf-input-row-grid :deep(.n-input) {
  --n-color: var(--sun-black) !important;
  --n-color-active: var(--sun-black) !important;
  --n-color-focus: var(--sun-black) !important;
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
</style>
