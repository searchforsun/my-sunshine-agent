<script setup lang="ts">
defineProps<{
  mode: 'diff' | 'file'
  oldLine?: number | null
  newLine?: number | null
  mark?: '' | '+' | '-'
}>()
</script>

<template>
  <span class="code-gutter" :class="`is-${mode}`" aria-hidden="true">
    <template v-if="mode === 'diff'">
      <span class="gutter-old">{{ oldLine ?? '' }}</span>
      <span class="gutter-new">{{ newLine ?? '' }}</span>
      <span class="gutter-mark">{{ mark || '' }}</span>
    </template>
    <template v-else>
      <span class="gutter-new">{{ newLine ?? '' }}</span>
    </template>
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
  min-width: 7.5ch;
}

.code-gutter.is-file {
  min-width: 3.5ch;
}

.gutter-old,
.gutter-new {
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
}
</style>
