<script setup lang="ts">
import { computed, h, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  NButton,
  NEmpty,
  NIcon,
  NSpin,
  NTree,
  useMessage,
  type TreeOption,
} from 'naive-ui'
import { DocumentTextOutline, FolderOutline } from '@vicons/ionicons5'
import {
  diffSkillVersions,
  listSkillFiles,
  listSkillVersions,
  type SkillVersion,
  type SkillVersionDiffResponse,
} from '../api/skills'
import { buildFileTree, collectDirKeys, type FileEntryLike } from '../utils/buildFileTree'
import { formatSkillVersionTime } from '../utils/formatSkillVersionTime'
import {
  inlineDiffPrefix,
  toSplitDiffRows,
  type SkillDiffViewMode,
} from '../utils/skillDiffView'

const route = useRoute()
const router = useRouter()
const message = useMessage()

const loading = ref(true)
const diffLoading = ref(false)
const versions = ref<SkillVersion[]>([])
const files = ref<FileEntryLike[]>([])
const diffData = ref<SkillVersionDiffResponse | null>(null)
const viewMode = ref<SkillDiffViewMode>('inline')
const expandedKeys = ref<string[]>([])

const skillId = computed(() => String(route.params.skillId ?? ''))
const fromVersion = computed(() => Number.parseInt(String(route.query.from ?? ''), 10))
const toVersion = computed(() => Number.parseInt(String(route.query.to ?? ''), 10))
const selectedPath = computed(() => {
  const p = route.query.path
  return typeof p === 'string' && p.trim() ? p.trim() : 'SKILL.md'
})

const isBinaryDiff = computed(() => !!diffData.value?.binary)
const md5Same = computed(() =>
  !!diffData.value?.binary
  && !!diffData.value.fromMd5
  && diffData.value.fromMd5 === diffData.value.toMd5,
)

const splitRows = computed(() => (diffData.value?.lines ? toSplitDiffRows(diffData.value.lines) : []))

const fileTreeNodes = computed(() => buildFileTree(files.value))

function treePrefix(isDir: boolean) {
  return () => h(NIcon, { size: 14, component: isDir ? FolderOutline : DocumentTextOutline })
}

const treeOptions = computed<TreeOption[]>(() => {
  function mapNodes(nodes: ReturnType<typeof buildFileTree>): TreeOption[] {
    return nodes.map(n => ({
      key: n.key,
      label: n.label,
      isLeaf: !n.isDir,
      disabled: n.isDir,
      prefix: treePrefix(n.isDir),
      children: n.isDir ? mapNodes(n.children) : undefined,
    }))
  }
  return mapNodes(fileTreeNodes.value)
})

function versionLabel(versionNum: number): string {
  const entry = versions.value.find(v => v.version === versionNum)
  const label = formatSkillVersionTime(entry?.createdAt)
  return label !== '—' ? label : `v${versionNum}`
}

const fromVersionLabel = computed(() => versionLabel(fromVersion.value))
const toVersionLabel = computed(() => versionLabel(toVersion.value))

function goBack() {
  if (window.history.length > 1) {
    router.back()
  } else {
    void router.push('/skills')
  }
}

async function loadVersions() {
  versions.value = await listSkillVersions(skillId.value)
}

async function loadFiles() {
  if (!Number.isFinite(fromVersion.value) || !Number.isFinite(toVersion.value)) return
  const [fromFiles, toFiles] = await Promise.all([
    listSkillFiles(skillId.value, fromVersion.value),
    listSkillFiles(skillId.value, toVersion.value),
  ])
  const merged = new Map<string, FileEntryLike>()
  for (const entry of [...fromFiles, ...toFiles]) {
    if (!entry.directory) merged.set(entry.path, entry)
  }
  files.value = Array.from(merged.values()).sort((a, b) => a.path.localeCompare(b.path))
  expandedKeys.value = collectDirKeys(buildFileTree(files.value))
}

