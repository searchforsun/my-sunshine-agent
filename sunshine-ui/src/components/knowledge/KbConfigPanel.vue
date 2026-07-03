<script setup lang="ts">
import { computed, h, onMounted, ref, toRaw, watch } from 'vue'
import {
  NAlert,
  NButton,
  NDropdown,
  NForm,
  NFormItem,
  NIcon,
  NInput,
  NInputNumber,
  NSelect,
  NSpace,
  NSwitch,
  NSpin,
  NTag,
  NText,
  useMessage,
  type DropdownOption,
} from 'naive-ui'
import {
  CloudUploadOutline,
  CreateOutline,
  DownloadOutline,
  EllipsisHorizontal,
} from '@vicons/ionicons5'
import {
  PublishGateError,
  ConfigVersionConflictError,
  activateKbConfigVersion,
  fetchKbConfigDraft,
  fetchKbConfigEffective,
  fetchKbConfigSchema,
  forkKbConfigVersion,
  listKbConfigVersions,
  publishKbConfigBundle,
  revertKbConfigVersionToDraft,
  saveKbConfigDraft,
  type ConfigScopeGroup,
  type ConfigVersionSummary,
  type FailedEvalSample,
} from '../../api/ragAdmin'
import type { TenantId } from '../../api/tenants'
import { friendlyErrorMessage } from '../../api/apiError'
import ConfigFieldHelp from './ConfigFieldHelp.vue'
import { fieldHelp, scopeHelp } from './kbConfigFieldHelp'
import {
  useKbWorkbenchContext,
  useKbPanelLoad,
} from '../../composables/useKbWorkbenchContext'
import {
  buildAppliedConfigForVersion,
  canEditConfigForm,
  canShowActivate,
  canShowCopyToDraft,
  canShowMoreMenu,
  canShowRevertToDraft,
  canShowSaveDraft,
  canShowSubmitEval,
  configVersionTimeLabel,
  configVersionStatusLabel,
  configVersionStatusTagType,
  isPipelineStatus,
  resolveConfigVersionStatus,
  type ConfigVersionUiStatus,
} from '../../utils/kbConfigVersion'
import { formatSkillVersionTimeForFilename } from '../../utils/formatSkillVersionTime'

const props = defineProps<{
  tenantId: TenantId
  kbId: string | null
}>()

const wb = useKbWorkbenchContext()
const panelLoad = useKbPanelLoad(wb.revision)
const message = useMessage()

const SCOPE_PATH: Record<string, string[]> = {
  'rag-search': ['search'],
  'rag-rerank': ['rerank'],
  'rag-chunk': ['chunk'],
  'rewrite-rag': ['rewrite', 'rag'],
  'rewrite-hyde': ['rewrite', 'hyde'],
  'rewrite-empty-recall': ['rewrite', 'emptyRecall'],
}

type VersionStatus = ConfigVersionUiStatus

const loading = ref(false)
const saving = ref(false)
const publishing = ref(false)
const forking = ref(false)
const activatingId = ref<number | null>(null)
const error = ref('')
const gateError = ref('')
const failedSamples = ref<FailedEvalSample[]>([])
const scopes = ref<ConfigScopeGroup[]>([])
const versions = computed(() => wb.configVersions.value)
const draftVersionId = ref<number | null>(null)
const bundlePayload = ref<Record<string, unknown>>({})
const formValues = ref<Record<string, Record<string, unknown>>>({})
const selectedVersionId = ref<number | null>(null)
const importInputRef = ref<HTMLInputElement | null>(null)
/** 草稿版本：默认只读，点击「编辑草稿」后进入编辑态 */
const draftEditing = ref(false)

const selectedVersion = computed(() =>
  versions.value.find((v) => v.id === selectedVersionId.value) ?? null,
)

const versionStatus = computed((): VersionStatus | null => {
  const ver = selectedVersion.value
  if (!ver) return null
  return resolveConfigVersionStatus(ver)
})

const canEdit = computed(
  () => canEditConfigForm(selectedVersion.value, versions.value) && draftEditing.value,
)

const showEditDraft = computed(
  () => canShowSaveDraft(selectedVersion.value, versions.value) && !draftEditing.value,
)

const showSaveDraftButton = computed(
  () => canShowSaveDraft(selectedVersion.value, versions.value) && draftEditing.value,
)

