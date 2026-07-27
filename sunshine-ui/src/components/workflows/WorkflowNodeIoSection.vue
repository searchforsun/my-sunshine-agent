<script setup lang="ts">
import { computed, ref } from 'vue'
import { useMessage } from 'naive-ui'
import CopyToggleIcon from '../icons/CopyToggleIcon.vue'
import type { ToolCatalogEntry } from '../../api/tools'
import type { WorkflowPlanNode } from '../../api/workflows'
import WorkflowNodeConfigSection from './WorkflowNodeConfigSection.vue'
import { workflowNodeFieldHelp } from './workflowFieldHelp'
import { copyText } from '../../utils/stream-markdown/clipboard'
import { nodeOutputRefs } from '../../utils/workflowNodeIo'
import '../../utils/stream-markdown/styles.css'

const props = defineProps<{
  node: WorkflowPlanNode
  readOnly: boolean
  toolCatalog?: ToolCatalogEntry | null
}>()

const message = useMessage()
const copiedRef = ref<string | null>(null)
let copyResetTimer: ReturnType<typeof setTimeout> | null = null

const outputRefs = computed(() => nodeOutputRefs(props.node, props.toolCatalog))

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
</script>

<template>
  <WorkflowNodeConfigSection title="输出" :help="workflowNodeFieldHelp('nodeOutputs')">
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
