<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import type { SkillCatalogIndexEntry } from '../../api/skills'
import type { ExpertCatalogIndexEntry } from '../../api/experts'
import type { WorkflowCatalogEntry } from '../../api/workflows'
import {
  displaySegments,
  editorNeedsChipSync,
  getCaretPlainOffset,
  insertPlainAtOffset,
  plainTextFromEditor,
  renderEditorSegments,
  setCaretPlainOffset,
  shouldRenderChips,
  type ComposerMentionContext,
} from '../../utils/skillMentionEditor'
import {
  parseSandboxPathDrag,
  sandboxPathPlainToken,
  SANDBOX_PATH_MIME,
} from '../../utils/sandboxPathChip'
import { useChatStore } from '../../stores/chatStore'
import { useSandboxWorkspaceDrawer } from '../../composables/useSandboxWorkspaceDrawer'

const props = defineProps<{
  modelValue: string
  allowsSkillMention: boolean
  allowsExpertMention?: boolean
  allowsWorkflowMention?: boolean
  catalog: SkillCatalogIndexEntry[]
  expertCatalog?: ExpertCatalogIndexEntry[]
  workflowCatalog?: WorkflowCatalogEntry[]
  placeholder?: string
}>()

const emit = defineEmits<{
  'update:modelValue': [value: string]
  keydown: [e: KeyboardEvent]
}>()

const editorRef = ref<HTMLDivElement | null>(null)
const syncing = ref(false)
const isComposing = ref(false)
const pathDragOver = ref(false)
const chatStore = useChatStore()
const sandboxDrawer = useSandboxWorkspaceDrawer()

const mentionContext = computed<ComposerMentionContext>(() => ({
  catalogs: {
    skills: props.catalog,
    experts: props.expertCatalog ?? [],
    workflows: props.workflowCatalog ?? [],
  },
  allows: {
    skill: props.allowsSkillMention,
    expert: props.allowsExpertMention ?? false,
    workflow: props.allowsWorkflowMention ?? false,
  },
}))

const isEditorEmpty = computed(() => !props.modelValue.trim())

function normalizeEmptyEditor(el: HTMLDivElement) {
  if (!plainTextFromEditor(el).trim() && el.childNodes.length > 0) {
    el.replaceChildren()
  }
}

function resizeEditor(el: HTMLDivElement) {
  el.style.height = 'auto'
  el.style.height = `${el.scrollHeight}px`
}

function syncChipEditor(plain: string, caret?: number) {
  const el = editorRef.value
  if (!el) return
  syncing.value = true
  const offset = caret ?? getCaretPlainOffset(el)
  const segments = displaySegments(plain, mentionContext.value)
  renderEditorSegments(el, segments)
  setCaretPlainOffset(el, offset)
  resizeEditor(el)
  syncing.value = false
}

function applyExternalValue(plain: string) {
  if (!editorRef.value) return
  syncChipEditor(plain, plain.length)
}

watch(
  () => props.modelValue,
  (val) => {
    if (syncing.value) return
    const current = editorRef.value ? plainTextFromEditor(editorRef.value) : ''
    if (val === current) return
    applyExternalValue(val)
  },
)

watch(
  mentionContext,
  () => {
    if (syncing.value) return
    if (shouldRenderChips(props.modelValue, mentionContext.value)) {
      syncChipEditor(props.modelValue)
    }
  },
  { deep: true },
)

function onEditorInput() {
  if (syncing.value || !editorRef.value) return
  const el = editorRef.value
  const plain = plainTextFromEditor(el)
  const caret = getCaretPlainOffset(el)
  syncing.value = true
  emit('update:modelValue', plain)
  syncing.value = false
  if (isComposing.value) {
    normalizeEmptyEditor(el)
    resizeEditor(el)
    return
  }
  if (!plain.trim()) {
    normalizeEmptyEditor(el)
    resizeEditor(el)
    return
  }
  if (editorNeedsChipSync(el, plain, mentionContext.value)) {
    syncChipEditor(plain, caret)
  } else {
    resizeEditor(el)
  }
}

function onCompositionEnd() {
  isComposing.value = false
  if (!editorRef.value || syncing.value) return
  const el = editorRef.value
  const plain = plainTextFromEditor(el)
  const caret = getCaretPlainOffset(el)
  if (!plain.trim()) {
    normalizeEmptyEditor(el)
    resizeEditor(el)
    return
  }
  if (editorNeedsChipSync(el, plain, mentionContext.value)) {
    syncChipEditor(plain, caret)
  } else {
    resizeEditor(el)
  }
}

