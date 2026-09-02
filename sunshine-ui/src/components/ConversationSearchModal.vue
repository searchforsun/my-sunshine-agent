<script setup lang="ts">
import { computed, ref, watch, onUnmounted } from 'vue'
import { NModal, NInput, NIcon, NSpin, useMessage } from 'naive-ui'
import { SearchOutline } from '@vicons/ionicons5'
import type { ConversationSearchItem, ConversationSummary } from '../api/conversations'
import { listConversations, searchConversations } from '../api/conversations'
import { friendlyErrorMessage } from '../api/apiError'
import { formatConversationTime } from '../utils/conversationTime'
import ConversationStatusIcon from './ConversationStatusIcon.vue'

const props = defineProps<{
  show: boolean
  /** task 会话显示项目名用（由 MainLayout 传入工作区反查函数） */
  workspaceNameOf?: (wsId?: string | null) => string
}>()

const emit = defineEmits<{
  (e: 'update:show', v: boolean): void
  (e: 'select', id: string): void
}>()

/** 默认展示的最近会话数（对话 / 任务各取若干） */
const DEFAULT_EACH = 5

const message = useMessage()
const keyword = ref('')
const results = ref<ConversationSearchItem[]>([])
/** 打开弹窗时加载的最近会话（未输入关键词时展示） */
const recentItems = ref<ConversationSearchItem[]>([])
const loading = ref(false)
const searched = ref(false)
/** 键盘导航高亮索引（对话在前、任务在后合并的平铺列表） */
const activeIndex = ref(-1)
const searchInputRef = ref<InstanceType<typeof NInput> | null>(null)
let debounceTimer: ReturnType<typeof setTimeout> | null = null

/** 展示列表：有关键词走搜索结果，否则为最近会话 */
const displayResults = computed(() =>
  keyword.value.trim() ? results.value : recentItems.value,
)
const chatResults = computed(() => displayResults.value.filter(r => r.kind !== 'task'))
const taskResults = computed(() => displayResults.value.filter(r => r.kind === 'task'))
const flatResults = computed(() => [...chatResults.value, ...taskResults.value])

function toSearchItem(c: ConversationSummary): ConversationSearchItem {
  return {
    id: c.id,
    title: c.title,
    createdAt: c.createdAt,
    updatedAt: c.updatedAt,
    kind: c.kind,
    workspaceId: c.workspaceId,
  }
}

async function loadRecent() {
  loading.value = true
  try {
    const all = await listConversations()
    recentItems.value = [
      ...all.filter(c => c.kind !== 'task').slice(0, DEFAULT_EACH).map(toSearchItem),
      ...all.filter(c => c.kind === 'task').slice(0, DEFAULT_EACH).map(toSearchItem),
    ]
  } catch (e) {
    recentItems.value = []
    message.error(friendlyErrorMessage(e, '加载失败'))
  } finally {
    loading.value = false
  }
}

watch(() => props.show, (open) => {
  if (!open) return
  keyword.value = ''
  results.value = []
  searched.value = false
  activeIndex.value = -1
  void loadRecent()
  requestAnimationFrame(() => searchInputRef.value?.focus())
})

onUnmounted(() => {
  if (debounceTimer) clearTimeout(debounceTimer)
})

function onKeywordInput(value: string) {
  keyword.value = value
  if (debounceTimer) clearTimeout(debounceTimer)
  activeIndex.value = -1
  const kw = value.trim()
  if (!kw) {
    results.value = []
    searched.value = false
    loading.value = false
    return
  }
  loading.value = true
  debounceTimer = setTimeout(() => void runSearch(kw), 300)
}

async function runSearch(kw: string) {
  try {
    results.value = await searchConversations(kw)
  } catch (e) {
    results.value = []
    message.error(friendlyErrorMessage(e, '搜索失败'))
  } finally {
    loading.value = false
    searched.value = true
  }
}

function handleSelect(item: ConversationSearchItem) {
  emit('select', item.id)
  emit('update:show', false)
}

function itemTime(item: ConversationSearchItem): string {
  return formatConversationTime(item.updatedAt)
}

function handleKeydown(e: KeyboardEvent) {
  if (e.key === 'ArrowDown') {
    e.preventDefault()
    if (flatResults.value.length === 0) return
    activeIndex.value = (activeIndex.value + 1) % flatResults.value.length
  } else if (e.key === 'ArrowUp') {
    e.preventDefault()
    if (flatResults.value.length === 0) return
    activeIndex.value = activeIndex.value <= 0
      ? flatResults.value.length - 1
      : activeIndex.value - 1
  } else if (e.key === 'Enter') {
    const target = activeIndex.value >= 0 ? flatResults.value[activeIndex.value] : flatResults.value[0]
    if (target) handleSelect(target)
  }
}
</script>