async function loadDiff() {
  if (!Number.isFinite(fromVersion.value) || !Number.isFinite(toVersion.value)) {
    message.error('缺少对比版本参数')
    return
  }
  diffLoading.value = true
  try {
    diffData.value = await diffSkillVersions(
      skillId.value,
      fromVersion.value,
      toVersion.value,
      selectedPath.value,
    )
  } catch (e) {
    message.error(e instanceof Error ? e.message : '加载 diff 失败')
    diffData.value = null
  } finally {
    diffLoading.value = false
  }
}

async function bootstrap() {
  loading.value = true
  try {
    await Promise.all([loadVersions(), loadFiles()])
    await loadDiff()
  } finally {
    loading.value = false
  }
}

function onSelectFile(keys: Array<string | number>) {
  const path = String(keys[0] ?? '')
  if (!path || path === selectedPath.value) return
  void router.replace({
    name: 'skill-diff',
    params: { skillId: skillId.value },
    query: {
      from: String(fromVersion.value),
      to: String(toVersion.value),
      path,
    },
  })
}

watch(selectedPath, () => {
  void loadDiff()
})

onMounted(() => {
  void bootstrap()
})
</script>

<template>
  <div class="skill-diff-root">
    <header class="page-header">
      <div class="header-left">
        <NButton size="small" quaternary @click="goBack">← 返回</NButton>
        <h2>版本对比</h2>
      </div>
      <div v-if="!isBinaryDiff" class="mode-switch" role="tablist" aria-label="对比方式">
        <NButton
          size="small"
          :type="viewMode === 'inline' ? 'primary' : 'default'"
          :secondary="viewMode !== 'inline'"
          @click="viewMode = 'inline'"
        >
          内嵌对比
        </NButton>
        <NButton
          size="small"
          :type="viewMode === 'split' ? 'primary' : 'default'"
          :secondary="viewMode !== 'split'"
          @click="viewMode = 'split'"
        >
          左右对比
        </NButton>
      </div>
    </header>

    <NSpin :show="loading" class="diff-layout-spin">
      <div class="diff-layout">
        <aside class="file-tree-pane">
          <div class="pane-title">文件</div>
          <NTree
            v-if="treeOptions.length"
            block-line
            selectable
            expand-on-click
            :data="treeOptions"
            :selected-keys="[selectedPath]"
            :expanded-keys="expandedKeys"
            @update:selected-keys="onSelectFile"
            @update:expanded-keys="keys => expandedKeys = keys as string[]"
          />
          <NEmpty v-else size="small" description="无文件" />
        </aside>

        <section class="diff-pane">
          <NSpin :show="diffLoading" class="diff-pane-spin">
            <template v-if="isBinaryDiff && diffData">
              <div class="diff-split">
                <div class="diff-split-col">
                  <div class="diff-split-head">{{ fromVersionLabel }}</div>
                  <pre class="diff-binary-body"><code>{{ diffData.fromMd5 ?? '—' }}</code></pre>
                </div>
                <div class="diff-split-col">
                  <div class="diff-split-head">{{ toVersionLabel }}</div>
                  <pre class="diff-binary-body"><code>{{ diffData.toMd5 ?? '—' }}</code></pre>
                </div>
              </div>
              <p v-if="md5Same" class="diff-binary-hint">MD5 相同，二进制内容一致</p>
            </template>
            <template v-else-if="viewMode === 'inline'">
              <pre v-if="diffData?.lines?.length" class="diff-inline"><code><span
                v-for="(line, idx) in diffData.lines"
                :key="idx"
                :class="['diff-line', `diff-line--${line.type}`]"
              >{{ inlineDiffPrefix(line.type) }}{{ line.text }}
