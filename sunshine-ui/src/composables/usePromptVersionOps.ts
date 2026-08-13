import { computed, h, ref, type Ref } from 'vue'
import { NIcon, type DropdownOption } from 'naive-ui'
import { CopyOutline } from '@vicons/ionicons5'
import {
  addPromptVersion,
  getPrompt,
  listPromptVersions,
  rollbackPrompt,
  publishPrompt,
  updatePrompt,
  type PromptDetail,
  type PromptVersionItem,
} from '../api/prompts'
import { friendlyErrorMessage } from '../api/apiError'
import { formatSkillVersionTime } from '../utils/formatSkillVersionTime'
import {
  resolvePromptVersionStatus,
  versionStatusLabel,
  versionStatusTagType,
  type PromptVersionStatus,
} from '../utils/prompts/promptVersionUtils'

export interface PromptVersionOpsDeps {
  selectedId: Ref<string | null>
  message: ReturnType<typeof import('naive-ui')['useMessage']>
  refreshList: (keepSelection?: boolean) => Promise<void>
  applyRoutingContent: (contentJson: string | null) => void
  creating: Ref<boolean>
}

export function usePromptVersionOps(deps: PromptVersionOpsDeps) {
  const { selectedId, message, refreshList, applyRoutingContent, creating } = deps

  const detailLoading = ref(false)
  const saving = ref(false)
  const publishing = ref(false)
  const rollingBack = ref(false)
  const forking = ref(false)

  const detail = ref<PromptDetail | null>(null)
  const versions = ref<PromptVersionItem[]>([])

  const editDisplayName = ref('')
  const editDescription = ref('')
  const editPriority = ref(0)
  const editContentText = ref('')
  const editContentJson = ref('')
  const contentUsesJson = ref(false)
  const editChangeNote = ref('')
  const selectedVersion = ref<number | null>(null)

  const selectedVersionEntry = computed(() => {
    if (selectedVersion.value == null) return null
    return versions.value.find(v => v.version === selectedVersion.value) ?? null
  })

  const hasDraft = computed(() => versions.value.some(v => v.status === 'draft'))

  const selectedVersionStatus = computed((): PromptVersionStatus | null => {
    const ver = selectedVersionEntry.value
    if (!ver || !detail.value) return null
    return resolvePromptVersionStatus(ver, detail.value.activeVersion)
  })

  const detailVersionTagType = computed(() => {
    const status = selectedVersionStatus.value
    return status ? versionStatusTagType(status) : 'default'
  })

  const selectedVersionStatusLabel = computed(() => {
    const status = selectedVersionStatus.value
    return status ? versionStatusLabel(status) : ''
  })

  const versionOptions = computed(() =>
    versions.value.map(v => ({
      label: formatSkillVersionTime(v.createdAt),
      value: v.version,
    })),
  )

  const showVersionSelect = computed(() => versions.value.length > 0)

  const isContentEditable = computed(() => selectedVersionStatus.value === 'draft')

  const showPrimaryPublishButton = computed(() => {
    const status = selectedVersionStatus.value
    return status === 'draft' || status === 'inactive'
  })

  const primaryPublishLabel = computed(() =>
    selectedVersionStatus.value === 'draft' ? '发布并生效' : '设为此生效版',
  )

  const showForkToDraft = computed(() => {
    const status = selectedVersionStatus.value
    return (status === 'live' || status === 'inactive') && !hasDraft.value
  })

  const showSaveDraftButton = computed(() => selectedVersionStatus.value === 'draft')

  const isActionBusy = computed(
    () => saving.value || publishing.value || rollingBack.value || forking.value || creating.value,
  )

  const moreMenuOptions = computed((): DropdownOption[] => {
    const opts: DropdownOption[] = []
    if (showForkToDraft.value) {
      opts.push({
        label: '复制为草稿',
        key: 'fork',
        icon: () => h(NIcon, { component: CopyOutline, size: 14 }),
        disabled: forking.value,
      })
    }
    return opts
  })

  const showMoreMenu = computed(() => moreMenuOptions.value.length > 0)

  function applyVersionContent(contentText: string | null, contentJson: string | null, kind?: string) {
    const preferJson = kind === 'timeline' || kind === 'routing-rule'
      || (!contentText?.trim() && !!contentJson?.trim())
    contentUsesJson.value = preferJson && kind !== 'routing-rule'
    if (kind === 'routing-rule') {
      editContentText.value = ''
      editContentJson.value = contentJson ?? ''
      contentUsesJson.value = true
      return
    }
    if (contentUsesJson.value) {
      editContentText.value = contentJson ?? ''
      editContentJson.value = contentJson ?? ''
    } else {
      editContentText.value = contentText ?? ''
      editContentJson.value = contentJson ?? ''
    }
  }

  function applyDetailToEditors(d: PromptDetail) {
    editDisplayName.value = d.displayName
    editDescription.value = d.description ?? ''
    editPriority.value = d.priority
    const content = d.activeVersionContent
    applyVersionContent(content?.contentText ?? null, content?.contentJson ?? null, d.kind)
    editChangeNote.value = ''
    if (d.kind === 'routing-rule') {
      applyRoutingContent(content?.contentJson ?? null)
    }
  }

  function loadVersionIntoEditor(ver: PromptVersionItem) {
    selectedVersion.value = ver.version
    applyVersionContent(ver.contentText, ver.contentJson, detail.value?.kind)
    editChangeNote.value = ''
    if (detail.value?.kind === 'routing-rule') {
      applyRoutingContent(ver.contentJson)
    }
  }

  async function loadDetail(id: string) {
    detailLoading.value = true
    try {
      const [d, vers] = await Promise.all([getPrompt(id), listPromptVersions(id)])
      detail.value = d
      versions.value = vers
      const draft = vers.find(v => v.status === 'draft')
      const active = vers.find(v => v.version === d.activeVersion)
      const pick = draft ?? active ?? vers[0] ?? null
      selectedVersion.value = pick?.version ?? null
      if (pick) {
        loadVersionIntoEditor(pick)
        editDisplayName.value = d.displayName
        editDescription.value = d.description ?? ''
        editPriority.value = d.priority
      } else {
        applyDetailToEditors(d)
      }
    } catch (e) {
      message.error(friendlyErrorMessage(e, '加载详情失败'))
      console.error(e)
    } finally {
      detailLoading.value = false
    }
  }

  function onVersionSelected(ver: number | null) {
    if (ver == null) return
    const entry = versions.value.find(v => v.version === ver)
    if (entry) loadVersionIntoEditor(entry)
  }

  async function saveMeta() {
    if (!selectedId.value || !detail.value) return
    if (!isContentEditable.value) {
      message.warning('请先复制为草稿后再修改')
      return
    }
    saving.value = true
    try {
      await updatePrompt(selectedId.value, {
        displayName: editDisplayName.value.trim(),
        description: editDescription.value.trim(),
        priority: editPriority.value,
        expectedUpdatedAt: detail.value.updatedAt ?? null,
      })
      message.success('元数据已保存')
      await refreshList()
    } catch (e) {
      message.error(friendlyErrorMessage(e, '保存元数据失败'))
      console.error(e)
    } finally {
      saving.value = false
    }
  }

  async function saveVersion(status: 'draft' | 'published' = 'draft') {
    if (!selectedId.value || !detail.value) return
    if (status === 'draft' && !isContentEditable.value) {
      message.warning('生效版本不可直接修改，请先「复制为草稿」')
      return
    }
    const raw = editContentText.value
    if (!raw.trim() && !editContentJson.value.trim()) {
      message.warning('请填写内容')
      return
    }
    const contentText = contentUsesJson.value ? null : raw
    const contentJson = contentUsesJson.value
      ? (raw.trim() || null)
      : (editContentJson.value.trim() || null)
    saving.value = true
    try {
      await updatePrompt(selectedId.value, {
        displayName: editDisplayName.value.trim() || detail.value.displayName,
        description: editDescription.value.trim(),
        priority: editPriority.value,
        expectedUpdatedAt: detail.value.updatedAt ?? null,
      })
      const latest = await getPrompt(selectedId.value)
      await addPromptVersion(selectedId.value, {
        status,
        contentText,
        contentJson,
        expectedUpdatedAt: latest.updatedAt ?? null,
      })
      message.success(status === 'published' ? '已保存并发布' : '草稿已保存')
      await refreshList()
    } catch (e) {
      message.error(friendlyErrorMessage(e, '保存版本失败'))
      console.error(e)
    } finally {
      saving.value = false
    }
  }

  async function handlePublish(version?: number) {
    if (!selectedId.value || !detail.value) return
    const target = version ?? selectedVersion.value ?? undefined
    publishing.value = true
    try {
      await publishPrompt(selectedId.value, {
        version: target,
        expectedUpdatedAt: detail.value.updatedAt ?? null,
      })
      message.success('已发布并生效')
      await refreshList()
    } catch (e) {
      message.error(friendlyErrorMessage(e, '发布失败'))
      console.error(e)
    } finally {
      publishing.value = false
    }
  }

  async function handlePrimaryPublish() {
    if (!showPrimaryPublishButton.value || selectedVersion.value == null) return
    await handlePublish(selectedVersion.value)
  }

  async function forkToDraft() {
    if (!selectedId.value || !detail.value || !selectedVersionEntry.value) return
    if (hasDraft.value) {
      message.warning('已有草稿，请先发布或切换到草稿编辑')
      return
    }
    const ver = selectedVersionEntry.value
    forking.value = true
    try {
      await addPromptVersion(selectedId.value, {
        status: 'draft',
        contentText: ver.contentText,
        contentJson: ver.contentJson,
        changeNote: `从 v${ver.version} 复制为新草稿`,
        expectedUpdatedAt: detail.value.updatedAt ?? null,
      })
      message.success('已复制为新草稿')
      await refreshList()
    } catch (e) {
      message.error(friendlyErrorMessage(e, '复制草稿失败'))
      console.error(e)
    } finally {
      forking.value = false
    }
  }

  async function handleMoreMenuSelect(key: string | number) {
    if (key === 'fork') await forkToDraft()
  }

  async function handleRollback(version: number) {
    if (!selectedId.value || !detail.value) return
    rollingBack.value = true
    try {
      await rollbackPrompt(selectedId.value, version, detail.value.updatedAt ?? null)
      message.success(`已回滚到 v${version}`)
      await refreshList()
    } catch (e) {
      message.error(friendlyErrorMessage(e, '回滚失败'))
      console.error(e)
    } finally {
      rollingBack.value = false
    }
  }

  return {
    detailLoading,
    saving,
    publishing,
    rollingBack,
    forking,
    detail,
    versions,
    editDisplayName,
    editDescription,
    editPriority,
    editContentText,
    editContentJson,
    contentUsesJson,
    editChangeNote,
    selectedVersion,
    selectedVersionEntry,
    hasDraft,
    selectedVersionStatus,
    detailVersionTagType,
    selectedVersionStatusLabel,
    versionOptions,
    showVersionSelect,
    isContentEditable,
    showPrimaryPublishButton,
    primaryPublishLabel,
    showForkToDraft,
    showSaveDraftButton,
    isActionBusy,
    moreMenuOptions,
    showMoreMenu,
    loadDetail,
    applyDetailToEditors,
    applyVersionContent,
    loadVersionIntoEditor,
    onVersionSelected,
    saveMeta,
    saveVersion,
    handlePublish,
    handlePrimaryPublish,
    forkToDraft,
    handleMoreMenuSelect,
    handleRollback,
  }
}