<template>
  <NModal
    :show="props.show"
    preset="card"
    title="搜索会话"
    style="width: 640px"
    class="conv-search-modal"
    @update:show="(v: boolean) => emit('update:show', v)"
  >
    <NInput
      ref="searchInputRef"
      v-model:value="keyword"
      class="cs-input"
      placeholder="搜索会话"
      clearable
      autofocus
      :input-props="{ autocomplete: 'off' }"
      @update:value="onKeywordInput"
      @keydown="handleKeydown"
    >
      <template #prefix>
        <NIcon :component="SearchOutline" class="cs-input-icon" />
      </template>
    </NInput>

    <div v-if="loading" class="cs-state">
      <NSpin size="small" />
      <span class="cs-state-text">加载中…</span>
    </div>

    <div v-else-if="searched && results.length === 0" class="cs-state">
      <span class="cs-state-text">无结果</span>
    </div>

    <div v-else class="cs-results">
      <section v-if="chatResults.length" class="cs-group">
        <div class="cs-group-label">对话</div>
        <button
          v-for="item in chatResults"
          :key="item.id"
          type="button"
          class="cs-item"
          :class="{ 'is-active': flatResults.indexOf(item) === activeIndex }"
          @click="handleSelect(item)"
        >
          <ConversationStatusIcon :state="null" />
          <span class="cs-item-main">
            <span class="cs-item-title">{{ item.title }}</span>
            <span v-if="item.snippet" class="cs-item-snippet">{{ item.snippet }}</span>
          </span>
          <span class="cs-item-time">{{ itemTime(item) }}</span>
        </button>
      </section>

      <section v-if="taskResults.length" class="cs-group">
        <div class="cs-group-label">任务</div>
        <button
          v-for="item in taskResults"
          :key="item.id"
          type="button"
          class="cs-item"
          :class="{ 'is-active': flatResults.indexOf(item) === activeIndex }"
          @click="handleSelect(item)"
        >
          <ConversationStatusIcon :state="null" />
          <span class="cs-item-main">
            <span class="cs-item-title">
              <span v-if="workspaceNameOf?.(item.workspaceId)" class="cs-item-ws">{{ workspaceNameOf(item.workspaceId) }}</span>
              {{ item.title }}
            </span>
            <span v-if="item.snippet" class="cs-item-snippet">{{ item.snippet }}</span>
          </span>
          <span class="cs-item-time">{{ itemTime(item) }}</span>
        </button>
      </section>
    </div>
  </NModal>
</template>

<style scoped>
.cs-input :deep(.n-input__prefix) {
  color: var(--sun-text-muted);
}

.cs-state {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 32px 16px;
  color: var(--sun-text-muted);
  border: 1px dashed var(--sun-border);
  border-radius: var(--radius-md);
  font-size: var(--sun-font-sm);
}

.cs-state-text {
  line-height: 1.5;
}

.cs-results {
  margin-top: 12px;
  max-height: 420px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding-right: 2px;
}

.cs-group {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.cs-group-label {
  padding: 2px 4px;
  font-size: var(--sun-font-xs);
  font-weight: 600;
  letter-spacing: 0.02em;
  color: var(--sun-text-muted);
  user-select: none;
}

.cs-item {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  width: 100%;
  padding: 8px 10px;
  border: none;
  border-radius: 8px;
  background: transparent;
  text-align: left;
  cursor: pointer;
  transition: background 0.15s;
}

.cs-item:hover,
.cs-item.is-active {
  background: var(--sun-row-hover);
}

.cs-item-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.cs-item-title {
  font-size: var(--sun-font-sm);
  font-weight: 500;
  color: var(--sun-text);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  line-height: var(--sun-line);
}

.cs-item-ws {
  color: var(--sun-accent, #4098ff);
  font-weight: 500;
  margin-right: 6px;
}

.cs-item-snippet {
  font-size: var(--sun-font-xs);
  color: var(--sun-text-muted);
  line-height: 1.5;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.cs-item-time {
  flex-shrink: 0;
  font-size: var(--sun-font-xs);
  color: var(--sun-text-muted);
  font-variant-numeric: tabular-nums;
  line-height: 1.4;
}
</style>
