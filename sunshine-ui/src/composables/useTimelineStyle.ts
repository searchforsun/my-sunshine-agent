import { ref, type Ref } from 'vue'

export type TimelineStyle = 'minimal' | 'standard'

const TIMELINE_STYLE_STORAGE_KEY = 'sunshine.timeline.style'

function loadTimelineStyle(): TimelineStyle {
  try {
    const raw = localStorage.getItem(TIMELINE_STYLE_STORAGE_KEY)
    if (raw === 'standard' || raw === 'minimal') return raw
  } catch { /* ignore */ }
  return 'minimal'
}

/** 模块级单例：与 useExecutionPreference 同模式，组件直接读取响应式生效 */
const timelineStyle = ref<TimelineStyle>(loadTimelineStyle())

export function useTimelineStyle() {
  function setTimelineStyle(next: TimelineStyle) {
    timelineStyle.value = next
    try {
      localStorage.setItem(TIMELINE_STYLE_STORAGE_KEY, next)
    } catch { /* ignore */ }
  }
  return { timelineStyle, setTimelineStyle }
}