const showSaveDraft = computed(() => canShowSaveDraft(selectedVersion.value, versions.value))

const showSubmitEval = computed(
  () => canShowSubmitEval(selectedVersion.value, versions.value) && !draftEditing.value,
)

const showCopyToDraftButton = computed(() =>
  canShowCopyToDraft(selectedVersion.value, versions.value),
)

const showRevertToDraftButton = computed(() =>
  canShowRevertToDraft(selectedVersion.value, versions.value),
)

const showActivateButton = computed(() =>
  canShowActivate(selectedVersion.value, versions.value),
)

const showMoreMenu = computed(() => canShowMoreMenu(versions.value))

const versionOptions = computed(() =>
  versions.value.map((v) => ({
    label: configVersionTimeLabel(v),
    value: v.id,
  })),
)

const moreMenuOptions = computed((): DropdownOption[] => {
  const opts: DropdownOption[] = []
  if (showRevertToDraftButton.value) {
    opts.push({
      label: '转为草稿',
      key: 'revert',
      icon: () => h(NIcon, { component: CreateOutline, size: 14 }),
      disabled: forking.value,
    })
  }
  if (showCopyToDraftButton.value) {
    opts.push({
      label: '复制为草稿',
      key: 'fork',
      icon: () => h(NIcon, { component: CreateOutline, size: 14 }),
      disabled: forking.value,
    })
  }
  opts.push({
    label: '导入 JSON',
    key: 'import',
    icon: () => h(NIcon, { component: CloudUploadOutline, size: 14 }),
    disabled: !canEdit.value,
  })
  opts.push({
    label: '导出 JSON',
    key: 'export',
    icon: () => h(NIcon, { component: DownloadOutline, size: 14 }),
  })
  return opts
})

function versionStatusLabel(status: VersionStatus): string {
  return configVersionStatusLabel(status)
}

function versionStatusTagType(status: VersionStatus): 'success' | 'warning' | 'error' | 'info' | 'default' {
  return configVersionStatusTagType(status)
}

function isAppliedVersion(versionId: number | null): boolean {
  return versionId != null && wb.appliedConfig.value.versionId === versionId
}

function sectionNode(payload: Record<string, unknown>, scope: string): Record<string, unknown> {
  const path = SCOPE_PATH[scope]
  if (!path) return {}
  let node: unknown = payload
  for (const key of path) {
    if (!(node instanceof Object) || node === null) return {}
    node = (node as Record<string, unknown>)[key]
  }
  return node instanceof Object && node !== null ? (node as Record<string, unknown>) : {}
}

function readField(payload: Record<string, unknown>, scope: string, fieldId: string): unknown {
  return sectionNode(payload, scope)[fieldId]
}

function writeField(payload: Record<string, unknown>, scope: string, fieldId: string, value: unknown) {
  const path = SCOPE_PATH[scope]
  if (!path) return
  let node: Record<string, unknown> = payload
  for (let i = 0; i < path.length; i++) {
    const key = path[i]
    if (i === path.length - 1) {
      const section = (node[key] as Record<string, unknown> | undefined) ?? {}
      section[fieldId] = value
      node[key] = section
      return
    }
    const next = (node[key] as Record<string, unknown> | undefined) ?? {}
    node[key] = next
    node = next
  }
}

function scopeValues(scope: string): Record<string, unknown> {
  if (!formValues.value[scope]) {
    formValues.value[scope] = {}
  }
  return formValues.value[scope]
}

function initScopeForm(scopeGroup: ConfigScopeGroup, payload: Record<string, unknown>) {
  const next: Record<string, unknown> = {}
  for (const field of scopeGroup.fields) {
    const draftVal = readField(payload, scopeGroup.scope, field.fieldId)
    next[field.fieldId] = draftVal !== undefined ? draftVal : field.currentValue
  }
  formValues.value[scopeGroup.scope] = next
}

function cloneConfigPayload(payload: Record<string, unknown>): Record<string, unknown> {
  return JSON.parse(JSON.stringify(toRaw(payload) ?? {})) as Record<string, unknown>
}

function buildPayloadFromForm(): Record<string, unknown> {
  const payload = cloneConfigPayload(bundlePayload.value)
  for (const scopeGroup of scopes.value) {
    for (const [fieldId, value] of Object.entries(scopeValues(scopeGroup.scope))) {
      writeField(payload, scopeGroup.scope, fieldId, value)
    }
  }
  return payload
}

