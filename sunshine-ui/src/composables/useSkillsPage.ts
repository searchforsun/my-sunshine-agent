import { computed, h, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch, type ComputedRef, type Ref } from 'vue'
import { onBeforeRouteLeave, useRouter } from 'vue-router'
import { NIcon, useMessage, type DropdownOption } from 'naive-ui'
import {
  CreateOutline,
  DocumentTextOutline,
  FolderOpenOutline,
  FolderOutline,
  RefreshOutline,
  SearchOutline,
  TrashOutline,
  CopyOutline,
  CheckmarkOutline,
  DownloadOutline,
  EllipsisHorizontal,
} from '@vicons/ionicons5'
import {
  createSkill,
  deleteSkill,
  deleteSkillVersion,
  downloadSkillPackage,
  forkSkillVersion,
  listSkillFiles,
  listSkills,
  listSkillVersions,
  publishSkillVersion,
  setSkillEnabled,
  updateSkill,
  uploadSkillPackage,
  zipFolderFiles,
  type SkillEntry,
  type SkillFileContent,
  type SkillVersion,
} from '../api/skills'
import { friendlyErrorMessage } from '../api/apiError'
import { buildFileTree, collectDirKeys, formatFileSize } from '../utils/buildFileTree'
import { formatSkillVersionTime, formatSkillVersionTimeForFilename } from '../utils/formatSkillVersionTime'
import {
  type SkillPhase,
  type VersionStatus,
  resolveVersionStatus,
  versionStatusLabel,
  versionStatusTagType,
  skillHasPublishedVersion,
  isSkillSwitchDisabled,
  versionOptionLabel,
  listCardActiveVersionLine,
  listCardMaintainer,
} from '../utils/skills/skillsVersionUtils'
import { useSkillFilePreview } from '../composables/useSkillFilePreview'

export const SKILLS_PAGE_KEY = Symbol('skillsPage')

