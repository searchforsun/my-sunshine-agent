<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import type { SkillCatalogIndexEntry } from '../../api/skills'
import {
  displaySegments,
  editorNeedsChipSync,
  getCaretPlainOffset,
  plainTextFromEditor,
  renderEditorSegments,
  setCaretPlainOffset,
  shouldRenderChips,
} from '../../utils/skillMentionEditor'

const props = defineProps<{
  modelValue: string
  allowsSkillMention: boolean
  catalog: SkillCatalogIndexEntry[]
  placeholder?: string
}>()

const emit = defineEmits<{
  'update:modelValue': [value: string]
  keydown: [e: KeyboardEvent]
}>()

const editorRef = ref<HTMLDivElement | null>(null)
const textareaRef = ref<HTMLTextAreaElement | null>(null)
const syncing = ref(false)
const isComposing = ref(false)

const useChipEditor = computed(() => props.allowsSkillMention)

function resizeTextarea(el: HTMLTextAreaElement) {
  el.style.height = 'auto'
  el.style.height = `${el.scrollHeight}px`
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
  const segments = displaySegments(plain, true, props.catalog)
  renderEditorSegments(el, segments)
  setCaretPlainOffset(el, offset)
  resizeEditor(el)
  syncing.value = false
}

function syncPlainInput(plain: string) {
  const el = textareaRef.value
  if (!el) return
  syncing.value = true
  if (el.value !== plain) el.value = plain
  resizeTextarea(el)
  syncing.value = false
}

function applyExternalValue(plain: string) {
  if (useChipEditor.value) {
    if (!editorRef.value) return
    syncChipEditor(plain, plain.length)
  } else if (textareaRef.value) {
    syncPlainInput(plain)
  }
}

watch(
  () => props.modelValue,
  (val) => {
    if (syncing.value) return
    const current = useChipEditor.value && editorRef.value
      ? plainTextFromEditor(editorRef.value)
      : textareaRef.value?.value ?? ''
    if (val === current) return
    applyExternalValue(val)
  },
)

watch(useChipEditor, () => {
  nextTick(() => applyExternalValue(props.modelValue))
})

watch(
  () => props.catalog,
  () => {
    if (!useChipEditor.value || syncing.value) return
    if (shouldRenderChips(props.modelValue, true, props.catalog)) {
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
    resizeEditor(el)
    return
  }
  if (editorNeedsChipSync(el, plain, true, props.catalog)) {
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
  if (editorNeedsChipSync(el, plain, true, props.catalog)) {
    syncChipEditor(plain, caret)
  } else {
    resizeEditor(el)
  }
}

function onTextareaInput(e: Event) {
  if (syncing.value) return
  const val = (e.target as HTMLTextAreaElement).value
  syncing.value = true
  emit('update:modelValue', val)
  syncing.value = false
  resizeTextarea(e.target as HTMLTextAreaElement)
}

function onPaste(e: ClipboardEvent) {
  if (!useChipEditor.value) return
  e.preventDefault()
  const text = e.clipboardData?.getData('text/plain') ?? ''
  document.execCommand('insertText', false, text)
}

function onEditorKeydown(e: KeyboardEvent) {
  emit('keydown', e)
}

function onTextareaKeydown(e: KeyboardEvent) {
  emit('keydown', e)
}

function focus() {
  if (useChipEditor.value) {
    editorRef.value?.focus()
  } else {
    textareaRef.value?.focus()
  }
}

defineExpose({ focus })

onMounted(() => {
  nextTick(() => applyExternalValue(props.modelValue))
})
</script>

<template>
  <div class="composer-skill-field">
    <div
      v-if="useChipEditor"
      ref="editorRef"
      class="composer-editor"
      contenteditable="true"
      role="textbox"
      aria-multiline="true"
      :data-placeholder="placeholder"
      @input="onEditorInput"
      @compositionstart="isComposing = true"
      @compositionend="onCompositionEnd"
      @keydown="onEditorKeydown"
      @paste="onPaste"
    />
    <textarea
      v-else
      ref="textareaRef"
      class="composer-textarea"
      :value="modelValue"
      :placeholder="placeholder"
      rows="1"
      @input="onTextareaInput"
      @keydown="onTextareaKeydown"
    />
  </div>
</template>

<style scoped>
.composer-skill-field {
  display: flex;
  flex: 1;
  min-width: 0;
}
.composer-editor,
.composer-textarea {
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
}

.composer-editor {
  display: block;
}

.composer-editor:empty::before {
  content: attr(data-placeholder);
  color: var(--sun-text-muted);
  pointer-events: none;
}

.composer-textarea::placeholder {
  color: var(--sun-text-muted);
}
</style>
