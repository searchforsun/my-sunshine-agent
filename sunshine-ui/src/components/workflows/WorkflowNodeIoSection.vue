<script setup lang="ts">
import { computed, ref } from 'vue'
import { NInput, NSelect, useMessage } from 'naive-ui'
import CopyToggleIcon from '../icons/CopyToggleIcon.vue'
import type { ToolCatalogEntry } from '../../api/tools'
import type { WorkflowPlanNode } from '../../api/workflows'
import WorkflowNodeConfigSection from './WorkflowNodeConfigSection.vue'
import ConfigFieldHelp from '../knowledge/ConfigFieldHelp.vue'
import { workflowNodeFieldHelp } from './workflowFieldHelp'
import { copyText } from '../../utils/stream-markdown/clipboard'
import {
  defaultToolOutputExtract,
  nodeOutputRefs,
  parseExtractKeys,
  toolExtractPresets,
  toolOutputExtract,
  toolOutputMode,
  TOOL_OUTPUT_MODE_OPTIONS,
  type ToolOutputMode,
} from '../../utils/workflowNodeIo'
import '../../utils/stream-markdown/styles.css'

const props = defineProps<{
  node: WorkflowPlanNode
  readOnly: boolean
  toolCatalog?: ToolCatalogEntry | null
}>()

const emit = defineEmits<{
  'update:output-mode': [mode: ToolOutputMode]
  'update:output-extract': [json: string]
}>()

const message = useMessage()
const copiedRef = ref<string | null>(null)
let copyResetTimer: ReturnType<typeof setTimeout> | null = null

const outputRefs = computed(() => nodeOutputRefs(props.node, props.toolCatalog))
const extractKeys = computed(() => parseExtractKeys(toolOutputExtract(props.node.params)))
const showExtractEditor = computed(() =>
  props.node.type === 'tool' && toolOutputMode(props.node.params) === 'extract',
)

async function onCopyRef(refText: string) {
  const ok = await copyText(refText)
  if (ok) {
    copiedRef.value = refText
    if (copyResetTimer) clearTimeout(copyResetTimer)
    copyResetTimer = setTimeout(() => {
      if (copiedRef.value === refText) copiedRef.value = null
    }, 2000)
    message.success('已复制引用')
  } else {
    message.warning('复制失败，请手动选择复制')
  }
}

function onModeChange(mode: ToolOutputMode) {
  if (props.readOnly) return
  emit('update:output-mode', mode)
  if (mode === 'extract' && !toolOutputExtract(props.node.params)) {
    emit('update:output-extract', defaultToolOutputExtract(props.toolCatalog))
  }
}

function onExtractChange(val: string) {
  if (props.readOnly) return
  emit('update:output-extract', val)
}

function applyPreset(value: string) {
  if (props.readOnly) return
  emit('update:output-extract', value)
}
</script>

<template>
  <WorkflowNodeConfigSection title="输出" :help="workflowNodeFieldHelp('nodeOutputs')">
    <template v-if="node.type === 'tool'">
      <div class="wf-out-row">
        <label class="wf-out-label">
          下游引用格式
          <ConfigFieldHelp :text="workflowNodeFieldHelp('toolOutputMode')" />
        </label>
        <NSelect
          class="sun-field"
          :disabled="readOnly"
          :value="toolOutputMode(node.params)"
          :options="TOOL_OUTPUT_MODE_OPTIONS"
          @update:value="v => onModeChange(v as ToolOutputMode)"
        />
      </div>
      <div v-if="showExtractEditor" class="wf-out-extract">
        <label class="wf-out-label">
          提取表达式
          <ConfigFieldHelp :text="workflowNodeFieldHelp('toolOutputExtract')" />
        </label>
        <div class="wf-preset-row">
          <button
            v-for="preset in toolExtractPresets()"
            :key="preset.label"
            type="button"
            class="wf-preset-btn"
            :disabled="readOnly"
            @click="applyPreset(preset.value)"
          >
            {{ preset.label }}
          </button>
        </div>
        <NInput
          class="sun-field wf-mono-input"
          type="textarea"
          :disabled="readOnly"
          :autosize="{ minRows: 2, maxRows: 6 }"
          :value="toolOutputExtract(node.params)"
          placeholder='{"count":"regex:共\\s*(\\d+)\\s*条"}'
          @update:value="onExtractChange"
        />
        <p v-if="extractKeys.length" class="wf-out-note">解析字段：{{ extractKeys.join('、') }}</p>
      </div>
    </template>
    <div v-if="outputRefs.length" class="wf-ref-list">
      <div v-for="item in outputRefs" :key="item.ref" class="wf-ref-card">
        <code class="wf-ref-expr">{{ item.ref }}</code>
        <span class="wf-ref-desc">{{ item.label }}</span>
        <button
          type="button"
          class="wf-ref-copy smd-toolbtn"
          :title="copiedRef === item.ref ? '已复制' : '复制'"
          @click.stop="onCopyRef(item.ref)"
        >
          <CopyToggleIcon :copied="copiedRef === item.ref" />
        </button>
      </div>
    </div>
    <p v-else class="wf-out-note">本节点无下游可引用输出</p>
  </WorkflowNodeConfigSection>
</template>

<style scoped>
.wf-out-row,
.wf-out-extract {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.wf-out-label {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  color: var(--sun-text-secondary);
}
.wf-preset-row {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.wf-preset-btn {
  padding: 3px 10px;
  font-size: var(--sun-font-xs);
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--sun-text-secondary);
  cursor: pointer;
}
.wf-preset-btn:hover:not(:disabled) {
  border-color: var(--sun-border-light);
  color: var(--sun-text);
}
.wf-preset-btn:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}
.wf-mono-input :deep(.n-input__textarea-el),
.wf-mono-input :deep(.n-input__input-el) {
  font-family: var(--sun-font-mono);
  font-size: var(--sun-font-sm);
  line-height: 1.5;
}
.wf-ref-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.wf-ref-card {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto 28px;
  align-items: center;
  gap: 8px;
  padding: 6px 6px 6px 10px;
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-sm);
}
.wf-ref-expr {
  font-family: var(--sun-font-mono);
  font-size: var(--sun-font-sm);
  color: var(--sun-text);
  word-break: break-all;
  line-height: 1.45;
}
.wf-ref-desc {
  font-size: var(--sun-font-xs);
  color: var(--sun-text-muted);
  white-space: nowrap;
}
.wf-out-note {
  margin: 0;
  font-size: var(--sun-font-xs);
  color: var(--sun-text-muted);
}
</style>