function applyPayloadToForm(payload: Record<string, unknown>) {
  bundlePayload.value = payload
  formValues.value = {}
  for (const scopeGroup of scopes.value) {
    initScopeForm(scopeGroup, payload)
  }
}

async function loadVersionPayload(versionId: number, signal: AbortSignal) {
  if (!props.kbId) return
  const ver = versions.value.find((v) => v.id === versionId)
  if (!ver) return
  let payload: Record<string, unknown>
  if (ver.status === 'draft') {
    const draft = await fetchKbConfigDraft(props.tenantId, props.kbId)
    if (signal.aborted) return
    payload = draft.payload ?? {}
    draftVersionId.value = draft.draftVersionId
  } else if (ver.active || ver.status === 'active') {
    payload = await fetchKbConfigEffective(props.tenantId, props.kbId, 'published')
    if (signal.aborted) return
  } else {
    payload = await fetchKbConfigEffective(props.tenantId, props.kbId, 'version', versionId)
    if (signal.aborted) return
  }
  if (isPipelineStatus(ver.status)) {
    draftVersionId.value = ver.id
  }
  applyPayloadToForm(payload)
}

/** 变更后拉取最新版本列表并通知各 Tab 刷新 */
async function notifyConfigChanged(opts?: { appliedVersionId?: number }) {
  if (!props.kbId) return
  const list = await listKbConfigVersions(props.tenantId, props.kbId)
  wb.setConfigVersions(list)
  const pipelineVer = list.find((v) => isPipelineStatus(v.status))
  draftVersionId.value = pipelineVer?.id ?? null
  if (opts?.appliedVersionId != null) {
    const ver = list.find((v) => v.id === opts.appliedVersionId)
    if (ver) {
      wb.setAppliedConfig(buildAppliedConfigForVersion(ver))
    }
  }
  wb.bumpRevision()
}

async function loadPanel(signal: AbortSignal) {
  if (!props.kbId) return
  loading.value = true
  error.value = ''
  gateError.value = ''
  failedSamples.value = []
  try {
    const [schema, versionList] = await Promise.all([
      fetchKbConfigSchema(props.tenantId, props.kbId),
      listKbConfigVersions(props.tenantId, props.kbId),
    ])
    if (signal.aborted) return
    scopes.value = schema.scopes
    wb.setConfigVersions(versionList)
    const pipelineVer = versionList.find((v) => isPipelineStatus(v.status))
    draftVersionId.value = pipelineVer?.id ?? null
    const activeVer = versionList.find((v) => v.active || v.status === 'active')
    const keepId = selectedVersionId.value
    const keepStillExists = keepId != null && versionList.some((v) => v.id === keepId)
    selectedVersionId.value = keepStillExists
      ? keepId
      : (pipelineVer?.id ?? activeVer?.id ?? versionList[0]?.id ?? null)
    if (selectedVersionId.value != null) {
      await loadVersionPayload(selectedVersionId.value, signal)
    }
  } catch (e) {
    if (signal.aborted) return
    error.value = friendlyErrorMessage(e, '加载参数配置失败')
  } finally {
    if (!signal.aborted) loading.value = false
  }
}

function resetPanelState() {
  scopes.value = []
  wb.setConfigVersions([])
  formValues.value = {}
  bundlePayload.value = {}
  draftVersionId.value = null
  selectedVersionId.value = null
  draftEditing.value = false
  error.value = ''
  gateError.value = ''
  failedSamples.value = []
}

async function reloadOnWorkbenchChange() {
  const signal = panelLoad.beginLoad()
  resetPanelState()
  await loadPanel(signal)
}

async function onVersionSelected(versionId: number) {
  if (versionId === selectedVersionId.value) return
  draftEditing.value = false
  selectedVersionId.value = versionId
  loading.value = true
  error.value = ''
  try {
    const signal = panelLoad.beginLoad()
    await loadVersionPayload(versionId, signal)
  } catch (e) {
    error.value = friendlyErrorMessage(e, '加载版本失败')
  } finally {
    loading.value = false
  }
}

