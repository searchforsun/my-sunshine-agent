import { computed, h, onMounted, ref, toRaw, watch, type ComputedRef, type Ref } from 'vue'
import { NIcon, useMessage, type DropdownOption } from 'naive-ui'
import {
  CloudUploadOutline,
  CreateOutline,
  DownloadOutline,
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
  type FailedEvalSample,
} from '../api/ragAdmin'
import type { TenantId } from '../api/tenants'
import { friendlyErrorMessage } from '../api/apiError'
import {
  useKbWorkbenchContext,
  useKbPanelLoad,
} from './useKbWorkbenchContext'
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
} from '../utils/kbConfigVersion'
import { formatSkillVersionTimeForFilename } from '../utils/formatSkillVersionTime'

const SCOPE_PATH: Record<string, string[]> = {
  'rag-search': ['search'],
  'rag-rerank': ['rerank'],
  'rag-chunk': ['chunk'],
  'rewrite-rag': ['rewrite', 'rag'],
  'rewrite-hyde': ['rewrite', 'hyde'],
  'rewrite-empty-recall': ['rewrite', 'emptyRecall'],
}

type VersionStatus = ConfigVersionUiStatus

export interface KbConfigPanelProps {
  tenantId: TenantId
  kbId: string | null
}

export function useKbConfigPanel(props: KbConfigPanelProps) {
  const wb = useKbWorkbenchContext()
  const panelLoad = useKbPanelLoad(wb.revision)
  const message = useMessage()

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

  function versionStatusLabelFn(status: VersionStatus): string {
    return configVersionStatusLabel(status)
  }

  function versionStatusTagTypeFn(status: VersionStatus): 'success' | 'warning' | 'error' | 'info' | 'default' {
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

  return {
    loading,
    saving,
    publishing,
    forking,
    activatingId,
    error,
    gateError,
    failedSamples,
    scopes,
    versions,
    selectedVersionId,
    importInputRef,
    versionStatus,
    canEdit,
    showEditDraft,
    showSaveDraftButton,
    showSubmitEval,
    showActivateButton,
    showMoreMenu,
    versionOptions,
    moreMenuOptions,
    versionStatusLabel: versionStatusLabelFn,
    versionStatusTagType: versionStatusTagTypeFn,
    onVersionSelected,
    handleSaveAll,
    startDraftEditing,
    handleSubmitEval,
    handleActivate,
    handleMoreMenuSelect,
    updateField,
    compactFields,
    promptFields,
    fieldLabel,
    promptCharCount,
    scopeValues,
    onImportFileChange,
  }
}

type UnwrapPageMember<T> =
  T extends Ref<infer V> ? V :
  T extends ComputedRef<infer V> ? V :
  T extends (...args: infer A) => infer R ? (...args: A) => R :
  T

type KbConfigPanelComposable = ReturnType<typeof useKbConfigPanel>

export type KbConfigPanelApi = {
  [K in keyof KbConfigPanelComposable]: UnwrapPageMember<KbConfigPanelComposable[K]>
}
