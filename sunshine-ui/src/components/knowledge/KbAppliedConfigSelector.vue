<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { NSelect, NTag } from 'naive-ui'
import { listKbConfigVersions } from '../../api/ragAdmin'
import type { TenantId } from '../../api/tenants'
import { useKbWorkbenchContext } from '../../composables/useKbWorkbenchContext'
import {
  appliedConfigVersions,
  buildAppliedConfigForVersion,
  configVersionStatusLabel,
  configVersionStatusTagType,
  configVersionTimeLabel,
  findDefaultAppliedVersion,
  isDraftConfigVersion,
  resolveConfigVersionStatus,
} from '../../utils/kbConfigVersion'

const props = defineProps<{
  tenantId: TenantId
  kbId: string | null
}>()

const wb = useKbWorkbenchContext()
const loading = ref(false)

const applyCandidates = computed(() => appliedConfigVersions(wb.configVersions.value))

const options = computed(() =>
  applyCandidates.value.map((v) => ({
    label: configVersionTimeLabel(v),
    value: v.id,
  })),
)

const selectedVersion = computed(() =>
  applyCandidates.value.find((v) => v.id === wb.appliedConfig.value.versionId) ?? null,
)

const appliedStatus = computed(() => {
  const ver = selectedVersion.value
  return ver ? resolveConfigVersionStatus(ver) : null
})

const appliedStatusTagType = computed(() => {
  const status = appliedStatus.value
  return status ? configVersionStatusTagType(status) : 'default'
})

const selectedId = computed({
  get: () => wb.appliedConfig.value.versionId,
  set: (id: number | null) => {
    if (id == null) return
    const ver = applyCandidates.value.find((v) => v.id === id)
    if (ver) {
      wb.setAppliedConfig(buildAppliedConfigForVersion(ver))
    }
  },
})

async function refreshVersions() {
  if (!props.kbId) {
    wb.setConfigVersions([])
    wb.resetAppliedConfig()
    return
  }
  loading.value = true
  try {
    const list = await listKbConfigVersions(props.tenantId, props.kbId)
    wb.setConfigVersions(list)
    const currentId = wb.appliedConfig.value.versionId
    const current = currentId != null ? list.find((v) => v.id === currentId) : null
    const stillValid = current != null && !isDraftConfigVersion(current)
    if (!stillValid) {
      const fallback = findDefaultAppliedVersion(list)
      if (fallback) {
        wb.setAppliedConfig(buildAppliedConfigForVersion(fallback))
      }
    }
  } finally {
    loading.value = false
  }
}

watch(
  () => [props.tenantId, props.kbId, wb.revision.value] as const,
  () => {
    void refreshVersions()
  },
  { immediate: true },
)
</script>

<template>
  <div v-if="kbId && options.length > 0" class="applied-config-row">
    <NTag
      v-if="appliedStatus"
      size="small"
      :bordered="false"
      round
      :type="appliedStatusTagType"
    >
      {{ configVersionStatusLabel(appliedStatus) }}
    </NTag>
    <NSelect
      v-model:value="selectedId"
      :options="options"
      size="small"
      class="applied-config-select"
      placeholder="选择应用配置"
      :loading="loading"
      :disabled="loading"
      :menu-props="{ class: 'kb-applied-config-menu' }"
    />
  </div>
</template>

<style scoped>
.applied-config-row {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.applied-config-select {
  width: min(200px, 32vw);
  flex-shrink: 1;
}

.applied-config-select :deep(.n-base-selection) {
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

<style>
.kb-applied-config-menu.n-base-select-menu {
  --n-color: var(--sun-black) !important;
  --n-option-color-active: transparent !important;
  --n-option-color-active-pending: var(--sun-row-hover) !important;
  --n-option-color-pending: var(--sun-row-hover) !important;
  --n-option-text-color: var(--sun-text) !important;
  --n-option-text-color-active: var(--sun-text) !important;
  --n-option-check-color: var(--sun-text) !important;
  background: var(--sun-black) !important;
  border: 1px solid var(--sun-border) !important;
  box-shadow: var(--shadow-elevated) !important;
}
</style>