</span></code></pre>
              <NEmpty v-else-if="!diffLoading" description="无差异" />
            </template>
            <template v-else>
              <div v-if="splitRows.length" class="diff-split">
                <div class="diff-split-col">
                  <div class="diff-split-head">{{ fromVersionLabel }}</div>
                  <pre class="diff-split-body"><code><div
                    v-for="(row, idx) in splitRows"
                    :key="`l-${idx}`"
                    :class="['diff-split-row', `diff-split-row--${row.left.type}`]"
                  ><span class="diff-ln">{{ row.left.lineNo ?? '' }}</span><span class="diff-text">{{ row.left.text }}</span></div></code></pre>
                </div>
                <div class="diff-split-col">
                  <div class="diff-split-head">{{ toVersionLabel }}</div>
                  <pre class="diff-split-body"><code><div
                    v-for="(row, idx) in splitRows"
                    :key="`r-${idx}`"
                    :class="['diff-split-row', `diff-split-row--${row.right.type}`]"
                  ><span class="diff-ln">{{ row.right.lineNo ?? '' }}</span><span class="diff-text">{{ row.right.text }}</span></div></code></pre>
                </div>
              </div>
              <NEmpty v-else-if="!diffLoading" description="无差异" />
            </template>
          </NSpin>
        </section>
      </div>
    </NSpin>
  </div>
</template>

<style scoped>
.skill-diff-root {
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

.header-left {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}

.header-left h2 {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
  color: var(--sun-text);
}

.mode-switch {
  display: flex;
  gap: 8px;
  flex-shrink: 0;
}

.diff-layout-spin {
  flex: 1;
  min-height: 0;
}

.diff-layout-spin :deep(.n-spin-content) {
  height: 100%;
}

.diff-layout {
  height: 100%;
  min-height: 0;
  display: grid;
  grid-template-columns: 240px minmax(0, 1fr);
  gap: 12px;
}

.file-tree-pane,
.diff-pane {
  min-height: 0;
  border: 1px solid var(--sun-border);
  border-radius: 12px;
  background: var(--sun-bg);
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

.pane-title {
  flex-shrink: 0;
  padding: 10px 12px;
  border-bottom: 1px solid var(--sun-border);
  font-size: var(--sun-font-sm);
  color: var(--sun-text-secondary);
}

.file-tree-pane :deep(.n-tree) {
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding: 8px;
}

.diff-pane-spin {
  flex: 1;
  min-height: 0;
}

.diff-pane-spin :deep(.n-spin-content) {
  height: 100%;
  min-height: 0;
  overflow: auto;
}

.diff-inline,
.diff-split-body,
.diff-binary-body {
  margin: 0;
  padding: 12px 14px;
  font-family: ui-monospace, 'JetBrains Mono', monospace;
  font-size: 12px;
  line-height: 1.55;
  white-space: pre-wrap;
  word-break: break-word;
}

.diff-binary-body {
  min-height: 48px;
}

.diff-binary-hint {
  margin: 0;
  padding: 10px 14px 14px;
  font-size: var(--sun-font-sm);
  color: var(--sun-text-muted);
}

.diff-line--added,
.diff-split-row--added .diff-text {
  background: color-mix(in srgb, #3fb950 18%, transparent);
}

.diff-line--removed,
.diff-split-row--removed .diff-text {
  background: color-mix(in srgb, #f85149 18%, transparent);
}

.diff-split {
  display: grid;
  grid-template-columns: 1fr 1fr;
  min-height: 100%;
}

.diff-split-col {
  min-width: 0;
  border-right: 1px solid var(--sun-border);
  display: flex;
  flex-direction: column;
}

.diff-split-col:last-child {
  border-right: none;
}

.diff-split-head {
  padding: 8px 12px;
  border-bottom: 1px solid var(--sun-border);
  font-size: var(--sun-font-sm);
  color: var(--sun-text-muted);
  background: color-mix(in srgb, var(--sun-row-hover) 50%, var(--sun-bg));
}

.diff-split-row {
  display: grid;
  grid-template-columns: 42px minmax(0, 1fr);
  gap: 8px;
  padding: 0 8px 0 0;
}

.diff-ln {
  text-align: right;
  color: var(--sun-text-muted);
  user-select: none;
  padding-right: 8px;
  border-right: 1px solid color-mix(in srgb, var(--sun-border) 70%, transparent);
}

.diff-text {
  padding: 0 4px;
  white-space: pre-wrap;
  word-break: break-word;
}

.diff-split-row--empty .diff-text {
  min-height: 1.55em;
}
</style>
