<script setup lang="ts">
import { computed, h, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import {
  NButton,
  NCard,
  NDropdown,
  NEmpty,
  NForm,
  NFormItem,
  NIcon,
  NInput,
  NModal,
  NSelect,
  NSpace,
  NSpin,
  NSwitch,
  NTag,
  NText,
  NTree,
  useMessage,
  type DropdownOption,
  type TreeOption,
} from 'naive-ui'
import {
  AddOutline,
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
  readSkillFile,
  setSkillEnabled,
  updateSkill,
  uploadSkillPackage,
  writeSkillFile,
  writeSkillFileKeepalive,
  zipFolderFiles,
  type SkillEntry,
  type SkillFileContent,
  type SkillVersion,
} from '../api/skills'
import { buildFileTree, collectDirKeys, formatFileSize } from '../utils/buildFileTree'
import { formatSkillVersionTime, formatSkillVersionTimeForFilename } from '../utils/formatSkillVersionTime'
import { createMarkdownIt } from '../utils/markdown/createMarkdownIt'
import { enhanceStaticMarkdown, reRenderStaticMermaids } from '../utils/stream-markdown/StaticEnhancer'
import '../utils/stream-markdown/styles.css'
import { theme } from '../composables/useTheme'
import 'katex/dist/katex.min.css'
import hljs from 'highlight.js/lib/core'
import markdown from 'highlight.js/lib/languages/markdown'
import python from 'highlight.js/lib/languages/python'
import bash from 'highlight.js/lib/languages/bash'
import json from 'highlight.js/lib/languages/json'
import yaml from 'highlight.js/lib/languages/yaml'
import sql from 'highlight.js/lib/languages/sql'
import xml from 'highlight.js/lib/languages/xml'
import javascript from 'highlight.js/lib/languages/javascript'
import 'highlight.js/styles/github-dark.css'

hljs.registerLanguage('markdown', markdown)
hljs.registerLanguage('python', python)
hljs.registerLanguage('bash', bash)
hljs.registerLanguage('json', json)
hljs.registerLanguage('yaml', yaml)
hljs.registerLanguage('sql', sql)
hljs.registerLanguage('xml', xml)
hljs.registerLanguage('javascript', javascript)
hljs.registerLanguage('mermaid', () => ({ contains: [] }))
const md = createMarkdownIt(hljs)

const mdPreviewRef = ref<HTMLElement | null>(null)
const copyPreviewDone = ref(false)

const message = useMessage()
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

const selectedSkill = computed(() => skills.value.find(s => s.id === selectedId.value) ?? null)

/** Skill 生命周期阶段 — 驱动主操作与引导文案 */
type SkillPhase = 'setup' | 'draft' | 'live' | 'history'

type VersionStatus = 'live' | 'inactive' | 'draft'

function resolveVersionStatus(v: SkillVersion, activeNum: number | null): VersionStatus {
  if (v.status === 'draft') return 'draft'
  if (v.version === activeNum) return 'live'
  return 'inactive'
}

function versionStatusLabel(status: VersionStatus): string {
  if (status === 'live') return '生效'
  if (status === 'draft') return '草稿'
  return '非生效'
}

function versionStatusTagType(status: VersionStatus): 'success' | 'warning' | 'default' {
  if (status === 'live') return 'success'
  if (status === 'draft') return 'warning'
  return 'default'
}

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
function skillHasPublishedVersion(skill: SkillEntry): boolean {
  if (skill.activeVersionPublished === true) return true
  if (skill.activeVersionPublished === false) return false
  // 旧 API 兜底：已开启说明曾发布过
  return skill.enabled
}

/** 仅阻止「未发布时开启」；已开启时须允许关闭 */
function isSkillSwitchDisabled(skill: SkillEntry): boolean {
  if (skill.enabled) return false
  return !skillHasPublishedVersion(skill)
}

const filteredSkills = computed(() => {
  const q = skillSearch.value.trim().toLowerCase()
  if (!q) return skills.value
  return skills.value.filter(
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

function versionOptionLabel(v: SkillVersion): string {
  return formatSkillVersionTime(v.createdAt) || (v.storagePath ? '—' : '待上传')
}

function listCardActiveVersionLine(skill: SkillEntry): string {
  if (!skillHasPublishedVersion(skill)) return '生效版本：未发布'
  return `生效版本：${formatSkillVersionTime(skill.activeVersionCreatedAt)}`
}

function listCardMaintainer(skill: SkillEntry): string | null {
  const name = skill.activeVersionMaintainerName
  return name ? `维护人：${name}` : null
}

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

const savingFile = ref(false)
const fileEditMode = ref(false)
const fileEditDraft = ref('')
const fileEditBaseline = ref('')

const canEditCurrentFile = computed(() => {
  if (!selectedFilePath.value || !fileContent.value || fileContent.value.binary) return false
  if (previewImageSrc.value) return false
  if (skillPhase.value !== 'draft') return false
  return selectedVersionEntry.value?.status === 'draft'
})

const fileEditDirty = computed(() =>
  fileEditMode.value && fileEditDraft.value !== fileEditBaseline.value,
)

/** 版本下拉已确认切换的目标（用于保存失败时回滚 v-model） */
const committedVersion = ref<number | null>(null)

function syncCommittedVersion() {
  committedVersion.value = selectedVersion.value
}

function exitFileEditMode() {
  fileEditMode.value = false
  fileEditDraft.value = ''
  fileEditBaseline.value = ''
}

/** 将当前编辑内容写入服务端；silent 时不弹成功提示 */
async function persistFileEdit(opts?: { silent?: boolean }): Promise<boolean> {
  if (!fileEditDirty.value) {
    if (fileEditMode.value) exitFileEditMode()
    return true
  }
  if (!selectedId.value || selectedVersion.value == null || !selectedFilePath.value) return true
  savingFile.value = true
  try {
    const saved = await writeSkillFile(
      selectedId.value,
      selectedVersion.value,
      selectedFilePath.value,
      fileEditDraft.value,
    )
    fileContent.value = saved
    exitFileEditMode()
    if (!opts?.silent) {
      message.success('已保存')
    }
    if (isSkillMdPath(selectedFilePath.value)) {
      await loadVersions(selectedId.value)
      skills.value = await listSkills()
      syncCommittedVersion()
    }
    return true
  } catch (e: unknown) {
    message.error(e instanceof Error ? e.message : '保存失败')
    return false
  } finally {
    savingFile.value = false
  }
}

/** 离开当前编辑上下文前自动保存；失败则阻止离开 */
async function flushFileEditBeforeLeave(): Promise<boolean> {
  return persistFileEdit({ silent: true })
}

function sendFileEditKeepaliveOnUnload() {
  if (!fileEditDirty.value) return
  if (!selectedId.value || selectedVersion.value == null || !selectedFilePath.value) return
  writeSkillFileKeepalive(
    selectedId.value,
    selectedVersion.value,
    selectedFilePath.value,
    fileEditDraft.value,
  )
}

function enterFileEditMode() {
  if (!fileContent.value || fileContent.value.binary) return
  fileEditDraft.value = fileContent.value.content
  fileEditBaseline.value = fileContent.value.content
  fileEditMode.value = true
}

async function handleSaveFileEdit() {
  await persistFileEdit()
}

function handleCancelFileEdit() {
  if (fileEditDirty.value && !window.confirm('放弃未保存的修改？')) return
  exitFileEditMode()
}

function isSkillMdPath(path: string): boolean {
  const p = path.replace(/\\/g, '/').toLowerCase()
  return p === 'skill.md' || p.endsWith('/skill.md')
}

function clearPreview() {
  exitFileEditMode()
  selectedFilePath.value = null
  fileContent.value = null
}

const fileTreeNodes = computed(() => buildFileTree(files.value))

const treeOptions = computed<TreeOption[]>(() => {
  function mapNodes(nodes: ReturnType<typeof buildFileTree>): TreeOption[] {
    return nodes.map(n => ({
      key: n.key,
      label: n.label,
      isLeaf: !n.isDir,
      suffix: () =>
        !n.isDir
          ? h('span', { class: 'tree-size' }, formatFileSize(n.size))
          : undefined,
      children: n.children.length ? mapNodes(n.children) : undefined,
    }))
  }
  return mapNodes(fileTreeNodes.value)
})

function previewLanguage(path: string): string | null {
  const dot = path.lastIndexOf('.')
  if (dot < 0) return null
  const ext = path.slice(dot).toLowerCase()
  const map: Record<string, string> = {
    '.py': 'python',
    '.sh': 'bash',
    '.bash': 'bash',
    '.json': 'json',
    '.yaml': 'yaml',
    '.yml': 'yaml',
    '.sql': 'sql',
    '.xml': 'xml',
    '.html': 'xml',
    '.htm': 'xml',
    '.js': 'javascript',
    '.ts': 'javascript',
    '.jsx': 'javascript',
    '.tsx': 'javascript',
    '.md': 'markdown',
  }
  return map[ext] ?? null
}

const previewHtml = computed(() => {
  if (!fileContent.value || fileContent.value.binary) return ''
  if (fileContent.value.contentType === 'text/markdown' || fileContent.value.path.endsWith('.md')) {
    return md.render(fileContent.value.content)
  }
  return ''
})

const previewCodeHtml = computed(() => {
  const fc = fileContent.value
  if (!fc || fc.binary || previewHtml.value) return ''
  const lang = previewLanguage(fc.path)
  if (!lang) return ''
  try {
    return hljs.highlight(fc.content, { language: lang }).value
  } catch {
    try {
      return hljs.highlightAuto(fc.content).value
    } catch {
      return ''
    }
  }
})

const previewPlain = computed(() => {
  if (!fileContent.value || fileContent.value.binary) return ''
  if (previewHtml.value || previewCodeHtml.value) return ''
  return fileContent.value.content
})

const previewImageSrc = computed(() => {
  const fc = fileContent.value
  if (!fc) return ''
  const path = fc.path.toLowerCase()
  if (fc.binary) {
    if (!/\.(png|jpe?g|gif|webp|svg|ico)$/.test(path)) return ''
    const ct = fc.contentType || 'application/octet-stream'
    return `data:${ct};base64,${fc.content}`
  }
  if (path.endsWith('.svg')) {
    return `data:image/svg+xml;charset=utf-8,${encodeURIComponent(fc.content)}`
  }
  return ''
})

const previewCodeLangClass = computed(() => {
  const path = selectedFilePath.value
  if (!path) return 'hljs'
  const lang = previewLanguage(path)
  return lang ? `hljs language-${lang}` : 'hljs'
})

const showPreviewCopy = computed(() => {
  if (!fileContent.value || fileContent.value.binary || previewImageSrc.value) return false
  return !!(previewHtml.value || previewCodeHtml.value || previewPlain.value)
})

async function copyPreviewContent() {
  const text = fileContent.value?.content
  if (!text) return
  try {
    await navigator.clipboard.writeText(text)
    copyPreviewDone.value = true
    message.success('已复制')
    setTimeout(() => { copyPreviewDone.value = false }, 2000)
  } catch {
    message.error('复制失败')
  }
}

function refreshPreviewEnhancements() {
  if (mdPreviewRef.value) {
    enhanceStaticMarkdown(mdPreviewRef.value)
  }
}

watch(previewHtml, async (html) => {
  if (!html) return
  await nextTick()
  refreshPreviewEnhancements()
})

watch(theme, async () => {
  await nextTick()
  reRenderStaticMermaids()
  refreshPreviewEnhancements()
})

function renderTreePrefix({ option }: { option: TreeOption }) {
  const isDir = !option.isLeaf
  return h(NIcon, { size: 15, class: isDir ? 'tree-icon-dir' : 'tree-icon-file' }, {
    default: () => h(isDir ? FolderOutline : DocumentTextOutline),
  })
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
    message.error(e instanceof Error ? e.message : '刷新失败')
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
    const msg = e instanceof Error ? e.message : '加载版本失败'
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
    const msg = e instanceof Error ? e.message : '加载文件失败'
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

async function loadFileContent(path: string, opts?: { silent?: boolean }): Promise<boolean> {
  if (!selectedId.value || selectedVersion.value == null) return false
  if (!(await flushFileEditBeforeLeave())) {
    return false
  }
  if (!opts?.silent) {
    fileLoading.value = true
  }
  try {
    fileContent.value = await readSkillFile(selectedId.value, selectedVersion.value, path)
  } catch (e: unknown) {
    const msg = e instanceof Error ? e.message : '读取文件失败'
    message.error(msg)
    throw e instanceof Error ? e : new Error(msg)
  } finally {
    if (!opts?.silent) {
      fileLoading.value = false
    }
  }
  return true
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
    message.error(e instanceof Error ? e.message : '创建失败')
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
  else if (key === 'delete-version') {
    if (!(await flushFileEditBeforeLeave())) return
    showDeleteVersionConfirm.value = true
  }
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
    message.error(e instanceof Error ? e.message : '保存失败')
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
    message.error(e instanceof Error ? e.message : '操作失败')
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
    message.error(e instanceof Error ? e.message : '上传失败')
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
    message.error(e instanceof Error ? e.message : '生效失败')
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
    message.error(e instanceof Error ? e.message : '复制草稿失败')
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
    message.error(e instanceof Error ? e.message : '下载失败')
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
    message.error(e instanceof Error ? e.message : '删除失败')
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
    message.error(e instanceof Error ? e.message : '删除版本失败')
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
</script>

<template>
  <div class="skills-root">
    <input
      id="skill-folder-picker"
      ref="folderInputRef"
      type="file"
      webkitdirectory
      directory
      multiple
      class="folder-picker-input"
      @change="onFolderPicked"
    />
    <header class="page-header">
      <h2>Skills 管理</h2>
      <NSpace :size="8">
        <NButton round secondary @click="showCreate = true">
          <template #icon><NIcon :component="AddOutline" /></template>
          新建
        </NButton>
        <NButton round type="primary" class="action-btn" :loading="loading" @click="refreshPage">
          <template #icon><NIcon :component="RefreshOutline" /></template>
          刷新
        </NButton>
      </NSpace>
    </header>

    <div class="skills-layout">
      <aside class="list-panel">
        <div class="panel-head">
          <span class="panel-title">列表</span>
          <NTag :bordered="false" size="tiny" round>{{ filteredSkills.length }}</NTag>
        </div>
        <div class="list-search">
          <NInput
            v-model:value="skillSearch"
            placeholder="搜索名称或 ID…"
            size="small"
            round
            clearable
            class="search-input"
            :disabled="loading"
          >
            <template #prefix>
              <NIcon :component="SearchOutline" :size="14" />
            </template>
          </NInput>
        </div>
        <NSpin :show="loading" size="small" class="list-spin">
          <div class="list-body">
            <div v-if="filteredSkills.length === 0 && !loading" class="empty-wrap">
              <NEmpty size="small" description="暂无 Skill" />
            </div>
            <div
              v-for="skill in filteredSkills"
              :key="skill.id"
              class="skill-card"
              :class="{ active: skill.id === selectedId, disabled: !skill.enabled }"
            >
              <button type="button" class="skill-card-hit" @click="void selectSkill(skill.id)">
                <div class="skill-card-top">
                  <div class="skill-card-names">
                    <span class="skill-title">{{ skill.id }}</span>
                    <span v-if="skill.displayName && skill.displayName !== skill.id" class="skill-subtitle">{{ skill.displayName }}</span>
                    <span class="skill-version-line">{{ listCardActiveVersionLine(skill) }}</span>
                    <span v-if="listCardMaintainer(skill)" class="skill-maintainer">{{ listCardMaintainer(skill) }}</span>
                  </div>
                  <NSwitch
                    :value="skill.enabled"
                    :disabled="isSkillSwitchDisabled(skill)"
                    size="small"
                    @click.stop
                    @update:value="(v: boolean) => toggleEnabled(skill, v)"
                  />
                </div>
                <p v-if="skill.description" class="skill-desc">{{ skill.description }}</p>
              </button>
              <NDropdown
                trigger="click"
                size="small"
                :options="cardMenuOptions"
                @select="(key) => handleCardMenuSelect(skill, String(key))"
              >
                <button
                  type="button"
                  class="skill-card-more-btn"
                  title="Skill 操作"
                  aria-label="Skill 操作"
                  @click.stop
                >
                  <NIcon :component="EllipsisHorizontal" :size="14" />
                </button>
              </NDropdown>
            </div>
          </div>
        </NSpin>
      </aside>

      <main v-if="selectedSkill" class="detail-panel">
        <div class="detail-panel-inner">
          <div class="detail-toolbar">
            <div class="detail-title-block">
              <div class="detail-name-row">
                <h3>{{ selectedSkill.id }}</h3>
              </div>
              <div class="detail-meta-inline">
                <span
                  v-if="selectedSkill.displayName && selectedSkill.displayName !== selectedSkill.id"
                  class="detail-subtitle"
                >
                  {{ selectedSkill.displayName }}
                </span>
                <span v-if="detailMaintainerText" class="detail-maintainer">
                  {{ detailMaintainerText }}
                </span>
              </div>
            </div>
            <div v-show="!detailLoading || isActionBusy" class="detail-actions">
              <div v-if="showVersionSelect" class="version-row">
                <span class="version-label">当前版本</span>
                <NTag
                  v-if="selectedVersionStatus"
                  size="small"
                  :bordered="false"
                  round
                  :type="detailVersionTagType"
                >
                  {{ versionStatusLabel(selectedVersionStatus) }}
                </NTag>
                <NSelect
                  v-model:value="selectedVersion"
                  :options="versionOptions"
                  size="small"
                  class="version-select"
                  placeholder="选择版本"
                  :disabled="isActionBusy"
                  @update:value="onVersionSelected"
                />
              </div>
              <NDropdown
                trigger="click"
                size="small"
                :options="moreMenuOptions"
                :disabled="isActionBusy"
                @select="handleMoreMenuSelect"
              >
                <NButton
                  size="small"
                  quaternary
                  class="more-menu-btn"
                  title="版本操作"
                  aria-label="版本与文件操作"
                  :loading="isActionBusy"
                  :disabled="isActionBusy"
                >
                  <template #icon><NIcon :component="EllipsisHorizontal" :size="16" /></template>
                </NButton>
              </NDropdown>
            </div>
          </div>

          <div v-show="!isDetailBusy" class="detail-content">
          <div v-if="skillPhase === 'setup' && !isDetailBusy" class="setup-panel">
            <NEmpty description="选择含 SKILL.md 的文件夹完成首次上传">
              <template #extra>
                <label for="skill-folder-picker" class="folder-picker-label" @click="onPickLabelClick">
                  <NButton tag="span" type="primary" class="upload-folder-btn" :loading="uploading">
                    <template #icon><NIcon :component="FolderOpenOutline" /></template>
                    选择 Skill 文件夹
                  </NButton>
                </label>
              </template>
            </NEmpty>
          </div>
          <div v-else class="explorer">
            <div class="file-tree-pane">
              <div class="tree-scroll">
                <NTree
                  v-if="treeOptions.length"
                  block-line
                  selectable
                  expand-on-click
                  :data="treeOptions"
                  :expanded-keys="expandedKeys"
                  :selected-keys="selectedFilePath ? [selectedFilePath] : []"
                  :render-prefix="renderTreePrefix"
                  @update:expanded-keys="(k) => (expandedKeys = k.map(String))"
                  @update:selected-keys="onTreeSelect"
                />
                <NEmpty v-else-if="!isDetailBusy" size="small" description="无文件" />
              </div>
            </div>
            <div class="file-preview-pane">
              <div v-if="selectedFilePath" class="preview-bar">
                <NIcon :component="DocumentTextOutline" :size="14" />
                <span class="preview-path">{{ selectedFilePath }}</span>
                <div v-if="canEditCurrentFile" class="preview-bar-actions">
                  <template v-if="fileEditMode">
                    <NButton size="tiny" quaternary :disabled="savingFile" @click="handleCancelFileEdit">取消</NButton>
                    <NButton
                      size="tiny"
                      type="primary"
                      class="action-btn"
                      :loading="savingFile"
                      :disabled="!fileEditDirty"
                      @click="handleSaveFileEdit"
                    >
                      保存
                    </NButton>
                  </template>
                  <NButton
                    v-else
                    size="tiny"
                    quaternary
                    title="编辑当前草稿"
                    @click="enterFileEditMode"
                  >
                    <template #icon><NIcon :component="CreateOutline" /></template>
                    在线编辑
                  </NButton>
                </div>
                <button
                  v-if="showPreviewCopy && !fileEditMode"
                  type="button"
                  class="preview-copy-btn smd-toolbtn"
                  :title="copyPreviewDone ? '已复制' : '复制'"
                  @click="copyPreviewContent"
                >
                  <NIcon :component="copyPreviewDone ? CheckmarkOutline : CopyOutline" :size="14" />
                </button>
              </div>
              <div class="preview-scroll">
                <div v-if="fileLoading" class="preview-loading-pane">
                  <NSpin size="small" />
                </div>
                <div v-else-if="fileEditMode" class="preview-editor-wrap">
                  <NInput
                    v-model:value="fileEditDraft"
                    type="textarea"
                    :autosize="{ minRows: 12, maxRows: 40 }"
                    class="preview-editor"
                    placeholder="编辑文件内容…"
                  />
                </div>
                <div v-else-if="previewImageSrc" class="preview-image-wrap">
                  <img :src="previewImageSrc" :alt="selectedFilePath ?? ''" class="preview-image" />
                </div>
                <div v-else-if="fileContent?.binary" class="preview-binary">
                  <NText depth="3">
                    二进制文件（{{ fileContent.contentType }}），约 {{ formatFileSize(Math.round(fileContent.content.length * 0.75)) }}
                  </NText>
                </div>
                <div
                  v-else-if="previewHtml"
                  ref="mdPreviewRef"
                  :key="`${selectedId}-${selectedVersion}-${selectedFilePath}`"
                  class="msg-md skill-md-preview"
                  v-html="previewHtml"
                />
                <div
                  v-else-if="previewCodeHtml"
                  :key="`${selectedId}-${selectedVersion}-${selectedFilePath}-code`"
                  class="skill-file-plain"
                >
                  <pre class="skill-file-plain-pre"><code :class="previewCodeLangClass" v-html="previewCodeHtml" /></pre>
                </div>
                <pre
                  v-else-if="previewPlain"
                  :key="`${selectedId}-${selectedVersion}-${selectedFilePath}-plain`"
                  class="skill-file-plain skill-file-plain-pre"
                ><code>{{ previewPlain }}</code></pre>
                <div v-else-if="!isDetailBusy && selectedFilePath" class="preview-loading-pane">
                  <NSpin size="small" />
                </div>
                <div v-else-if="!isDetailBusy" class="preview-empty">
                  <NEmpty size="small" description="选择左侧文件预览" />
                </div>
              </div>
            </div>
          </div>
          </div>

          <div v-if="isDetailBusy" class="detail-panel-overlay">
            <NSpin size="medium" />
            <NText depth="3">{{ layoutBusyText }}</NText>
          </div>
        </div>
      </main>

      <NCard v-else class="detail-empty" size="small">
        <NSpin :show="loading">
          <NEmpty />
        </NSpin>
      </NCard>
    </div>

    <NModal v-model:show="showCreate" preset="dialog" title="新建 Skill" class="sunshine-dialog">
      <NForm label-placement="left" label-width="90">
        <NFormItem label="ID" required>
          <NInput v-model:value="createForm.id" placeholder="finance-analysis" />
        </NFormItem>
        <NFormItem label="显示名" required>
          <NInput v-model:value="createForm.displayName" placeholder="财务合规分析" />
        </NFormItem>
        <NFormItem label="描述">
          <NInput v-model:value="createForm.description" type="textarea" :autosize="{ minRows: 2, maxRows: 4 }" placeholder="可选；上传 SKILL.md 后将以其 frontmatter description 为准" />
        </NFormItem>
      </NForm>
      <template #action>
        <NButton @click="showCreate = false">取消</NButton>
        <NButton type="primary" class="action-btn" :loading="creating" @click="handleCreateConfirm">创建</NButton>
      </template>
    </NModal>
    <NModal
      v-model:show="showEdit"
      preset="dialog"
      title="修改 Skill"
      class="sunshine-dialog"
      @after-leave="editTargetSkill = null"
    >
      <NForm label-placement="left" label-width="90">
        <NFormItem label="ID">
          <NInput :value="editTargetSkill?.id ?? ''" disabled />
        </NFormItem>
        <NFormItem label="显示名" required>
          <NInput v-model:value="editForm.displayName" placeholder="财务合规分析" />
        </NFormItem>
        <NFormItem label="描述">
          <NInput
            v-model:value="editForm.description"
            type="textarea"
            :autosize="{ minRows: 2, maxRows: 4 }"
            placeholder="可选"
          />
        </NFormItem>
      </NForm>
      <template #action>
        <NButton @click="showEdit = false">取消</NButton>
        <NButton type="primary" class="action-btn" :loading="savingEdit" @click="handleEditConfirm">保存</NButton>
      </template>
    </NModal>
    <NModal
      v-model:show="showDeleteConfirm"
      preset="dialog"
      title="删除 Skill"
      class="sunshine-dialog"
      @after-leave="deleteTargetSkill = null"
    >
      <p>确定删除整个 Skill「{{ deleteTargetSkill?.id }}」（{{ deleteTargetSkill?.displayName }}）？此操作不可恢复。</p>
      <template #action>
        <NButton @click="showDeleteConfirm = false">取消</NButton>
        <NButton type="error" :loading="deleting" @click="handleDeleteConfirm">删除</NButton>
      </template>
    </NModal>
    <NModal v-model:show="showDeleteVersionConfirm" preset="dialog" title="删除该版本" class="sunshine-dialog">
      <p>
        确定删除版本「{{ formatSkillVersionTime(selectedVersionEntry?.createdAt) }}」？
        仅删除该版本文件，Skill 本身保留。
      </p>
      <template #action>
        <NButton @click="showDeleteVersionConfirm = false">取消</NButton>
        <NButton type="error" :loading="deletingVersion" @click="handleDeleteVersionConfirm">删除该版本</NButton>
      </template>
    </NModal>
  </div>
</template>

<style scoped>
.skills-root {
  height: 100vh;
  display: flex;
  flex-direction: column;
  padding: 20px 24px;
  gap: 12px;
  box-sizing: border-box;
  overflow: hidden;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
  flex-shrink: 0;
}

.page-header h2 {
  margin: 0;
  font-size: 20px;
  font-weight: 700;
  letter-spacing: -0.4px;
  color: var(--sun-text);
}

.skills-layout {
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: minmax(280px, 320px) 1fr;
  gap: 16px;
}

.list-panel,
.detail-panel,
.detail-empty {
  border-radius: var(--radius-lg);
  border: 1px solid var(--sun-border);
  background: var(--sun-surface);
  min-height: 0;
  overflow: hidden;
}

.list-panel {
  display: flex;
  flex-direction: column;
}

.panel-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 14px 16px 0;
}

.panel-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--sun-text);
}

.list-search {
  padding: 10px 12px;
}

.search-input {
  --n-color: var(--sun-deep) !important;
  --n-color-focus: var(--sun-deep) !important;
  --n-text-color: var(--sun-text) !important;
  --n-placeholder-color: var(--sun-text-muted) !important;
  --n-border: 1px solid var(--sun-border) !important;
  --n-border-focus: 1px solid var(--sun-border-light) !important;
  --n-border-hover: 1px solid var(--sun-border-light) !important;
  --n-box-shadow-focus: none !important;
}

.list-spin {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.list-spin :deep(.n-spin-content) {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.list-body {
  flex: 1;
  overflow-y: auto;
  padding: 0 10px 12px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.skill-card {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 6px;
  width: 100%;
  padding: 12px 12px 30px;
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-md);
  background: var(--sun-deep);
  transition: border-color 0.2s, background 0.2s;
}

.skill-card-hit {
  width: 100%;
  padding: 0;
  border: none;
  background: transparent;
  cursor: pointer;
  text-align: left;
  font-family: inherit;
}

.skill-card-more-btn {
  position: absolute;
  right: 6px;
  bottom: 4px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  padding: 0;
  border: none;
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--sun-text-secondary);
  cursor: pointer;
  transition: background 0.2s, color 0.2s;
}

.skill-card-more-btn:hover {
  background: var(--sun-surface-hover);
  color: var(--sun-text);
}

.skill-card:hover {
  border-color: var(--sun-border-light);
  background: var(--sun-surface-hover);
}

.skill-card.active {
  border-color: var(--sun-border-light);
  background: var(--sun-accent-muted);
  box-shadow: inset 0 0 0 1px var(--sun-accent-muted);
}

.skill-card.disabled {
  opacity: 0.72;
}

.skill-card-top {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 8px;
}

.skill-card-names {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.skill-title {
  font-weight: 600;
  font-size: 14px;
  color: var(--sun-text);
  line-height: 1.3;
  font-family: 'JetBrains Mono', monospace;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.skill-subtitle {
  font-size: 12px;
  color: var(--sun-text-secondary);
  line-height: 1.35;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.skill-version-line {
  font-size: 11px;
  color: var(--sun-text-secondary);
  font-family: 'JetBrains Mono', monospace;
  line-height: 1.35;
}

.skill-maintainer {
  font-size: 11px;
  color: var(--sun-text-muted);
  line-height: 1.35;
}

.skill-desc {
  margin: 0;
  font-size: 12px;
  color: var(--sun-text-secondary);
  line-height: 1.45;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.detail-panel {
  display: flex;
  flex-direction: column;
  padding: 16px;
  min-height: 0;
}

.detail-panel-inner {
  position: relative;
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.detail-panel-overlay {
  position: absolute;
  inset: 0;
  z-index: 3;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  background: var(--sun-surface);
  border-radius: var(--radius-md);
}

.detail-content {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.setup-panel {
  flex: 1;
  min-height: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 1px dashed var(--sun-border);
  border-radius: var(--radius-md);
  background: var(--sun-deep);
}

.detail-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: var(--radius-lg) !important;
  border: 1px solid var(--sun-border) !important;
  background: var(--sun-surface) !important;
}

.detail-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
  flex-wrap: wrap;
  flex-shrink: 0;
}

.detail-title-block h3 {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  color: var(--sun-text);
  letter-spacing: -0.2px;
  font-family: 'JetBrains Mono', monospace;
}

.detail-name-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.detail-meta-inline {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 4px;
}

.detail-subtitle {
  font-size: 13px;
  color: var(--sun-text-secondary);
}

.detail-maintainer {
  font-size: 12px;
  color: var(--sun-text-muted);
}

.detail-actions {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  flex-shrink: 0;
  min-height: 28px;
}

.version-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.version-label {
  font-size: 13px;
  color: var(--sun-text-secondary);
  white-space: nowrap;
}

.version-select {
  width: min(200px, 38vw);
}

.preview-bar {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 12px;
  border-bottom: 1px solid var(--sun-border);
  flex-shrink: 0;
  background: var(--sun-surface);
}

.preview-bar-actions {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-left: auto;
  flex-shrink: 0;
}

.preview-editor-wrap {
  padding: 12px;
  height: 100%;
  box-sizing: border-box;
}

.preview-editor {
  width: 100%;
  font-family: var(--sun-font-mono, 'JetBrains Mono', monospace);
  font-size: var(--sun-font-base);
  --n-font-size: var(--sun-font-base) !important;
}

.preview-copy-btn {
  margin-left: auto;
  flex-shrink: 0;
}

.preview-bar-actions + .preview-copy-btn {
  margin-left: 0;
}

.upload-folder-btn {
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

.folder-picker-input {
  position: fixed;
  left: -10000px;
  top: 0;
  width: 1px;
  height: 1px;
  opacity: 0;
}

.folder-picker-label {
  cursor: pointer;
  display: inline-flex;
}

.explorer {
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: minmax(220px, 260px) 1fr;
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-md);
  overflow: hidden;
  background: var(--sun-deep);
  position: relative;
}

.file-tree-pane,
.file-preview-pane {
  display: flex;
  flex-direction: column;
  min-height: 0;
  overflow: hidden;
}

.file-tree-pane {
  border-right: 1px solid var(--sun-border);
}

.preview-path {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-family: 'JetBrains Mono', monospace;
  font-weight: 500;
  font-size: 11px;
}

.preview-loading {
  font-size: 11px;
  flex-shrink: 0;
}

.tree-scroll,
.preview-scroll {
  flex: 1;
  overflow: auto;
  padding: 8px;
}

.file-tree-pane :deep(.n-tree) {
  --n-node-color-active: var(--sun-accent-muted) !important;
  --n-node-color-hover: var(--sun-row-hover) !important;
  font-size: 13px;
}

.file-tree-pane :deep(.n-tree-node-content) {
  border-radius: var(--radius-sm);
}

.file-tree-pane :deep(.tree-size) {
  font-size: 10px;
  color: var(--sun-text-muted);
  font-family: 'JetBrains Mono', monospace;
  margin-left: 4px;
}

.file-tree-pane :deep(.tree-icon-dir) {
  color: var(--sun-amber);
}

.file-tree-pane :deep(.tree-icon-file) {
  color: var(--sun-text-secondary);
}

.preview-image-wrap {
  padding: 16px;
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 120px;
}

.preview-image {
  max-width: 100%;
  max-height: 480px;
  object-fit: contain;
  border-radius: var(--radius-sm);
  border: 1px solid var(--sun-border);
}

.preview-binary {
  padding: 16px;
  text-align: center;
}

.preview-loading-pane {
  flex: 1;
  min-height: 160px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.preview-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 160px;
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

.more-menu-btn {
  padding: 0 6px;
}

:deep(.more-menu-delete) {
  color: var(--n-color-error);
}

.empty-wrap {
  padding: 24px 0;
}

@media (max-width: 960px) {
  .skills-layout {
    grid-template-columns: 1fr;
    grid-template-rows: auto 1fr;
  }

  .list-panel {
    max-height: 240px;
  }

  .explorer {
    grid-template-columns: 1fr;
    grid-template-rows: 200px 1fr;
  }

  .file-tree-pane {
    border-right: none;
    border-bottom: 1px solid var(--sun-border);
  }
}
</style>
