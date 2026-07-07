import { computed, h, nextTick, ref, watch, type ComputedRef, type Ref } from 'vue'
import { NIcon, type MessageApi, type TreeOption } from 'naive-ui'
import { DocumentTextOutline, FolderOutline } from '@vicons/ionicons5'
import markdown from 'highlight.js/lib/languages/markdown'
import {
  readSkillFile,
  writeSkillFile,
  writeSkillFileKeepalive,
  type SkillFileContent,
  type SkillVersion,
} from '../api/skills'
import { friendlyErrorMessage } from '../api/apiError'
import { buildFileTree, formatFileSize } from '../utils/buildFileTree'
import { createMarkdownIt } from '../utils/markdown/createMarkdownIt'
import { registerHljsLanguages } from '../utils/markdown/registerHljsLanguages'
import { enhanceStaticMarkdown, reRenderStaticMermaids } from '../utils/stream-markdown/StaticEnhancer'
import { copyText } from '../utils/stream-markdown/clipboard'
import { theme } from '../composables/useTheme'
import type { SkillPhase } from '../utils/skills/skillsVersionUtils'
import '../utils/stream-markdown/styles.css'

const hljs = registerHljsLanguages()
hljs.registerLanguage('markdown', markdown)
const md = createMarkdownIt(hljs)

export interface SkillFilePreviewDeps {
  selectedId: Ref<string | null>
  selectedVersion: Ref<number | null>
  selectedFilePath: Ref<string | null>
  fileContent: Ref<SkillFileContent | null>
  fileLoading: Ref<boolean>
  files: Ref<{ path: string; size: number; directory: boolean }[]>
  skillPhase: ComputedRef<SkillPhase>
  selectedVersionEntry: ComputedRef<SkillVersion | null | undefined>
  message: MessageApi
}

export function useSkillFilePreview(deps: SkillFilePreviewDeps) {
  const mdPreviewRef = ref<HTMLElement | null>(null)
  const copyPreviewDone = ref(false)
  const savingFile = ref(false)
  const fileEditMode = ref(false)
  const fileEditDraft = ref('')
  const fileEditBaseline = ref('')

  const canEditCurrentFile = computed(() => {
    if (!deps.selectedFilePath.value || !deps.fileContent.value || deps.fileContent.value.binary) return false
    if (previewImageSrc.value) return false
    if (deps.skillPhase.value !== 'draft') return false
    return deps.selectedVersionEntry.value?.status === 'draft'
  })

  const fileEditDirty = computed(() =>
    fileEditMode.value && fileEditDraft.value !== fileEditBaseline.value,
  )

  function exitFileEditMode() {
    fileEditMode.value = false
    fileEditDraft.value = ''
    fileEditBaseline.value = ''
  }

  async function persistFileEdit(opts?: { silent?: boolean }): Promise<boolean> {
    if (!fileEditDirty.value) {
      if (fileEditMode.value) exitFileEditMode()
      return true
    }
    if (!deps.selectedId.value || deps.selectedVersion.value == null || !deps.selectedFilePath.value) return true
    savingFile.value = true
    try {
      const saved = await writeSkillFile(
        deps.selectedId.value,
        deps.selectedVersion.value,
        deps.selectedFilePath.value,
        fileEditDraft.value,
      )
      deps.fileContent.value = saved
      fileEditBaseline.value = saved.content
      exitFileEditMode()
      if (!opts?.silent) deps.message.success('已保存')
      return true
    } catch (e: unknown) {
      deps.message.error(friendlyErrorMessage(e, '保存失败'))
      return false
    } finally {
      savingFile.value = false
    }
  }

  async function flushFileEditBeforeLeave(): Promise<boolean> {
    return persistFileEdit({ silent: true })
  }

  function sendFileEditKeepaliveOnUnload() {
    if (!fileEditDirty.value) return
    if (!deps.selectedId.value || deps.selectedVersion.value == null || !deps.selectedFilePath.value) return
    writeSkillFileKeepalive(
      deps.selectedId.value,
      deps.selectedVersion.value,
      deps.selectedFilePath.value,
      fileEditDraft.value,
    )
  }

  function enterFileEditMode() {
    if (!deps.fileContent.value || deps.fileContent.value.binary) return
    fileEditDraft.value = deps.fileContent.value.content
    fileEditBaseline.value = deps.fileContent.value.content
    fileEditMode.value = true
  }

  async function handleSaveFileEdit() {
    await persistFileEdit()
  }

  function handleCancelFileEdit() {
    if (fileEditDirty.value && !window.confirm('放弃未保存的修改？')) return
    exitFileEditMode()
  }

  function clearPreview() {
    exitFileEditMode()
    deps.selectedFilePath.value = null
    deps.fileContent.value = null
  }

  const fileTreeNodes = computed(() => buildFileTree(deps.files.value))

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
    if (!deps.fileContent.value || deps.fileContent.value.binary) return ''
    if (deps.fileContent.value.contentType === 'text/markdown' || deps.fileContent.value.path.endsWith('.md')) {
      return md.render(deps.fileContent.value.content)
    }
    return ''
  })

  const previewCodeHtml = computed(() => {
    const fc = deps.fileContent.value
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
    if (!deps.fileContent.value || deps.fileContent.value.binary) return ''
    if (previewHtml.value || previewCodeHtml.value) return ''
    return deps.fileContent.value.content
  })

  const previewImageSrc = computed(() => {
    const fc = deps.fileContent.value
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
    const path = deps.selectedFilePath.value
    if (!path) return 'hljs'
    const lang = previewLanguage(path)
    return lang ? `hljs language-${lang}` : 'hljs'
  })

  const showPreviewCopy = computed(() => {
    if (!deps.fileContent.value || deps.fileContent.value.binary || previewImageSrc.value) return false
    return !!(previewHtml.value || previewCodeHtml.value || previewPlain.value)
  })

  async function copyPreviewContent() {
    const text = deps.fileContent.value?.content
    if (!text) return
    const ok = await copyText(text)
    if (ok) {
      copyPreviewDone.value = true
      deps.message.success('已复制')
      setTimeout(() => { copyPreviewDone.value = false }, 2000)
    }
  }

  function refreshPreviewEnhancements() {
    const el = mdPreviewRef.value
    if (!el) return
    enhanceStaticMarkdown(el)
    reRenderStaticMermaids()
  }

  watch(previewHtml, async (html) => {
    if (!html) return
    await nextTick()
    refreshPreviewEnhancements()
  })

  watch(() => theme.value, () => {
    refreshPreviewEnhancements()
  })

  function renderTreePrefix({ option }: { option: TreeOption }) {
    const isDir = !option.isLeaf
    return h(NIcon, { size: 15, class: isDir ? 'tree-icon-dir' : 'tree-icon-file' }, {
      default: () => h(isDir ? FolderOutline : DocumentTextOutline),
    })
  }

  async function loadFileContent(path: string, opts?: { silent?: boolean }): Promise<boolean> {
    if (!deps.selectedId.value || deps.selectedVersion.value == null) return false
    if (!(await flushFileEditBeforeLeave())) {
      return false
    }
    if (!opts?.silent) {
      deps.fileLoading.value = true
    }
    try {
      deps.fileContent.value = await readSkillFile(deps.selectedId.value, deps.selectedVersion.value, path)
    } catch (e: unknown) {
      const msg = friendlyErrorMessage(e, '读取文件失败')
      deps.message.error(msg)
      throw e instanceof Error ? e : new Error(msg)
    } finally {
      if (!opts?.silent) {
        deps.fileLoading.value = false
      }
    }
    return true
  }

  return {
    mdPreviewRef,
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
  }
}