async function handleSaveAll() {
  if (!props.kbId || !canEdit.value || scopes.value.length === 0) return
  saving.value = true
  error.value = ''
  try {
    const payload = buildPayloadFromForm()
    await saveKbConfigDraft(props.tenantId, props.kbId, payload)
    bundlePayload.value = payload
    draftEditing.value = false
    message.success('草稿已保存')
  } catch (e) {
    error.value = friendlyErrorMessage(e, '保存草稿失败')
  } finally {
    saving.value = false
  }
}

async function startDraftEditing() {
  if (!showSaveDraft.value || !props.kbId) return
  const versionId = selectedVersionId.value
  if (versionId == null) return
  loading.value = true
  error.value = ''
  try {
    const signal = panelLoad.beginLoad()
    const versionList = await listKbConfigVersions(props.tenantId, props.kbId)
    if (signal.aborted) return
    wb.setConfigVersions(versionList)
    draftVersionId.value = versionList.find((v) => isPipelineStatus(v.status))?.id ?? null
    await loadVersionPayload(versionId, signal)
    if (signal.aborted) return
    draftEditing.value = true
  } catch (e) {
    error.value = friendlyErrorMessage(e, '加载草稿失败')
  } finally {
    loading.value = false
  }
}

async function handleSubmitEval() {
  if (!props.kbId || !showSubmitEval.value) return
  publishing.value = true
  error.value = ''
  gateError.value = ''
  failedSamples.value = []
  try {
    if (selectedVersion.value?.status === 'draft') {
      const payload = draftEditing.value ? buildPayloadFromForm() : cloneConfigPayload(bundlePayload.value)
      await saveKbConfigDraft(props.tenantId, props.kbId, payload)
      bundlePayload.value = payload
    }
    const submitted = await publishKbConfigBundle(props.tenantId, props.kbId)
    message.success('已提交为待评测，请在右上角切换应用配置后于「评测」Tab 运行评测')
    await notifyConfigChanged({ appliedVersionId: submitted.versionId })
  } catch (e) {
    if (e instanceof ConfigVersionConflictError) {
      error.value = e.message
    } else {
      error.value = friendlyErrorMessage(e, '提交评测失败')
    }
  } finally {
    publishing.value = false
  }
}

async function handleActivate(versionId: number) {
  if (!props.kbId) return
  activatingId.value = versionId
  error.value = ''
  gateError.value = ''
  try {
    await activateKbConfigVersion(props.tenantId, props.kbId, versionId)
    message.success('已生效')
    const list = await listKbConfigVersions(props.tenantId, props.kbId)
    const active = list.find((v) => v.active || v.status === 'active')
    await notifyConfigChanged({ appliedVersionId: active?.id })
  } catch (e) {
    if (e instanceof PublishGateError) {
      gateError.value = `生效失败：Recall@5 ${e.gate.recallAt5.toFixed(4)} 低于基线 ${e.gate.baselineRecallAt5.toFixed(4)}`
      failedSamples.value = e.gate.failedSamples ?? []
    } else if (e instanceof ConfigVersionConflictError) {
      error.value = e.message
    } else {
      error.value = friendlyErrorMessage(e, '生效失败')
    }
  } finally {
    activatingId.value = null
  }
}

async function handleRevertToDraft() {
  if (!props.kbId || selectedVersionId.value == null || !showRevertToDraftButton.value) return
  if (isAppliedVersion(selectedVersionId.value)) {
    message.warning('该版本正作为应用配置，请先切换到非草稿版本')
    return
  }
  forking.value = true
  error.value = ''
  try {
    await revertKbConfigVersionToDraft(props.tenantId, props.kbId, selectedVersionId.value)
    message.success('已转为草稿')
    await notifyConfigChanged()
  } catch (e) {
    if (e instanceof ConfigVersionConflictError) {
      error.value = e.message
    } else {
      error.value = friendlyErrorMessage(e, '转为草稿失败')
    }
  } finally {
    forking.value = false
  }
}

async function handleForkToDraft() {
  if (!props.kbId || selectedVersionId.value == null || !showCopyToDraftButton.value) return
  forking.value = true
  error.value = ''
  try {
    await forkKbConfigVersion(props.tenantId, props.kbId, selectedVersionId.value)
    message.success('已复制为草稿')
    await notifyConfigChanged()
  } catch (e) {
    if (e instanceof ConfigVersionConflictError) {
      error.value = e.message
    } else {
      error.value = friendlyErrorMessage(e, '复制草稿失败')
    }
  } finally {
    forking.value = false
  }
}