function onPaste(e: ClipboardEvent) {
  e.preventDefault()
  const text = e.clipboardData?.getData('text/plain') ?? ''
  document.execCommand('insertText', false, text)
}

function onEditorKeydown(e: KeyboardEvent) {
  emit('keydown', e)
}

function isPathDragTypes(dt: DataTransfer | null): boolean {
  if (!dt) return false
  const types = Array.from(dt.types)
  // Chromium 自定义 MIME 会出现在 types；兜底 text/plain（我们拖拽必写）
  return types.includes(SANDBOX_PATH_MIME) || types.includes('text/plain')
}

function onDragOver(e: DragEvent) {
  // dragover 阶段 getData 常为空，仅靠 MIME types
  if (!isPathDragTypes(e.dataTransfer)) return
  e.preventDefault()
  if (e.dataTransfer) e.dataTransfer.dropEffect = 'copy'
  pathDragOver.value = true
}

function onDragLeave(e: DragEvent) {
  const related = e.relatedTarget as Node | null
  const root = e.currentTarget as HTMLElement
  if (related && root.contains(related)) return
  pathDragOver.value = false
}

function onDrop(e: DragEvent) {
  pathDragOver.value = false
  const payload = parseSandboxPathDrag(e.dataTransfer)
  if (!payload) return
  e.preventDefault()
  const el = editorRef.value
  if (!el) return
  el.focus()
  const plain = plainTextFromEditor(el)
  const caret = getCaretPlainOffset(el)
  const token = sandboxPathPlainToken(payload.path)
  const { next, caret: nextCaret } = insertPlainAtOffset(plain, caret, token)
  syncing.value = true
  emit('update:modelValue', next)
  syncing.value = false
  nextTick(() => syncChipEditor(next, nextCaret))
}

function onEditorClick(e: MouseEvent) {
  const el = (e.target as HTMLElement | null)?.closest?.('.mention-chip--path') as HTMLElement | null
  if (!el) return
  const path = el.dataset.mentionId?.trim()
  const cid = chatStore.currentId
  if (!path || !cid) return
  e.preventDefault()
  e.stopPropagation()
  sandboxDrawer.open({ conversationId: cid, focusPath: path })
}

function focus() {
  editorRef.value?.focus()
}

defineExpose({ focus })

onMounted(() => {
  nextTick(() => applyExternalValue(props.modelValue))
})
</script>

<template>
  <div
    class="composer-skill-field"
    :class="{ 'is-path-dragover': pathDragOver }"
    @dragover="onDragOver"
    @dragleave="onDragLeave"
    @drop="onDrop"
  >
    <div
      ref="editorRef"
      class="composer-editor"
      :class="{ 'is-empty': isEditorEmpty }"
      contenteditable="true"
      role="textbox"
      aria-multiline="true"
      :data-placeholder="placeholder"
      @input="onEditorInput"
      @compositionstart="isComposing = true"
      @compositionend="onCompositionEnd"
      @keydown="onEditorKeydown"
      @paste="onPaste"
      @click="onEditorClick"
    />
  </div>
</template>

<style scoped>
.composer-skill-field {
  display: flex;
  flex: 1;
  min-width: 0;
  border-radius: 4px;
  transition: box-shadow 0.12s ease, outline-color 0.12s ease;
}

.composer-skill-field.is-path-dragover {
  outline: 1px dashed var(--sun-border);
  outline-offset: 2px;
  box-shadow: inset 0 0 0 1px transparent;
}

.composer-editor {
  flex: 1;
  width: 100%;
  min-width: 0;
  min-height: 28px;
  max-height: 144px;
  overflow-y: auto;
  padding: 4px 2px;
  border: none;
  outline: none;
  resize: none;
  background: transparent;
  font-family: inherit;
  font-size: var(--sun-font-md);
  line-height: var(--sun-line, 1.5);
  color: var(--sun-text);
  white-space: pre-wrap;
  word-break: break-word;
  display: block;
}

.composer-editor.is-empty::before {
  content: attr(data-placeholder);
  color: var(--sun-text-muted);
  font-size: var(--sun-font-sm);
  line-height: 1.4;
  pointer-events: none;
}
</style>
