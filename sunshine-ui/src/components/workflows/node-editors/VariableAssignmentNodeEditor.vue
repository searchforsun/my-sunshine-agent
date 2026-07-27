<script setup lang="ts">
import { computed } from 'vue'
import { NButton, NFormItem, NInput, NSelect } from 'naive-ui'
import VariableReferencePicker from '../VariableReferencePicker.vue'
import WorkflowNodeConfigSection from '../WorkflowNodeConfigSection.vue'
import type { WorkflowPlanNode } from '../../../api/workflows'
import { workflowNodeFieldHelp } from '../workflowFieldHelp'

interface Assignment {
  name: string
  source: string
  type: string
}

const props = defineProps<{
  node: WorkflowPlanNode
  readOnly: boolean
  upstreamNodes: WorkflowPlanNode[]
}>()

const emit = defineEmits<{
  'update:assignments': [assignments: Assignment[]]
}>()

const ASSIGNMENT_TYPE_OPTIONS = [
  { label: 'string', value: 'string' },
  { label: 'number', value: 'number' },
  { label: 'boolean', value: 'boolean' },
  { label: 'object', value: 'object' },
  { label: 'array', value: 'array' },
]

/** 从 params.assignments 读取赋值列表（兼容原生数组与 JSON 字符串两种存储形态） */
const assignments = computed<Assignment[]>(() => {
  const raw = props.node.params?.assignments
  if (Array.isArray(raw)) {
    return raw.filter((a): a is Assignment => !!a && typeof a === 'object' && 'name' in a)
  }
  if (typeof raw === 'string') {
    try {
      const parsed = JSON.parse(raw)
      return Array.isArray(parsed)
        ? parsed.filter((a): a is Assignment => !!a && typeof a === 'object' && 'name' in a)
        : []
    } catch {
      return []
    }
  }
  return []
})

function emitAssignments(next: Assignment[]) {
  if (props.readOnly) return
  emit('update:assignments', next)
}

function addAssignment() {
  if (props.readOnly) return
  emitAssignments([...assignments.value, { name: '', source: '', type: 'string' }])
}

function updateAssignment(idx: number, patch: Partial<Assignment>) {
  if (props.readOnly) return
  const next = assignments.value.map((a, i) => (i === idx ? { ...a, ...patch } : a))
  emitAssignments(next)
}

function removeAssignment(idx: number) {
  if (props.readOnly) return
  emitAssignments(assignments.value.filter((_, i) => i !== idx))
}
</script>

<template>
  <WorkflowNodeConfigSection title="变量赋值" :help="workflowNodeFieldHelp('nodeInputs')">
    <p v-pre class="wf-assign-hint">将上游变量或字面量赋值给命名变量，供下游以 <code>{{节点-id.变量名}}</code> 引用。</p>
    <div v-if="assignments.length === 0" class="wf-assign-empty">
      <p class="wf-assign-empty-hint">暂无赋值。</p>
      <NButton size="small" secondary :disabled="readOnly" @click="addAssignment">+ 添加赋值</NButton>
    </div>
    <template v-else>
      <div v-for="(item, idx) in assignments" :key="idx" class="wf-assign-row">
        <div class="wf-assign-row-head">
          <span class="wf-assign-row-index">#{{ idx + 1 }}</span>
          <button
            v-if="!readOnly"
            type="button"
            class="wf-assign-row-del"
            title="删除该赋值"
            @click="removeAssignment(idx)"
          >
            ×
          </button>
        </div>
        <div class="wf-assign-row-grid">
          <NFormItem :show-feedback="false">
            <template #label>
              <span class="wf-param-label"><code class="wf-param-name">变量名</code></span>
            </template>
            <NInput
              class="sun-field wf-mono-field"
              :disabled="readOnly"
              :value="item.name"
              placeholder="如 expenseId"
              @update:value="(v) => updateAssignment(idx, { name: v })"
            />
          </NFormItem>
          <NFormItem :show-feedback="false">
            <template #label>
              <span class="wf-param-label"><code class="wf-param-name">值（变量引用）</code></span>
            </template>
            <VariableReferencePicker
              :upstream-nodes="upstreamNodes"
              :model-value="item.source"
              :disabled="readOnly"
              placeholder="{{上游节点.output.field}} 或字面量"
              @update:model-value="(v) => updateAssignment(idx, { source: v })"
            />
          </NFormItem>
          <NFormItem :show-feedback="false">
            <template #label>
              <span class="wf-param-label"><code class="wf-param-name">类型</code></span>
            </template>
            <NSelect
              class="sun-field"
              :disabled="readOnly"
              :value="item.type || 'string'"
              :options="ASSIGNMENT_TYPE_OPTIONS"
              @update:value="(v) => updateAssignment(idx, { type: String(v) })"
            />
          </NFormItem>
        </div>
      </div>
      <div class="wf-assign-footer">
        <NButton size="small" secondary :disabled="readOnly" @click="addAssignment">+ 添加赋值</NButton>
      </div>
    </template>
  </WorkflowNodeConfigSection>
</template>

<style scoped>
.wf-assign-hint {
  margin: 0;
  font-size: var(--sun-font-xs);
  color: var(--sun-text-muted);
  line-height: 1.5;
}
.wf-assign-empty {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.wf-assign-empty-hint {
  margin: 0;
  font-size: var(--sun-font-xs);
  color: var(--sun-text-muted);
}
.wf-assign-row {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 10px;
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-sm);
}
.wf-assign-row + .wf-assign-row {
  margin-top: 8px;
}
.wf-assign-row-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.wf-assign-row-index {
  font-size: var(--sun-font-xs);
  font-family: var(--sun-font-mono);
  color: var(--sun-text-muted);
}
.wf-assign-row-del {
  border: none;
  background: transparent;
  color: var(--sun-text-muted);
  cursor: pointer;
  font-size: 16px;
  line-height: 1;
  padding: 2px 6px;
  border-radius: 4px;
}
.wf-assign-row-del:hover:not(:disabled) {
  color: var(--sun-danger, #e88080);
  background: color-mix(in srgb, var(--sun-border) 35%, transparent);
}
.wf-assign-row-grid {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.wf-assign-footer {
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
.wf-assign-row-grid :deep(.n-base-selection),
.wf-assign-row-grid :deep(.n-input) {
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