function handleExportJson() {
  const ver = selectedVersion.value
  if (!ver || !props.kbId) return
  const payload = canEdit.value ? buildPayloadFromForm() : cloneConfigPayload(bundlePayload.value)
  const blob = new Blob([JSON.stringify(payload, null, 2)], { type: 'application/json' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  const ts = formatSkillVersionTimeForFilename(
    ver.status === 'draft' ? ver.createdAt : (ver.publishedAt ?? ver.createdAt),
  )
  a.download = `${props.kbId}-config-${ts}.json`
  document.body.appendChild(a)
  a.click()
  a.remove()
  URL.revokeObjectURL(url)
  message.success('配置 JSON 已开始下载')
}

function triggerImportJson() {
  if (!showSaveDraft.value) {
    message.warning('请先切换到草稿版本再导入')
    return
  }
  if (!draftEditing.value) {
    message.warning('请先点击编辑草稿')
    return
  }
  importInputRef.value?.click()
}

async function onImportFileChange(ev: Event) {
  const input = ev.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file || !props.kbId) return
  try {
    const text = await file.text()
    const payload = JSON.parse(text) as Record<string, unknown>
    if (!payload || typeof payload !== 'object') {
      throw new Error('无效 JSON')
    }
    await saveKbConfigDraft(props.tenantId, props.kbId, payload)
    applyPayloadToForm(payload)
    draftEditing.value = false
    message.success('已导入到草稿')
  } catch (e) {
    error.value = friendlyErrorMessage(e, '导入 JSON 失败')
  }
}

function handleMoreMenuSelect(key: string) {
  if (key === 'revert') void handleRevertToDraft()
  else if (key === 'fork') void handleForkToDraft()
  else if (key === 'import') triggerImportJson()
  else if (key === 'export') handleExportJson()
}

function updateField(scope: string, fieldId: string, value: unknown) {
  if (!canEdit.value) return
  scopeValues(scope)[fieldId] = value
}

function compactFields(scopeGroup: ConfigScopeGroup) {
  return scopeGroup.fields.filter((field) => field.type !== 'text')
}

function promptFields(scopeGroup: ConfigScopeGroup) {
  return scopeGroup.fields.filter((field) => field.type === 'text')
}

function fieldLabel(fieldId: string, label: string) {
  if (fieldId === 'systemPrompt') return '系统提示词'
  return label
}

function promptCharCount(scope: string, fieldId: string) {
  return String(scopeValues(scope)[fieldId] ?? '').length
}

watch(
  () => wb.revision.value,
  () => {
    void reloadOnWorkbenchChange()
  },
)

onMounted(() => {
  void reloadOnWorkbenchChange()
})
</script>

<template>
  <div class="config-panel">
    <input
      ref="importInputRef"
      type="file"
      accept="application/json,.json"
      class="import-input"
      @change="onImportFileChange"
    />

    <div class="config-toolbar">
      <div v-if="versions.length > 0" class="config-actions">
        <div class="version-row">
          <span class="version-label">版本</span>
          <NTag
            v-if="versionStatus"
            size="small"
            :bordered="false"
            round
            :type="versionStatusTagType(versionStatus)"
          >
            {{ versionStatusLabel(versionStatus) }}
          </NTag>
          <NSelect
            :value="selectedVersionId"
            :options="versionOptions"
            size="small"
            class="version-select"
            placeholder="选择版本"
            :disabled="loading || saving || publishing"
            :menu-props="{ class: 'kb-config-version-menu' }"
            @update:value="onVersionSelected"
          />
          <NDropdown
            v-if="showMoreMenu"
            trigger="click"
            size="small"
            :options="moreMenuOptions"
            :disabled="loading || moreMenuOptions.length === 0"
            @select="handleMoreMenuSelect"
          >
            <NButton size="small" quaternary class="more-menu-btn" title="版本操作">
              <template #icon><NIcon :component="EllipsisHorizontal" :size="16" /></template>
            </NButton>
          </NDropdown>
        </div>
        <div v-if="showEditDraft || showSaveDraftButton || showSubmitEval || showActivateButton" class="action-group">
          <NButton
            v-if="showEditDraft"
            size="small"
            round
            secondary
            :disabled="loading || scopes.length === 0 || !kbId"
            @click="startDraftEditing"
          >
            编辑草稿
          </NButton>
          <NButton
            v-if="showSaveDraftButton"
            size="small"
            round
            secondary
            :loading="saving"
            :disabled="loading || scopes.length === 0 || !kbId"
            @click="handleSaveAll"
          >
            保存草稿
          </NButton>
          <NButton
            v-if="showSubmitEval"
            size="small"
            round
            type="primary"
            class="action-btn"
            :loading="publishing"
            :disabled="loading || !kbId || saving"
            @click="handleSubmitEval"
          >
            提交评测
          </NButton>
          <NButton
            v-if="showActivateButton"
            size="small"
            round
            type="primary"
            class="action-btn"
            :loading="activatingId === selectedVersionId"
            :disabled="loading || selectedVersionId == null"
            @click="selectedVersionId != null && handleActivate(selectedVersionId)"
          >
            生效
          </NButton>
        </div>
      </div>
    </div>

    <NAlert v-if="error" type="error" :bordered="false" class="config-alert">{{ error }}</NAlert>
    <NAlert v-if="gateError" type="warning" :bordered="false" class="config-alert">{{ gateError }}</NAlert>

    <NSpin :show="loading" class="config-spin">
      <div class="config-scroll">
        <section
          v-for="scopeGroup in scopes"
          :key="scopeGroup.scope"
          class="scope-section"
        >
          <header class="scope-header">
            <h3>
              {{ scopeGroup.label }}
              <ConfigFieldHelp :text="scopeHelp(scopeGroup.scope)" />
            </h3>
          </header>

          <NForm label-placement="top" size="small" class="config-form">
            <NFormItem
              v-for="field in compactFields(scopeGroup)"
              :key="field.fieldId"
            >
              <template #label>
                <span class="field-label-row">
                  {{ fieldLabel(field.fieldId, field.label) }}
                  <ConfigFieldHelp :text="fieldHelp(scopeGroup.scope, field.fieldId)" />
                </span>
              </template>
              <NSwitch
                v-if="field.type === 'boolean'"
                :value="Boolean(scopeValues(scopeGroup.scope)[field.fieldId])"
                :disabled="!canEdit"
                @update:value="(v: boolean) => updateField(scopeGroup.scope, field.fieldId, v)"
              />
              <NSelect
                v-else-if="field.type === 'enum'"
                :value="String(scopeValues(scopeGroup.scope)[field.fieldId] ?? '')"
                :options="(field.enumValues ?? []).map((v) => ({ label: v, value: v }))"
                class="field-control"
                :disabled="!canEdit"
                :menu-props="{ class: 'kb-config-select-menu' }"
                @update:value="(v: string) => updateField(scopeGroup.scope, field.fieldId, v)"
              />
              <NInputNumber
                v-else-if="field.type === 'number'"
                :value="Number(scopeValues(scopeGroup.scope)[field.fieldId] ?? 0)"
                :min="typeof field.min === 'number' ? field.min : undefined"
                :max="typeof field.max === 'number' ? field.max : undefined"
                class="field-control"
                :disabled="!canEdit"
                @update:value="(v: number | null) => updateField(scopeGroup.scope, field.fieldId, v ?? 0)"
              />
              <NInput
                v-else
                :value="String(scopeValues(scopeGroup.scope)[field.fieldId] ?? '')"
                class="field-control"
                :disabled="!canEdit"
                @update:value="(v: string) => updateField(scopeGroup.scope, field.fieldId, v)"
              />
            </NFormItem>
          </NForm>

          <div
            v-for="field in promptFields(scopeGroup)"
            :key="field.fieldId"
            class="prompt-block"
          >
            <div class="prompt-head">
              <span class="prompt-label">
                {{ fieldLabel(field.fieldId, field.label) }}
                <ConfigFieldHelp :text="fieldHelp(scopeGroup.scope, field.fieldId)" />
              </span>
              <NText depth="3" class="prompt-count">{{ promptCharCount(scopeGroup.scope, field.fieldId) }} 字</NText>
            </div>
            <NInput
              type="textarea"
              :value="String(scopeValues(scopeGroup.scope)[field.fieldId] ?? '')"
              :autosize="{ minRows: 8, maxRows: 24 }"
              :disabled="!canEdit"
              placeholder="仅草稿版本可编辑；可在右上角切换应用配置后在调试/评测 Tab 验证…"
              class="prompt-input"
              @update:value="(v: string) => updateField(scopeGroup.scope, field.fieldId, v)"
            />
          </div>
        </section>

        <div v-if="failedSamples.length > 0" class="failed-samples">
          <NText depth="3">未通过样本（前 {{ failedSamples.length }} 条）</NText>
          <ul>
            <li v-for="sample in failedSamples" :key="sample.queryId">
              {{ sample.queryId }} · {{ sample.query }}
            </li>
          </ul>
        </div>
      </div>
    </NSpin>
  </div>
</template>

<style scoped>
.config-panel {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  gap: 12px;
  overflow: hidden;
}

.import-input {
  display: none;
}

.config-toolbar {
  flex-shrink: 0;
  padding-bottom: 4px;
  border-bottom: 1px solid var(--sun-border);
}

.config-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  min-height: 32px;
}

