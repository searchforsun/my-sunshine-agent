<script setup lang="ts">
import { onMounted } from 'vue'
import { NFormItem, NInputNumber, NSelect } from 'naive-ui'
import ConfigFieldHelp from '../knowledge/ConfigFieldHelp.vue'
import type { WorkflowNodeDefaultsResponse } from '../../api/workflows'
import {
  ON_FAILURE_OPTIONS,
  RETRY_PARAM_KEYS,
  buildRetryParams,
  hasRetryParams,
  patchNodeParams,
  readRetryBackoffMs,
  readRetryMaxAttempts,
  readRetryOnFailure,
  resolveNodeDefaults,
} from '../../utils/workflowNodeParams'
import { workflowRetryFieldHelp } from './workflowFieldHelp'

const props = defineProps<{
  nodeType: string
  params?: Record<string, unknown>
  readOnly: boolean
  nodeDefaults: WorkflowNodeDefaultsResponse | null
}>()

const emit = defineEmits<{
  'update:params': [Record<string, unknown>]
}>()

function emitPatch(patch: Record<string, string | number | null | undefined>) {
  emit('update:params', patchNodeParams(props.params, patch))
}

/** 展示用默认值未写入 params 时，选中节点即物化到 plan */
onMounted(() => {
  if (props.readOnly || hasRetryParams(props.params)) return
  emit('update:params', {
    ...(props.params ?? {}),
    ...buildRetryParams(props.nodeType, resolveNodeDefaults(props.nodeDefaults)),
  })
})
</script>

<template>
  <div class="execution-policy">
    <NFormItem>
      <template #label>
        <span class="field-label-row">最大重试次数<ConfigFieldHelp :text="workflowRetryFieldHelp('maxAttempts')" /></span>
      </template>
      <NInputNumber
        class="sun-field"
        :value="readRetryMaxAttempts(params, nodeType, nodeDefaults)"
        :disabled="readOnly"
        :min="1"
        :max="10"
        @update:value="v => emitPatch({ [RETRY_PARAM_KEYS.maxAttempts]: v ?? 1 })"
      />
    </NFormItem>
    <NFormItem>
      <template #label>
        <span class="field-label-row">重试间隔 (ms)<ConfigFieldHelp :text="workflowRetryFieldHelp('backoffMs')" /></span>
      </template>
      <NInputNumber
        class="sun-field"
        :value="readRetryBackoffMs(params, nodeType, nodeDefaults)"
        :disabled="readOnly"
        :min="100"
        :max="30000"
        :step="100"
        @update:value="v => emitPatch({ [RETRY_PARAM_KEYS.backoffMs]: v ?? 500 })"
      />
    </NFormItem>
    <NFormItem>
      <template #label>
        <span class="field-label-row">失败后策略<ConfigFieldHelp :text="workflowRetryFieldHelp('onFailure')" /></span>
      </template>
      <NSelect
        class="sun-field"
        :value="readRetryOnFailure(params, nodeType, nodeDefaults)"
        :disabled="readOnly"
        :options="[...ON_FAILURE_OPTIONS]"
        @update:value="v => emitPatch({ [RETRY_PARAM_KEYS.onFailure]: v ?? 'continue' })"
      />
    </NFormItem>
  </div>
</template>

<style scoped>
.execution-policy {
  margin: 0;
  padding: 0;
  border: none;
}

.field-label-row {
  display: inline-flex;
  align-items: center;
  gap: 2px;
}

.execution-policy :deep(.n-input),
.execution-policy :deep(.n-input-number .n-input),
.execution-policy :deep(.n-base-selection) {
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

.execution-policy :deep(.n-input-number .n-input.n-input--disabled) {
  background-color: var(--sun-black) !important;
}
</style>
