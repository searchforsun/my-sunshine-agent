<script setup lang="ts">
import { NIcon } from 'naive-ui'
import { CheckmarkOutline, CopyOutline } from '@vicons/ionicons5'
import type { ProcessingStep } from '../../api/processingSteps'
import { useSandboxToolExpand } from '../../composables/useSandboxToolExpand'

const props = defineProps<{
  step: ProcessingStep
}>()

const emit = defineEmits<{
  openPath: [path: string]
}>()

const {
  isSandboxExec,
  execCommand,
  sandboxRaw,
  sandboxPathEntries,
  editDiffRendered,
  execCommandHtml,
  execOutputHtml,
  sandboxContentHtml,
  sandboxCopyDone,
  copySandboxContent,
} = useSandboxToolExpand(() => props.step)
</script>

<template>
  <div class="op-sandbox">
    <button
      v-if="execCommand || sandboxRaw"
      type="button"
      class="op-sandbox-copy smd-toolbtn"
      :title="sandboxCopyDone ? '已复制' : '复制'"
      @click.stop="copySandboxContent"
    >
      <NIcon :component="sandboxCopyDone ? CheckmarkOutline : CopyOutline" :size="14" />
    </button>
    <template v-if="isSandboxExec">
      <pre v-if="execCommand" class="op-exec-cmd"><span class="op-exec-prompt">$</span><code v-if="execCommandHtml" class="hljs language-bash" v-html="execCommandHtml" /><span v-else class="op-exec-cmd-plain">{{ execCommand }}</span></pre>
      <pre v-if="sandboxRaw" class="op-exec-out"><code v-if="execOutputHtml" class="hljs language-bash" v-html="execOutputHtml" /><template v-else>{{ sandboxRaw }}</template></pre>
      <p v-if="!execCommand && !sandboxRaw" class="op-exec-empty">无输出</p>
    </template>
    <template v-else-if="sandboxPathEntries.length">
      <ul class="op-sandbox-paths">
        <li v-for="entry in sandboxPathEntries" :key="entry.path">
          <button
            type="button"
            class="op-sandbox-path-link"
            :title="entry.path"
            @click.stop="emit('openPath', entry.path)"
          >{{ entry.name }}</button>
        </li>
      </ul>
    </template>
    <template v-else-if="editDiffRendered.length">
      <pre class="op-sandbox-diff"><code
        v-for="(line, idx) in editDiffRendered"
        :key="idx"
        class="op-diff-line hljs"
        :class="`is-${line.kind}`"
        v-html="line.html"
      /></pre>
    </template>
    <template v-else>
      <pre v-if="sandboxContentHtml" class="op-sandbox-code"><code class="hljs" v-html="sandboxContentHtml" /></pre>
      <pre v-else-if="sandboxRaw" class="op-sandbox-code">{{ sandboxRaw }}</pre>
      <p v-else class="op-exec-empty">无输出</p>
    </template>
  </div>
</template>

<style scoped>
.op-sandbox {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding-right: 28px;
  font-family: var(--sun-font-mono, 'JetBrains Mono', ui-monospace, monospace);
  font-size: var(--sun-font-sm, 12px);
  font-weight: 400;
  line-height: 1.55;
  letter-spacing: 0;
  font-variant-ligatures: none;
  tab-size: 4;
  color: var(--sun-text-muted);
}

.op-sandbox :deep(pre),
.op-sandbox :deep(code),
.op-sandbox :deep(.hljs),
.op-sandbox :deep(span) {
  font-family: var(--sun-font-mono, 'JetBrains Mono', ui-monospace, monospace) !important;
  letter-spacing: 0;
  font-variant-ligatures: none;
}

.op-sandbox-copy {
  position: absolute;
  top: -2px;
  right: 0;
  z-index: 1;
}

.op-exec-cmd,
.op-exec-out,
.op-sandbox-code,
.op-sandbox-diff {
  margin: 0;
  padding: 0;
  white-space: pre-wrap;
  overflow-wrap: break-word;
  word-break: normal;
  background: transparent;
  border: none;
  font-family: var(--sun-font-mono, 'JetBrains Mono', ui-monospace, monospace);
  font-size: var(--sun-font-sm, 12px);
  font-weight: 400;
  line-height: 1.55;
  letter-spacing: 0;
  font-variant-ligatures: none;
  tab-size: 4;
}

.op-exec-cmd {
  color: var(--sun-text-secondary);
  font-weight: 500;
}

.op-exec-cmd :deep(.hljs),
.op-exec-cmd-plain {
  display: inline;
  padding: 0;
  background: transparent !important;
  color: var(--sun-text-secondary);
  white-space: inherit;
}

.op-exec-prompt {
  color: color-mix(in srgb, var(--sun-accent, #6cb6ff) 72%, var(--sun-text-muted));
  margin-right: 6px;
  font-weight: 600;
  user-select: none;
}

.op-exec-out,
.op-sandbox-code {
  color: var(--sun-text-muted);
  opacity: 0.88;
}

.op-exec-out :deep(.hljs),
.op-sandbox-code :deep(.hljs) {
  display: block;
  padding: 0;
  background: transparent !important;
  color: var(--sun-text-muted);
  white-space: inherit;
  word-break: inherit;
}

.op-exec-empty {
  margin: 0;
  font-size: var(--sun-font-sm);
  color: var(--sun-text-muted);
  opacity: 0.85;
}

.op-sandbox-paths {
  margin: 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.op-sandbox-path-link {
  display: inline;
  padding: 0;
  border: none;
  background: transparent;
  color: var(--sun-text-secondary);
  font-family: var(--sun-font-mono, 'JetBrains Mono', ui-monospace, monospace);
  font-size: inherit;
  font-weight: 500;
  text-align: left;
  cursor: pointer;
}

.op-sandbox-path-link:hover {
  color: var(--sun-accent, #6cb6ff);
  text-decoration: underline;
}

.op-sandbox-diff {
  margin: 0;
  padding: 0;
  white-space: pre-wrap;
  overflow-wrap: break-word;
  word-break: normal;
  background: transparent;
  border: none;
  font-family: var(--sun-font-mono, 'JetBrains Mono', ui-monospace, monospace);
  font-size: var(--sun-font-sm, 12px);
  line-height: 1.45;
  letter-spacing: 0;
  font-variant-ligatures: none;
  tab-size: 4;
}

.op-diff-line {
  display: block;
  padding: 0 4px;
  background: transparent !important;
  white-space: pre-wrap;
  font-family: var(--sun-font-mono, 'JetBrains Mono', ui-monospace, monospace) !important;
}

.op-sandbox-diff :deep(span) {
  font-family: var(--sun-font-mono, 'JetBrains Mono', ui-monospace, monospace) !important;
}

.op-diff-line.is-del {
  background: color-mix(in srgb, #c44 14%, transparent) !important;
}

.op-diff-line.is-add {
  background: color-mix(in srgb, #2a9a5c 14%, transparent) !important;
}
</style>