.version-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: nowrap;
  min-width: 0;
}

.version-label {
  font-size: 13px;
  color: var(--sun-text-secondary);
  white-space: nowrap;
}

.version-select {
  width: min(220px, 36vw);
  flex-shrink: 1;
}

.version-select :deep(.n-base-selection) {
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

.more-menu-btn {
  flex-shrink: 0;
}

.action-group {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
  margin-left: auto;
}

.action-btn {
  --n-color: var(--sun-accent) !important;
  --n-color-hover: var(--sun-accent-hover) !important;
  --n-color-pressed: var(--sun-accent-hover) !important;
  --n-color-focus: var(--sun-accent-hover) !important;
  --n-text-color: var(--btn-primary-text) !important;
  --n-text-color-hover: var(--btn-primary-text) !important;
  --n-text-color-pressed: var(--btn-primary-text) !important;
  --n-text-color-focus: var(--btn-primary-text) !important;
  --n-border: none !important;
}

.config-alert {
  flex-shrink: 0;
}

.config-spin {
  flex: 1;
  min-height: 0;
}

.config-spin :deep(.n-spin-content) {
  height: 100%;
  min-height: 0;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

.config-scroll {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding-right: 4px;
}

.scope-section {
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-md);
  padding: 14px 16px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.scope-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.scope-header h3 {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  color: var(--sun-text);
  display: inline-flex;
  align-items: center;
}

.field-label-row {
  display: inline-flex;
  align-items: center;
}

.prompt-label {
  display: inline-flex;
  align-items: center;
  font-size: 13px;
  font-weight: 500;
  color: var(--sun-text);
}

.config-form {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: 10px 20px;
}

.field-control {
  width: 100%;
}

.config-form :deep(.n-base-selection) {
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
  --n-box-shadow-hover: none !important;
  --n-box-shadow-active: none !important;
}

.config-form :deep(.n-input),
.prompt-input {
  --n-color: var(--sun-black) !important;
  --n-color-focus: var(--sun-black) !important;
  --n-color-disabled: var(--sun-black) !important;
  --n-text-color: var(--sun-text) !important;
  --n-placeholder-color: var(--sun-text-muted) !important;
  --n-border: 1px solid var(--sun-border) !important;
  --n-border-hover: 1px solid var(--sun-border-light) !important;
  --n-border-focus: 1px solid var(--sun-border-light) !important;
  --n-box-shadow-focus: none !important;
}

.prompt-block {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-top: 4px;
  padding-top: 12px;
  border-top: 1px solid var(--sun-border);
}

.prompt-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
}

.prompt-count {
  font-size: 12px;
  flex-shrink: 0;
}

.prompt-input {
  width: 100%;
}

.prompt-input :deep(.n-input__textarea-el) {
  font-size: var(--sun-font-base, 14px);
  line-height: 1.6;
  font-family: inherit;
}

.failed-samples {
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-md);
  padding: 12px 16px;
}

.failed-samples ul {
  margin: 8px 0 0;
  padding-left: 18px;
  color: var(--sun-text-secondary);
  font-size: 13px;
  line-height: 1.5;
}
</style>

<style>
.kb-config-select-menu.n-base-select-menu,
.kb-config-version-menu.n-base-select-menu {
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
