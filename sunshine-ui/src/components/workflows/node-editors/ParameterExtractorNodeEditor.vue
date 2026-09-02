<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { NButton, NFormItem, NInput, NSelect } from 'naive-ui'
import VariableReferencePicker from '../VariableReferencePicker.vue'
import WorkflowNodeConfigSection from '../WorkflowNodeConfigSection.vue'
import type { WorkflowPlanNode } from '../../../api/workflows'
import { workflowNodeFieldHelp } from '../workflowFieldHelp'

interface SchemaField {
  fieldName: string
  type: string
  description?: string
  enumValues?: string
}

const props = defineProps<{
  node: WorkflowPlanNode
  readOnly: boolean
  upstreamNodes: WorkflowPlanNode[]
}>()

const emit = defineEmits<{
  'update:input': [input: string]
  'update:instruction': [instruction: string]
  'update:schema': [schemaJson: string]
}>()

const FIELD_TYPE_OPTIONS = [
  { label: 'string', value: 'string' },
  { label: 'number', value: 'number' },
  { label: 'boolean', value: 'boolean' },
  { label: 'object', value: 'object' },
  { label: 'array', value: 'array' },
]

const inputValue = computed(() => String(props.node.params?.input ?? ''))
const instructionValue = computed(() => String(props.node.params?.instruction ?? ''))

/** 编辑中的 schema 字段（含未命名草稿行）；保存时仅序列化已命名字段 */
const schemaFields = ref<SchemaField[]>([])

watch(
  () => props.node.params?.schema,
  (raw) => {
    schemaFields.value = parseSchemaFields(typeof raw === 'string' ? raw : '')
  },
  { immediate: true },
)

function parseSchemaFields(raw: string): SchemaField[] {
  if (!raw.trim()) return []
  try {
    const obj = JSON.parse(raw) as Record<string, unknown>
    if (!obj || typeof obj !== 'object' || Array.isArray(obj)) return []
    return Object.entries(obj).map(([fieldName, def]) => {
      const d = (def && typeof def === 'object' ? def : {}) as Record<string, unknown>
      const type = String(d.type ?? 'string').toLowerCase()
      const enumArr = Array.isArray(d.enum) ? d.enum.map((v) => String(v)).join(', ') : ''
      return {
        fieldName,
        type: ['string', 'number', 'boolean', 'object', 'array'].includes(type) ? type : 'string',
        description: typeof d.description === 'string' ? d.description : '',
        enumValues: enumArr,
      }
    })
  } catch {
    return []
  }
}

function onInputUpdate(v: string) {
  if (props.readOnly) return
  emit('update:input', v)
}

function onInstructionUpdate(v: string) {
  if (props.readOnly) return
  emit('update:instruction', v)
}

function addSchemaField() {
  if (props.readOnly) return
  schemaFields.value.push({ fieldName: '', type: 'string', description: '', enumValues: '' })
  emitSchemaFields(schemaFields.value)
}

function updateSchemaField(idx: number, patch: Partial<SchemaField>) {
  if (props.readOnly) return
  const next = schemaFields.value.map((f, i) => (i === idx ? { ...f, ...patch } : f))
  emitSchemaFields(next)
}

function removeSchemaField(idx: number) {
  if (props.readOnly) return
  const next = schemaFields.value.filter((_, i) => i !== idx)
  schemaFields.value = next
  emitSchemaFields(next, true)
}

/** 将字段列表序列化为 JSON 字符串并 emit（跳过未命名草稿行）
 *  force=true 时允许空 schema（用于删除最后一个字段） */
function emitSchemaFields(fields: SchemaField[], force = false) {
  const obj: Record<string, Record<string, unknown>> = {}
  for (const f of fields) {
    const name = f.fieldName.trim()
    if (!name) continue
    const def: Record<string, unknown> = { type: f.type || 'string' }
    if (f.description?.trim()) def.description = f.description.trim()
    const enums = (f.enumValues ?? '')
      .split(',')
      .map((s) => s.trim())
      .filter(Boolean)
    if (enums.length > 0) def.enum = enums
    obj[name] = def
  }
  if (!force && Object.keys(obj).length === 0) return
  emit('update:schema', JSON.stringify(obj))
}
</script>

