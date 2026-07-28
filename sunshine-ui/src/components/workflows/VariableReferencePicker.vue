<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { NInput, NSelect, type SelectGroupOption, type SelectOption } from 'naive-ui'
import ConfigFieldHelp from '../knowledge/ConfigFieldHelp.vue'
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

/** 输入模式：reference（从上游选择变量）| literal（输入字面量/固定值） */
const inputMode = ref<'reference' | 'literal'>('reference')

/** 是否已有值且非引用格式（自动检测为字面量模式） */
watch(
  () => props.modelValue,
  (val) => {
    if (!val) {
      inputMode.value = 'reference'
      return
    }
    const trimmed = val.trim()
    if (trimmed.startsWith('{{') && trimmed.endsWith('}}')) {
      inputMode.value = 'reference'
    } else {
      inputMode.value = 'literal'
    }
  },
  { immediate: true },
)

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
        label: f.children?.length
          ? `${f.name} · ${f.type}（可展开子字段）`
          : `${f.name} · ${f.type}`,
        value: `{{${node.id}.${f.name}}}`,
      })) as SelectOption[],
    })
  }
  return groups
})

const hasOptions = computed(() =>
  selectOptions.value.some(
    (g) => 'type' in g && g.type === 'group' && Array.isArray(g.children) && g.children.length > 0,
  ),
)

/** 选中一级字段后，在路径编辑框中预填，用户可继续编辑嵌套路径 */
const editablePath = ref('')

watch(
  () => props.modelValue,
  (val) => {
    editablePath.value = val
  },
  { immediate: true },
)

function onSelectRef(val: string | null) {
  if (props.disabled) return
  const ref = val ?? ''
  editablePath.value = ref
  emit('update:modelValue', ref)
}

function onPathEdit(val: string) {
  if (props.disabled) return
  editablePath.value = val
  emit('update:modelValue', val)
}

function onLiteralInput(val: string) {
  if (props.disabled) return
  emit('update:modelValue', val)
}

function switchMode(mode: 'reference' | 'literal') {
  if (props.disabled) return
  inputMode.value = mode
  // 切换到字面量时清空当前引用；切换到引用时清空字面量
  if (mode === 'literal' && props.modelValue.trim().startsWith('{{')) {
    emit('update:modelValue', '')
    editablePath.value = ''
  } else if (mode === 'reference' && !props.modelValue.trim().startsWith('{{')) {
    emit('update:modelValue', '')
    editablePath.value = ''
  }
}
</script>

<template>
  <div class="var-ref-picker-wrapper">
    <!-- 模式切换 -->
    <div class="var-ref-mode-tabs">
      <button
        type="button"
        class="var-ref-mode-tab"
        :class="{ active: inputMode === 'reference' }"
        :disabled="disabled"
        @click="switchMode('reference')"
      >
        变量引用
      </button>
      <button
        type="button"
        class="var-ref-mode-tab"
        :class="{ active: inputMode === 'literal' }"
        :disabled="disabled"
        @click="switchMode('literal')"
      >
        字面量
      </button>
      <ConfigFieldHelp
        text="变量引用：从上游节点输出中选择变量，支持嵌套路径（如 {{node.output.id}}）。选择一级字段后可在下方输入框中继续追加 .fieldName。

字面量：直接输入固定值（如 approved、100、true）。切换模式时会清空当前值。"
      />
    </div>

    <!-- 引用模式：下拉选择 + 路径编辑 -->
    <template v-if="inputMode === 'reference'">
      <NSelect
        class="sun-field var-ref-select"
        filterable
        :disabled="disabled"
        :value="modelValue"
        :options="selectOptions"
        :placeholder="placeholder ?? (hasOptions ? '选择上游变量' : '暂无上游变量')"
        @update:value="onSelectRef"
      />
      <!-- 路径编辑：选中后可继续编辑嵌套路径 -->
      <div v-if="modelValue" class="var-ref-path-edit">
        <NInput
          class="sun-field var-ref-path-input"
          :disabled="disabled"
          :value="editablePath"
          placeholder="{{nodeId.output.fieldName}}"
          @update:value="onPathEdit"
        />
      </div>
    </template>

    <!-- 字面量模式：纯文本输入 -->
    <template v-else>
      <NInput
        class="sun-field var-ref-literal-input"
        :disabled="disabled"
        :value="modelValue"
        placeholder="输入固定值"
        @update:value="onLiteralInput"
      />
    </template>
  </div>
</template>

<style scoped>
.var-ref-picker-wrapper {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.var-ref-mode-tabs {
  display: flex;
  gap: 0;
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-sm);
  overflow: hidden;
}
.var-ref-mode-tab {
  flex: 1;
  padding: 4px 8px;
  font-size: var(--sun-font-xs);
  border: none;
  background: transparent;
  color: var(--sun-text-muted);
  cursor: pointer;
  transition: all 0.15s;
}
.var-ref-mode-tab + .var-ref-mode-tab {
  border-left: 1px solid var(--sun-border);
}
.var-ref-mode-tab:hover:not(:disabled) {
  color: var(--sun-text);
}
.var-ref-mode-tab.active {
  background: color-mix(in srgb, var(--sun-border) 30%, transparent);
  color: var(--sun-text);
  font-weight: 500;
}
.var-ref-mode-tab:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}
.var-ref-mode-tabs :deep(.field-help-btn) {
  margin: 0 6px;
  align-self: center;
}
.var-ref-select :deep(.n-base-selection) {
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
.var-ref-path-edit {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.var-ref-path-input :deep(.n-input__input-el) {
  font-family: var(--sun-font-mono);
  font-size: var(--sun-font-sm);
  color: var(--sun-text);
}
.var-ref-literal-input :deep(.n-input__input-el) {
  font-family: var(--sun-font-mono);
  font-size: var(--sun-font-sm);
}
</style>
