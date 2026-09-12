/**
 * 视口模式 SSOT：布局三档（与 global.css / 组件内 @media 断点数值严格一致）
 * - 窄屏 ≤768px（手机/平板竖屏）：堆栈式，菜单转抽屉、面板全屏
 * - 紧凑 769–1260px（四面板最小宽放不下）：右侧抽屉转浮层堆栈
 * - 宽敞 ≥1261px：四面板并排
 *
 * 呈现层（布局/覆盖/全屏）一律由纯 CSS @media 分辨率驱动，与本旗标零耦合；
 * 旗标仅供行为层使用（侧栏默认收起、点按交互、拖拽开关）。
 * 信号源 = window.innerWidth 单一来源 + resize 监听，不用 matchMedia
 * （真机 matchMedia 状态曾与实际布局脱节；innerWidth 与 CSS 求值同源且兼容性最好）。
 * Node 环境（单测/SSR）无 window：默认宽敞档，保证纯逻辑测试可运行。
 */
import { ref } from 'vue'
import { NARROW_VIEWPORT_MAX_WIDTH, COMPACT_VIEWPORT_MAX_WIDTH } from './viewportBreakpoints'

const hasWindow = typeof window !== 'undefined'

const viewportWidth = ref(hasWindow ? window.innerWidth : 1920)

if (hasWindow) {
  window.addEventListener('resize', () => {
    viewportWidth.value = window.innerWidth
  })
}

const isNarrowViewport = ref(viewportWidth.value <= NARROW_VIEWPORT_MAX_WIDTH)
const isCompactViewport = ref(
  viewportWidth.value > NARROW_VIEWPORT_MAX_WIDTH && viewportWidth.value <= COMPACT_VIEWPORT_MAX_WIDTH,
)

if (hasWindow) {
  window.addEventListener('resize', () => {
    isNarrowViewport.value = window.innerWidth <= NARROW_VIEWPORT_MAX_WIDTH
    isCompactViewport.value =
      window.innerWidth > NARROW_VIEWPORT_MAX_WIDTH && window.innerWidth <= COMPACT_VIEWPORT_MAX_WIDTH
  })
}

export function useViewportMode() {
  return { isNarrowViewport, isCompactViewport, viewportWidth }
}
