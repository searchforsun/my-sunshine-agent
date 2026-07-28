<script setup lang="ts">
import { NDropdown, type DropdownOption } from 'naive-ui'
import { EllipsisHorizontal } from '@vicons/ionicons5'
import { computed, ref } from 'vue'
import { useRoute } from 'vue-router'
import { useChatStore } from '../stores/chatStore'
import { useConversationAttention } from '../composables/useConversationAttention'
import { useConversationSidebarGroups } from '../composables/useConversationSidebarGroups'
import { useConversationSidebarIndicator, type SidebarConvIndicator } from '../composables/useConversationSidebarIndicator'
import { formatSidebarItemTime } from '../utils/conversationTime'
import type { Conversation } from '../stores/chatStore'
import ConversationStatusIcon from './ConversationStatusIcon.vue'
import ConversationHoverCard from './ConversationHoverCard.vue'

const emit = defineEmits<{
  switch: [id: string]
  menu: [key: string]
}>()

defineProps<{
  menuOptions: (id: string) => DropdownOption[]
}>()

const route = useRoute()
const chatStore = useChatStore()
const { resolveIndicator } = useConversationSidebarIndicator()
const { attentionByConv } = useConversationAttention()
const { groups, now } = useConversationSidebarGroups(computed(() => chatStore.conversations))

/** hover 详情卡：当前 hovered 会话 + anchor + card 引用 */
const hoverConv = ref<Conversation | null>(null)
const hoverAnchor = ref<HTMLElement | null>(null)
const hoverCardRef = ref<InstanceType<typeof ConversationHoverCard> | null>(null)

function onItemEnter(conv: Conversation, e: MouseEvent) {
  hoverConv.value = conv
  hoverAnchor.value = e.currentTarget as HTMLElement
  // 等 card 挂载后 show
  requestAnimationFrame(() => hoverCardRef.value?.show())
}

function onItemLeave() {
  hoverCardRef.value?.hide()
  hoverConv.value = null
  hoverAnchor.value = null
}

/** 仅在对话页高亮当前会话；进入平台页后取消选中态 */
function isActiveConv(id: string): boolean {
  return route.name === 'chat' && id === chatStore.currentId
}

function indicator(conv: Conversation): SidebarConvIndicator | null {
  void attentionByConv.size
  return resolveIndicator(conv.id, conv.messages)
}

function convTime(conv: Conversation): string {
  void now.value
  return formatSidebarItemTime(conv.updatedAt, now.value)
}

function handleSwitch(id: string) {
  emit('switch', id)
}

function handleMenu(key: string) {
  emit('menu', key)
}
</script>

<template>
  <div class="conversation-sidebar-list">
    <div v-if="groups.length > 0" class="history-list">
    <section v-for="group in groups" :key="group.key" class="history-group">
      <div class="history-group-label">{{ group.label }}</div>
      <div
        v-for="conv in group.items"
        :key="conv.id"
        class="history-item"
        :class="{
          active: isActiveConv(conv.id),
          'is-hitl-pending': indicator(conv) === 'hitl_pending',
        }"
        @click="handleSwitch(conv.id)"
        @mouseenter="onItemEnter(conv, $event)"
        @mouseleave="onItemLeave"
      >
        <ConversationStatusIcon
          :state="indicator(conv)"
          :active="isActiveConv(conv.id)"
          :title="indicator(conv) === 'streaming' ? '正在生成' : indicator(conv) === 'hitl_pending' ? '待确认' : indicator(conv) === 'completed' ? '新回复' : undefined"
        />
        <span class="history-item-title">{{ conv.title }}</span>
        <span class="history-item-time">{{ convTime(conv) }}</span>
        <NDropdown
          trigger="click"
          size="small"
          placement="bottom-end"
          :options="menuOptions(conv.id)"
          @select="handleMenu"
        >
          <button
            type="button"
            class="history-item-more"
            title="更多"
            aria-label="更多"
            @click.stop
          >
            <EllipsisHorizontal width="16" height="16" />
          </button>
        </NDropdown>
      </div>
    </section>
  </div>
    <div v-else class="history-empty">
      <span class="history-empty-text">暂无对话</span>
    </div>
    <ConversationHoverCard
      v-if="hoverConv"
      ref="hoverCardRef"
      :conversation="hoverConv"
      :anchor="hoverAnchor"
    />
  </div>
</template>

<style scoped>
.conversation-sidebar-list {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.history-list {
  flex: 1;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin-top: 2px;
  padding-top: 4px;
}

.history-group {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.history-group-label {
  padding: 4px 8px 2px;
  font-size: var(--sun-font-xs);
  font-weight: 600;
  letter-spacing: 0.02em;
  color: var(--sun-text-muted);
  user-select: none;
}

.history-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 8px 8px 6px;
  border-radius: 8px;
  cursor: pointer;
  transition: background 0.15s, color 0.15s;
  flex-shrink: 0;
}

.history-item:hover { background: var(--sun-row-hover); }
.history-item.active { background: var(--sun-row-hover); }
.history-item.active .history-item-title {
  color: var(--sun-text);
  font-weight: 500;
}

.history-item.is-hitl-pending .history-item-title {
  color: var(--sun-text);
  font-weight: 500;
}

.history-item-title {
  flex: 1;
  min-width: 0;
  font-size: var(--sun-font-sm);
  font-weight: 400;
  color: var(--sun-text-secondary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  line-height: var(--sun-line);
}

.history-item-time {
  flex-shrink: 0;
  font-size: var(--sun-font-xs);
  color: var(--sun-text-muted);
  font-variant-numeric: tabular-nums;
  line-height: 1;
}

.history-item-more {
  width: 24px;
  height: 24px;
  border-radius: 6px;
  border: none;
  background: transparent;
  color: var(--sun-text-muted);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  opacity: 0;
  transition: background 0.15s, color 0.15s, opacity 0.15s;
}

.history-item:hover .history-item-more,
.history-item.active .history-item-more {
  opacity: 0.55;
}

.history-item-more:hover {
  opacity: 1 !important;
  color: var(--sun-text);
  background: var(--sun-row-hover);
}

.history-empty {
  flex: 1;
  margin-top: 4px;
  padding: 20px 8px 4px;
  text-align: center;
  flex-shrink: 0;
}

.history-empty-text {
  font-size: var(--sun-font-sm);
  color: var(--sun-text-muted);
}
</style>
