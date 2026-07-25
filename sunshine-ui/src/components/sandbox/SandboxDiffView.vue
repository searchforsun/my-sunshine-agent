<script setup lang="ts">
import { computed } from 'vue'
import type { SandboxDiffLine } from '../../api/sandboxEditDiff'
import { registerHljsLanguages } from '../../utils/markdown/registerHljsLanguages'
import CodeLineGutter from './CodeLineGutter.vue'

const props = defineProps<{
  lines: SandboxDiffLine[]
  lang: string | null
}>()

const hljs = registerHljsLanguages()

function escapeHtml(s: string): string {
  return s
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

function highlightCode(text: string, lang: string | null): string {
  if (!text) return ''
  try {
    if (lang && hljs.getLanguage(lang)) {
      return hljs.highlight(text, { language: lang }).value
    }
    return hljs.highlightAuto(text).value
  } catch {
    return ''
  }
}

const renderedLines = computed(() =>
  props.lines.map(line => {
    if (line.kind === 'fold') {
      return {
        kind: line.kind,
        oldLine: line.oldLine,
        newLine: line.newLine,
        mark: '' as const,
        html: '',
        isFold: true,
      }
    }
    const mark = (line.kind === 'del' ? '-' : line.kind === 'add' ? '+' : '') as '' | '+' | '-'
    const html = highlightCode(line.text || ' ', props.lang) || escapeHtml(line.text || ' ')
    return {
      kind: line.kind,
      oldLine: line.oldLine,
      newLine: line.newLine,
      mark,
      html,
      isFold: false,
    }
  }),
)
</script>

<template>
  <div class="sandbox-diff-view">
    <div
      v-for="(line, idx) in renderedLines"
      :key="idx"
      class="diff-row"
      :class="[`is-${line.kind}`, { 'is-fold': line.isFold }]"
    >
      <CodeLineGutter
        mode="diff"
        :old-line="line.oldLine"
        :new-line="line.newLine"
        :mark="line.mark"
      />
      <code v-if="line.isFold" class="diff-fold">···</code>
      <code v-else class="diff-code hljs" v-html="line.html" />
    </div>
  </div>
</template>

<style scoped>
.sandbox-diff-view {
  display: flex;
  flex-direction: column;
  font-family: var(--sun-font-mono, 'JetBrains Mono', ui-monospace, monospace);
  font-size: var(--sun-font-sm, 12px);
  line-height: 1.45;
  letter-spacing: 0;
  font-variant-ligatures: none;
  tab-size: 4;
  color: var(--sun-text-muted);
}

.diff-row {
  display: flex;
  align-items: flex-start;
  min-width: 0;
  white-space: pre-wrap;
  overflow-wrap: break-word;
  word-break: normal;
}

.diff-row.is-del {
  background: color-mix(in srgb, #c44 28%, transparent);
}

.diff-row.is-add {
  background: color-mix(in srgb, #2a9a5c 28%, transparent);
}

.diff-row.is-fold {
  background: transparent;
}

.diff-code,
.diff-fold {
  flex: 1;
  min-width: 0;
  padding: 0 4px;
  margin: 0;
  background: transparent !important;
  white-space: pre-wrap;
  font-family: var(--sun-font-mono, 'JetBrains Mono', ui-monospace, monospace) !important;
}

.diff-fold {
  color: var(--sun-text-muted);
  opacity: 0.65;
  user-select: none;
}

.diff-row :deep(span) {
  font-family: var(--sun-font-mono, 'JetBrains Mono', ui-monospace, monospace) !important;
}
</style>
