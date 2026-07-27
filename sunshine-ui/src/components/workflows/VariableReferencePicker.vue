<script setup lang="ts">
import { computed } from 'vue'
import { NSelect, type SelectGroupOption, type SelectOption } from 'naive-ui'
import type { WorkflowPlanNode } from '../../api/workflows'
import { nodeOutputFields } from '../../utils/workflowVariableRefs'
import { formatPlanNodeType } from '../../api/executionPlans'

const props = defineProps<{
  /** 当前节点之前的上游节点（按拓扑序） */
  upstreamNodes: WorkflowPlanNode[]
  /** 当前选中的引用，如 `{{tool_1.output.id}}` */
  modelValue: string
  disabled?: boolean
  /** 占位文案 */
  placeholder?: string
}>()

const emit = defineEmits<{
  'update:modelValue': [ref: string]
}>()

/** 构建分组下拉：组 = 节点（displayName + type），项 = 输出字段 */
const selectOptions = computed<(SelectGroupOption | SelectOption)[]>(() => {
  const groups: SelectGroupOption[] = []
  for (const node of props.upstreamNodes) {
    const fields = nodeOutputFields(node)
    if (fields.length === 0) continue
    const nodeLabel = node.displayName?.trim() || node.id
    const typeLabel = formatPlanNodeType(node.type)
    groups.push({
      type: 'group',
      label: `${nodeLabel}（${typeLabel}）`,
      key: node.id,
      children: fields.map((f) => ({
        label: `${f.name} · ${f.type}`,
        value: `{{${node.id}.${f.name}}}`,
      })) as SelectOption[],
    })
  }
  return groups
})

const hasOptions = computed(() =>
  selectOptions.value.some((g) => 'type' in g && g.type === 'group' && Array.isArray(g.children) && g.children.length > 0),
)

/** NSelect 受控值：直接用 modelValue；允许用户输入不在列表中的字面量/引用 */
function onChange(val: string | null) {
  if (props.disabled) return
  emit('update:modelValue', val ?? '')
}
</script>

<template>
  <NSelect
    class="sun-field var-ref-picker"
    filterable
    tag
    :disabled="disabled"
    :value="modelValue"
    :options="selectOptions"
    :placeholder="placeholder ?? (hasOptions ? '选择上游变量或输入字面量' : '暂无上游变量')"
    @update:value="onChange"
  />
</template>

<style scoped>
.var-ref-picker :deep(.n-base-selection) {
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
</style>
