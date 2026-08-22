<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import MentionChip from './MentionChip.vue'
import type { SkillCatalogIndexEntry } from '../../api/skills'
import type { AgentCatalogIndexEntry } from '../../api/agents'
import type { WorkflowCatalogEntry } from '../../api/workflows'
import type { ExecutionMode } from '../../api/executionModes'
import { allowsForPreference, segmentChatMentions } from '../../utils/chatMention'

const props = defineProps<{
  content: string
  catalog: SkillCatalogIndexEntry[]
  agentCatalog?: AgentCatalogIndexEntry[]
  workflowCatalog?: WorkflowCatalogEntry[]
  executionPreference?: ExecutionMode
}>()

const segments = computed(() =>
  segmentChatMentions(
    props.content,
    {
      skills: props.catalog,
      agents: props.agentCatalog ?? [],
      workflows: props.workflowCatalog ?? [],
    },
    allowsForPreference(props.executionPreference ?? 'fast'),
  ),
)

const hasMentionChip = computed(() =>
  segments.value.some(s => s.type !== 'text'),
)

// 折叠：气泡限高纵向滚动，点击气泡展开全部，再次点击折叠
const MAX_COLLAPSED_HEIGHT = 240
const collapseRef = ref<HTMLElement | null>(null)
const expanded = ref(false)
const overflowing = ref(false)

function measureOverflow() {
  const el = collapseRef.value
  if (!el) return
  overflowing.value = el.scrollHeight > MAX_COLLAPSED_HEIGHT
}

function toggleExpand() {
  if (!overflowing.value) return
  expanded.value = !expanded.value
}

// 拖选文本时位移超过阈值则不视为点击，避免误触展开/折叠
let downX = 0
let downY = 0
function onPointerDown(e: PointerEvent) {
  downX = e.clientX
  downY = e.clientY
}
function onClick(e: MouseEvent) {
  if (Math.abs(e.clientX - downX) > 5 || Math.abs(e.clientY - downY) > 5) return
  toggleExpand()
}

let ro: ResizeObserver | null = null

onMounted(() => {
  measureOverflow()
  if (typeof ResizeObserver !== 'undefined') {
    ro = new ResizeObserver(() => measureOverflow())
    if (collapseRef.value) ro.observe(collapseRef.value)
  }
})

onBeforeUnmount(() => {
  ro?.disconnect()
  ro = null
})
</script>

<template>
  <div class="user-msg-wrap">
    <div
      ref="collapseRef"
      class="user-msg-collapse"
      :class="{ 'is-expanded': expanded, 'is-collapsible': overflowing }"
      @pointerdown="onPointerDown"
      @click="onClick"
    >
      <span v-if="hasMentionChip" class="user-message-content">
        <template v-for="(seg, idx) in segments" :key="idx">
          <MentionChip
            v-if="seg.type === 'skill'"
            kind="skill"
            :token="seg.token"
            :display-name="seg.skill.displayName"
          />
          <MentionChip
            v-else-if="seg.type === 'agent'"
            kind="agent"
            :token="seg.token"
            :display-name="seg.agent.displayName"
          />
          <MentionChip
            v-else-if="seg.type === 'workflow'"
            kind="workflow"
            :token="seg.token"
            :display-name="seg.workflow.displayName"
          />
          <MentionChip
            v-else-if="seg.type === 'path'"
            kind="path"
            :token="seg.token"
            :label="seg.label"
            :line-start="seg.lineStart"
            :line-end="seg.lineEnd"
          />
          <span v-else>{{ seg.value }}</span>
        </template>
      </span>
      <span v-else>{{ content }}</span>
    </div>
  </div>
</template>

<style scoped>
.user-msg-wrap {
  display: inline-block;
  max-width: 100%;
  text-align: left;
  vertical-align: top;
}

.user-msg-collapse {
  position: relative;
  max-height: 240px;
  overflow-y: auto;
  overflow-x: hidden;
  white-space: pre-wrap;
  word-break: break-word;
  line-height: inherit;
}

.user-msg-collapse.is-expanded {
  max-height: none;
  overflow-y: visible;
}

/* 内容超高时整块可点击切换展开/收起 */
.user-msg-collapse.is-collapsible {
  cursor: pointer;
}

/* 折叠态底部渐隐：提示内容被截断、可点击展开 */
.user-msg-collapse.is-collapsible:not(.is-expanded)::after {
  content: '';
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  height: 32px;
  background: linear-gradient(to bottom, transparent, var(--sun-surface));
  pointer-events: none;
}

.user-message-content {
  display: inline;
}
</style>