export function useSkillsPage(): SkillsPageApi {
  const message = useMessage()
  const router = useRouter()
  const skills = ref<SkillEntry[]>([])
  const loading = ref(false)
  const selectedId = ref<string | null>(null)
  const versions = ref<SkillVersion[]>([])
  const selectedVersion = ref<number | null>(null)
  const files = ref<{ path: string; size: number; directory: boolean }[]>([])
  const selectedFilePath = ref<string | null>(null)
  const fileContent = ref<SkillFileContent | null>(null)
  const fileLoading = ref(false)
  const skillSearch = ref('')
  const expandedKeys = ref<string[]>([])

  const showCreate = ref(false)
  const showEdit = ref(false)
  const createForm = ref({ id: '', displayName: '', description: '' })
  const editTargetSkill = ref<SkillEntry | null>(null)
  const editForm = ref({ displayName: '', description: '' })
  const creating = ref(false)
  const savingEdit = ref(false)
  const uploading = ref(false)
  const downloading = ref(false)
  const forking = ref(false)
  const showDeleteConfirm = ref(false)
  const showDeleteVersionConfirm = ref(false)
  const deleting = ref(false)
  const deletingVersion = ref(false)
  const deleteTargetSkill = ref<SkillEntry | null>(null)
  const detailLoading = ref(false)
  /** 阻止 selectedVersion watch 与手动 loadDetail 重复触发 */
  let suppressVersionWatch = false
  /** 取消切换文件时回滚 selectedFilePath，避免重复触发 confirm */
  let suppressFilePathWatch = false

  const isDetailBusy = computed(() => detailLoading.value || uploading.value || downloading.value)
  const isActionBusy = computed(() => uploading.value || downloading.value || forking.value)

  const folderPickPending = ref(false)

  const uploadOverlayText = computed(() => {
    if (folderPickPending.value) return '等待选择文件夹…'
    return '正在打包并上传…'
  })

  const layoutBusyText = computed(() => {
    if (uploading.value) return uploadOverlayText.value
    if (downloading.value) return '下载中…'
    return '加载中…'
  })

  const folderInputRef = ref<HTMLInputElement | null>(null)

  function bindFolderInputRef(el: HTMLInputElement | null) {
    folderInputRef.value = el
  }

  const selectedSkill = computed(() => skills.value.find(s => s.id === selectedId.value) ?? null)

  /** Skill 生命周期阶段 — 驱动主操作与引导文案 */



  const activeVersionNum = computed(() => selectedSkill.value?.version ?? null)

  const selectedVersionEntry = computed(() =>
    versions.value.find(v => v.version === selectedVersion.value) ?? null,
  )

  const hasAnyUploadedVersion = computed(() =>
    versions.value.some(v => v.storagePath),
  )

  const hasPublishedVersion = computed(() =>
    versions.value.some(v => v.status === 'published' && v.storagePath),
  )

  const selectedHasFiles = computed(() => files.value.length > 0)

  const skillPhase = computed((): SkillPhase => {
    if (!hasAnyUploadedVersion.value) return 'setup'
    const ver = selectedVersionEntry.value
    if (!ver?.storagePath) return 'setup'
    if (ver.status === 'draft') return 'draft'
    if (ver.version === activeVersionNum.value) return 'live'
    return 'history'
  })

  const preview = useSkillFilePreview({
    selectedId,
    selectedVersion,
    selectedFilePath,
    fileContent,
    fileLoading,
    files,
    skillPhase,
    selectedVersionEntry,
    message,
  })
  const {
    bindPreviewScrollRef,
    copyPreviewDone,
    savingFile,
    fileEditMode,
    fileEditDraft,
    canEditCurrentFile,
    fileEditDirty,
    fileTreeNodes,
    treeOptions,
    previewHtml,
    previewCodeHtml,
    previewPlain,
    previewImageSrc,
    previewCodeLangClass,
    showPreviewCopy,
    clearPreview,
    flushFileEditBeforeLeave,
    sendFileEditKeepaliveOnUnload,
    enterFileEditMode,
    handleSaveFileEdit,
    handleCancelFileEdit,
    copyPreviewContent,
    renderTreePrefix,
    loadFileContent,
  } = preview

  const showVersionSelect = computed(() => hasAnyUploadedVersion.value)

  const showEnableCurrentButton = computed(() =>
    selectedHasFiles.value && skillPhase.value !== 'live',
  )

  /** setup 空状态区首次上传；生效/历史版在无草稿时可上传建新草稿；草稿版用在线编辑 */
  const showUploadButton = computed(() => {
    if (skillPhase.value === 'setup' || skillPhase.value === 'draft') return false
    if (skillPhase.value === 'live' || skillPhase.value === 'history') return !hasContentDraft.value
    return false
  })

  const showDownloadButton = computed(
    () => selectedId.value != null && selectedVersion.value != null && selectedHasFiles.value,
  )

  /** 生效/历史版本 → 复制为新草稿；已有内容草稿时不可再 fork */
  const showForkToDraftButton = computed(
    () => selectedHasFiles.value
      && (skillPhase.value === 'live' || skillPhase.value === 'history')
      && !hasContentDraft.value,
  )

  const showDeleteVersionButton = computed(
    () => selectedId.value != null && selectedVersion.value != null && versions.value.length > 1,
  )

  const showDiffWithActiveButton = computed(() => {
    const active = activeVersionNum.value
    const current = selectedVersion.value
    if (!selectedId.value || active == null || current == null || active === current) return false
    if (!selectedFilePath.value || fileContent.value?.binary) return false
    return selectedHasFiles.value
  })

  const cardMenuOptions: DropdownOption[] = [
    {
      label: '修改',
      key: 'edit',
      icon: () => h(NIcon, { component: CreateOutline, size: 14 }),
    },
    { type: 'divider', key: 'divider-card-delete' },
    {
      label: () => h('span', { class: 'more-menu-delete' }, '删除'),
      key: 'delete',
      icon: () => h(NIcon, { component: TrashOutline, size: 14, class: 'more-menu-delete' }),
    },
  ]

  /** 是否已有带内容的草稿（同一 Skill 同时只能存在一个） */
  const hasContentDraft = computed(() =>
    versions.value.some(v => v.status === 'draft' && !!v.storagePath),
  )

  const moreMenuOptions = computed((): DropdownOption[] => {
    const opts: DropdownOption[] = []
    if (showEnableCurrentButton.value) {
      opts.push({
        label: skillPhase.value === 'draft' ? '发布并生效' : '设为此生效版',
        key: 'publish',
        icon: () => h(NIcon, { component: CheckmarkOutline, size: 14 }),
      })
    }
    if (showForkToDraftButton.value) {
      opts.push({
        label: '复制为草稿',
        key: 'fork',
        icon: () => h(NIcon, { component: CreateOutline, size: 14 }),
        disabled: forking.value,
      })
    }
    if (showUploadButton.value) {
      opts.push({
        label: '上传文件夹',
        key: 'upload',
        icon: () => h(NIcon, { component: FolderOpenOutline, size: 14 }),
        disabled: uploading.value,
      })
    }
    if (showDownloadButton.value) {
      opts.push({
        label: '下载 ZIP',
        key: 'download',
        icon: () => h(NIcon, { component: DownloadOutline, size: 14 }),
        disabled: downloading.value,
      })
    }
    if (showDiffWithActiveButton.value) {
      opts.push({
        label: '对比生效版',
        key: 'diff-active',
        icon: () => h(NIcon, { component: DocumentTextOutline, size: 14 }),
      })
    }
    if (showDeleteVersionButton.value) {
      if (opts.length > 0) {
        opts.push({ type: 'divider', key: 'divider-before-delete-version' })
      }
      opts.push({
        label: () => h('span', { class: 'more-menu-delete' }, '删除此版本'),
        key: 'delete-version',
        icon: () => h(NIcon, { component: TrashOutline, size: 14, class: 'more-menu-delete' }),
      })
    }
    return opts
  })

  /** 列表卡片：当前 active 版本是否已发布生效 */

  /** 仅阻止「未发布时开启」；已开启时须允许关闭 */

  const filteredSkills = computed(() => {
    const base = skills.value.filter((s): s is SkillEntry => !!s?.id)
    const q = skillSearch.value.trim().toLowerCase()
    if (!q) return base
    return base.filter(
      s =>
        s.id.toLowerCase().includes(q)
        || s.displayName.toLowerCase().includes(q)
        || (s.description ?? '').toLowerCase().includes(q),
    )
  })

  const versionOptions = computed(() =>
    versions.value.map(v => ({
      label: versionOptionLabel(v),
      value: v.version,
    })),
  )




  const detailMaintainerText = computed((): string | null => {
    const name = selectedVersionEntry.value?.maintainerName
    return name ? `维护人：${name}` : null
  })

  const selectedVersionStatus = computed((): VersionStatus | null => {
    const ver = selectedVersionEntry.value
    if (!ver?.storagePath) return null
    return resolveVersionStatus(ver, activeVersionNum.value)
  })

  const detailVersionTagType = computed(() => {
    const status = selectedVersionStatus.value
    return status ? versionStatusTagType(status) : 'default'
  })

  /** 版本下拉已确认切换的目标（用于保存失败时回滚 v-model） */
  const committedVersion = ref<number | null>(null)

  function syncCommittedVersion() {
    committedVersion.value = selectedVersion.value
  }

  /** 刷新整页：左侧列表 + 右侧当前 Skill/版本/文件预览 */
  async function refreshPage() {
    if (!(await flushFileEditBeforeLeave())) return
    const keepSkillId = selectedId.value
    const keepVersion = selectedVersion.value
    const keepFilePath = selectedFilePath.value
    loading.value = true
    if (keepSkillId) {
      detailLoading.value = true
    }
    try {
      skills.value = await listSkills()
      loading.value = false
      if (!keepSkillId) {
        if (skills.value.length > 0) {
          selectedId.value = skills.value[0].id
        }
        return
      }
      if (!skills.value.some(s => s.id === keepSkillId)) {
        selectedId.value = skills.value[0]?.id ?? null
        return
      }
      suppressVersionWatch = true
      try {
        await loadVersions(keepSkillId, { preserveVersion: keepVersion ?? undefined })
        await loadDetailContent({ preservePath: keepFilePath ?? undefined })
      } finally {
        suppressVersionWatch = false
      }
    } catch (e: unknown) {
      message.error(friendlyErrorMessage(e, '刷新失败'))
    } finally {
      loading.value = false
      detailLoading.value = false
    }
  }

  async function loadVersions(
    skillId: string,
    opts?: { preferDraft?: boolean; preserveVersion?: number },
  ) {
    try {
      versions.value = await listSkillVersions(skillId)
      if (opts?.preserveVersion != null
        && versions.value.some(v => v.version === opts.preserveVersion)) {
        selectedVersion.value = opts.preserveVersion
        syncCommittedVersion()
        return
      }
      const active = skills.value.find(s => s.id === skillId)?.version
      const draftWithFiles = versions.value.find(v => v.status === 'draft' && v.storagePath)
      const activePublished = versions.value.find(
        v => v.version === active && v.status === 'published' && v.storagePath,
      )
      if (opts?.preferDraft && draftWithFiles) {
        selectedVersion.value = draftWithFiles.version
      } else if (draftWithFiles && !activePublished) {
        selectedVersion.value = draftWithFiles.version
      } else {
        selectedVersion.value = active ?? versions.value[0]?.version ?? null
      }
      syncCommittedVersion()
    } catch (e: unknown) {
      const msg = friendlyErrorMessage(e, '加载版本失败')
      message.error(msg)
      throw e instanceof Error ? e : new Error(msg)
    }
  }

  async function loadFiles(opts?: { preservePath?: string }) {
    if (!selectedId.value || selectedVersion.value == null) {
      files.value = []
      expandedKeys.value = []
      clearPreview()
      return
    }
    try {
      const next = await listSkillFiles(selectedId.value, selectedVersion.value)
      files.value = next
      expandedKeys.value = collectDirKeys(buildFileTree(next))
      if (next.length > 0) {
        const preserved = opts?.preservePath
          && next.some(f => f.path === opts.preservePath && !f.directory)
        if (preserved) {
          selectedFilePath.value = opts.preservePath!
        } else {
          const skillMd = next.find(f => {
            if (f.directory) return false
            const p = f.path.replace(/\\/g, '/').toLowerCase()
            return p === 'skill.md' || p.endsWith('/skill.md')
          })
          selectedFilePath.value = skillMd?.path ?? next.find(f => !f.directory)?.path ?? null
        }
      } else {
        clearPreview()
      }
    } catch (e: unknown) {
      const msg = friendlyErrorMessage(e, '加载文件失败')
      message.error(msg)
      throw e instanceof Error ? e : new Error(msg)
    }
  }

  async function loadDetailContent(opts?: { preservePath?: string }) {
    if (!selectedId.value || selectedVersion.value == null) {
      files.value = []
      expandedKeys.value = []
      clearPreview()
      return
    }
    clearPreview()
    await loadFiles(opts)
    if (selectedFilePath.value) {
      await loadFileContent(selectedFilePath.value, { silent: true })
    }
  }

  async function reloadDetailForVersion() {
    if (!selectedId.value || selectedVersion.value == null) return
    detailLoading.value = true
    try {
      await loadDetailContent()
    } catch {
      /* 错误已在 load 方法内 toast */
    } finally {
      detailLoading.value = false
    }
  }

  async function onVersionSelected(ver: number | null) {
    if (suppressVersionWatch || !selectedId.value || ver == null) return
    if (ver === committedVersion.value) return
    if (!(await flushFileEditBeforeLeave())) {
      suppressVersionWatch = true
      selectedVersion.value = committedVersion.value
      await nextTick()
      suppressVersionWatch = false
      return
    }
    committedVersion.value = ver
    await reloadDetailForVersion()
  }


  async function handleCreateConfirm() {
    if (!createForm.value.id.trim() || !createForm.value.displayName.trim()) return
    if (!(await flushFileEditBeforeLeave())) return
    creating.value = true
    try {
      const created = await createSkill(
        createForm.value.id.trim(),
        createForm.value.displayName.trim(),
        createForm.value.description.trim(),
      )
      skills.value = [...skills.value, created]
      selectedId.value = created.id
      showCreate.value = false
      createForm.value = { id: '', displayName: '', description: '' }
      message.success('Skill 已创建，请上传 Skill 文件夹')
    } catch (e: unknown) {
      message.error(friendlyErrorMessage(e, '创建失败'))
    } finally {
      creating.value = false
    }
  }

  async function selectSkill(id: string) {
    if (id === selectedId.value) return
    if (!(await flushFileEditBeforeLeave())) return
    selectedId.value = id
  }

  function onPickLabelClick(e: MouseEvent) {
    e.preventDefault()
    triggerFolderPick()
  }

  async function triggerFolderPick() {
    if (!selectedId.value) {
      message.warning('请先选择 Skill')
      return
    }
    if (!(await flushFileEditBeforeLeave())) return
    if (hasContentDraft.value && (skillPhase.value === 'live' || skillPhase.value === 'history')) {
      message.warning('已有草稿版本，请先发布或删除后再上传')
      return
    }
    const input = folderInputRef.value
    if (!input) {
      message.error('上传组件未就绪，请刷新页面后重试')
      return
    }
    if (uploading.value) return
    input.value = ''
    uploading.value = true
    folderPickPending.value = true
    await nextTick()
    await waitForPaint()
    const onWindowFocus = () => {
      window.removeEventListener('focus', onWindowFocus)
      setTimeout(() => {
        if (!folderPickPending.value) return
        folderPickPending.value = false
        if (!input.files?.length) {
          uploading.value = false
        }
      }, 500)
    }
    window.addEventListener('focus', onWindowFocus)
    input.click()
  }

  /** 等浏览器完成一帧绘制，避免打包阻塞导致 loading 来不及显示 */
  function waitForPaint(): Promise<void> {
    return new Promise(resolve => {
      requestAnimationFrame(() => requestAnimationFrame(() => resolve()))
    })
  }

  async function handleMoreMenuSelect(key: string) {
    if (key === 'publish') await handlePublish()
    else if (key === 'fork') await handleForkToDraft()
    else if (key === 'upload') await triggerFolderPick()
    else if (key === 'download') handleDownload()
    else if (key === 'diff-active') await handleDiffWithActive()
    else if (key === 'delete-version') {
      if (!(await flushFileEditBeforeLeave())) return
      showDeleteVersionConfirm.value = true
    }
  }

  async function handleDiffWithActive() {
    if (!selectedId.value || selectedVersion.value == null || activeVersionNum.value == null) return
    const path = selectedFilePath.value ?? 'SKILL.md'
    await router.push({
      name: 'skill-diff',
      params: { skillId: selectedId.value },
      query: {
        from: String(activeVersionNum.value),
        to: String(selectedVersion.value),
        path,
      },
    })
  }

  function openDeleteSkillConfirm(skill: SkillEntry) {
    deleteTargetSkill.value = skill
    showDeleteConfirm.value = true
  }

  function openEditSkill(skill: SkillEntry) {
    editTargetSkill.value = skill
    editForm.value = {
      displayName: skill.displayName,
      description: skill.description ?? '',
    }
    showEdit.value = true
  }

  function handleCardMenuSelect(skill: SkillEntry, key: string) {
    if (key === 'edit') openEditSkill(skill)
    else if (key === 'delete') openDeleteSkillConfirm(skill)
  }

  async function handleEditConfirm() {
    if (!editTargetSkill.value || !editForm.value.displayName.trim()) return
    savingEdit.value = true
    try {
      const updated = await updateSkill(
        editTargetSkill.value.id,
        editForm.value.displayName.trim(),
        editForm.value.description.trim(),
      )
      skills.value = skills.value.map(s => (s.id === updated.id ? updated : s))
      showEdit.value = false
      message.success('已保存')
    } catch (e: unknown) {
      message.error(friendlyErrorMessage(e, '保存失败'))
    } finally {
      savingEdit.value = false
    }
  }

  async function runUploadPackage(blob: Blob, filename: string) {
    if (!selectedId.value) return
    suppressVersionWatch = true
    try {
      const updated = await uploadSkillPackage(selectedId.value, blob, filename)
      skills.value = skills.value.map(s => (s.id === updated.id ? updated : s))
      await loadVersions(selectedId.value, { preferDraft: true })
      await loadDetailContent()
    } finally {
      suppressVersionWatch = false
    }
  }

  async function toggleEnabled(skill: SkillEntry, enabled: boolean) {
    if (enabled && !skillHasPublishedVersion(skill)) {
      message.warning('请先发布并生效某一版本后再开启 Skill')
      return
    }
    try {
      const updated = await setSkillEnabled(skill.id, enabled)
      skills.value = skills.value.map(s => (s.id === updated.id ? updated : s))
      message.success(enabled ? 'Skill 已开启' : 'Skill 已关闭')
    } catch (e: unknown) {
      message.error(friendlyErrorMessage(e, '操作失败'))
    }
  }

  async function onFolderPicked(e: Event) {
    const input = e.target as HTMLInputElement
    const list = input.files
    if (!list?.length) {
      if (folderPickPending.value) {
        folderPickPending.value = false
        uploading.value = false
      }
      return
    }
    folderPickPending.value = false
    if (!selectedId.value) {
      message.warning('请先选择 Skill')
      input.value = ''
      uploading.value = false
      return
    }
    const hasSkillMd = Array.from(list).some(f => {
      const p = ((f as File & { webkitRelativePath?: string }).webkitRelativePath || f.name).replace(/\\/g, '/').toLowerCase()
      return p === 'skill.md' || p.endsWith('/skill.md')
    })
    if (!hasSkillMd) {
      message.error('文件夹内须包含 SKILL.md')
      input.value = ''
      uploading.value = false
      return
    }
    if (!uploading.value) {
      uploading.value = true
      await nextTick()
      await waitForPaint()
    }
    try {
      const zip = await zipFolderFiles(list)
      await runUploadPackage(zip, 'skill-package.zip')
      message.success('已上传为草稿，请预览后发布并生效')
    } catch (e: unknown) {
      message.error(friendlyErrorMessage(e, '上传失败'))
    } finally {
      input.value = ''
      folderPickPending.value = false
      uploading.value = false
    }
  }

  async function handlePublish() {
    if (!selectedId.value || selectedVersion.value == null || !showEnableCurrentButton.value) return
    if (!(await flushFileEditBeforeLeave())) return
    try {
      const updated = await publishSkillVersion(selectedId.value, selectedVersion.value)
      skills.value = skills.value.map(s => (s.id === updated.id ? updated : s))
      message.success(`版本 ${formatSkillVersionTime(updated.activeVersionCreatedAt)} 已生效，Skill 已自动开启`)
      await loadVersions(selectedId.value)
    } catch (e: unknown) {
      message.error(friendlyErrorMessage(e, '生效失败'))
    }
  }

  async function handleForkToDraft() {
    if (!selectedId.value || selectedVersion.value == null || !showForkToDraftButton.value) return
    if (!(await flushFileEditBeforeLeave())) return
    forking.value = true
    detailLoading.value = true
    suppressVersionWatch = true
    try {
      await forkSkillVersion(selectedId.value, selectedVersion.value)
      skills.value = await listSkills()
      await loadVersions(selectedId.value, { preferDraft: true })
      await loadDetailContent()
      message.success('已复制为草稿')
    } catch (e: unknown) {
      message.error(friendlyErrorMessage(e, '复制草稿失败'))
    } finally {
      suppressVersionWatch = false
      forking.value = false
      detailLoading.value = false
    }
  }

  async function handleDownload() {
    if (!selectedId.value || selectedVersion.value == null || !showDownloadButton.value) return
    downloading.value = true
    try {
      const blob = await downloadSkillPackage(selectedId.value, selectedVersion.value)
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      const verTime = formatSkillVersionTimeForFilename(selectedVersionEntry.value?.createdAt)
      a.download = `${selectedId.value}-${verTime}.zip`
      document.body.appendChild(a)
      a.click()
      a.remove()
      URL.revokeObjectURL(url)
      message.success('Skill 包已开始下载')
    } catch (e: unknown) {
      message.error(friendlyErrorMessage(e, '下载失败'))
    } finally {
      downloading.value = false
    }
  }

  async function handleDeleteConfirm() {
    const skill = deleteTargetSkill.value
    if (!skill) return
    deleting.value = true
    try {
      await deleteSkill(skill.id)
      skills.value = skills.value.filter(s => s.id !== skill.id)
      if (selectedId.value === skill.id) {
        selectedId.value = skills.value[0]?.id ?? null
        clearPreview()
      }
      showDeleteConfirm.value = false
      deleteTargetSkill.value = null
      message.success('Skill 已删除')
    } catch (e: unknown) {
      message.error(friendlyErrorMessage(e, '删除失败'))
    } finally {
      deleting.value = false
    }
  }

  async function handleDeleteVersionConfirm() {
    if (!selectedId.value || selectedVersion.value == null) return
    const versionTime = formatSkillVersionTime(selectedVersionEntry.value?.createdAt)
    deletingVersion.value = true
    try {
      const updated = await deleteSkillVersion(selectedId.value, selectedVersion.value)
      skills.value = skills.value.map(s => (s.id === updated.id ? updated : s))
      suppressVersionWatch = true
      try {
        await loadVersions(selectedId.value)
        await loadDetailContent()
      } finally {
        suppressVersionWatch = false
      }
      showDeleteVersionConfirm.value = false
      message.success(`版本 ${versionTime} 已删除`)
    } catch (e: unknown) {
      message.error(friendlyErrorMessage(e, '删除版本失败'))
    } finally {
      deletingVersion.value = false
    }
  }

  function onTreeSelect(keys: Array<string | number>) {
    const key = String(keys[0] ?? '')
    if (!key) return
    const entry = files.value.find(f => f.path === key)
    if (entry && !entry.directory && key !== selectedFilePath.value) {
      selectedFilePath.value = key
    }
  }

  function onBeforeUnload() {
    sendFileEditKeepaliveOnUnload()
  }

  onBeforeRouteLeave(async (_to, _from, next) => {
    if (await flushFileEditBeforeLeave()) {
      next()
    } else {
      next(false)
    }
  })

  watch(selectedId, async (id) => {
    clearPreview()
    files.value = []
    versions.value = []
    selectedVersion.value = null
    if (!id) return
    detailLoading.value = true
    suppressVersionWatch = true
    try {
      await loadVersions(id)
      await loadDetailContent()
    } catch {
      /* 错误已在 load 方法内 toast */
    } finally {
      suppressVersionWatch = false
      detailLoading.value = false
    }
  })

  watch(selectedFilePath, async (path, oldPath) => {
    if (suppressFilePathWatch) return
    if (path !== oldPath) {
      copyPreviewDone.value = false
    }
    if (isDetailBusy.value) return
    if (path && path !== oldPath) {
      const ok = await loadFileContent(path)
      if (!ok) {
        suppressFilePathWatch = true
        selectedFilePath.value = oldPath ?? null
        await nextTick()
        suppressFilePathWatch = false
      }
    } else if (!path) {
      fileContent.value = null
    }
  })

  watch(fileTreeNodes, (nodes) => {
    if (nodes.length) expandedKeys.value = collectDirKeys(nodes)
  })

  onMounted(() => {
    window.addEventListener('beforeunload', onBeforeUnload)
    void refreshPage()
  })

  onBeforeUnmount(() => {
    window.removeEventListener('beforeunload', onBeforeUnload)
  })
  // reactive 包装：子组件 prop 内嵌 Ref/ComputedRef 可自动解包，避免 v-for 迭代 Ref 对象
  return reactive({
    message,
    router,
    skills,
    loading,
    selectedId,
    versions,
    selectedVersion,
    files,
    selectedFilePath,
    fileContent,
    fileLoading,
    skillSearch,
    expandedKeys,
    showCreate,
    showEdit,
    createForm,
    editTargetSkill,
    editForm,
    creating,
    savingEdit,
    uploading,
    downloading,
    forking,
    showDeleteConfirm,
    showDeleteVersionConfirm,
    deleting,
    deletingVersion,
    deleteTargetSkill,
    detailLoading,
    isDetailBusy,
    isActionBusy,
    folderPickPending,
    uploadOverlayText,
    layoutBusyText,
    bindFolderInputRef,
    folderInputRef,
    selectedSkill,
    activeVersionNum,
    selectedVersionEntry,
    hasAnyUploadedVersion,
    hasPublishedVersion,
    selectedHasFiles,
    skillPhase,
    showVersionSelect,
    showEnableCurrentButton,
    showUploadButton,
    showDownloadButton,
    showForkToDraftButton,
    showDeleteVersionButton,
    showDiffWithActiveButton,
    cardMenuOptions,
    hasContentDraft,
    moreMenuOptions,
    filteredSkills,
    versionOptions,
    detailMaintainerText,
    selectedVersionStatus,
    detailVersionTagType,
    savingFile,
    fileEditMode,
    fileEditDraft,
    canEditCurrentFile,
    fileEditDirty,
    fileTreeNodes,
    treeOptions,
    previewHtml,
    previewCodeHtml,
    previewPlain,
    previewImageSrc,
    previewCodeLangClass,
    showPreviewCopy,
    bindPreviewScrollRef,
    copyPreviewDone,
    refreshPage,
    selectSkill,
    toggleEnabled,
    handleCardMenuSelect,
    openEditSkill,
    handleCreateConfirm,
    handleEditConfirm,
    handleDeleteConfirm,
    handleDeleteVersionConfirm,
    onVersionSelected,
    handleMoreMenuSelect,
    triggerFolderPick,
    onPickLabelClick,
    onFolderPicked,
    onTreeSelect,
    renderTreePrefix,
    copyPreviewContent,
    handleSaveFileEdit,
    handleCancelFileEdit,
    enterFileEditMode,
    flushFileEditBeforeLeave,
    versionStatusLabel,
    versionStatusTagType,
    formatSkillVersionTime,
    formatFileSize,
  }) as unknown as SkillsPageApi
}

/** 子组件 prop 用：模板侧按解包后的 Ref/ComputedRef 访问 */
type UnwrapPageMember<T> =
  T extends Ref<infer V> ? V :
  T extends ComputedRef<infer V> ? V :
  T extends (...args: infer A) => infer R ? (...args: A) => R :
  T

type SkillsPageComposable = ReturnType<typeof useSkillsPage>

export type SkillsPageApi = {
  [K in keyof SkillsPageComposable]: UnwrapPageMember<SkillsPageComposable[K]>
}
