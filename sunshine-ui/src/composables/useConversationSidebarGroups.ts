import { computed, onMounted, onUnmounted, ref, type Ref } from 'vue'
import type { Conversation } from '../stores/chatStore'
import {
  conversationDayBucketKey,
  conversationDayLabel,
  daysBeforeToday,
} from '../utils/conversationTime'

export interface ConversationSidebarGroup {
  key: string
  label: string
  sortOrder: number
  items: Conversation[]
}

function useNowTick(intervalMs = 60_000): Ref<number> {
  const now = ref(Date.now())
  let timer: ReturnType<typeof setInterval> | undefined
  onMounted(() => {
    timer = setInterval(() => { now.value = Date.now() }, intervalMs)
  })
  onUnmounted(() => {
    if (timer) clearInterval(timer)
  })
  return now
}

/** 侧栏会话列表：按创建日分组（近 7 天逐日，更早合并） */
export function useConversationSidebarGroups(conversations: Ref<Conversation[]>) {
  const now = useNowTick()

  const groups = computed((): ConversationSidebarGroup[] => {
    const tick = now.value
    const sorted = [...conversations.value].sort((a, b) => b.createdAt - a.createdAt)
    const map = new Map<string, ConversationSidebarGroup>()
    for (const conv of sorted) {
      const bucket = conversationDayBucketKey(conv.createdAt, tick)
      const label = conversationDayLabel(conv.createdAt, tick)
      const sortOrder = bucket === 'older' ? 9999 : daysBeforeToday(conv.createdAt, tick)
      const existing = map.get(bucket)
      if (existing) {
        existing.items.push(conv)
      } else {
        map.set(bucket, { key: bucket, label, sortOrder, items: [conv] })
      }
    }
    return [...map.values()].sort((a, b) => a.sortOrder - b.sortOrder)
  })

  return { groups, now }
}
