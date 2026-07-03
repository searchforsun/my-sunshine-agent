<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { NTabPane, NTabs } from 'naive-ui'
import { listEvalSuites, type EvalSuiteSummary, type KbDocument } from '../../api/ragAdmin'
import type { TenantId } from '../../api/tenants'
import KbEvalRunTab from './KbEvalRunTab.vue'
import KbEvalSuiteTab from './KbEvalSuiteTab.vue'
import { useKbWorkbenchContext, useKbPanelLoad } from '../../composables/useKbWorkbenchContext'
import { DEFAULT_EVAL_SUITE_KEY } from '../../utils/evalConstants'

const props = defineProps<{
  tenantId: TenantId
  kbId: string | null
  kbDisplayName?: string
  documents: KbDocument[]
  loadingDocs?: boolean
}>()

defineEmits<{
  refreshDocuments: []
}>()

const wb = useKbWorkbenchContext()
const panelLoad = useKbPanelLoad(wb.revision)

const activeTab = ref('run')
const suiteKey = ref(DEFAULT_EVAL_SUITE_KEY)
const suites = ref<EvalSuiteSummary[]>([])

const runTabRef = ref<InstanceType<typeof KbEvalRunTab> | null>(null)
const suiteTabRef = ref<InstanceType<typeof KbEvalSuiteTab> | null>(null)

async function loadSuites() {
  try {
    suites.value = await listEvalSuites(props.tenantId)
    if (!suites.value.some((s) => s.suiteKey === suiteKey.value) && suites.value.length > 0) {
      suiteKey.value =
        suites.value.find((s) => s.suiteKey === DEFAULT_EVAL_SUITE_KEY)?.suiteKey ?? suites.value[0].suiteKey
    }
  } catch {
    // ignore
  }
}

function onSuitesChanged() {
  void loadSuites()
  void runTabRef.value?.reloadHistory()
}

function resetPanelState() {
  suiteKey.value = DEFAULT_EVAL_SUITE_KEY
  activeTab.value = 'run'
  runTabRef.value?.reset()
}

async function reloadOnWorkbenchChange() {
  const signal = panelLoad.beginLoad()
  resetPanelState()
  await loadSuites()
  if (signal.aborted) return
}

watch(() => wb.revision.value, () => { void reloadOnWorkbenchChange() })
onMounted(() => { void reloadOnWorkbenchChange() })
</script>

<template>
  <div class="eval-panel">
    <NTabs v-model:value="activeTab" type="line" :animated="false" class="eval-tabs">
      <NTabPane name="run" tab="运行评测">
        <KbEvalRunTab
          ref="runTabRef"
          :tenant-id="tenantId"
          :kb-id="kbId"
          :suites="suites"
          :suite-key="suiteKey"
          @update:suite-key="suiteKey = $event"
        />
      </NTabPane>
      <NTabPane name="suites" tab="评测脚本">
        <KbEvalSuiteTab
          ref="suiteTabRef"
          :tenant-id="tenantId"
          :kb-id="kbId"
          :kb-display-name="kbDisplayName"
          :selected-suite-key="suiteKey"
          :documents="documents"
          :loading-docs="loadingDocs"
          @update:selected-suite-key="suiteKey = $event"
          @suites-changed="onSuitesChanged"
          @refresh-documents="$emit('refreshDocuments')"
        />
      </NTabPane>
    </NTabs>
  </div>
</template>

<style scoped>
.eval-panel {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.eval-tabs {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
.eval-tabs :deep(.n-tabs-nav) {
  flex-shrink: 0;
}
.eval-tabs :deep(.n-tab-pane) {
  flex: 1;
  min-height: 0;
  overflow: hidden;
  padding-top: 12px !important;
  display: flex;
  flex-direction: column;
}
</style>
