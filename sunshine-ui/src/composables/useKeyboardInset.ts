import { onMounted, onUnmounted, ref } from 'vue'

/**
 * 软键盘压缩视口的抬升量（px）。
 * iOS Safari 键盘不收缩布局视口（viewport 的 interactive-widget 仅 Android Chrome 生效），
 * absolute 贴底元素不会自动避让，须经 visualViewport 实时计算：
 * inset = innerHeight - vv.height - vv.offsetTop（可视区底边与布局视口底边之差）。
 * Android resizes-content 模式下 innerHeight 随键盘收缩，inset 恒为 0，不会二次抬升。
 */
export function useKeyboardInset() {
  const keyboardInset = ref(0)
  const vv = window.visualViewport
  if (!vv) return { keyboardInset }

  let raf = 0
  const update = () => {
    raf = 0
    keyboardInset.value = Math.max(0, Math.round(window.innerHeight - vv.height - vv.offsetTop))
  }
  /** resize/scroll 高频触发，rAF 合帧避免抖动 */
  const scheduleUpdate = () => {
    if (!raf) raf = requestAnimationFrame(update)
  }

  onMounted(() => {
    vv.addEventListener('resize', scheduleUpdate)
    vv.addEventListener('scroll', scheduleUpdate)
    scheduleUpdate()
  })
  onUnmounted(() => {
    vv.removeEventListener('resize', scheduleUpdate)
    vv.removeEventListener('scroll', scheduleUpdate)
    if (raf) cancelAnimationFrame(raf)
  })

  return { keyboardInset }
}