<template>
  <div>
    <WorkflowNodeConfigSection title="输入" :help="workflowNodeFieldHelp('nodeInputs')">
      <NFormItem :show-feedback="false">
        <template #label>
          <span class="wf-param-label"><code class="wf-param-name">input</code> · 待提取文本</span>
        </template>
        <VariableReferencePicker
          :upstream-nodes="upstreamNodes"
          :model-value="inputValue"
          :disabled="readOnly"
          placeholder="{{上游节点.output}}"
          @update:model-value="onInputUpdate"
        />
      </NFormItem>
      <NFormItem :show-feedback="false">
        <template #label>
          <span class="wf-param-label"><code class="wf-param-name">instruction</code> · 提取指令</span>
        </template>
        <NInput
          class="sun-field wf-mono-field"
          type="textarea"
          :disabled="readOnly"
          :autosize="{ minRows: 2, maxRows: 5 }"
          :value="instructionValue"
          placeholder="如：提取审批人、结果、意见"
          @update:value="onInstructionUpdate"
        />
      </NFormItem>
    </WorkflowNodeConfigSection>

    <WorkflowNodeConfigSection title="输出 Schema" :help="workflowNodeFieldHelp('nodeOutputs')">
      <div v-if="schemaFields.length === 0" class="wf-schema-empty">
        <p class="wf-schema-empty-hint">暂无字段。</p>
        <NButton size="small" secondary :disabled="readOnly" @click="addSchemaField">+ 添加字段</NButton>
      </div>
      <template v-else>
        <div v-for="(f, idx) in schemaFields" :key="idx" class="wf-schema-row">
          <div class="wf-schema-row-head">
            <span class="wf-schema-row-index">#{{ idx + 1 }}</span>
            <button
              v-if="!readOnly"
              type="button"
              class="wf-schema-row-del"
              title="删除该字段"
              @click="removeSchemaField(idx)"
            >
              ×
            </button>
          </div>
          <div class="wf-schema-row-grid">
            <NFormItem :show-feedback="false">
              <template #label>
                <span class="wf-param-label"><code class="wf-param-name">字段名</code></span>
              </template>
              <NInput
                class="sun-field wf-mono-field"
                :disabled="readOnly"
                :value="f.fieldName"
                placeholder="如 approver"
                @update:value="(v) => updateSchemaField(idx, { fieldName: v })"
              />
            </NFormItem>
            <NFormItem :show-feedback="false">
              <template #label>
                <span class="wf-param-label"><code class="wf-param-name">类型</code></span>
              </template>
              <NSelect
                class="sun-field"
                :disabled="readOnly"
                :value="f.type"
                :options="FIELD_TYPE_OPTIONS"
                @update:value="(v) => updateSchemaField(idx, { type: String(v) })"
              />
            </NFormItem>
            <NFormItem :show-feedback="false">
              <template #label>
                <span class="wf-param-label"><code class="wf-param-name">描述</code></span>
              </template>
              <NInput
                class="sun-field"
                :disabled="readOnly"
                :value="f.description ?? ''"
                placeholder="可选"
                @update:value="(v) => updateSchemaField(idx, { description: v })"
              />
            </NFormItem>
            <NFormItem :show-feedback="false">
              <template #label>
                <span class="wf-param-label"><code class="wf-param-name">枚举值（逗号分隔）</code></span>
              </template>
              <NInput
                class="sun-field"
                :disabled="readOnly"
                :value="f.enumValues ?? ''"
                placeholder="如 approved, rejected"
                @update:value="(v) => updateSchemaField(idx, { enumValues: v })"
              />
            </NFormItem>
          </div>
        </div>
        <div class="wf-schema-footer">
          <NButton size="small" secondary :disabled="readOnly" @click="addSchemaField">+ 添加字段</NButton>
        </div>
      </template>
    </WorkflowNodeConfigSection>
  </div>
</template>

<style scoped>
.wf-schema-empty {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.wf-schema-empty-hint {
  margin: 0;
  font-size: var(--sun-font-xs);
  color: var(--sun-text-muted);
}
.wf-schema-row {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 10px;
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-sm);
}
.wf-schema-row + .wf-schema-row {
  margin-top: 8px;
}
.wf-schema-row-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.wf-schema-row-index {
  font-size: var(--sun-font-xs);
  font-family: var(--sun-font-mono);
  color: var(--sun-text-muted);
}
.wf-schema-row-del {
  border: none;
  background: transparent;
  color: var(--sun-text-muted);
  cursor: pointer;
  font-size: 16px;
  line-height: 1;
  padding: 2px 6px;
  border-radius: 4px;
}
.wf-schema-row-del:hover:not(:disabled) {
  color: var(--sun-danger, #e88080);
  background: color-mix(in srgb, var(--sun-border) 35%, transparent);
}
.wf-schema-row-grid {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.wf-schema-footer {
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
.wf-mono-field :deep(.n-input__textarea-el),
.wf-mono-field :deep(.n-input__input-el) {
  font-family: var(--sun-font-mono);
  font-size: var(--sun-font-sm);
  line-height: 1.5;
}
.wf-schema-row-grid :deep(.n-base-selection),
.wf-schema-row-grid :deep(.n-input) {
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
