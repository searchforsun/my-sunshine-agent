<script setup lang="ts">
import { NButton, NInput, NRadio, NRadioGroup, NSelect } from 'naive-ui'
import type { WorkflowPlanNode, WorkflowPlanEdgeCondition, WorkflowPlanEdgeConditionGroup } from '../../api/workflows'
import VariableReferencePicker from './VariableReferencePicker.vue'

const props = defineProps<{
  modelValue: WorkflowPlanEdgeConditionGroup
  upstreamNodes: WorkflowPlanNode[]
  disabled?: boolean
}>()

const emit = defineEmits<{
  'update:modelValue': [val: WorkflowPlanEdgeConditionGroup]
}>()

const CONDITION_OP_OPTIONS = [
  { label: '为空 empty', value: 'empty' },
  { label: '非空 not_empty', value: 'not_empty' },
  { label: '包含 contains', value: 'contains' },
  { label: '不包含 not_contains', value: 'not_contains' },
  { label: '等于 eq', value: 'eq' },
  { label: '不等于 not_eq', value: 'not_eq' },
  { label: '大于 gt', value: 'gt' },
  { label: '小于 lt', value: 'lt' },
  { label: '大于等于 gte', value: 'gte' },
  { label: '小于等于 lte', value: 'lte' },
  { label: '属于 in', value: 'in' },
  { label: '不属于 not_in', value: 'not_in' },
]

function updateLogic(logic: 'and' | 'or') {
  emit('update:modelValue', { ...props.modelValue, logic })
}

function updateItem(index: number, patch: Partial<WorkflowPlanEdgeCondition>) {
  const items = props.modelValue.items.map((item, i) =>
    i === index ? { ...item, ...patch } : item,
  )
  emit('update:modelValue', { ...props.modelValue, items })
}

function removeItem(index: number) {
  const items = props.modelValue.items.filter((_, i) => i !== index)
  emit('update:modelValue', { ...props.modelValue, items })
}

function addItem() {
  const items = [...props.modelValue.items, { left: '', op: 'not_empty', right: '' }]
  emit('update:modelValue', { ...props.modelValue, items })
}
</script>

<template>
  <div class="condition-group-editor">
    <div class="condition-logic-row">
      <NRadioGroup
        :value="modelValue.logic"
        :disabled="disabled"
        @update:value="v => updateLogic(v as 'and' | 'or')"
      >
        <NRadio value="and">全部满足 (AND)</NRadio>
        <NRadio value="or">任一满足 (OR)</NRadio>
      </NRadioGroup>
    </div>
    <div
      v-for="(item, idx) in modelValue.items"
      :key="idx"
      class="condition-row"
    >
      <VariableReferencePicker
        class="cond-left"
        :model-value="item.left"
        :upstream-nodes="upstreamNodes"
        :disabled="disabled"
        placeholder="{{node.field}}"
        @update:modelValue="v => updateItem(idx, { left: v })"
      />
      <NSelect
        class="sun-field cond-op"
        :value="item.op"
        :options="CONDITION_OP_OPTIONS"
        :disabled="disabled"
        @update:value="v => updateItem(idx, { op: String(v) })"
      />
      <NInput
        v-if="item.op !== 'empty' && item.op !== 'not_empty'"
        class="sun-field cond-right"
        :value="item.right ?? ''"
        :disabled="disabled"
        placeholder="比较值"
        @update:value="v => updateItem(idx, { right: v })"
      />
      <NButton
        quaternary
        size="small"
        :disabled="disabled"
        @click="removeItem(idx)"
      >
        ✕
      </NButton>
    </div>
    <NButton
      quaternary
      size="small"
      :disabled="disabled"
      @click="addItem"
    >
      + 添加条件
    </NButton>
  </div>
</template>

<style scoped>
.condition-group-editor {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.condition-logic-row {
  font-size: 12px;
  color: var(--sun-text-secondary);
}
.condition-row {
  display: flex;
  align-items: center;
  gap: 6px;
}
.cond-left {
  flex: 1;
  min-width: 0;
}
.cond-op {
  width: 140px;
  flex-shrink: 0;
}
.cond-right {
  flex: 1;
  min-width: 0;
}
.cond-op :deep(.n-base-selection) {
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
.cond-right :deep(.n-input__input-el) {
  font-family: var(--sun-font-mono);
  font-size: var(--sun-font-sm);
  color: var(--sun-text);
}
</style>
