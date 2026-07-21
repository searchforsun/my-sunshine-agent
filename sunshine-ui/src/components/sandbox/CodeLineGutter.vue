<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{
  mode: 'diff' | 'file'
  oldLine?: number | null
  newLine?: number | null
  mark?: '' | '+' | '-'
}>()

/** 统一 diff：单列行号（del 用旧号、add 用新号、ctx 两侧同号） */
const displayLine = computed(() => {
  if (props.mode === 'file') return props.newLine ?? ''
  if (props.mark === '-') return props.oldLine ?? ''
  if (props.mark === '+') return props.newLine ?? ''
  return props.newLine ?? props.oldLine ?? ''
})
</script>

<template>
  <span class="code-gutter" :class="[`is-${mode}`, mark ? `mark-${mark === '+' ? 'add' : 'del'}` : '']" aria-hidden="true">
    <span class="gutter-line">{{ displayLine }}</span>
    <span v-if="mode === 'diff'" class="gutter-mark">{{ mark || '' }}</span>
  </span>
</template>

<style scoped>
.code-gutter {
  display: inline-flex;
  flex-shrink: 0;
  align-items: stretch;
  gap: 0;
  user-select: none;
  font-family: var(--sun-font-mono, 'JetBrains Mono', ui-monospace, monospace);
  font-size: var(--sun-font-sm, 12px);
  line-height: 1.45;
  color: var(--sun-text-muted);
  opacity: 0.72;
}

.code-gutter.is-diff {
  min-width: 4.5ch;
}

.code-gutter.is-file {
  min-width: 3.5ch;
}

.gutter-line {
  display: inline-block;
  min-width: 2.5ch;
  padding: 0 4px;
  text-align: right;
  box-sizing: content-box;
}

.gutter-mark {
  display: inline-block;
  width: 1.2ch;
  text-align: center;
  flex-shrink: 0;
  font-weight: 400;
  opacity: 0.85;
}

.code-gutter.mark-add {
  opacity: 1;
}

.code-gutter.mark-add .gutter-mark {
  color: #2a9a5c;
}

.code-gutter.mark-del {
  opacity: 1;
}

.code-gutter.mark-del .gutter-mark {
  color: #c44;
}
</style>
